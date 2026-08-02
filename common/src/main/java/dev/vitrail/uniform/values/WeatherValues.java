package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

/**
 * Rain, thunder and the fog the game itself computed.
 * <p>
 * {@code wetness} is not the rain strength, whatever the two names suggest: it is the rain strength
 * smoothed with two half lives, the rise set by the pack and the fall fixed at two hundred
 * deciseconds because no pack can reach it. Writing the raw value into it gives a number that moves
 * at the wrong speed and looks entirely reasonable, which is why the wrong version of this survived
 * as long as it did.
 * <p>
 * The fog comes from the game's own state rather than from Sodium's relay of it. There is no
 * density on 26.2: the environmental fog is a start and an end, so it is linear by construction,
 * and nothing here chooses a mode on the sign of something that does not exist. {@code fogMode} and
 * {@code fogShape} say whether the pass being drawn fogs at all, which is a question for the frame
 * state and not for this table.
 */
public final class WeatherValues {

	private static final FrameSmoothed WETNESS = new FrameSmoothed();

	private WeatherValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("rainStrength", UniformShape.FLOAT, (world, out) -> out.set(world.rainStrength()));
		builder.add("thunderStrength", UniformShape.FLOAT,
				(world, out) -> out.set(world.thunderStrength()));
		builder.add("wetness", UniformShape.FLOAT, (world, out) -> out.set(WETNESS.get(world,
				world.rainStrength(), world.wetnessHalfLife(), world.drynessHalfLife())));

		builder.add("skyColor", UniformShape.VEC3, (world, out) -> {
			int packed = world.skyColorPacked();
			out.set(((packed >> 16) & 255) / 255.0F, ((packed >> 8) & 255) / 255.0F,
					(packed & 255) / 255.0F);
		});

		builder.add("fogColor", UniformShape.VEC3,
				(world, out) -> out.set(world.fogR(), world.fogG(), world.fogB()));
		builder.add("fogStart", UniformShape.FLOAT, (world, out) -> out.set(world.fogStart()));
		builder.add("fogEnd", UniformShape.FLOAT, (world, out) -> out.set(world.fogEnd()));
		builder.add("fogDensity", UniformShape.FLOAT,
				(world, out) -> out.set(Math.max(0.0F, world.fogDensity())));
		builder.add("fogMode", UniformShape.INT, (world, out) -> out.set(world.fogMode()));
		builder.add("fogShape", UniformShape.INT, (world, out) -> out.set(world.fogShape()));

		// The fixed function fog block, whose scale member is the reciprocal of the range and not
		// a factor: a program that wrote its own linear fog read gl_Fog.scale for exactly that.
		builder.add("of_Fog", UniformShape.FOG, (world, out) -> {
			float range = world.fogEnd() - world.fogStart();
			out.setFog(world.fogR(), world.fogG(), world.fogB(), world.fogA(),
					Math.max(0.0F, world.fogDensity()), world.fogStart(), world.fogEnd(),
					range == 0.0F ? 0.0F : 1.0F / range);
		});
	}
}
