package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.WorldState;

import java.time.LocalDateTime;

/**
 * The clocks and the counters: how long the world has been running, how long this frame took, and
 * what number this frame is.
 * <p>
 * Nothing here steps anything. The frame state advances its clocks once, at a named point, and
 * everything read from here during that frame sees the same numbers; a counter that stepped on
 * being read would give two passes of one frame two different times, which is exactly the kind of
 * difference that shows up as a shimmer nobody can place.
 */
public final class TimeValues {

	private static final int SECONDS_PER_DAY = 86400;

	private static final WallClock WALL_CLOCK = new WallClock();

	private TimeValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("frameCounter", UniformShape.INT, (world, out) -> out.set(world.frameCounter()));
		builder.add("frameTime", UniformShape.FLOAT, (world, out) -> out.set(world.frameTime()));
		builder.add("frameTimeCounter", UniformShape.FLOAT,
				(world, out) -> out.set(world.frameTimeCounter()));

		builder.add("worldTime", UniformShape.INT, (world, out) -> out.set((int) world.worldTime()));
		builder.add("worldDay", UniformShape.INT, (world, out) -> out.set((int) world.worldDay()));
		builder.add("moonPhase", UniformShape.INT, (world, out) -> out.set(world.moonPhase()));

		// The real world clock, which packs use for a seasonal tint or a date joke. Read once a
		// frame like everything else here: read per source, two passes of one frame either side of
		// a second boundary would be handed two different times.
		builder.add("currentDate", UniformShape.IVEC3, (world, out) -> {
			LocalDateTime now = WALL_CLOCK.at(world);
			out.set(now.getYear(), now.getMonthValue(), now.getDayOfMonth());
		});
		builder.add("currentTime", UniformShape.IVEC3, (world, out) -> {
			LocalDateTime now = WALL_CLOCK.at(world);
			out.set(now.getHour(), now.getMinute(), now.getSecond());
		});
		builder.add("currentYearTime", UniformShape.IVEC2, (world, out) -> {
			LocalDateTime now = WALL_CLOCK.at(world);
			int elapsed = (now.getDayOfYear() - 1) * SECONDS_PER_DAY + now.getHour() * 3600
					+ now.getMinute() * 60 + now.getSecond();
			out.set(elapsed, now.toLocalDate().lengthOfYear() * SECONDS_PER_DAY - elapsed);
		});
	}

	/**
	 * The real world clock, sampled once per frame.
	 * <p>
	 * It does not come through the world state because it is not world state: it is the same
	 * outside a level and it is the same in the off-game fixture, so putting it behind the
	 * interface would buy three accessors and no fidelity. What it does need is the frame number,
	 * which is what keeps the three date values agreeing with each other and with themselves
	 * across the passes of one frame.
	 */
	// The machine's own zone is the answer here rather than a default nobody chose: a pack asking
	// for the date wants the clock on the wall behind the player.
	@SuppressWarnings("JavaTimeDefaultTimeZone")
	private static final class WallClock {

		private int lastFrame = -1;
		private LocalDateTime sampled = LocalDateTime.now();

		private LocalDateTime at(WorldState world) {
			if (world.frameCounter() != this.lastFrame) {
				this.lastFrame = world.frameCounter();
				this.sampled = LocalDateTime.now();
			}

			return this.sampled;
		}
	}
}
