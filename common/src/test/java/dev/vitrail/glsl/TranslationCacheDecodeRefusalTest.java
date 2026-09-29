package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the translation cache to saying so when a blob its digest answers for does not read back,
 * since what that is, is this build's writer and reader disagreeing and not a damaged file.
 * <p>
 * The blob planted is a real one written for another vertex format than the one asked for: the
 * format is in the key, so no ordinary road reaches it, and it is refused inside the codec after
 * the digest has passed, which is the silence this holds shut. The refusal is latched once a run,
 * and {@link TranslationCacheTest} raises it as well, so the statics are put back around each test
 * here as they are there: a latch left up would leave the first refusal of the other unsaid, and a
 * cache left installed would point at a folder JUnit has already deleted.
 */
class TranslationCacheDecodeRefusalTest {

	private static final String FAMILY = "0.13.0_mc26.2";

	@TempDir
	Path temp;

	@BeforeEach
	void startWithTheStaticsAsTheClassLoadLeftThem() throws ReflectiveOperationException {
		TranslationCacheTest.putTheStaticsBack();
	}

	@AfterEach
	void leaveTheStaticsAsTheClassLoadLeftThem() throws ReflectiveOperationException {
		TranslationCacheTest.putTheStaticsBack();
	}

	@Test
	void saysSoWhenABlobPassesItsDigestAndStillDoesNotRead() throws IOException {
		TranslationCache.install(temp, FAMILY, FAMILY);
		assertTrue(TranslationCache.installed(), "the cache is off: " + TranslationCache.problem());

		ProgramTranslator.TranslatedProgram world = new ProgramTranslator.TranslatedProgram(
				Map.of(), List.of(), List.of(), Set.of(), Map.of(), VertexInputs.WORLD);
		byte[] packed = deflate(TranslatedProgramCodec.write(world));
		Path file = temp.resolve("translations").resolve(FAMILY).resolve("planted.tr");
		Files.write(file, packed);
		Files.write(file, sha256(packed), StandardOpenOption.APPEND);

		assertNull(TranslationCache.lookup("planted", VertexInputs.TERRAIN),
				"a blob made for another vertex format was served");

		String refused = TranslationCache.takeRefusal();
		assertTrue(refused.contains("made for WORLD"), "the refusal does not say why: " + refused);
	}

	/** Squeezed into the zlib stream the cache inflates, which is what its digest answers for. */
	private static byte[] deflate(byte[] blob) throws IOException {
		ByteArrayOutputStream packed = new ByteArrayOutputStream();
		try (DeflaterOutputStream out = new DeflaterOutputStream(packed)) {
			out.write(blob);
		}

		return packed.toByteArray();
	}

	private static byte[] sha256(byte[] raw) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(raw);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
