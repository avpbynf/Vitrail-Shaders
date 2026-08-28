package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * The fixed function state of a pass drawn from the light instead of from the camera.
 * <p>
 * The same six names {@link GeometryValues} answers, layered over it and answered from the shadow
 * pair. That is where OptiFine and Iris put them: during the shadow pass the model view and the
 * projection <em>are</em> the shadow ones, and a pack's {@code shadow.vsh} says
 * {@code gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex} or {@code ftransform()} without ever
 * naming a shadow. Handed the camera pair instead it would draw the map from the player's eye, which
 * is a shadow map of exactly the wrong thing and looks like a shadow map all the same.
 * <p>
 * Everything here reads the DRAWN pair, this frame's, where the published {@code shadowModelView}
 * is the previous frame's: the map is drawn at the end of a frame for the next one, so the pair a
 * sampling pass needs is one frame older than the pair this stage draws with. The four explicit
 * names are overridden below for the same reason, and it is not optional: {@code shadow.vsh}
 * multiplies {@code shadowModelViewInverse * shadowProjectionInverse * ftransform()} and counts on
 * the product collapsing, which it only does when the inverses and the pair under
 * {@code ftransform} are the same frame's.
 */
public final class ShadowGeometryValues {

	private static final Matrix4f MODEL_VIEW_PROJECTION = new Matrix4f();
	private static final Matrix3f NORMAL = new Matrix3f();

	private ShadowGeometryValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("of_ModelViewMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowModelView()));
		builder.add("of_ModelViewMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowModelViewInverse()));
		builder.add("of_ProjectionMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowProjection()));
		builder.add("of_ProjectionMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowProjectionInverse()));

		builder.add("of_ModelViewProjectionMatrix", UniformShape.MAT4, (world, out) ->
				out.set(MODEL_VIEW_PROJECTION.set(world.drawnShadowProjection())
						.mul(world.drawnShadowModelView())));

		// The basis as it stands, where GeometryValues computes an inverse transpose off the pass
		// matrix. Not a shortcut taken here: that one is computed because a pass model view can
		// arrive carrying a scale, and this one cannot. ViewMatrices.advanceShadow builds the shadow
		// model view from an identity, three rotations and the grid snap, and the snap is a
		// translate, so it lands in the fourth column; Matrix3f.set(Matrix4fc) reads the upper left
		// three by three and never sees it. What is read here is therefore a product of rotations,
		// whose inverse is its transpose, so inverting and transposing it hands back what went in.
		//
		// What would break it is a scale multiplied into the shadow model view, and the symptom
		// would be shadow pass normals off by that scale rather than anything that fails loudly.
		builder.add("of_NormalMatrix", UniformShape.MAT3, (world, out) ->
				out.set(NORMAL.set(world.drawnShadowModelView())));

		builder.add("shadowModelView", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowModelView()));
		builder.add("shadowModelViewInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowModelViewInverse()));
		builder.add("shadowProjection", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowProjection()));
		builder.add("shadowProjectionInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.drawnShadowProjectionInverse()));
	}
}
