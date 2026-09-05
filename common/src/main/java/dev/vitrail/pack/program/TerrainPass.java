package dev.vitrail.pack.program;

import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.RenderStage;
import dev.vitrail.pack.source.ShaderProperties;

import java.util.Map;

/**
 * The passes the chunk renderer draws the world in, and what a pack owes each of them.
 * <p>
 * Sodium meshes a section once and draws it three times, in this order, with the same vertex format
 * every time. What changes between them is not the geometry: it is which program of the pack serves
 * the pass, what alpha it discards at, and whether the result is blended into what is already there.
 * All three are properties of the pass rather than of the file, which is why the same
 * {@code gbuffers_terrain} can serve two of them and behave differently in each.
 * <p>
 * The shadow map adds two more, drawn from the same mesh with the same renderer and every difference
 * carried here: another program, another target, another depth convention. They are in this enum
 * rather than in one of their own because everything downstream is keyed by the pass, and a second
 * enum would mean a second answer to "which program serves this draw".
 * <p>
 * The names and the defaults are Iris's, taken from {@code SodiumPrograms.Pass} and
 * {@code SodiumPrograms.getAlphaTest}, read on 3 August 2026. Iris is copyright the Iris
 * contributors and licensed under the GNU LGPL version 3, the same licence as this project. They
 * are reproduced rather than chosen because packs are written against Iris and against nothing
 * else: a cutout threshold of a tenth instead of a half is not a preference, it is leaves with the
 * wrong silhouette.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
public enum TerrainPass {

	/** Everything opaque. No alpha test at all, and nothing to blend with. */
	SOLID("gbuffers_terrain_solid", AlphaTest.OFF, false, false),

	/**
	 * Leaves, grass, glass panes, torches. The pass whose whole point is the discard, and the reason
	 * a pack cannot be given the cutout pass without one: the texture is opaque where it is drawn and
	 * transparent everywhere else, so without a test a leaf is a cube.
	 */
	CUTOUT("gbuffers_terrain_cutout", AlphaTest.CUTOUT, false, false),

	/** Water, ice, stained glass. Blended, and discarding only what is completely transparent. */
	TRANSLUCENT("gbuffers_water", AlphaTest.NON_ZERO, true, false),

	/** The opaque world seen from the light, into the shadow map. */
	SHADOW_SOLID("shadow_solid", AlphaTest.OFF, false, true),

	/**
	 * The same for the cutout half, and the discard is what makes it worth a second pass: leaves
	 * without one cast the shadow of a cube, which is the one shadow artefact everybody recognises.
	 */
	SHADOW_CUTOUT("shadow_cutout", AlphaTest.CUTOUT, false, true),

	/**
	 * Water, ice and stained glass seen from the light, and it is not blended: what a shadow map
	 * wants from a translucent surface is the depth it stands at and the colour it tints the light
	 * with, both written outright. Iris gives this half no alpha test either, so a pack that means
	 * to let something through says so with a discard of its own.
	 */
	SHADOW_TRANSLUCENT("shadow_water", AlphaTest.OFF, false, true);

	private final String program;
	// A record over an enum and a float, so it is immutable in fact; the analyser wants an
	// annotation to prove it, and that annotation is not on this compile classpath.
	@SuppressWarnings("ImmutableEnumChecker")
	private final AlphaTest fallback;
	private final boolean blended;
	private final boolean shadow;

	TerrainPass(String program, AlphaTest fallback, boolean blended, boolean shadow) {
		this.program = program;
		this.fallback = fallback;
		this.blended = blended;
		this.shadow = shadow;
	}

	/** The program to ask the pack for. It resolves through the fallback tree like any other. */
	public String program() {
		return this.program;
	}

	/** Whether what this pass draws is blended into the target rather than replacing it. */
	public boolean blended() {
		return this.blended;
	}

	/**
	 * Whether this pass draws the shadow map rather than the world, which decides everything the
	 * two halves do not share: the target, the matrices the fixed function names answer, the depth
	 * convention the map stores, and the moment of the frame it is drawn at.
	 */
	public boolean shadow() {
		return this.shadow;
	}

	/**
	 * Whether this pass writes the mask saying where it drew, which is what keeps the game's own
	 * picture off the pixels the pack has already answered for.
	 * <p>
	 * The two opaque halves of the world do, and nothing else. The translucent half is drawn after
	 * that picture has been put in and blends onto it, so it has nothing to keep out; the shadow map
	 * is not the picture at all.
	 */
	public boolean covers() {
		return !this.blended && !this.shadow;
	}

	/**
	 * The same geometry drawn into the shadow map, or null for a half the shadow stage does not
	 * draw. The renderer knows only its own three passes, so this is how one of them becomes the
	 * shadow pass that shares its mesh.
	 * <p>
	 * A pass with no shadow counterpart answers null and has to leave the renderer's own shader
	 * alone, rather than be given a program written for another one.
	 */
	public TerrainPass inShadow() {
		return switch (this) {
			case SOLID -> SHADOW_SOLID;
			case CUTOUT -> SHADOW_CUTOUT;
			case TRANSLUCENT -> SHADOW_TRANSLUCENT;
			default -> null;
		};
	}

	/**
	 * Whether this pass draws after the deferred stage rather than before it, which decides the
	 * halves its targets are on.
	 * <p>
	 * The OptiFine frame runs the opaque geometry, then the deferreds, then the translucent
	 * geometry, and Iris wires Sodium the same way: every chunk pass is handed the
	 * {@code flippedAfterPrepare} snapshot except {@code Pass.TRANSLUCENT}, which is handed
	 * {@code flippedAfterTranslucent}, the state the deferreds leave behind. Answered by the pass
	 * and never by the file that serves it, exactly as Iris keys it: one {@code gbuffers_terrain}
	 * can serve two passes standing on either side of that boundary.
	 */
	public boolean afterDeferred() {
		return this == TRANSLUCENT;
	}

	/**
	 * What a pack is told it is drawing, which it reads as {@code renderStage}.
	 * <p>
	 * The three halves of the world take the name of their own half rather than one name for the
	 * opaque pair. Iris's vanilla path cannot tell them apart, {@code fromTerrainRenderType} being
	 * handed a whole {@code ChunkSectionLayerGroup} and answering {@code TERRAIN_SOLID} for both, and
	 * its Sodium path sets no phase at all; but the constants exist separately, the renderer really
	 * does draw the two in two passes, and telling a pack that a cutout draw is a solid one is a
	 * worse answer than the one this can give.
	 * <p>
	 * <strong>A shadow half answers the same name as the half it shadows, and that is a judgement
	 * rather than a reading.</strong> The enum has no shadow value at all, so the choice is between
	 * naming the geometry and saying nothing, and the geometry is the same terrain drawn by the same
	 * renderer. Nothing is lost either way: the shadow halves are served by their own programs, so a
	 * pack tells them apart before it ever looks at this.
	 */
	public RenderStage stage() {
		return switch (this) {
			case SOLID, SHADOW_SOLID -> RenderStage.TERRAIN_SOLID;
			case CUTOUT, SHADOW_CUTOUT -> RenderStage.TERRAIN_CUTOUT;
			case TRANSLUCENT, SHADOW_TRANSLUCENT -> RenderStage.TERRAIN_TRANSLUCENT;
		};
	}

	/**
	 * The alpha test this pass is drawn under, once the pack has had its say.
	 *
	 * @param overrides what {@link ShaderProperties#alphaTests} resolved under the settings in
	 *                  force
	 * @param servedBy  the program that really serves this pass, which is the name the pack writes
	 *                  the override under. A pack shipping one {@code gbuffers_terrain} and a line
	 *                  {@code alphaTest.gbuffers_terrain=GREATER 0.1} moves both the solid and the
	 *                  cutout pass with it, which is what Iris does and what Bliss counts on
	 */
	public AlphaTest alphaTest(Map<String, AlphaTest> overrides, String servedBy) {
		return overrides.getOrDefault(servedBy, this.fallback);
	}
}
