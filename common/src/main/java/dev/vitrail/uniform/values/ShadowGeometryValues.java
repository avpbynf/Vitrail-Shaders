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
 * {@code shadowModelView} and {@code shadowProjection} are not touched here. They keep meaning what
 * they mean everywhere else, which is what lets a shadow program compare against the same matrix the
 * composite reading its map will use.
 */
public final class ShadowGeometryValues {

	private ShadowGeometryValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("of_ModelViewMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowModelView()));
		builder.add("of_ModelViewMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowModelViewInverse()));
		builder.add("of_ProjectionMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowProjection()));
		builder.add("of_ProjectionMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.shadowProjectionInverse()));

		builder.add("of_ModelViewProjectionMatrix", UniformShape.MAT4, (world, out) ->
				out.set(new Matrix4f(world.shadowProjection()).mul(world.shadowModelView())));

		// The shadow model view carries the grid snap as a translation, so this is no longer the
		// transpose of a pure rotation and computing it is the only way to get it right.
		builder.add("of_NormalMatrix", UniformShape.MAT3, (world, out) ->
				out.set(new Matrix3f().set(world.shadowModelView()).invert().transpose()));
	}
}
