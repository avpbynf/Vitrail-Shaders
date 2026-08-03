package dev.vitrail.pack.program;

import dev.vitrail.pack.source.ShaderProperties;

/**
 * The three passes the chunk renderer draws the world in, and what a pack owes each of them.
 * <p>
 * Sodium meshes a section once and draws it three times, in this order, with the same vertex format
 * every time. What changes between them is not the geometry: it is which program of the pack serves
 * the pass, what alpha it discards at, and whether the result is blended into what is already there.
 * All three are properties of the pass rather than of the file, which is why the same
 * {@code gbuffers_terrain} can serve two of them and behave differently in each.
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
	SOLID("gbuffers_terrain_solid", AlphaTest.OFF, false),

	/**
	 * Leaves, grass, glass panes, torches. The pass whose whole point is the discard, and the reason
	 * a pack cannot be given the cutout pass without one: the texture is opaque where it is drawn and
	 * transparent everywhere else, so without a test a leaf is a cube.
	 */
	CUTOUT("gbuffers_terrain_cutout", AlphaTest.CUTOUT, false),

	/** Water, ice, stained glass. Blended, and discarding only what is completely transparent. */
	TRANSLUCENT("gbuffers_water", AlphaTest.NON_ZERO, true);

	private final String program;
	private final AlphaTest fallback;
	private final boolean blended;

	TerrainPass(String program, AlphaTest fallback, boolean blended) {
		this.program = program;
		this.fallback = fallback;
		this.blended = blended;
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
	 * The alpha test this pass is drawn under, once the pack has had its say.
	 *
	 * @param servedBy the program that really serves this pass, which is the name the pack writes
	 *                 the override under. A pack shipping one {@code gbuffers_terrain} and a line
	 *                 {@code alphaTest.gbuffers_terrain=GREATER 0.1} moves both the solid and the
	 *                 cutout pass with it, which is what Iris does and what Bliss counts on
	 */
	public AlphaTest alphaTest(ShaderProperties properties, String servedBy) {
		return properties.alphaTest(servedBy).orElse(this.fallback);
	}
}
