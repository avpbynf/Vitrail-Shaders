package dev.vitrail.render;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;

/**
 * Whether Complementary-style {@code shadowcomp} may leave the graphics command buffer.
 * <p>
 * Off by default, so a jar that nobody A/B'd cannot change the order every player is running.
 * A file {@code vitrail/async-compute} in the game directory, or
 * {@code -Dvitrail.asyncCompute=true} where a launcher can take it, is the same shape as
 * {@link PassBarrier}: it arms a launch, not a frame.
 */
public final class AsyncCompute {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.asyncCompute");

	private static final String ARM_FILE = "async-compute";

	/** Null until the game directory can be resolved, which is not true on the first calls. */
	private static Boolean armed;

	private AsyncCompute() {
	}

	/**
	 * True while shadow compute may be submitted on a spare compute queue. The serial path is
	 * byte-identical in ordering when this is false: same command buffer, head of the frame.
	 */
	public static boolean on() {
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
}
