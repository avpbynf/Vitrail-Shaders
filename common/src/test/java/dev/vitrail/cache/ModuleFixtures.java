package dev.vitrail.cache;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.LocalZeroes;
import dev.vitrail.render.PackNames;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.SamplerReach;
import dev.vitrail.render.ShaderDebugInfo;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Random;

import org.lwjgl.Version;

/**
 * A second, independent reading of what the module cache writes: the file layout and the key
 * recipe, spelled out from the contract in the class's own documentation and sharing no code with
 * it. A test that builds its inputs here and compares against the cache is comparing two readings
 * of one contract, which is what keeps a refactor of the cache honest.
 * <p>
 * The layout of a unit: the length of the SPIR-V in bytes as a big endian int, the SPIR-V itself
 * in native byte order, whatever tables the game keeps beside it (none on 26.3, four lists on
 * 26.2), and behind all of that the SHA-256 of everything before it. The key: SHA-256 over a fixed
 * list of strings, each as a four byte big endian length and then its UTF-8 bytes, in an order
 * that differs by one word between the games.
 */
final class ModuleFixtures {

	/** First word of SPIR-V. */
	static final int MAGIC = 0x07230203;

	/** Written after the words, and what the game keeps of them. */
	interface Tables {

		void write(DataOutputStream out) throws IOException;
	}

	/** 26.3 keeps nothing beside the words. */
	static final Tables NONE = out -> { };

	/** 26.2 keeps four lists, and an empty list is a count of nought. */
	static final Tables EMPTY = out -> {
		for (int list = 0; list < 4; list++) {
			out.writeInt(0);
		}
	};

	/** An arbitrary point long before any file this suite makes, for a stamp that is plainly old. */
	static final Instant LONG_AGO = Instant.parse("2001-01-01T00:00:00Z");

	private ModuleFixtures() {
	}

	static Tables emptyFor(boolean game263) {
		return game263 ? NONE : EMPTY;
	}

	/**
	 * Words that open on the SPIR-V magic word, in native order as the cache reads them, and are
	 * otherwise a fixed pseudo-random run. The cache never parses them past the magic word.
	 */
	static byte[] words(int wordCount, long seed) {
		ByteBuffer buffer = ByteBuffer.allocate(wordCount * Integer.BYTES).order(ByteOrder.nativeOrder());
		buffer.putInt(MAGIC).putInt(0x00010500).putInt(0).putInt(wordCount).putInt(0);
		Random random = new Random(seed);
		while (buffer.hasRemaining()) {
			buffer.putInt(random.nextInt());
		}

		return buffer.array();
	}

	/** A unit before its digest: the length, the words, then the tables. */
	static byte[] body(byte[] words, Tables tables) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				out.writeInt(words.length);
				out.write(words);
				tables.write(out);
			}

			return bytes.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The body with the digest of the body behind it, which is what lies on disk. */
	static byte[] sealed(byte[] body) {
		byte[] digest = sha256().digest(body);
		byte[] file = new byte[body.length + digest.length];
		System.arraycopy(body, 0, file, 0, body.length);
		System.arraycopy(digest, 0, file, body.length, digest.length);

		return file;
	}

	static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	static String sha256Hex(byte[] bytes) {
		return hex(sha256().digest(bytes));
	}

	/**
	 * The key the cache is documented to give a unit, worked out from the platform in place and the
	 * engine's own words as they stand now. Only what the cache itself decides is spelled out here:
	 * the format token, the order, the framing.
	 *
	 * @param defines the defines handed beside the text, which only 26.3 keys
	 */
	static String key(boolean game263, String source, String stage, String defines, boolean ours) {
		MessageDigest digest = sha256();
		feed(digest, game263 ? "vitrail-module-26.3-1" : "vitrail-module-3");
		feed(digest, Vitrail.cacheVersion());
		if (!Vitrail.buildIdentity().isEmpty()) {
			feed(digest, Vitrail.buildIdentity());
		}

		feed(digest, Vitrail.platform().minecraftVersion());
		feed(digest, Vitrail.platform().loaderName());
		feed(digest, Vitrail.platform().loaderVersion());
		feed(digest, Version.getVersion());
		feed(digest, RawLocals.cacheWord());
		feed(digest, LocalZeroes.VERSION);
		feed(digest, ShaderDebugInfo.cacheWord());
		feed(digest, PackNames.cacheWord());
		if (!game263) {
			feed(digest, SamplerReach.cacheWord());
		}

		feed(digest, ours ? "ours" : "theirs");
		feed(digest, stage);
		if (game263) {
			feed(digest, defines);
		}

		feed(digest, source);

		return hex(digest.digest());
	}

	private static void feed(MessageDigest digest, String text) {
		byte[] raw = text.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(raw.length).array());
		digest.update(raw);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * A file that is big to anyone who asks its size and takes no disk, which is how a sweep over
	 * hundreds of mebibytes is reached without writing them.
	 */
	static Path sparse(Path file, long size, int secondsAfterLongAgo) throws IOException {
		try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
			raf.setLength(size);
		}

		return stamp(file, secondsAfterLongAgo);
	}

	/** Sets a stamp that is an exact number of seconds after {@link #LONG_AGO}, and never the clock. */
	static Path stamp(Path file, int secondsAfterLongAgo) throws IOException {
		Files.setLastModifiedTime(file, FileTime.from(LONG_AGO.plusSeconds(secondsAfterLongAgo)));

		return file;
	}
}
