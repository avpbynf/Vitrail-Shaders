package dev.vitrail.pack.program;

/**
 * What a pack is told it is drawing, under the name {@code renderStage} and the constants
 * {@code MC_RENDER_STAGE_*}.
 * <p>
 * The order is the whole content of this enum, because the value a pack reads is the ORDINAL and
 * nothing else: {@code EngineDefines} writes each name out against its position here, and the block
 * carries the same number, so a constant inserted in the middle silently renumbers every branch
 * every pack of the corpus takes. It is Iris's {@code pipeline/WorldRenderingPhase}, reproduced
 * as it stands and for the reason everything else of Iris's is: packs are written against it.
 * <p>
 * One table and not two. The names and the numbers used to be written out a second time in
 * {@code EngineDefines}, which is the shape of a failure nobody finds: two lists that agree today
 * and are edited on different days.
 * <p>
 * Most of these are not reachable yet. Nothing here draws particles, the hand or the world border,
 * and a stage nothing sets is simply never answered; the constants are defined all the same, since
 * a pack comparing against one it was not given is a wall of undeclared identifiers.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
public enum RenderStage {

	/** Everything drawn outside a gbuffers pass, which is every full screen pass of the chain. */
	NONE,
	SKY,
	SUNSET,
	CUSTOM_SKY,
	SUN,
	MOON,
	STARS,

	/** The dark disc under the horizon, which the game draws and Iris calls the void. */
	VOID,
	TERRAIN_SOLID,
	TERRAIN_CUTOUT_MIPPED,
	TERRAIN_CUTOUT,
	ENTITIES,
	BLOCK_ENTITIES,
	DESTROY,
	OUTLINE,
	DEBUG,
	HAND_SOLID,
	TERRAIN_TRANSLUCENT,
	TRIPWIRE,
	PARTICLES,
	CLOUDS,
	RAIN_SNOW,
	WORLD_BORDER,
	HAND_TRANSLUCENT;

	/** The symbol a pack compares {@code renderStage} against, for this stage. */
	public String symbol() {
		return "MC_RENDER_STAGE_" + name();
	}
}
