package dev.vitrail.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The platform's own "which file" window, for importing and exporting a pack's settings. This is
 * Iris's {@code FileDialogUtil}, asked through SDL.
 * <p>
 * <b>This is the Minecraft 26.3 half.</b> The 26.2 half, and Iris, ask LWJGL's tinyfd, which 26.3 no
 * longer ships. The game itself picks no file anywhere in 26.3, but it did move the one thing it
 * asked tinyfd for, its message box, onto SDL ({@code MessageBox} through {@code SDLMessageBox}), and
 * this half follows it onto SDL's file dialog, {@code SDL_ShowFileDialogWithProperties}. A class of
 * the same name and the same surface sits under {@code src/mc26.2/}, and a build compiles exactly one
 * of them.
 * <p>
 * <b>SDL turns the threading of the 26.2 half around.</b> tinyfd does not return until the player
 * has answered, so that half asks on a thread of its own to keep the game drawing. SDL has to be asked
 * on the thread that runs its events, which is the game's own, and it returns at once: the answer
 * comes later through a callback, on the game's thread or on a thread of SDL's own, depending on the
 * system. What a caller sees is the same either way. The answer comes back as a
 * {@link CompletableFuture} on some thread that is not promised, so whoever asked has to check that
 * the screen is still the one it was before acting on it, which both callers already do.
 * <p>
 * <b>The dialog belongs to the game's window.</b> On macOS SDL shows it as a sheet over that window
 * and the game keeps running under it; asked without a window, SDL would run it modally on the game's
 * thread and freeze the game behind it, which is exactly what the 26.2 half's thread is there to
 * prevent. Belonging to the window also makes it modal to it on the systems that honour that, which
 * keeps the game from taking the click that would ask for a second one while the first is up. The
 * 26.2 half queues a second one behind the first instead; SDL offers no way to wait for one dialog
 * without holding the game's thread, so here each question is simply answered on its own.
 */
public final class FileDialog {

	/** What the window offers to show, in SDL's form: extensions without the dot, and a label. */
	private static final String FILTER = "txt";
	private static final String FILTER_LABEL = "Shader Pack Settings (.txt)";

	/**
	 * The questions SDL has not answered yet, by the number handed to it with each one. SDL passes
	 * that number back to the callback, which is how an answer finds its question.
	 */
	private static final Map<Long, CompletableFuture<Optional<Path>>> WAITING =
			new ConcurrentHashMap<>();

	private static final AtomicLong NEXT = new AtomicLong(1);

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
		long question = NEXT.getAndIncrement();

		// Before asking and not after, since SDL answers at once, before it returns, when it cannot
		// show a dialog at all.
		WAITING.put(question, answer);

		try {
			ask(kind, title, origin, question);
		} catch (RuntimeException | LinkageError e) {
			// A platform with no dialog to offer is not a broken screen, so the failure goes back
			// through the future and the caller says it once, the same as on 26.2.
			WAITING.remove(question);
			answer.completeExceptionally(e);
		}

		return answer;
	}

	private static void ask(Kind kind, String title, Path origin, long question) {
		// SDL's own rule for this call, and on macOS a broken one is a crash rather than an error.
		RenderSystem.assertOnRenderThread();

		SDL_DialogFileFilter.Buffer filters = Kept.FILTERS;
		long window = Minecraft.getInstance().getWindow().handle();

		int properties = SDLProperties.SDL_CreateProperties();
		if (properties == 0) {
			throw new IllegalStateException(
					"SDL could not make a file dialog: " + SDLError.SDL_GetError());
		}

		// SDL is done with the set once the call returns: the functions it builds on this one destroy
		// their own set straight after asking. The filters are the exception, read through their
		// pointer until the callback has run, which is why they are kept rather than made here.
		try {
			SDLProperties.SDL_SetPointerProperty(properties,
					SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER, window);
			SDLProperties.SDL_SetPointerProperty(properties,
					SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER, filters.address());
			SDLProperties.SDL_SetNumberProperty(properties,
					SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, filters.remaining());
			SDLProperties.SDL_SetStringProperty(properties,
					SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title);
			// A file rather than a folder, as the 26.2 half hands tinyfd. SDL takes either, and each
			// system does with it what its own dialog does: the folder it is in, the name offered to
			// save under, or both.
			String at = origin.toAbsolutePath().toString();
			SDLProperties.SDL_SetStringProperty(properties,
					SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING, at);

			int type = kind == Kind.SAVE
					? SDLDialog.SDL_FILEDIALOG_SAVEFILE
					: SDLDialog.SDL_FILEDIALOG_OPENFILE;
			SDLDialog.SDL_ShowFileDialogWithProperties(type, Kept.CALLBACK, question, properties);
		} finally {
			SDLProperties.SDL_DestroyProperties(properties);
		}
	}

	/**
	 * SDL's answer to one question. The list of files is null when SDL could not show the dialog, and
	 * empty when the player dismissed it; otherwise its first entry is the file, since no question
	 * here allows more than one. The list is SDL's and is gone once this returns, so the path is read
	 * out of it here.
	 * <p>
	 * Nothing may be thrown from here: this is called from native code, where an exception has
	 * nowhere to go. What goes wrong goes into the future instead.
	 */
	private static void answered(long question, long files, int filter) {
		CompletableFuture<Optional<Path>> answer = WAITING.remove(question);
		if (answer == null) {
			return;
		}

		try {
			if (files == MemoryUtil.NULL) {
				answer.completeExceptionally(new IllegalStateException(
						"SDL could not show a file dialog: " + SDLError.SDL_GetError()));

				return;
			}

			long first = MemoryUtil.memGetAddress(files);
			answer.complete(first == MemoryUtil.NULL
					? Optional.empty()
					: Optional.of(Paths.get(MemoryUtil.memUTF8(first))));
		} catch (RuntimeException e) {
			answer.completeExceptionally(e);
		}
	}

	/**
	 * What SDL is handed and holds on to: the callback every dialog answers through, and the filter
	 * list. Both are made the first time a dialog is asked for and kept for as long as the game runs.
	 * <p>
	 * Kept rather than freed after each dialog because nothing says when SDL is done with them. It
	 * reads the filters until the callback has run, and the callback is a stub SDL calls into, which
	 * cannot be freed from inside itself. A few dozen bytes and one stub for the life of the game is
	 * the price of never freeing either one too early.
	 * <p>
	 * A holder class so that making them happens inside {@link #choose}'s own try, where a failure goes
	 * back through the future, rather than when the outer class is first touched, where it would go
	 * up through the button that was pressed.
	 */
	private static final class Kept {

		static final SDL_DialogFileCallback CALLBACK =
				SDL_DialogFileCallback.create(FileDialog::answered);

		private static final ByteBuffer LABEL = MemoryUtil.memUTF8(FILTER_LABEL);
		private static final ByteBuffer PATTERN = MemoryUtil.memUTF8(FILTER);

		static final SDL_DialogFileFilter.Buffer FILTERS = SDL_DialogFileFilter.calloc(1);

		static {
			FILTERS.get(0).set(LABEL, PATTERN);
		}

		private Kept() {
		}
	}
}
