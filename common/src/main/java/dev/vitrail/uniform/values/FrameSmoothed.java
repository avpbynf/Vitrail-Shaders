package dev.vitrail.uniform.values;

import dev.vitrail.uniform.Smoothed;
import dev.vitrail.uniform.WorldState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A smoothed value that steps once per frame however many passes read it.
 * <p>
 * A {@code UniformSource} is not allowed to remember anything, and for good reason: a frame runs
 * forty passes and each of them writes the block, so a smoother that stepped on being read would
 * integrate forty times per frame and converge forty times too fast. The rule this class exists to
 * keep is the same rule, honoured rather than broken: the value moves when the frame number moves
 * and at no other time, so every pass of a frame is handed the same number and the answer does not
 * depend on how many passes the pack happens to declare.
 * <p>
 * The frame number comes from the world state, which is the one place that knows where a frame
 * ends, so this needs no hook of its own and works unchanged against the off-game fixture.
 * <p>
 * The accumulator outlives the frame state, though, because the catalogue is built once for the
 * process while a frame state is built per pack. That is what {@link #forgetAll()} is for: a world
 * change and a pack reload both have to lose the history, or the ground stays wet across a
 * dimension and the first seconds of a reloaded pack fade from a number the previous one left.
 */
public final class FrameSmoothed {

	/**
	 * Every instance, so that a caller with nothing but a frame boundary can clear them all. There
	 * are three of them, they are made in static initialisers and never after, so the list is
	 * complete before the first frame and never grows again.
	 */
	private static final List<FrameSmoothed> ALL = new ArrayList<>();

	/**
	 * The accumulators a pack's own {@code smooth()} calls own, made when its expressions are
	 * parsed and dropped with them. Held weakly, so that a pack that is gone takes its
	 * accumulators with it, and forgotten with the three above: a dimension change that keeps
	 * the pack's directory would otherwise serve the previous world's number for the seconds
	 * its half-life takes, while wetness and the eye's brightness restart beside it.
	 */
	private static final Set<Smoothed> TRACKED =
			Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

	private final Smoothed smoothed = new Smoothed();

	private int lastFrame = -1;
	private float value;

	FrameSmoothed() {
		ALL.add(this);
	}

	/** An accumulator for a pack's own {@code smooth()}, forgotten with the engine's. */
	public static Smoothed tracked() {
		Smoothed smoothed = new Smoothed();
		TRACKED.add(smoothed);

		return smoothed;
	}

	/** Drops every accumulator. For a world change and for a pack load, and for nothing else. */
	public static void forgetAll() {
		for (FrameSmoothed smoothed : ALL) {
			smoothed.smoothed.reset();
			smoothed.lastFrame = -1;
			smoothed.value = 0.0F;
		}

		synchronized (TRACKED) {
			TRACKED.forEach(Smoothed::reset);
		}
	}

	float get(WorldState world, float raw, float halfLifeUp, float halfLifeDown) {
		if (world.frameCounter() != this.lastFrame) {
			this.lastFrame = world.frameCounter();
			this.value = this.smoothed.updateAndGet(raw, halfLifeUp, halfLifeDown, world.frameTime());
		}

		return this.value;
	}
}
