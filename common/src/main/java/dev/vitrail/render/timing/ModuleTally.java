package dev.vitrail.render.timing;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How many of the programs a family built are the same text over again, how the modules they asked
 * the compiler for were answered, and the words that say so. The sums behind {@link ModuleCensus},
 * held apart for the reason {@link FrameTally} is.
 * <p>
 * Two things are counted, on the two sides of the compiler. A program is identified by what it hands
 * it: the text of its vertex stage, the text of its fragment stage, the text of a geometry stage the
 * device binds as a module of its own, and the layout of the mesh it reads. Two programs with all of
 * those equal are one compile made twice. That is what the pack asks for, and the compiler's answers
 * are what the engine did about it: every module a program asked for was compiled, served from the
 * disk cache, or shared with an earlier program of the same load, and the compiled ones are keyed by
 * the digest of what they were compiled from, so a text compiled twice shows as a repeat.
 * <p>
 * Texts on the first side are held as a 64 bit hash and never as the text, so a tally over a pack of
 * a few hundred programs costs a few hundred longs instead of the megabytes the stages come to. The
 * hash is a census's: wide enough that two stages of one pack meeting in it is not a thing to plan
 * for, and no key anything is decided by. The second side holds the digests the cache already made.
 * <p>
 * A module is filed under the family whose program owns its name, and one nobody registered is filed
 * under {@link #UNOWNED}: the composites, the computes and the game's own shaders are asked for through
 * the same compiler, and leaving them out would make the compiled total disagree with the count the
 * module cache prints for the same load.
 * <p>
 * <strong>Synchronized, unlike the frame tally</strong>: the programs are built on the pack-load
 * workers, more than one at a time, the modules are asked for from every thread that compiles, and the
 * report is read from the render thread and from the worker that closes the warm-up.
 */
final class ModuleTally {

	/** What the modules no program owns are filed under. */
	static final String UNOWNED = "units no program owns (composites, computes, the game's own)";

	/** How a module a program asked the compiler for was answered. */
	enum Supply {
		/** The compiler ran. */
		COMPILED,
		/** The disk cache held it. */
		SERVED,
		/** An earlier program of the same load had it made already. */
		SHARED
	}

	/** The things a program hands the compiler that decide whether it is a new one. */
	private record Triple(long vertex, long fragment, long geometry, Object format) {
	}

	/** One family's programs and modules, in the order they arrived. */
	private static final class Family {

		int programs;

		/** How many programs and modules the last line printed for this family knew of. */
		int reported;

		final Set<Triple> triples = new HashSet<>();
		final Set<Long> vertices = new HashSet<>();
		final Set<Long> fragments = new HashSet<>();

		int compiled;
		int served;
		int shared;

		/** Compiled modules that had a key, and which of the keys are distinct. */
		int keyed;
		final Set<String> keys = new HashSet<>();

		int asked() {
			return this.compiled + this.served + this.shared;
		}

		int known() {
			return this.programs + asked();
		}
	}

	private final Map<String, Family> families = new LinkedHashMap<>();

	/** The family that owns each module name a program registered. */
	private final Map<String, String> owners = new HashMap<>();

	/**
	 * A 64 bit FNV-1a over the characters of a text: no allocation, one pass, and wide enough that two
	 * different stages of one pack meeting in it is not a thing to plan for.
	 */
	static long hash(String text) {
		long hash = 0xcbf29ce484222325L;
		for (int at = 0; at < text.length(); at++) {
			hash ^= text.charAt(at);
			hash *= 0x100000001b3L;
		}

		return hash;
	}

	/**
	 * One program built.
	 *
	 * @param family   what the log calls the family, {@code entity} or {@code chunk}
	 * @param vertex   {@link #hash} of the vertex stage the compiler is handed
	 * @param fragment {@link #hash} of the fragment stage
	 * @param geometry {@link #hash} of the geometry stage the device binds as a module, or 0 for a
	 *                 program with none
	 * @param format   the mesh layout, compared by {@code equals}, or null for a family with none
	 * @param names    the names the program's modules reach the compiler under, so that what is asked
	 *                 for under them is filed under this family
	 */
	synchronized void built(String family, long vertex, long fragment, long geometry, Object format,
			List<String> names) {
		Family tally = this.families.computeIfAbsent(family, name -> new Family());
		tally.programs++;
		tally.triples.add(new Triple(vertex, fragment, geometry, format));
		tally.vertices.add(vertex);
		tally.fragments.add(fragment);
		for (String name : names) {
			this.owners.put(name, family);
		}
	}

	/**
	 * One module asked of the compiler and how it was answered.
	 *
	 * @param name the name the module was asked for under, which is how its family is found
	 * @param unit the digest of what the module is made from, or null for a module the cache cannot
	 *             key, which is never counted as a repeat of another
	 * @param how  what became of the ask
	 */
	synchronized void unit(String name, @Nullable String unit, Supply how) {
		Family tally = this.families.computeIfAbsent(this.owners.getOrDefault(name, UNOWNED),
				family -> new Family());
		switch (how) {
			case COMPILED -> {
				tally.compiled++;
				if (unit != null) {
					tally.keyed++;
					tally.keys.add(unit);
				}
			}

			case SERVED -> tally.served++;
			case SHARED -> tally.shared++;
		}
	}

	/**
	 * A line for every family that has built programs or been asked for modules since it was last
	 * said, in the order the families first appeared. A family whose counts have not moved says
	 * nothing again, and one that gained a program or a module says its whole tally over, the terrain
	 * being the one that does: its programs are made when the renderer first asks for its shader and
	 * not with the other six.
	 * <p>
	 * <strong>What is compiled is said next to what was asked for, and its repeats are the compiled
	 * modules whose digest an earlier compile of the load had already made.</strong> They are nought
	 * where the load shares its modules and the disk cache serves the rest, and every duplicate where
	 * neither does.
	 */
	synchronized List<String> lines() {
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, Family> entry : this.families.entrySet()) {
			Family family = entry.getValue();
			if (family.known() == family.reported) {
				continue;
			}

			family.reported = family.known();
			StringBuilder line = new StringBuilder("Module census, ").append(entry.getKey())
					.append(": ");
			if (family.programs > 0) {
				line.append(String.format(Locale.ROOT, "programs built %d, distinct (vertex text, "
								+ "fragment text, vertex format) triples %d, distinct vertex texts %d, "
								+ "distinct fragment texts %d; ", family.programs,
						family.triples.size(), family.vertices.size(), family.fragments.size()));
			}

			line.append(String.format(Locale.ROOT, "modules asked for %d: compiled %d (of which "
							+ "repeats %d), served from the disk cache %d, shared within the load %d",
					family.asked(), family.compiled, family.keyed - family.keys.size(), family.served,
					family.shared));
			lines.add(line.toString());
		}

		return lines;
	}

	/** How many distinct triples a family has built, for the tests. */
	synchronized int triples(String family) {
		Family tally = this.families.get(family);

		return tally == null ? 0 : tally.triples.size();
	}

	/** How many modules a family had compiled, for the tests. */
	synchronized int compiled(String family) {
		Family tally = this.families.get(family);

		return tally == null ? 0 : tally.compiled;
	}

	synchronized void clear() {
		this.families.clear();
		this.owners.clear();
	}
}
