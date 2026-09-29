package dev.vitrail.render;

import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;

/**
 * The arithmetic of {@link FrameState} that names no game object: the frame clock, the wrap of a
 * celestial angle, the table of what the eye is in and the darkness effect's light term. Each takes
 * numbers and answers a number, so each can be run against a hand-worked value without a game.
 * <p>
 * The float operation order is part of the contract: a pack is written against Iris's numbers, so
 * neither the order of an addition nor the type a division runs in is free to change.
 */
final class FrameMath {

	/** OptiFine's counter wraps at an hour, which keeps a float's precision usable for noise. */
	private static final float COUNTER_WRAP = 3600.0F;

	/** And the frame counter wraps here. Both numbers are observable by a pack. */
	private static final int FRAME_WRAP = 720720;

	private FrameMath() {
	}

	/**
	 * The time one frame took, in seconds, quantised to the millisecond: the microseconds are cut by
	 * an integer division first, so it is a whole number of milliseconds over a thousand.
	 */
	@SuppressWarnings("NarrowCalculation")
	static float frameTime(long elapsedNanos) {
		return (elapsedNanos / 1000L) / 1000L / 1000.0F;
	}

	/**
	 * The running sum of the frame times, back at nought once it reaches an hour. Nought and not the
	 * excess over the hour, as the reference has it.
	 */
	static float counterAfter(float counter, float frameTime) {
		float next = counter + frameTime;

		return next >= COUNTER_WRAP ? 0.0F : next;
	}

	/** The frame after this one, which wraps at {@link #FRAME_WRAP}. */
	static int nextFrame(int frame) {
		return (frame + 1) % FRAME_WRAP;
	}

	/**
	 * One step of wrapping and not a modulus, exactly as Iris
	 * {@code uniforms/CelestialUniforms.java:30-42} does it. A shader that reads the value either
	 * side of the wrap has to see the discontinuity in the same place.
	 */
	static float sunAngle(float degrees) {
		float angle = degrees + 90.0F;
		if (angle < 0.0F) {
			angle += 360.0F;
		} else if (angle > 360.0F) {
			angle -= 360.0F;
		}

		return angle;
	}

	/**
	 * Iris's table, which is not the ordinals of {@link FogType}: that enum reads
	 * {@code LAVA, WATER, POWDER_SNOW, ATMOSPHERIC, NONE} and a pack wants
	 * {@code 0 nothing, 1 water, 2 lava, 3 powder snow}, so the mapping is written out.
	 * <p>
	 * Lava not counting in spectator is a rule Iris lays over the game rather than a property of
	 * it, {@code Camera.getFluidInCamera} never looks at the game mode. It is here because packs
	 * are written against Iris.
	 */
	static int eyeInWater(FogType submersion, boolean spectator) {
		if (submersion == FogType.WATER) {
			return 1;
		} else if (!spectator && submersion == FogType.LAVA) {
			return 2;
		} else if (submersion == FogType.POWDER_SNOW) {
			return 3;
		}

		return 0;
	}

	/**
	 * The light the darkness effect takes away. The option scale enters twice, once through the
	 * effect blend and once at the end, and dropping either occurrence halves the result.
	 *
	 * @param tickCount   the player's tick count
	 * @param partialTick how far into the tick the frame is
	 * @param blend       the effect's blend factor at this partial tick
	 * @param scale       the player's darkness effect option
	 */
	static float darknessLight(int tickCount, float partialTick, float blend, float scale) {
		float gamma = blend * scale;
		float darkness = Math.max(0.0F,
				Mth.cos((tickCount - partialTick) * (float) Math.PI * 0.025F) * 0.45F * gamma);

		return darkness * scale;
	}
}
