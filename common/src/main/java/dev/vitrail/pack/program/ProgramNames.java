package dev.vitrail.pack.program;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What counts as the name of a program, and what does not.
 * <p>
 * The list of {@code gbuffers_} names is closed on purpose. The measuring prototype accepted
 * anything after the prefix, which is fine when the goal is to count files, but an engine has
 * to know what it is being handed: a name outside this list has no place to be drawn from and
 * silently ignoring it would leave the pack looking broken with nothing in the log. Anything
 * rejected here is meant to be reported, not dropped.
 */
public final class ProgramNames {

	/**
	 * The named programs are exactly those that take part in the fallback tree, so the list
	 * lives in one place. Keeping a second copy here is how the two drift apart and how a
	 * program ends up recognised but unplaceable, or the reverse.
	 */
	private static Set<String> named() {
		return ProgramFallbacks.names();
	}

	/**
	 * Families that carry an optional number, as in {@code composite3}. Setup and begin belong
	 * here too, however singular they sound: a pack may write {@code setup1} through
	 * {@code setup99} and expect them run in order.
	 */
	private static final Set<String> NUMBERED = Set.of(
			"composite", "deferred", "prepare", "shadowcomp", "setup", "begin");

	/** Families that stand alone. */
	private static final Set<String> SIMPLE = Set.of("shadow", "final");

	/**
	 * The full screen families whose passes a compute may hang off, which is every family the
	 * chain draws. Setup is the one left out: Iris runs it once at load and this engine has no
	 * such moment yet.
	 */
	private static final Set<String> CHAINED = Set.of("begin", "prepare", "deferred", "composite",
			"final");

	/** Highest slot the format allows on a numbered family. */
	private static final int MAX_SLOT = 99;

	/**
	 * An underscore and a letter mark a compute pass attached to the program before it, as in
	 * {@code composite21_a.csh}. The underscore is required: without it, any program whose name
	 * happens to end in a letter would be read as a compute pass hanging off a shorter name.
	 */
	private static final Pattern COMPUTE_SUFFIX = Pattern.compile("^(.*?)_([a-z])$");

	/** Where the world is drawn, which everything else is placed before or after. */
	public static final int GEOMETRY_RANK = 3;

	private ProgramNames() {
	}

	/**
	 * The order a frame runs the families in, which is not the order their directives are folded
	 * in. Kept here rather than beside either of its two readers: a schedule and a plan that
	 * disagree about where a stage begins produce no error, only a half read in one place and
	 * written in another.
	 */
	public static int frameRank(String family) {
		if (geometry(family)) {
			return GEOMETRY_RANK;
		}

		return switch (family) {
			case "begin" -> 0;
			case "shadowcomp" -> 1;
			case "prepare" -> 2;
			case "deferred" -> 4;
			case "composite" -> 5;
			default -> 6;
		};
	}

	/**
	 * The order Iris folds directives in, from {@code ProgramSet.locateDirectives}. Setup programs
	 * are not in it: they go to the compute list there and are only ever read for their work group
	 * size, so they declare no format however they are written.
	 * <p>
	 * Kept beside {@link #frameRank} rather than beside either of its own readers, for the same
	 * reason: two readers that disagree about where directives fold produce no error, only a wrong
	 * answer in one place and not the other.
	 */
	public static int directiveRank(String family) {
		return switch (family) {
			case "shadowcomp" -> 0;
			case "begin" -> 1;
			case "prepare" -> 2;
			case "deferred" -> 4;
			case "composite" -> 5;
			default -> 3;
		};
	}

	/** A pass drawn over the world rather than over a quad, which never flips anything. */
	public static boolean geometry(String family) {
		return family.startsWith("gbuffers") || family.startsWith("dh_")
				|| shadowGeometry(family);
	}

	/** A full screen pass over the shadow targets, which are not the colour targets of a place. */
	public static boolean shadowComposite(String family) {
		return family.equals("shadowcomp");
	}

	/**
	 * Geometry drawn from the light instead of from the camera, whose draw buffers name
	 * {@code shadowcolor} and never {@code colortex}.
	 * <p>
	 * The whole of what it is for is that a number written there indexes another set of targets. A
	 * shadow program read as a colour one allocates somebody's colortex, says it is written, and
	 * sends the shadow map's albedo into whatever that index means to the chain.
	 * <p>
	 * <strong>{@code dh_shadow} is one of them and it does not begin with {@code shadow}.</strong>
	 * It is the Distant Horizons geometry drawn from the light, Iris sends it to the shadow
	 * framebuffer like the rest ({@code IrisRenderingPipeline.java:1366-1368}), and a list matching
	 * the prefix alone lets it through. Bliss ships one, {@code world0/dh_shadow.fsh}, with no
	 * directive on it: read as a colour program it allocates a colortex0 nothing writes.
	 */
	public static boolean shadowGeometry(String family) {
		return family.equals("shadow") || family.startsWith("shadow_") || family.equals("dh_shadow");
	}

	/** The family a program belongs to, {@code composite} for {@code composite4}. */
	public static String familyOf(String baseName) {
		return parse(baseName).map(ProgramName::family).orElse(baseName);
	}

	/**
	 * The full screen pass a compute file hangs off, {@code deferred4} for {@code deferred4_a} and
	 * for {@code deferred4} itself: Iris reads the letter-less file first and the lettered ones
	 * after it, stopping at the first letter missing ({@code ProgramSet.readComputeArray}), all
	 * off the same pass. Empty for a file that hangs off nothing this engine runs as a pass,
	 * setup among them. Whether the pass itself runs, and whether the letter follows on from the
	 * one before, is the plan's to say.
	 *
	 * @param file the compute file's name without its extension, which is a name of its own
	 */
	public static Optional<String> computeBase(String file) {
		return parse(file)
				.filter(name -> CHAINED.contains(name.family()))
				.map(ProgramName::baseName);
	}

	/**
	 * The letter that orders the computes of one pass, the letter-less file first and then
	 * {@code a} before {@code b}.
	 */
	public static String computeLetter(String file) {
		return parse(file).map(ProgramName::computeSuffix).orElse("");
	}

	public static Optional<ProgramName> parse(String baseName) {
		if (named().contains(baseName) || SIMPLE.contains(baseName)) {
			return Optional.of(new ProgramName(baseName, -1, null));
		}

		Optional<ProgramName> numbered = parseNumbered(baseName);
		if (numbered.isPresent()) {
			return numbered;
		}

		// Only after the plain forms have failed: a compute pass is written by hanging a letter
		// off a name that has to be valid on its own.
		Matcher compute = COMPUTE_SUFFIX.matcher(baseName);
		if (compute.matches()) {
			String stem = compute.group(1);
			if (SIMPLE.contains(stem)) {
				return Optional.of(new ProgramName(stem, -1, compute.group(2)));
			}

			Optional<ProgramName> stemNumbered = parseNumbered(stem);
			if (stemNumbered.isPresent()) {
				ProgramName base = stemNumbered.get();
				return Optional.of(new ProgramName(base.family(), base.slot(), compute.group(2)));
			}
		}

		return Optional.empty();
	}

	private static Optional<ProgramName> parseNumbered(String baseName) {
		for (String family : NUMBERED) {
			if (!baseName.startsWith(family)) {
				continue;
			}

			String tail = baseName.substring(family.length());
			if (tail.isEmpty()) {
				return Optional.of(new ProgramName(family, 0, null));
			}

			if (tail.length() > 2 || !tail.chars().allMatch(Character::isDigit)) {
				continue;
			}

			int slot = Integer.parseInt(tail);
			if (slot <= MAX_SLOT) {
				return Optional.of(new ProgramName(family, slot, null));
			}
		}

		return Optional.empty();
	}

	/**
	 * A program's identity: its family, the slot for the numbered families, and the letter that
	 * marks a compute pass hanging off it.
	 */
	public record ProgramName(String family, int slot, String computeSuffix) {

		public boolean isCompute() {
			return this.computeSuffix != null;
		}

		public String baseName() {
			return this.slot < 0 ? this.family : this.family + (this.slot == 0 ? "" : Integer.toString(this.slot));
		}
	}
}
