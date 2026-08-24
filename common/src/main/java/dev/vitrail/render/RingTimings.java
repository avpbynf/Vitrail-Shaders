package dev.vitrail.render;

import dev.vitrail.Vitrail;

import net.caffeinemc.mods.sodium.client.gpu.device.backend.DrawBackend;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.util.Locale;

/**
 * How long Sodium's command-ring {@code rotate} takes on the render thread, and how many times a
 * frame it runs.
 * <p>
 * {@code prepareRender} rotates once for the camera. The shadow walk used to call it again, which
 * on the Vulkan indirect path fences a mapped buffer the GPU may still be reading. Issue 115 is
 * that wait. This clock sits around {@code DefaultChunkRenderer.rotate}, which is the call that
 * does the fence, the swap and the remap, so the number in the log is the stall itself rather than
 * a guess from the frame counter.
 * <p>
 * Off unless {@code -Dvitrail.ringTimings=N} asks for a report every N seconds, the same shape the
 * per-pass profile takes: a clock nobody armed must not write a line into a player's log. A second
 * flag, {@code -Dvitrail.keepShadowRotate=true}, puts the old second rotate back, so both paths are
 * timed on ONE jar rather than on two builds whose difference is anybody's guess. A
 * {@code vitrail/keep-shadow-rotate} file in the game directory says the same thing as that flag,
 * because the launcher's arguments are a place a session cannot reach while a file next to the
 * pack is one it can. The file is asked once and the answer kept: it arms a launch, not a frame.
 */
public final class RingTimings {

	private static final int REPORT_SECONDS = Integer.getInteger("vitrail.ringTimings", 0);

	private static final boolean ENABLED = REPORT_SECONDS > 0;

	private static final boolean KEEP = Boolean.getBoolean("vitrail.keepShadowRotate");

	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private static int rotatesThisFrame;

	private static long begin;

	private static long frames;

	private static long firstNanos;

	private static long firstCount;

	private static long firstMax;

	private static long secondNanos;

	private static long secondCount;

	private static long secondMax;

	private static long lastReport;

	private static boolean loggedBackend;

	/** The marker file's answer, held after the first ask: a stat per frame is not a probe cost. */
	private static Boolean keepFromFile;

	private RingTimings() {
	}

	/**
	 * The old shadow walk, the one that goes through {@code prepareRender} and rotates again, asked
	 * for by the JVM flag or by the marker file. The file is read once, on the first frame that can
	 * resolve the game directory.
	 */
	public static boolean keepSecondRotate() {
		if (KEEP) {
			return true;
		}

		if (keepFromFile == null) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameDirectory == null) {
				return false;
			}

			keepFromFile = Files.isRegularFile(minecraft.gameDirectory.toPath()
					.resolve("vitrail").resolve("keep-shadow-rotate"));
		}

		return keepFromFile;
	}

	public static void beginRotate() {
		if (!ENABLED) {
			return;
		}

		begin = System.nanoTime();
	}

	public static void endRotate() {
		if (!ENABLED) {
			return;
		}

		long dt = System.nanoTime() - begin;
		rotatesThisFrame++;
		if (rotatesThisFrame == 1) {
			firstNanos += dt;
			firstCount++;
			if (dt > firstMax) {
				firstMax = dt;
			}
		} else if (rotatesThisFrame == 2) {
			secondNanos += dt;
			secondCount++;
			if (dt > secondMax) {
				secondMax = dt;
			}
		}

		if (!loggedBackend) {
			loggedBackend = true;
			Vitrail.logger().info("Ring timings: backend {}, keepShadowRotate {}",
					DrawBackend.BACKEND, keepSecondRotate());
		}
	}

	public static void endFrame() {
		if (!ENABLED) {
			return;
		}

		long now = System.nanoTime();
		if (lastReport == 0) {
			lastReport = now;
		}

		frames++;
		rotatesThisFrame = 0;
		if (now - lastReport >= REPORT_SECONDS * NANOS_PER_SECOND) {
			report(now);
		}
	}

	private static void report(long now) {
		double seconds = (now - lastReport) / (double) NANOS_PER_SECOND;
		Vitrail.logger().info(
				"Ring timings over {} s, {} frames, backend {}, keepShadowRotate {}",
				String.format(Locale.ROOT, "%.1f", seconds), frames, DrawBackend.BACKEND,
				keepSecondRotate());
		Vitrail.logger().info("  first rotate:  {} ms avg, {} ms max, {} times",
				millis(firstNanos, firstCount), millis(firstMax, 1), firstCount);
		Vitrail.logger().info("  second rotate: {} ms avg, {} ms max, {} times",
				millis(secondNanos, secondCount), millis(secondMax, 1), secondCount);

		frames = 0;
		firstNanos = 0;
		firstCount = 0;
		firstMax = 0;
		secondNanos = 0;
		secondCount = 0;
		secondMax = 0;
		lastReport = now;
	}

	private static String millis(long nanos, long count) {
		if (count == 0) {
			return "  0.000";
		}

		return String.format(Locale.ROOT, "%7.3f", nanos / 1_000_000.0 / count);
	}
}
