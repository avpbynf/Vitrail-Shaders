package dev.vitrail.pack.model;

import dev.vitrail.pack.model.ProgramNames;

import java.util.Optional;

/**
 * The seven moments of a frame a pack may hang one of its own textures on, and which programs
 * each of them covers.
 * <p>
 * There are seven names and nine families, because two of the names cover two families each:
 * {@code gbuffers} covers the shadow passes as well, and {@code composite} covers the final. That
 * is not a shorthand a reader may undo. A pack overriding {@code colortex3} for
 * {@code composite} means it for the final too, and Iris hands both renderers the one map.
 * <p>
 * A name outside the seven is a line this engine drops with a word in the log rather than a
 * stage it invents: the set is closed in Iris and in OptiFine both, and a pack writing
 * {@code texture.shadow.x} has written a line nothing has ever honoured.
 */
public enum TextureStage {

	SETUP,
	BEGIN,
	SHADOWCOMP,
	PREPARE,
	/** Every gbuffers program, and the shadow passes with them. */
	GBUFFERS,
	DEFERRED,
	/** Every composite, and the final with them. */
	COMPOSITE;

	/** The word a pack writes between {@code texture.} and the sampler name. */
	public static Optional<TextureStage> parse(String name) {
		return switch (name) {
			case "setup" -> Optional.of(SETUP);
			case "begin" -> Optional.of(BEGIN);
			case "shadowcomp" -> Optional.of(SHADOWCOMP);
			case "prepare" -> Optional.of(PREPARE);
			case "gbuffers" -> Optional.of(GBUFFERS);
			case "deferred" -> Optional.of(DEFERRED);
			case "composite" -> Optional.of(COMPOSITE);
			default -> Optional.empty();
		};
	}

	/**
	 * Which stage a program is drawn in, by its bare name, {@code composite4} or
	 * {@code gbuffers_water}. Empty for a name no family claims, which is a name nothing runs.
	 */
	public static Optional<TextureStage> of(String program) {
		String family = ProgramNames.familyOf(program);
		if (ProgramNames.geometry(family)) {
			return Optional.of(GBUFFERS);
		}

		return switch (family) {
			case "setup" -> Optional.of(SETUP);
			case "begin" -> Optional.of(BEGIN);
			case "shadowcomp" -> Optional.of(SHADOWCOMP);
			case "prepare" -> Optional.of(PREPARE);
			case "deferred" -> Optional.of(DEFERRED);
			case "composite", "final" -> Optional.of(COMPOSITE);
			default -> Optional.empty();
		};
	}
}
