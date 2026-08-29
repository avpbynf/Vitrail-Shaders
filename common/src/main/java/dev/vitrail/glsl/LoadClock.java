package dev.vitrail.glsl;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the time of a pack load goes, split into the two posts nothing else can separate: the
 * translation of the pack's GLSL, and turning the translated text into modules, which is shaderc
 * and the SPIRV-Cross reflection together.
 * <p>
 * <strong>It exists because the split decides what gets built next.</strong> With shaderc served
 * from disk and the driver's own cache accepted, a load still costs seconds, so the remaining
 * cost is one of these two, and they call for different remedies: a translation cache is keyed
 * on the pack and the settings before any text exists, a reflection cache stores binding tables
 * against bytes that already do. Building either without this number is building blind.
 * <p>
 * The module count listens at the game's own compiler, which is the funnel every road goes
 * through: the background warmup, the terrain, the composite passes, and whatever a first draw
 * or a resource reload still owes. The one road that does not pass there is the shadow
 * computes' own, which counts itself. The funnel is class-wide, so a module that is not pack
 * text lands in the figure too when it compiles while a tally is live, the game's and Sodium's
 * own pipelines and this engine's helper passes alike; on a load into a world those follow the
 * first draws. Outside both figures: the {@code rebind} rewrite and the compute's layout, both
 * small, and the driver's own pipeline build behind {@code vkCreateGraphicsPipelines}, which on
 * a cold driver cache is not.
 * <p>
 * Both counts are tallies in the sense the trig counter is: they are emptied at the head of a
 * load, a first report prints beside the pack-opened line for every installed chain, and a
 * second prints when the background warmup hands back its own figure, the families then in.
 * Two loads can smear into each other in both directions and neither reaches a live program:
 * a worker of the chain that has just been released can land its spans after the reset, and a
 * swap can reset the tallies while the outgoing chain's warmup is still in flight, whose
 * report then reads the incoming load's figures beside its own leftover count. A shadow
 * compute compiles at its first dispatch, so whether it is in a report depends on whether a
 * shadow frame ran before that report printed.
 * <p>
 * In this package and not in {@code render}, where the line that prints it lives: the off-game
 * harness compiles this package without the render tree, and the translator could not name a
 * class over there without taking the harness down with it.
 */
public final class LoadClock {

	private static final AtomicLong TRANSLATION_NANOS = new AtomicLong();

	private static final AtomicInteger TRANSLATED = new AtomicInteger();

	private static final AtomicLong MODULE_NANOS = new AtomicLong();

	private static final AtomicInteger MODULES = new AtomicInteger();

	private LoadClock() {
	}

	/** Emptied at the head of a pack load: a tally belongs to the load it was taken under. */
	public static void reset() {
		TRANSLATION_NANOS.set(0);
		TRANSLATED.set(0);
		MODULE_NANOS.set(0);
		MODULES.set(0);
	}

	/** One translator call's worth of {@link System#nanoTime} span, workers included. */
	public static void translation(long nanos) {
		TRANSLATION_NANOS.addAndGet(nanos);
		TRANSLATED.incrementAndGet();
	}

	/**
	 * One module's span through shaderc and SPIRV-Cross, whichever of the two really ran.
	 * Called by the mixin on the game's compiler for every road that goes through it, and by the
	 * shadow-compute road for itself.
	 */
	public static void module(long nanos) {
		MODULE_NANOS.addAndGet(nanos);
		MODULES.incrementAndGet();
	}

	public static long translationMillis() {
		return TRANSLATION_NANOS.get() / 1_000_000L;
	}

	public static int translated() {
		return TRANSLATED.get();
	}

	public static long moduleMillis() {
		return MODULE_NANOS.get() / 1_000_000L;
	}

	public static int modules() {
		return MODULES.get();
	}
}
