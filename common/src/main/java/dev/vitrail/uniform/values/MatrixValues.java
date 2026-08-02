package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

/**
 * The gbuffer matrices, this frame's and the previous one's.
 * <p>
 * Every projection published here is the OpenGL form, converted from the matrix the pipeline draws
 * with, and every inverse is the inverse of what was published rather than the conversion of an
 * inverse. Those two are different matrices and only one of them sends a point back where it came
 * from, which is what a reprojection is.
 * <p>
 * All six are pass throughs, and that is the point of the split: the work needs a matrix captured
 * off the running game and a memory of the frame before, and a source is allowed neither.
 */
public final class MatrixValues {

	private MatrixValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("gbufferModelView", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferModelView()));
		builder.add("gbufferModelViewInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferModelViewInverse()));
		builder.add("gbufferPreviousModelView", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferPreviousModelView()));

		builder.add("gbufferProjection", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferProjection()));
		builder.add("gbufferProjectionInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferProjectionInverse()));
		builder.add("gbufferPreviousProjection", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferPreviousProjection()));
	}
}
