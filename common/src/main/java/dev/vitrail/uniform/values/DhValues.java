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
 * planes and the render distance are DH's own the moment DH has drawn one, and fall back to
 * Iris's 0.01 otherwise, which is deliberately a number no pack can mistake for a real distance.
 * The three matrices stay the game's own on both roads, and ViewMatrices says why that is an
 * answer rather than a gap.
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
