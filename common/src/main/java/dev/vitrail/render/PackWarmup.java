package dev.vitrail.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Util;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Puts the game's own loading overlay up while {@link PackChain} is still compiling, and takes it
 * down when the pack can draw.
 * <p>
 * The overlay is the same {@link LoadingOverlay} a resource reload uses, so the wait looks like a
 * load rather than a frozen vanilla world. It is not started over a reload that is already on
 * screen: that one owns the overlay until it finishes, and this wait starts after it.
 * <p>
 * Vanilla's overlay fades in two beats. The first drops the red and leaves the logo; the second
 * draws the world underneath and is the blink. This class keeps the first and cuts the overlay
 * before the second.
 */
final class PackWarmup {

	/** Vanilla's first fade beat, just under the second so that beat never starts. */
	private static final long FIRST_FADE_MS = 950L;

	private static CompletableFuture<Void> future;
	private static Overlay overlay;
	private static long fadeStarted = -1L;

	private PackWarmup() {
	}

	static void show() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}

		Overlay current = minecraft.gui.overlay();
		if (covering()) {
			return;
		}

		if (current instanceof LoadingOverlay) {
			return;
		}

		future = new CompletableFuture<>();
		fadeStarted = -1L;
		ReloadInstance reload = new ReloadInstance() {
			@Override
			public CompletableFuture<?> done() {
				return future;
			}

			@Override
			public float getActualProgress() {
				return PackChain.warmupProgress();
			}
		};
		overlay = new LoadingOverlay(minecraft, reload, PackWarmup::finished, false);
		minecraft.gui.setOverlay(overlay);
	}

	static void finish() {
		if (future != null && !future.isDone()) {
			future.complete(null);
		}
	}

	/** The overlay is still on screen, including the beat after compile while the red drops. */
	static boolean covering() {
		Minecraft minecraft = Minecraft.getInstance();

		return minecraft != null && overlay != null && minecraft.gui.overlay() == overlay;
	}

	/**
	 * Drops the overlay at the end of the first fade beat, before vanilla would draw the world
	 * under a fading logo, which is the blink.
	 */
	static void dismissIfDue() {
		if (overlay == null || fadeStarted < 0L) {
			return;
		}

		if (Util.getMillis() - fadeStarted < FIRST_FADE_MS) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft != null && minecraft.gui.overlay() == overlay) {
			minecraft.gui.setOverlay(null);
		}

		overlay = null;
		future = null;
		fadeStarted = -1L;
	}

	private static void finished(Optional<Throwable> error) {
		fadeStarted = Util.getMillis();
	}
}
