package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * The fixed function state of a pass drawn over the world rather than over a quad.
 * <p>
 * Six names and nothing else, layered over {@link DrawValues}, which answers all six with the stand
 * ins a full screen pass needs: an identity model view and the matrix that carries the quad to the
 * screen. Handing those to a terrain program would put every block of the world inside a unit cube
 * at the corner of the screen, so the six are answered again here and the rest of the table is
 * shared, which is the point of layering rather than of a second catalogue.
 * <p>
 * What they hold is the gbuffer pair, and it has to be that pair rather than anything rebuilt.
 * Every pack of the corpus writes its clip position as some arrangement of
 * {@code gl_ProjectionMatrix}, {@code gbufferModelView} and {@code gl_ModelViewMatrix}, and BSL
 * writes {@code gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex} on the way in: the two
 * cancel only if they are the same matrix, and a difference of one frame between them shows as a
 * world that lags the sky.
 * <p>
 * The projection is the published one, which is the OpenGL form. A vertex stage ends on the epilogue
 * that puts a clip depth back into the volume the target is rasterised in, so the pack is handed the
 * matrix it was written for at both ends.
 */
public final class GeometryValues {

	/**
	 * How far a chunk mesh's texture coordinate is pulled into its sprite, and where the two numbers
	 * come from.
	 * <p>
	 * Sodium's {@code UniformBufferManager.update} writes
	 * {@code 3.0517578E-5 - 1/atlasSize/subTexelPrecision} per axis, and its
	 * {@code GPULimits.getSubTexelPrecisionBits} is eight everywhere but macOS. The first term is one
	 * unit of the fifteen bit encoding the coordinate arrives in; the second takes off the sub texel
	 * the rasteriser is allowed to be wrong by. Copied rather than approximated: the whole point of
	 * the number is that it is smaller than a texel and larger than the error, and a value invented
	 * here would be one or the other by luck.
	 */
	private static final float SUB_TEXEL_OFFSET = 3.0517578E-5F;
	private static final float SUB_TEXEL_PRECISION = 256.0F;

	private GeometryValues() {
	}

	/** One axis of the shrink, from the atlas that axis is measured on. */
	private static float shrink(int size) {
		return SUB_TEXEL_OFFSET - 1.0F / Math.max(1, size) / SUB_TEXEL_PRECISION;
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("of_TexShrink", UniformShape.VEC2, (world, out) ->
				out.set(shrink(world.atlasWidth()), shrink(world.atlasHeight())));

		// The colour the game modulates a whole draw by, which for the sky is where its colour is:
		// the mesh of a sky disc carries a position and nothing else, and the mesh of the sunrise
		// band carries only the fade. White for every pass that has not set one.
		builder.add("of_PassColour", UniformShape.VEC4, (world, out) -> out.set(
				world.passColour().x(), world.passColour().y(), world.passColour().z(),
				world.passColour().w()));

		// The PASS's model view and not the frame's, and the two are one matrix for every pass but
		// the sky's and the four entity pieces the game nudges towards the viewer. What separates
		// them is written out in ViewSource.passModelView: the game
		// puts the sun where it is by pushing a rotation onto its own stack, and a pack reads that
		// rotation here while reading the camera under gbufferModelView, using both at once.
		builder.add("of_ModelViewMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.passModelView()));
		builder.add("of_ModelViewMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.passModelViewInverse()));
		builder.add("of_ProjectionMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferProjection()));
		builder.add("of_ProjectionMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferProjectionInverse()));

		// Composed here rather than published, because nothing else in the engine reads it: a full
		// screen pass is handed the quad projection for it and a pack calling ftransform() is the
		// only reader. Left to right, so that the pack's own gl_ProjectionMatrix * gl_ModelViewMatrix
		// and its ftransform() are the same matrix.
		builder.add("of_ModelViewProjectionMatrix", UniformShape.MAT4, (world, out) ->
				out.set(new Matrix4f(world.gbufferProjection()).mul(world.passModelView())));

		// The inverse transpose of the model view's rotation. The level's model view is a pure
		// rotation, so this is its transpose, but it is computed rather than assumed: a shadow pass
		// or a pack directive could put a scale in it, and normalising a normal afterwards hides
		// the difference exactly until it does not.
		builder.add("of_NormalMatrix", UniformShape.MAT3, (world, out) ->
				out.set(new Matrix3f().set(world.passModelView()).invert().transpose()));
	}
}
