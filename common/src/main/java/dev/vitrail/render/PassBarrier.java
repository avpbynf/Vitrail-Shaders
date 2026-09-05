package dev.vitrail.render;

import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;

/**
 * Which wait a pass of ours closes on: the game's full memory barrier, the named one this engine
 * uses by default, or a still narrower destination that drops compute and transfer.
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
 * the synchronisation. That file's meaning does not move: it is still the wide wait, and it still
 * wins over the rest.
 * <p>
 * A second file, {@code vitrail/narrow-pass-barrier}, or {@code -Dvitrail.narrowPassBarrier=true},
 * goes the other way and is an instrument rather than a road: the default destination names compute
 * and every transfer, and this drops those two so a reading can say what waiting for them costs on
 * a backend. Off is the default, so a jar nobody has armed is the engine as it stands, down to the
 * masks written at the close.
 * <p>
 * <strong>Armed, the image may race, and the two stages dropped are the two that would show
 * it.</strong> Compute is on the destination for a pack's own dispatch, which reads a target the
 * pass that just closed wrote as a colour attachment; without the stage, that read is free to run
 * before the write is visible. Transfer is there for the blit that fills a target's mip chain and
 * for the depth copies, each of which writes an image the closing pass also wrote: ordered by
 * nothing else, those are write-after-write, and a driver may run them in either order. A frame
 * read with the file in place is therefore a figure for what the two waits cost, never an image to
 * judge.
 * <p>
 * Asked once and the answer kept: each arms a launch, not a frame. The first close of a pass of
 * ours reads the directory once and every close after it reads a field.
 */
public final class PassBarrier {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.fullPassBarrier");

	private static final boolean NARROW_PROPERTY = Boolean.getBoolean("vitrail.narrowPassBarrier");

	private static final String ARM_FILE = "full-pass-barrier";

	private static final String NARROW_FILE = "narrow-pass-barrier";

	/** Null until the game directory can be resolved, which is not true on the first calls. */
	private static Boolean armed;

	private static Boolean narrowArmed;

	private static boolean announced;

	private static boolean narrowAnnounced;

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
	 * True while a pass of ours drops compute and transfer from the destination of its close.
	 * False, which is the default, keeps those two on the destination, the wait this engine ships.
	 * {@link #full()} wins over this: the wide wait is the one that cannot be the cause of a wrong
	 * image, so it is the one a reporter arms first.
	 */
	public static boolean narrow() {
		if (NARROW_PROPERTY) {
			announceNarrow(true);

			return true;
		}

		// Announced only once the directory has been resolved, and not before: a line saying
		// "default" while the answer is still unknown would latch, and every reading of the session
		// would then name a destination the passes were not closing on.
		if (narrowArmed == null) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft == null || minecraft.gameDirectory == null) {
				return false;
			}

			narrowArmed = Files.isRegularFile(minecraft.gameDirectory.toPath()
					.resolve("vitrail").resolve(NARROW_FILE));
		}

		announceNarrow(narrowArmed);

		return narrowArmed;
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

	/**
	 * Said once and said BOTH WAYS. A line that only appeared when the file was there would make
	 * every reading taken without one unable to name the destination it closed on.
	 */
	private static void announceNarrow(boolean asked) {
		if (narrowAnnounced) {
			return;
		}

		narrowAnnounced = true;
		if (asked) {
			Vitrail.logger().warn("pass-barrier narrow=true file={} property=vitrail.narrowPassBarrier",
					NARROW_FILE);
			return;
		}

		Vitrail.logger().info("pass-barrier narrow=false default");
	}
}
