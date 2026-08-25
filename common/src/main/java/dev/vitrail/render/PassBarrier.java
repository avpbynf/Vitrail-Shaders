package dev.vitrail.render;

import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;

/**
 * Whether a pass of ours closes on the game's full memory barrier instead of the narrow one.
 * <p>
 * The game ends every render pass by waiting for the whole of memory. Iris binds a framebuffer and
 * draws, so it waits for nothing, and on a backend where each pass is a queue submission that wait
 * is the cost the whole pass work is aimed at. What replaces it names the stages and the accesses
 * a pass of ours really leaves behind and really hands on, which is correct exactly as long as the
 * list is complete.
 * <p>
 * <strong>The list has had to grow three times, and each time the miss was invisible here.</strong>
 * A dependency a driver honours by accident gives the right picture on the machine that wrote it
 * and a wrong one somewhere else, with nothing in any log to say which. That is not a defect this
 * engine can find by reading itself: it needs the machine that shows it, and the person in front of
 * that machine needs a way to answer the question in one launch rather than in a build.
 * <p>
 * So: a file {@code vitrail/full-pass-barrier} in the game directory, or
 * {@code -Dvitrail.fullPassBarrier=true} where somebody has a launcher to type it in. Either puts
 * the game's own wait back on both roads where this engine traded one away: the close of a pass of
 * ours, and the mip chain, which gave up a pass per level and the barrier each of those ended on.
 * It is slower and it cannot be the cause of anything, so an image that comes right with it names
 * the synchronisation.
 * <p>
 * Asked once and the answer kept: it arms a launch, not a frame.
 */
public final class PassBarrier {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.fullPassBarrier");

	private static final String ARM_FILE = "full-pass-barrier";

	/** Null until the game directory can be resolved, which is not true on the first calls. */
	private static Boolean armed;

	private static boolean announced;

	private PassBarrier() {
	}

	/** True while every pass, ours included, is to close on the game's full memory barrier. */
	public static boolean full() {
		if (PROPERTY) {
			announce();

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

		if (armed) {
			announce();
		}

		return armed;
	}

	/**
	 * Said once, and it has to be said: a session running on the wide barrier is measuring a
	 * different engine, and a frame rate read there means nothing beside one read without it.
	 */
	private static void announce() {
		if (announced) {
			return;
		}

		announced = true;
		Vitrail.logger().warn("Every render pass closes on the game's full memory barrier, asked "
				+ "for by vitrail/{} or by -Dvitrail.fullPassBarrier. The image is the safe one and "
				+ "the frame is slower; remove it to go back to the narrow wait", ARM_FILE);
	}
}
