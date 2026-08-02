package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

/**
 * The Distant Horizons values, supplied unconditionally with a fallback.
 * <p>
 * {@code DISTANT_HORIZONS} itself is not defined, which is the parity: a pack must not be told the
 * mod is there. But the names are read outside that guard by packs that only meant to be careful,
 * and a name nothing declares is a wall of undeclared identifiers rather than a missing feature, so
 * they are answered from our own view either way. This is what closes the twenty four
 * {@code dhProjection} failures the translation stage measured.
 * <p>
 * The two planes answer 0.01, which is Iris's own fallback and is deliberately a number no pack
 * can mistake for a real distance.
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
				(world, out) -> out.set(world.dhRenderDistanceChunks()));
	}
}
