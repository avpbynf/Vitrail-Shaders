package dev.vitrail.render;

import com.mojang.blaze3d.GpuDeviceLossException;

import java.util.function.BiConsumer;

/**
 * What a hook of {@link PackChain} does with an exception that got out of its work: the pack stops
 * being drawn, the exception is logged, and the chain hands back what it holds.
 * <p>
 * Every hook is an entry point the game or the loader calls from inside a frame of its own. An
 * exception that leaves one reaches the game through an event handler and comes back on the very
 * next frame, so what the player sees is not a pack that stopped drawing but a game that will not
 * run, and each of them ends in this same catch. It is held here, with the three steps as
 * parameters, so that the order they run in and what a step that throws does to the ones behind it
 * are checked without a device: written out at a dozen sites, a site that put the release ahead of
 * the log would lose the reason the next time the release threw.
 * <p>
 * The order is the undo, the stop, the log and then the release. The log stands before the release
 * because the release touches the device and is the step that can throw again. A step that throws
 * ends the sequence, so the ones behind it do not run and its exception is the one that
 * propagates, without the one that was being handled attached to it.
 * <p>
 * An exception a hook's own catch does not name never gets here: the sites catch
 * {@link RuntimeException} and an {@link Error} passes through them untouched.
 */
final class HookFailure {

	/** The line the log carries, which is what a report of a pack stopped by a frame is found by. */
	static final String MESSAGE = "Vitrail stopped drawing this pack after an error";

	/** For a hook with nothing to undo, or whose own work already released. */
	static final Runnable NOTHING = () -> { };

	private final Runnable stop;
	private final BiConsumer<String, Throwable> log;

	/**
	 * @param stop what stops the frame drawing the pack
	 * @param log  where the message and the exception go, as an error
	 */
	HookFailure(Runnable stop, BiConsumer<String, Throwable> log) {
		this.stop = stop;
		this.log = log;
	}

	/** {@link #abandon(RuntimeException, Runnable, Runnable)} with nothing to undo. */
	void abandon(RuntimeException failure, Runnable release) {
		abandon(failure, NOTHING, release);
	}

	/**
	 * Stops the pack for a failure of one hook.
	 * <p>
	 * A device loss is rethrown as it came, before any step runs, the undo included: it is not about
	 * this pack, the device is gone, and {@code PackChoice} rethrows it out of a release for the same
	 * reason.
	 *
	 * @param failure what the hook threw
	 * @param undo    what the hook itself has half done and must take back, before the pack is
	 *                stopped
	 * @param release what hands the chain's memory back, after the log
	 */
	void abandon(RuntimeException failure, Runnable undo, Runnable release) {
		if (failure instanceof GpuDeviceLossException) {
			throw failure;
		}

		undo.run();
		this.stop.run();
		this.log.accept(MESSAGE, failure);
		release.run();
	}
}
