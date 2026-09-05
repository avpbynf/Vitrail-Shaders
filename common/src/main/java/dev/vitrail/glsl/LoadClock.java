package dev.vitrail.glsl;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where the time of a pack load goes, split into the three posts nothing else can separate:
 * flattening the pack's entry files into units, translating the GLSL those units hold, and turning
 * the translated text into modules, which is shaderc and the SPIRV-Cross reflection together.
 * <p>
 * <strong>The flattening post is the one a load with both stores warm still pays.</strong> The key
 * that finds a translation on disk is the flattened text, so nothing can be asked of either cache
 * before it exists, and what is left of such a load is very nearly this figure alone. It is taken
 * inside the expander rather than at its callers, there being five of those across three packages
 * and a post missed at one of them reading as a unit that cost nothing to build.
 * <p>
 * One of those five is deliberately outside it, and it is the pack report's walk of every entry
 * point a pack ships. That walk runs on the first load of a given pack and on none of the reloads
 * of it, so counting it in would leave the figure several times larger on one load than on the
 * next with nothing on the line saying which of the two had just been read.
 * <p>
 * It carries a second number, which is the units an opening handed back instead of building: a
 * load walks one place of the pack over and over, for its directives, its chain, its chunk
 * programs and each of its computes. That number says how much of the flattening was taken off
 * rather than how fast what remained of it ran, and the two move apart, so a report that carried
 * only the milliseconds could not tell a pack that got quicker from a machine that was busier.
 * <p>
 * <strong>It exists because the split decided what got built next.</strong> With shaderc alone
 * served from disk this figure only halved, which said the reflection was the other half of the
 * WORK and that a cache of the bytes on their own could not reach it. What answers that is the
 * module cache, which stores the binding tables beside the bytes they were read off, so a served
 * unit costs a file read and the key that found it, and nothing native at all.
 * <p>
 * <strong>What that buys is work removed and not time.</strong> Measured on the bench: a load
 * with both caches warm builds no module and translates no program, and the wait before the pack
 * draws is the same as a load that builds every one of them. So this figure says what the engine
 * spends and it does not say what a player waits, and the two have been measured apart.
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
 * The counts are tallies in the sense the trig counter is: they are emptied at the head of a
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

	private static final AtomicLong EXPANSION_NANOS = new AtomicLong();

	private static final AtomicInteger EXPANDED = new AtomicInteger();

	private static final AtomicInteger EXPANSIONS_SERVED = new AtomicInteger();

	private static final AtomicLong TRANSLATION_NANOS = new AtomicLong();

	private static final AtomicInteger TRANSLATED = new AtomicInteger();

	private static final AtomicLong MODULE_NANOS = new AtomicLong();

	private static final AtomicInteger MODULES = new AtomicInteger();

	private LoadClock() {
	}

	/** Emptied at the head of a pack load: a tally belongs to the load it was taken under. */
	public static void reset() {
		EXPANSION_NANOS.set(0);
		EXPANDED.set(0);
		EXPANSIONS_SERVED.set(0);
		TRANSLATION_NANOS.set(0);
		TRANSLATED.set(0);
		MODULE_NANOS.set(0);
		MODULES.set(0);
	}

	/** One entry file's worth of flattening, includes followed and settings applied. */
	public static void expansion(long nanos) {
		EXPANSION_NANOS.addAndGet(nanos);
		EXPANDED.incrementAndGet();
	}

	/** One unit asked for again inside the opening that had already built it, and not rebuilt. */
	public static void expansionServed() {
		EXPANSIONS_SERVED.incrementAndGet();
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

	public static long expansionMillis() {
		return EXPANSION_NANOS.get() / 1_000_000L;
	}

	public static int expanded() {
		return EXPANDED.get();
	}

	public static int expansionsServed() {
		return EXPANSIONS_SERVED.get();
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
