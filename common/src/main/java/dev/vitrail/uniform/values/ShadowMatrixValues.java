package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

/**
 * The four shadow matrices, which are owed to a pack whether or not a shadow pass runs.
 * <p>
 * They are not the shadow map. They are where the light is and how it looks at the world, and a
 * composite reads them to place a sample it takes from somewhere else entirely; all eight packs of
 * the corpus read the two direct ones. Iris registers them unconditionally for the same reason, and
 * they cost nothing to compute: an unshifted camera position, the sky angle, and an orthographic
 * matrix built from the pack's own distance.
 * <p>
 * What they answer is the pair the map ON HAND was drawn with, one frame older than the camera,
 * see {@link dev.vitrail.uniform.ViewSource}. The shadow programs alone read the fresh pair, and
 * they get it from the layer {@link ShadowGeometryValues} puts over these four names.
 */
public final class ShadowMatrixValues {

	private ShadowMatrixValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("shadowModelView", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowModelView()));
		builder.add("shadowModelViewInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowModelViewInverse()));
		builder.add("shadowProjection", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowProjection()));
		builder.add("shadowProjectionInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowProjectionInverse()));
	}
}
