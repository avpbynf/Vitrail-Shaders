package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.WorldState;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * The sky: where the sun, the moon and the shadow light are, in eye space, plus the one answer this
 * engine needs in world space, {@link #shadowLightVector}.
 * <p>
 * These live before the projection, so no clip space convention touches them and they are the one
 * family that carries over unchanged. Two things about them are counter-intuitive and are in the
 * format rather than in the maths: the positions have length one hundred and are not normalised,
 * and the moon is not the sun turned round, it is the same rotation applied to the same starting
 * vector with a different angle.
 * <p>
 * Ported from Iris {@code uniforms/CelestialUniforms.java} at b0ae41c, with the rotations written
 * out in JOML because {@code com.mojang.math.Axis} is a game type and cannot cross into this
 * package. Three traps came with it, all of them the sort that produce a sky that is off by a
 * quarter turn and otherwise convincing:
 * <ul>
 * <li>the angle a pack reads is the raw attribute plus ninety with <b>one</b> step of wrapping,
 * not a modulus, and the discontinuity has to sit where it sits;</li>
 * <li>{@code getMoonPosition} passes a y of minus one hundred that the function never reads, so
 * the moon starts from the same {@code (0, 100, 0)} the sun does. Honouring that parameter is a
 * hundred and eighty degree error;</li>
 * <li>{@code upPosition} skips the sky angle entirely and carries a w of zero, so it is a
 * direction and not a place.</li>
 * </ul>
 * The two dependencies Iris takes on its own pipeline object are cut: whether an end flash exists,
 * and whether the pack asked its shadows to follow it, are both questions for the world state.
 */
public final class CelestialValues {

	/**
	 * Scratch. Every call overwrites all of it before reading any of it, so two passes of one frame
	 * still get the same answer, which is the only invariant a source has to keep.
	 */
	private static final Matrix4f SCRATCH = new Matrix4f();
	private static final Vector4f POSITION = new Vector4f();

	/**
	 * The four eye space answers as they were last built, each beside the inputs it was built from.
	 * <p>
	 * A name is asked for once per program that declares it and a pack has many programs, so the
	 * rotations below were built again and again inside one frame out of numbers that had not moved
	 * between two of the asks. {@link Settled} carries what the key is and why it is the inputs
	 * rather than the frame. What belongs here is that these four do not move together, so they do
	 * not share one: {@code upPosition} turns on the camera alone and settles again the instant the
	 * head stops turning, the sun and the moon each carry an angle of their own, and the flash
	 * carries two angles nothing else reads.
	 */
	private static final Settled SUN = new Settled();
	private static final Settled MOON = new Settled();
	private static final Settled UP = new Settled();
	private static final Settled FLASH = new Settled();

	private static final Vector3f SUN_POSITION = new Vector3f();
	private static final Vector3f MOON_POSITION = new Vector3f();
	private static final Vector3f UP_POSITION = new Vector3f();
	private static final Vector3f FLASH_POSITION = new Vector3f();

	private CelestialValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("sunAngle", UniformShape.FLOAT,
				(world, out) -> out.set(sunAngle(world, true) / 360.0F));
		builder.add("shadowAngle", UniformShape.FLOAT,
				(world, out) -> out.set(sunAngle(world, isDay(world)) / 360.0F));

		builder.add("sunPosition", UniformShape.VEC3, (world, out) -> celestial(world, true, out));
		builder.add("moonPosition", UniformShape.VEC3, (world, out) -> celestial(world, false, out));
		builder.add("upPosition", UniformShape.VEC3, CelestialValues::up);

		// The pack has to have opted in for this one, and not for the position below it. Where the
		// light comes from is something a pack is written around; where the flash is is a fact.
		builder.add("shadowLightPosition", UniformShape.VEC3, (world, out) -> {
			if (inEndFlash(world) && world.endFlashShadows()) {
				endFlash(world, out);
			} else {
				celestial(world, isDay(world), out);
			}
		});

		builder.add("endFlashPosition", UniformShape.VEC3, (world, out) -> {
			if (inEndFlash(world)) {
				endFlash(world, out);
			} else {
				out.set(0.0F, 0.0F, 0.0F);
			}
		});

		builder.add("endFlashIntensity", UniformShape.FLOAT,
				(world, out) -> out.set(world.endFlashIntensity()));
		builder.add("previousEndFlashIntensity", UniformShape.FLOAT,
				(world, out) -> out.set(world.previousEndFlashIntensity()));
	}

	/**
	 * Where the light stands in WORLD space, as a unit vector from the origin.
	 * <p>
	 * <strong>The same rotations as the {@code shadowLightPosition} uniform, and deliberately
	 * without the model view.</strong> Iris keeps the pair apart for the same reason and under two
	 * names, {@code getCelestialPositionInWorldSpace} against {@code getCelestialPosition}
	 * ({@code uniforms/CelestialUniforms.java:145,165}): a pack reads where the sun is in EYE space,
	 * because that is the space its lighting is written in, while a cull has to know where it is in
	 * the world. Handing the eye space answer to the cull would swing the light with the player's
	 * head, and every shadow caster the light stands behind would be kept or dropped by where the
	 * player happens to be looking.
	 * <p>
	 * The End flash is taken on the same condition the shadow MATRICES are built on rather than on
	 * Iris's, which tests the dimension and the pack's opt in and not the flash itself
	 * ({@code uniforms/CelestialUniforms.java:138}). The two have to agree here, or a frame would
	 * extrude a frustum along one light and draw its map from another.
	 *
	 * @param dest the vector to write, returned
	 */
	public static Vector3f shadowLightVector(WorldState world, Vector3f dest) {
		// The shared scratch, on the invariant the field's own note carries: every call fills all of
		// it before reading any of it, so a reader between two writes is impossible.
		if (inEndFlash(world) && world.endFlashShadows()) {
			SCRATCH.identity()
					.rotateY((float) Math.toRadians(180.0F - world.endFlashYAngleDegrees()))
					.rotateX((float) Math.toRadians(-90.0F - world.endFlashXAngleDegrees()));
		} else {
			SCRATCH.identity()
					.rotateY((float) Math.toRadians(-90.0F))
					.rotateZ((float) Math.toRadians(world.sunPathRotation()))
					.rotateX((float) Math.toRadians(isDay(world)
							? world.sunAngleDegrees() : world.moonAngleDegrees()));
		}

		// A direction and not a place, which is why the w is nought: the hundred is a length the
		// normalisation throws away, and it is kept only so that the rotations are fed the very
		// vector the uniform feeds them.
		POSITION.set(0.0F, 100.0F, 0.0F, 0.0F);
		SCRATCH.transform(POSITION);

		return dest.set(POSITION.x, POSITION.y, POSITION.z).normalize();
	}

	/** One step of wrapping, not a modulus. See the class note. */
	private static float sunAngle(WorldState world, boolean sun) {
		float angle = (sun ? world.sunAngleDegrees() : world.moonAngleDegrees()) + 90.0F;
		if (angle < 0.0F) {
			angle += 360.0F;
		} else if (angle > 360.0F) {
			angle -= 360.0F;
		}

		return angle;
	}

	private static boolean isDay(WorldState world) {
		return sunAngle(world, true) < 180.0F;
	}

	private static boolean inEndFlash(WorldState world) {
		return world.dimensionOrdinal() == 1 && world.hasEndFlash();
	}

	/**
	 * The same fixed quarter turn as the sky, and deliberately not the sky angle: this one is where
	 * up is, not where the sun is. The camera is the whole of what it turns on.
	 */
	private static void up(WorldState world, Val out) {
		if (!UP.holds(world.gbufferModelView())) {
			SCRATCH.set(world.gbufferModelView()).rotateY((float) Math.toRadians(-90.0F));
			POSITION.set(0.0F, 100.0F, 0.0F, 0.0F);
			SCRATCH.transform(POSITION);
			UP_POSITION.set(POSITION.x, POSITION.y, POSITION.z);
		}

		out.set(UP_POSITION);
	}

	/**
	 * The transformation the sky renderer applies, moved forward so that a pack can read the result
	 * before vanilla performs it. The angle is the <b>raw</b> attribute, not the wrapped one the
	 * {@code sunAngle} uniform carries.
	 * <p>
	 * Built again only when the camera or one of the two angles has moved, which is the whole of
	 * what it is a function of.
	 */
	private static void celestial(WorldState world, boolean sun, Val out) {
		Settled settled = sun ? SUN : MOON;
		Vector3f held = sun ? SUN_POSITION : MOON_POSITION;
		float angle = sun ? world.sunAngleDegrees() : world.moonAngleDegrees();

		if (!settled.holds(world.gbufferModelView(), world.sunPathRotation(), angle)) {
			SCRATCH.set(world.gbufferModelView())
					.rotateY((float) Math.toRadians(-90.0F))
					.rotateZ((float) Math.toRadians(world.sunPathRotation()))
					.rotateX((float) Math.toRadians(angle));

			POSITION.set(0.0F, 100.0F, 0.0F, 1.0F);
			SCRATCH.transform(POSITION);
			held.set(POSITION.x, POSITION.y, POSITION.z);
		}

		out.set(held);
	}

	/** The camera and the two flash angles, on the same terms as {@link #celestial}. */
	private static void endFlash(WorldState world, Val out) {
		if (!FLASH.holds(world.gbufferModelView(),
				world.endFlashXAngleDegrees(), world.endFlashYAngleDegrees())) {
			SCRATCH.set(world.gbufferModelView())
					.rotateY((float) Math.toRadians(180.0F - world.endFlashYAngleDegrees()))
					.rotateX((float) Math.toRadians(-90.0F - world.endFlashXAngleDegrees()));

			POSITION.set(0.0F, 100.0F, 0.0F, 0.0F);
			SCRATCH.transform(POSITION);
			FLASH_POSITION.set(POSITION.x, POSITION.y, POSITION.z);
		}

		out.set(FLASH_POSITION);
	}
}
