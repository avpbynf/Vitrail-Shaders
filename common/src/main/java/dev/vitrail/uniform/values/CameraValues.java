package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.Val;

import org.joml.Vector3dc;

/**
 * Where the camera is, and how far it can see.
 * <p>
 * The positions are the ones the world state already shifted: the shift keeps a coordinate a long
 * way from the origin inside a float, and the previous frame's position is shifted by the same
 * amount as this frame's or every reprojection tears on the frame the shift moves.
 * <p>
 * Which of the two positions a name carries is not a detail. {@code cameraPosition} and
 * {@code previousCameraPosition} are the shifted ones, because a pack differences them; the
 * integer and fractional splits are the <em>unshifted</em> ones, because their whole purpose is to
 * carry a real world coordinate at full precision, and a shift would make that a lie. Iris draws
 * the line in the same place.
 */
public final class CameraValues {

	private CameraValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("near", UniformShape.FLOAT, (world, out) -> out.set(world.near()));
		builder.add("far", UniformShape.FLOAT, (world, out) -> out.set(world.far()));

		builder.add("cameraPosition", UniformShape.VEC3,
				(world, out) -> out.set(world.cameraPosition()));
		builder.add("previousCameraPosition", UniformShape.VEC3,
				(world, out) -> out.set(world.previousCameraPosition()));

		// The Y of the shifted position, which is the raw Y: only X and Z are ever shifted.
		builder.add("eyeAltitude", UniformShape.FLOAT,
				(world, out) -> out.set((float) world.cameraPosition().y()));

		builder.add("cameraPositionInt", UniformShape.IVEC3,
				(world, out) -> whole(world.cameraPositionUnshifted(), out));
		builder.add("cameraPositionFract", UniformShape.VEC3,
				(world, out) -> fraction(world.cameraPositionUnshifted(), out));
		builder.add("previousCameraPositionInt", UniformShape.IVEC3,
				(world, out) -> whole(world.previousCameraPositionUnshifted(), out));
		builder.add("previousCameraPositionFract", UniformShape.VEC3,
				(world, out) -> fraction(world.previousCameraPositionUnshifted(), out));

		builder.add("eyePosition", UniformShape.VEC3, (world, out) -> out.set(world.eyePosition()));
		// Camera minus eye, in that order. The other way round is a sign error that only shows up
		// in third person, where the two are far enough apart to notice.
		builder.add("relativeEyePosition", UniformShape.VEC3, (world, out) -> {
			Vector3dc camera = world.cameraPositionUnshifted();
			Vector3dc eye = world.eyePosition();
			out.set((float) (camera.x() - eye.x()), (float) (camera.y() - eye.y()),
					(float) (camera.z() - eye.z()));
		});
	}

	private static void whole(Vector3dc position, Val out) {
		out.set((int) Math.floor(position.x()), (int) Math.floor(position.y()),
				(int) Math.floor(position.z()));
	}

	private static void fraction(Vector3dc position, Val out) {
		out.set((float) (position.x() - Math.floor(position.x())),
				(float) (position.y() - Math.floor(position.y())),
				(float) (position.z() - Math.floor(position.z())));
	}
}
