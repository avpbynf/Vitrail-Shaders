package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

/**
 * The Distant Horizons values, supplied unconditionally.
 * <p>
 * Unconditionally, because the names are read outside the {@code DISTANT_HORIZONS} guard by packs
 * that only meant to be careful, and a name nothing declares is a wall of undeclared identifiers
 * rather than a missing feature. This is what closes the twenty four {@code dhProjection} failures
 * the translation stage measured, and it is what Iris does as well: it registers all six without a
 * condition.
 * <p>
 * What each of them answers is {@code render/ViewMatrices}'s and moves with the frame. <strong>The
 * two planes and the render distance fall back on questions of their own, and that is Iris's shape
 * rather than a convenience.</strong> The planes are DH's own the moment DH has drawn a frame, and
 * without one they fall back to Iris's 0.01, {@code compat/dh/DHCompat.java:94} and {@code :104},
 * deliberately a number no pack can mistake for a real distance. The render distance answers
 * earlier than that, as soon as that mod holds a configuration and its rendering switch is on,
 * because it is a setting rather than something read off a drawn matrix; it falls back to the
 * game's own in chunks, which is Iris's fallback as well, {@code compat/dh/DHCompat.java:114}, and
 * the change of unit that comes with it is on {@code uniform/ViewSource}. What holding the
 * distance back to the planes cost is on {@code render/ViewMatrices.advanceDistant}. The three
 * matrices stay the game's own on both roads, and ViewMatrices says why that is a divergence with a
 * reason under it rather than a gap.
 */
public final class DhValues {

	private DhValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("dhProjection", UniformShape.MAT4, (world, out) -> out.set(world.dhProjection()));
		builder.add("dhProjectionInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.dhProjectionInverse()));
		builder.add("dhPreviousProjection", UniformShape.MAT4,
				(world, out) -> out.set(world.dhPreviousProjection()));

		builder.add("dhNearPlane", UniformShape.FLOAT, (world, out) -> out.set(world.dhNearPlane()));
		builder.add("dhFarPlane", UniformShape.FLOAT, (world, out) -> out.set(world.dhFarPlane()));
		builder.add("dhRenderDistance", UniformShape.INT,
				(world, out) -> out.set(world.dhRenderDistance()));
	}
}
