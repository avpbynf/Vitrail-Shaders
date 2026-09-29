package dev.vitrail.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Keeps the published camera position inside the range a float can still resolve.
 * <p>
 * Ported from Iris {@code uniforms/CameraUniforms.java:62-143}. Both constants are observable
 * by a pack, and so is the fact that <b>only X and Z are ever shifted</b>, which is why the
 * altitude and the raw Y are the same number. Shifting by whole multiples of the range rather
 * than by the overshoot is a requirement of at least one pack, not a rounding preference.
 */
final class CameraShift {

	private static final double WALK_RANGE = 30000.0;
	private static final double TP_RANGE = 1000.0;

	private final Vector3d shift = new Vector3d();
	private final Vector3d current = new Vector3d();
	private final Vector3d previous = new Vector3d();
	private final Vector3d currentUnshifted = new Vector3d();
	private final Vector3d previousUnshifted = new Vector3d();

	private boolean seeded;

	void advance(Vec3 position) {
		this.previous.set(this.current);
		this.previousUnshifted.set(this.currentUnshifted);
		this.currentUnshifted.set(position.x, position.y, position.z);
		this.current.set(this.currentUnshifted).add(this.shift);

		if (!this.seeded) {
			// The same guard the matrices give themselves, and for the same reason: the first
			// frame after a world change has no previous position, and the origin standing in
			// for it is a motion vector the width of the world. It also keeps the teleport test
			// below from reading the spawn itself as a teleport.
			this.previous.set(this.current);
			this.previousUnshifted.set(this.currentUnshifted);
			this.seeded = true;
		}

		double dX = shiftOf(this.current.x, this.previous.x);
		double dZ = shiftOf(this.current.z, this.previous.z);
		if (dX == 0.0 && dZ == 0.0) {
			return;
		}

		// This frame and the previous one move by the same amount, so that the difference
		// between them, which is all a motion vector is, survives the shift.
		this.shift.x += dX;
		this.current.x += dX;
		this.previous.x += dX;
		this.shift.z += dZ;
		this.current.z += dZ;
		this.previous.z += dZ;
	}

	void reset() {
		this.shift.zero();
		this.current.zero();
		this.previous.zero();
		this.currentUnshifted.zero();
		this.previousUnshifted.zero();
		this.seeded = false;
	}

	private static double shiftOf(double value, double previous) {
		if (Math.abs(value) > WALK_RANGE || Math.abs(value - previous) > TP_RANGE) {
			return -(value - (value % WALK_RANGE));
		}

		return 0.0;
	}

	Vector3dc shifted() {
		return this.current;
	}

	Vector3dc previousShifted() {
		return this.previous;
	}

	Vector3dc unshifted() {
		return this.currentUnshifted;
	}

	Vector3dc previousUnshifted() {
		return this.previousUnshifted;
	}
}
