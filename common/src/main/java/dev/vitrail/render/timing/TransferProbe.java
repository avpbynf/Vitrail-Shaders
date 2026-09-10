package dev.vitrail.render.timing;

import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Names the buffer and texture transfers, colour and depth clears and pass openings recorded into
 * the frame's command buffer while a render pass is already open on it.
 * <p>
 * The game refuses a copy inside a pass on the {@code CommandEncoder} it hands out, but it hands
 * out a new one on every {@code createCommandEncoder()} over the one Vulkan encoder, so the refusal
 * only sees the passes that same instance opened. A copy asked for through any other instance goes
 * straight into whatever pass is recording. Vulkan forbids a copy, a clear or a barrier there, and
 * what a driver makes of one differs between drivers. Which calls land where is not something
 * reading settles, so this says it.
 * <p>
 * A file {@code vitrail/transfer-in-pass} in the game directory, or
 * {@code -Dvitrail.transferInPass=true}. Each distinct call, pass and caller is said the first time
 * it is seen; the running counts follow, at most every ten seconds, for as long as such calls keep
 * arriving. Asked once and the answer kept.
 */
public final class TransferProbe {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.transferInPass");

	private static final String ARM_FILE = "transfer-in-pass";

	private static final long REPORT_NANOS = 10_000_000_000L;

	private static final int CALLER_FRAMES = 5;

	private static final StackWalker WALKER = StackWalker.getInstance();

	private static final Map<String, int[]> COUNTS = new HashMap<>();

	/** Null until the game directory can be resolved, which is not true on the first calls. */
	private static Boolean armed;

	private static boolean announced;

	private static long lastReport;

	private TransferProbe() {
	}

	/**
	 * One call reaching the Vulkan encoder.
	 *
	 * @param call the encoder method, as the report names it
	 * @param open the label of the pass open at that moment, or null when none is
	 */
	public static void seen(String call, Supplier<String> open) {
		if (!armed()) {
			return;
		}

		if (!announced) {
			announced = true;
			lastReport = System.nanoTime();
			Vitrail.logger().warn("Transfer probe armed by vitrail/{}: a buffer or texture transfer, a "
					+ "colour or depth clear or a pass opening recorded while a render pass is open is "
					+ "named here", ARM_FILE);
		}

		if (open == null) {
			return;
		}

		String caller = WALKER.walk(frames -> frames
				.filter(frame -> outside(frame.getClassName()))
				.limit(CALLER_FRAMES)
				.map(frame -> simple(frame.getClassName()) + "." + frame.getMethodName())
				.collect(Collectors.joining(" <- ")));
		String key = call + " inside \"" + open.get() + "\" from " + caller;
		int[] count = COUNTS.get(key);
		if (count == null) {
			count = new int[1];
			COUNTS.put(key, count);
			Vitrail.logger().warn("Transfer inside an open pass: {}", key);
		}

		count[0]++;

		long now = System.nanoTime();
		if (now - lastReport >= REPORT_NANOS) {
			lastReport = now;
			COUNTS.forEach((name, n) -> Vitrail.logger().info("Transfer inside an open pass, {} "
					+ "times so far: {}", n[0], name));
		}
	}

	private static boolean armed() {
		if (PROPERTY) {
			return true;
		}

		if (armed == null) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameDirectory == null) {
				return false;
			}

			armed = Files.isRegularFile(minecraft.gameDirectory.toPath()
					.resolve("vitrail").resolve(ARM_FILE));
		}

		return armed;
	}

	private static boolean outside(String className) {
		return !className.startsWith("com.mojang.blaze3d.vulkan.VulkanCommandEncoder")
				&& !className.startsWith("com.mojang.blaze3d.systems.CommandEncoder")
				&& !className.startsWith("dev.vitrail.render.timing.TransferProbe")
				&& !className.startsWith("java.");
	}

	private static String simple(String className) {
		int dot = className.lastIndexOf('.');

		return dot < 0 ? className : className.substring(dot + 1);
	}
}
