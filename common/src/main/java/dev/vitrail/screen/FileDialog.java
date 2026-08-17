package dev.vitrail.screen;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The platform's own "which file" window, for importing and exporting a pack's settings. This is
 * Iris's {@code FileDialogUtil}.
 * <p>
 * <b>On a thread of its own, and that is the whole reason this class exists</b>: the dialog does not
 * return until the player has answered it, and asking on the render thread would freeze the game
 * behind a window the game is not drawing. The answer comes back as a
 * {@link CompletableFuture}, so whoever asked has to check that the screen is still the one it was
 * before acting on it.
 */
public final class FileDialog {

	/** One thread, kept: two of these windows at once is not a thing any platform handles well. */
	private static final ExecutorService DIALOGS = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Vitrail file dialog");
		// Daemon, so that a dialog nobody answered cannot keep the game from closing.
		thread.setDaemon(true);

		return thread;
	});

	/** What the window offers to show, and what a settings file is called. */
	private static final String FILTER = "*.txt";
	private static final String FILTER_LABEL = "Shader Pack Settings (.txt)";

	public enum Kind {
		OPEN, SAVE
	}

	private FileDialog() {
	}

	/**
	 * Asks the platform for a file.
	 *
	 * @param title  the window's own title, which is not translated: it is handed to the platform
	 *               rather than drawn by the game, and Iris leaves its own untranslated for the same
	 *               reason
	 * @param origin where the window opens, which is the file the settings are usually kept in
	 * @return the file chosen, or nothing when the window was dismissed
	 */
	public static CompletableFuture<Optional<Path>> choose(Kind kind, String title, Path origin) {
		CompletableFuture<Optional<Path>> answer = new CompletableFuture<>();

		DIALOGS.submit(() -> {
			try {
				answer.complete(Optional.ofNullable(ask(kind, title, origin)).map(Paths::get));
			} catch (RuntimeException | LinkageError e) {
				// A platform with no dialog to offer is not a broken screen, so the failure goes back
				// through the future and the caller says it once rather than throwing on this thread,
				// where nothing would see it.
				answer.completeExceptionally(e);
			}
		});

		return answer;
	}

	private static String ask(Kind kind, String title, Path origin) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer filters = stack.mallocPointer(1);
			filters.put(stack.UTF8(FILTER));
			filters.flip();

			String at = origin.toAbsolutePath().toString();

			return kind == Kind.SAVE
					? TinyFileDialogs.tinyfd_saveFileDialog(title, at, filters, FILTER_LABEL)
					: TinyFileDialogs.tinyfd_openFileDialog(title, at, filters, FILTER_LABEL, false);
		}
	}
}
