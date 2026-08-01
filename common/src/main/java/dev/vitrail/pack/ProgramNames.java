package dev.vitrail.pack;

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

	/** Every {@code gbuffers_} program the format defines. */
	private static final Set<String> GBUFFERS = Set.of(
			"gbuffers_basic",
			"gbuffers_line",
			"gbuffers_textured",
			"gbuffers_textured_lit",
			"gbuffers_skybasic",
			"gbuffers_skytextured",
			"gbuffers_clouds",
			"gbuffers_terrain",
			"gbuffers_terrain_solid",
			"gbuffers_terrain_cutout_mip",
			"gbuffers_terrain_cutout",
			"gbuffers_damagedblock",
			"gbuffers_block",
			"gbuffers_beaconbeam",
			"gbuffers_item",
			"gbuffers_entities",
			"gbuffers_entities_glowing",
			"gbuffers_armor_glint",
			"gbuffers_spidereyes",
			"gbuffers_hand",
			"gbuffers_weather",
			"gbuffers_water",
			"gbuffers_hand_water",
			"gbuffers_particles",
			"gbuffers_particles_translucent",
			"gbuffers_entities_translucent",
			"gbuffers_block_translucent",
			"gbuffers_lightning",
			"gbuffers_shadow");

	/**
	 * Programs that draw Distant Horizons geometry. They are a later addition to the format
	 * rather than part of the original set, and packs ship them whether or not that mod is
	 * installed, so leaving them out makes a pack look short of a dozen programs.
	 */
	private static final Set<String> DISTANT_HORIZONS = Set.of(
			"dh_terrain", "dh_water", "dh_shadow", "dh_generic");

	/** Families that carry an optional number, as in {@code composite3}. */
	private static final Set<String> NUMBERED = Set.of(
			"composite", "deferred", "prepare", "shadowcomp");

	/** Families that stand alone. */
	private static final Set<String> SIMPLE = Set.of(
			"shadow", "final", "setup", "begin");

	/** Highest slot the format allows on a numbered family. */
	private static final int MAX_SLOT = 99;

	/**
	 * A trailing letter marks a compute pass attached to the program before it. The underscore
	 * is optional because both spellings are in use across the corpus, and a pack that writes
	 * {@code composite21_a.csh} would otherwise lose every compute pass it has.
	 */
	private static final Pattern COMPUTE_SUFFIX = Pattern.compile("^(.*?)_?([a-z])$");

	private ProgramNames() {
	}

	public static Optional<ProgramName> parse(String baseName) {
		if (GBUFFERS.contains(baseName) || SIMPLE.contains(baseName) || DISTANT_HORIZONS.contains(baseName)) {
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

	public static Set<String> gbuffers() {
		return GBUFFERS;
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
