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
 * What each of them answers is {@code render/ViewMatrices}'s and moves with the frame. The two
 * planes and the render distance are DH's own the moment DH has drawn one. Without one the planes
 * fall back to Iris's 0.01, {@code compat/dh/DHCompat.java:94} and {@code :104}, deliberately a
 * number no pack can mistake for a real distance; the render distance falls back to the game's own
 * in chunks, which is Iris's fallback as well, {@code compat/dh/DHCompat.java:114}, and the change
 * of unit that comes with it is on {@code uniform/ViewSource}. The three matrices stay the game's
 * own on both roads, and ViewMatrices says why that is a divergence with a reason under it rather
 * than a gap.
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
