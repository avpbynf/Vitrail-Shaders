package dev.vitrail.pack.source;

import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.Vitrail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * One pack left open between two loads, so that the second does not mount the archive, walk it for
 * its options, parse its properties and flatten its entry files all over again to reach the same
 * answers.
 * <p>
 * <strong>What a load pays without this is not the translation.</strong> The translated programs
 * are already kept on disk, but the key they are looked up under is the FLATTENED text, so the
 * flattening runs before the lookup can even be attempted: the chain's forty odd units come to
 * three tenths of a second on the thread that draws, and the mount, the option walk and the
 * properties parse stand in front of them. All of it is a pure function of the archive's bytes and
 * the settings, and all of it was being thrown away at {@code close}.
 * <p>
 * <strong>Iris is not the model here, and the difference is worth stating rather than borrowing.
 * </strong> It keeps a parsed pack across a portal because it does not read the pack again there at
 * all; when it does reload, it builds a whole new one. This engine reads the pack again at a portal,
 * against a define table that has moved, so what it can keep is not a parsed pack but the reading
 * material underneath one: the mounted archive and the memos that are functions of its bytes.
 * <p>
 * <strong>One opening, and only for the reader that runs on the render thread.</strong> The memos
 * inside a {@link ShaderPackSource} belong to the single thread reading through it, so the family
 * worker keeps opening one of its own: what is offered here is offered to the chain's reader alone,
 * which is sequential by construction. A second pack, a settings change or a machine that answers
 * differently replaces what is held rather than joining it.
 * <p>
 * <strong>What decides that two loads may share an opening is written out rather than assumed.</strong>
 * The pack path, the settings the player chose, the profile, and the engine's whole define table,
 * which carries the world's registries and is exactly what moves under a pack at a world join. On
 * top of those the pack's own files are fingerprinted by size and modification time, so an archive
 * or a directory edited on the disk is opened afresh; a directory pack being the one every workshop
 * edits between two loads, that check is the reason this can be offered to a directory at all.
 * <p>
 * <strong>And what a reader worked out of an opening is NOT kept.</strong> {@link
 * ShaderPackSource#forgetDerived} empties those at that reading's close: a plan, a texture set or a
 * program tree is a function of more than the archive, the size of the window among it, and serving
 * one across a load would be a picture that is credible and wrong. What is kept is the mount, the
 * listings, the file lines and the flattened units, and each of those depends on nothing but the
 * archive's bytes and the settings this key already compares.
 * <p>
 * The line is drawn where the answers are FILED and not where they could have been: a reader is free
 * to file a pure function of the archive under {@code derived}, and one does, so that one is thrown
 * away with the rest. Trading a little of the saving for a rule with no exceptions is the deal, and
 * the exception is what would cost a picture.
 * <p>
 * <strong>What a hit does NOT save is worth having written down</strong>, so that nothing here is
 * read as more than it is: the dimension and program enumeration, the target plan, the pack's
 * textures, whose bytes are not memoised and are read again, the half translation that answers what
 * a chunk program reads, and every translation and compile after that. What it saves is the mount,
 * the listings, the file lines, the option walk, the properties parse and the flattening.
 * <p>
 * {@code -Dvitrail.keepPackOpen=false} closes every opening at its load's end, which is the road
 * before this class, and the line printed at every open names the one taken either way.
 */
final class KeptPack {

	/** Off by property rather than by rebuild, so a before and an after come out of one jar. */
	private static final boolean ENABLED = Boolean.parseBoolean(
			System.getProperty("vitrail.keepPackOpen", "true"));

	/**
	 * How many entries a pack may hold before it is fingerprinted at all. Far above the widest of
	 * the corpus, and low enough that a folder somebody dropped a world into cannot turn every
	 * pack load into a walk of it: past this the opening is simply not kept.
	 */
	private static final int MOST_ENTRIES = 20_000;

	/**
	 * The opening being held, with everything that decides whether it may serve, or null while
	 * none is.
	 * <p>
	 * The three travel as one object behind one volatile field rather than as three fields, and
	 * that is not decoration: the first reading of a session runs on the loader's setup thread on
	 * NeoForge and every reading after it on the render thread. Three fields could be seen in any
	 * mixture by the second thread, and one of those mixtures is an opening served under the key of
	 * another.
	 * <p>
	 * <strong>What this field does NOT publish is the opening's own memos</strong>, and nothing here
	 * could: they are filled by the reader after this method has returned. What publishes those is
	 * the loader joining the executor its setup ran on, which stands between that reading and the
	 * first frame, together with the fact that no two readings through one opening overlap. That
	 * second half is a property of the one caller entitled to this and is enforced by nothing but
	 * that: see {@link OpenedPack#openKept}.
	 */
	private static volatile Held held;

	private KeptPack() {
	}

	/**
	 * The pack opened, from the opening already held where every input it depends on still answers
	 * the same, and freshly otherwise.
	 */
	static OpenedPack open(Path packPath, Map<String, OptionValue> chosen, String profile)
			throws IOException {
		if (!ENABLED) {
			Vitrail.logger().info("Pack opened afresh: kept openings are off, "
					+ "property=vitrail.keepPackOpen");

			return OpenedPack.open(packPath, chosen, profile);
		}

		Key wanted = Key.of(packPath, chosen, profile);
		String print = fingerprint(packPath);
		Held standing = held;
		if (standing != null && print != null && wanted.equals(standing.key())
				&& print.equals(standing.print()) && atTheAskedScale(standing)) {
			ShaderPackSource source = standing.pack().source();
			Vitrail.logger().info("The pack was already open and is read again from that opening: "
					+ "{} files and {} flattened units kept, property=vitrail.keepPackOpen",
					source.filesRead(), source.unitsFlattened());

			return standing.pack();
		}

		// Opened before the one held is let go, so that a pack that cannot be read leaves the
		// session with the opening it had rather than with none.
		OpenedPack opened = OpenedPack.open(packPath, chosen, profile);
		Vitrail.logger().info("Pack opened afresh: {}, property=vitrail.keepPackOpen",
				why(standing, print, wanted));
		held = print == null ? null : new Held(opened, wanted, print);
		drop(standing);

		return opened;
	}

	/**
	 * Lets the opening go, for a load that has decided there is no pack to draw.
	 * <p>
	 * Those roads never reach {@link #open}, so nothing there would ever replace what is held, and
	 * a player who picks None would leave a mounted archive and a pack's worth of source strings
	 * standing for the rest of the session with no chain that could ever use them.
	 */
	static void forget() {
		Held standing = held;
		held = null;
		drop(standing);
	}

	/**
	 * Whether this opening is the one being held, which is what tells {@link OpenedPack#close}
	 * that its {@code close} is a no-op rather than the end of the archive.
	 * <p>
	 * By identity and not by value, and it has to be: {@link OpenedPack} is a record, so two
	 * openings of one pack under one set of settings answer equal while owning two mounted
	 * archives, and the wrong one would then be left open and the right one never closed.
	 */
	static boolean holds(OpenedPack pack) {
		Held standing = held;
		if (standing == null) {
			return false;
		}

		@SuppressWarnings("ReferenceEquality")
		boolean same = standing.pack() == pack;

		return same;
	}

	/**
	 * Whether the opening held resolved its settings at the shadow map scale the engine is asking
	 * for now.
	 * <p>
	 * <strong>Asked of the reading itself and not mirrored into the key</strong>, because the scale
	 * is the one input a {@link SettingSet} takes that is neither an argument of the reading nor
	 * part of the define table: it is pushed onto that class as a static, so a key built beside it
	 * would go on answering right while the two drifted. What it guards is the setting's whole
	 * point. The scale is applied by rewriting the pack's own declaration of the shadow map size in
	 * the flattened text, so a reading served at the old number carries the old declaration, the map
	 * is allocated off that same text, and the slider becomes a silent no-op for the rest of the
	 * session while the log says it moved.
	 */
	private static boolean atTheAskedScale(Held standing) {
		return standing.pack().settings().scale() == SettingSet.askedShadowMapScale();
	}

	/** Why the opening held could not serve, in the words the line above prints. */
	private static String why(Held standing, String print, Key wanted) {
		if (standing == null) {
			return "none was held";
		}

		if (print == null) {
			return "this one is not fingerprinted, so it will not be held either";
		}

		if (!wanted.equals(standing.key())) {
			return "the pack, its settings or the machine's defines have moved";
		}

		if (!atTheAskedScale(standing)) {
			return "the shadow map scale has moved";
		}

		return "its files have moved on the disk";
	}

	private static void drop(Held standing) {
		if (standing == null) {
			return;
		}

		try {
			standing.pack().source().close();
		} catch (IOException | RuntimeException e) {
			// Said rather than thrown: the load that provoked this has a fresh opening in hand and
			// nothing about it is wrong, and a mounted archive that would not close is a handle
			// held until the game is closed rather than a reason to refuse a pack.
			Vitrail.logger().warn("Vitrail could not close the pack opening it was holding", e);
		}
	}

	/** The opening held and the two answers it is held under. */
	private record Held(OpenedPack pack, Key key, String print) {
	}

	/**
	 * What the pack's files are, by their names, their sizes and when they were last written, or
	 * null when the walk could not answer, in which case nothing is held.
	 * <p>
	 * Names and stamps and not content: reading a pack whole to decide whether to avoid reading it
	 * whole answers nothing. What that misses is an edit that leaves the size and the stamp where
	 * they were, which no editor does and which the settings screen's own reload answers anyway.
	 * <p>
	 * Directories go in beside the files, under their own word: a place is a DIRECTORY of a pack,
	 * so one added or removed changes which programs a world is served even when it holds no file
	 * of its own.
	 */
	private static String fingerprint(Path packPath) {
		List<String> entries = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(packPath)) {
			for (Path path : (Iterable<Path>) walk::iterator) {
				if (entries.size() >= MOST_ENTRIES) {
					return null;
				}

				// What the READER will read, which for a symlink is the target and not the link:
				// the reading side goes through Files.readAllBytes, which follows, so a print taken
				// off the link would stand still while the file the pack is built from moved.
				//
				// One entry that cannot be stat'd, a broken link among them, is written down as
				// exactly that rather than abandoning the whole print: a single dangling link in a
				// pack folder would otherwise make that pack unfingerprintable, and with it never
				// kept again for the rest of the session, in silence. A target that appears later
				// makes the read succeed, which moves the entry, which moves the print.
				BasicFileAttributes attributes;
				try {
					attributes = Files.readAttributes(path, BasicFileAttributes.class);
				} catch (IOException e) {
					entries.add(packPath.relativize(path) + " unreadable");

					continue;
				}

				if (attributes.isDirectory()) {
					entries.add(packPath.relativize(path) + " dir");

					continue;
				}

				entries.add(packPath.relativize(path) + " " + attributes.size() + " "
						+ attributes.lastModifiedTime().toMillis());
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}

		// Sorted rather than taken in the walk's own order, which is the filesystem's business and
		// not a property of the pack.
		Collections.sort(entries);
		MessageDigest digest = sha256();
		for (String entry : entries) {
			digest.update(entry.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
		}

		return HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
		}
	}

	/**
	 * Everything outside the archive that decides what a reading of it produces.
	 *
	 * @param settings the chosen values written out, because {@link OptionValue} answers equality by
	 *                 identity and two readings of one file build two objects for one value
	 * @param defines  the engine's whole table, which is what a world join moves and what every
	 *                 flattened unit was branched on
	 */
	private record Key(Path packPath, String settings, String profile, Map<String, String> defines) {

		static Key of(Path packPath, Map<String, OptionValue> chosen, String profile) {
			List<String> written = new ArrayList<>(chosen.size());
			chosen.forEach((name, value) -> written.add(name + " " + value.isBoolean()
					+ " " + Objects.toString(value.asText(), "")));
			Collections.sort(written);

			return new Key(packPath, String.join("\n", written), profile,
					Map.copyOf(EngineDefines.table(EngineDefines.machine())));
		}
	}
}
