package dev.vitrail.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.server.packs.resources.ReloadInstance;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Puts the game's own loading overlay up while {@link PackChain} is still compiling, and takes it
 * down when the pack can draw.
 * <p>
 * The overlay is the same {@link LoadingOverlay} a resource reload uses, so the wait looks like a
 * load rather than a frozen vanilla world. It is not started over a reload that is already on
 * screen: that one owns the overlay until it finishes, and this wait starts after it.
 */
final class PackWarmup {

	private static CompletableFuture<Void> future;

	private PackWarmup() {
	}

	static void show() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}

		Overlay overlay = minecraft.gui.overlay();
		if (ours(overlay)) {
			return;
		}

		if (overlay instanceof LoadingOverlay) {
			return;
		}

		future = new CompletableFuture<>();
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
		minecraft.gui.setOverlay(new LoadingOverlay(minecraft, reload, PackWarmup::finished, false));
	}

	static void finish() {
		if (future != null && !future.isDone()) {
			future.complete(null);
		}
	}

	private static boolean ours(Overlay overlay) {
		return overlay instanceof LoadingOverlay && future != null && !future.isDone();
	}

	private static void finished(Optional<Throwable> error) {
		future = null;
	}
}
