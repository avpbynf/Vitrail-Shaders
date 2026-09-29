package dev.vitrail.cache;

import dev.vitrail.render.PackChain;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.SamplerReach;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Keeps what the game's compiler makes of each shader unit, on disk, so a second load of the same
 * pack does not pay for the compile again.
 * <p>
 * <strong>The 26.3 half, and what it stores is the SPIR-V alone.</strong> The 26.2 half stores the
 * module the compiler made together with the reflection SPIRV-Cross read off it, serialised through
 * the records of the game's {@code IntermediaryShaderModule}, because 26.2 reflected every module as
 * it was made. 26.3 removed those records: its module is the words and the stage, and reflects
 * itself on demand when a pipeline is built from it. So the words are all there is to keep, as the
 * compiler left them and this engine's passes patched them, and a served unit is a file read and a
 * module made around it, which reflects when asked exactly as a compiled one does. What the
 * reflection then leaves out, {@code SamplerReach}, is read off the served words the same way.
 * <p>
 * The text a unit is keyed on is the one handed to {@code GlslCompiler.compileToSpv}, and on 26.3
 * that is not the whole input: the defines travel beside it and are handed to shaderc as macros,
 * so they are keyed too, and a unit that includes another file is not stored at all, the included
 * text being fetched by the compiler rather than handed over. This engine's units are flattened
 * before they reach the compiler, so none of its own has an include.
 * <p>
 * <strong>The key IS the input, hashed</strong>, and nothing else: the exact text handed to the
 * compiler, the stage it is compiled for, and everything that decides what that text turns into,
 * which is the mod's version and, on a development build, the commit behind it, the game's, the
 * loader with its own version, and the LWJGL build whose bundled shaderc and SPIRV-Cross do the
 * work. Nothing is keyed on a pack name, a file path or the debug name the module carries, so
 * there is no invalidation to get wrong and none is written. An edited shader, a moved pack
 * setting, a translator that emits one word differently, a loader that patches the compiler: each
 * is a different key, and the blob under the old one is never asked for again.
 * <p>
 * <strong>The debug name stays out of the key on purpose.</strong> The game's pipeline cache is
 * keyed on the identifier and never on the text, so this engine puts the load number in that name
 * ({@code pack/<load>/...}): two chains must not share an identifier when their GLSL differs
 * ({@code docs/internals/game-graphics-api.md}). The disk key already carries the text, so hashing
 * the load number as well would make every Apply, every R and every portal a miss of identical
 * GLSL. F3+T does not bump the load and already hit; a pack reload now hits too. A module on this
 * game carries no name at all, so a served one is the same object a compiled one would have been.
 * Two texts colliding under one blob would need a SHA-256 collision of the source, which is not
 * the trap the load number exists to prevent.
 * <p>
 * <strong>One bit of the name is keyed all the same</strong>, because it changes the bytes: whether
 * the unit is this engine's, which is what {@code RawLocals.ours} reads off the name. Only such a
 * unit is compiled with its locations assigned and the OpenGL names of two builtins, and only such
 * a unit is given its zeroes and has its names stripped, so one text compiled under a name of
 * ours and under the game's comes out as two different modules. The bit goes in and the name,
 * load number and all, stays out.
 * <p>
 * <strong>What is stored is the module as its maker handed it over</strong>, at the one instant at
 * which it is finished and nothing has yet read it: {@code PipelineBuilder} rewrites the bindings
 * of a module it builds a pipeline from, so a module is stored before any caller has had it, and
 * every hit is an allocation of its own.
 * <p>
 * <strong>The same digest names the unit in memory.</strong> {@link ModuleShare} keeps what one pack
 * load has made under it, so a second program with the same text is handed a copy of the first one's
 * unit instead of a second compile or a second file read; {@link #keep} is what fills both places
 * and {@link #shared} reads the first. It is made without a disk when there is none
 * ({@link #shareKeyOf}), so a load with this cache switched off shares all the same.
 * <p>
 * Everything from the digest that vouches for a file to the ceiling and the folder of each
 * edition is the same on both games and is written in {@link ModuleStore}.
 */
public final class ModuleCache {

	/**
	 * How large the store may grow, in mebibytes, offered on the Sodium page. Half a gigabyte is
	 * what this class shipped as a constant; the slider keeps that as its untouched value.
	 */
	public static final int MIN_CEILING_MIB = ModuleStore.MIN_CEILING_MIB;
	public static final int MAX_CEILING_MIB = ModuleStore.MAX_CEILING_MIB;
	public static final int DEFAULT_CEILING_MIB = ModuleStore.DEFAULT_CEILING_MIB;
	public static final int CEILING_STEP_MIB = ModuleStore.CEILING_STEP_MIB;

	/**
	 * Bumped by hand when the layout of a file changes rather than its content. This is the 26.3
	 * layout, the words behind their length and nothing else, and its name is not the 26.2 one, so
	 * the two games never read each other's blobs: the key carries the game's version as well, and
	 * the folder the edition, so this is the third fence and not the first. What the zero pass emits
	 * for a module can move without this layout moving, so the pass carries a version of its own
	 * that {@link #keyOf} hashes beside this.
	 */
	private static final String FORMAT = "vitrail-module-26.3-1";

	private ModuleCache() {
	}

	/**
	 * How large the store may grow, in mebibytes, which is what the Sodium slider reads. An
	 * absent or unreadable file is {@link #DEFAULT_CEILING_MIB}.
	 */
	public static int ceilingMib() {
		return ModuleStore.ceilingMib();
	}

	/**
	 * Writes the ceiling and keeps the live answer, so the next store sees it. A store already
	 * over the new number is swept at once: no pack reload and no restart.
	 */
	public static void setCeilingMib(int mib) {
		ModuleStore.setCeilingMib(mib);
	}

	/**
	 * What names this unit on disk, or null when there is nowhere to look and nowhere to write.
	 * <p>
	 * Worked out once by the caller and handed to both ends, because the source of a composite runs
	 * to hundreds of kilobytes and hashing it twice to answer one question is work for nothing.
	 * <p>
	 * The debug name is not an argument. It used to be, and a pack reload then missed every unit
	 * whose GLSL had not moved, because the name carries the load number ({@code pack/<load>/...})
	 * and {@code PackChain} increments that number on every new chain. The file layout did not
	 * change, so the format token stays; old blobs under the names-in-the-key hashes sit until
	 * the sweep collects them.
	 *
	 * @param source  the text the compiler was handed, the pipeline's defines already injected
	 * @param stage   vertex, fragment, or the compute recipe token, which decides the whole compile
	 * @param defines the defines handed to shaderc beside the text, as macros
	 * @param ours    whether {@code RawLocals.ours} claims the unit's debug name, which decides the
	 *                options it is compiled with and the passes run over what comes out
	 */
	public static @Nullable String keyOf(String source, String stage, String defines,
			boolean ours) {
		if (ModuleStore.directory() == null || source.contains("#include")) {
			return null;
		}

		return digestOf(source, stage, defines, ours);
	}

	/**
	 * What names this unit in {@link ModuleShare}: the same digest as {@link #keyOf}, made whether or
	 * not there is a disk to keep it on, so a load with the cache switched off shares its units all
	 * the same. Null for a unit that includes another file, which is no unit for the reason
	 * {@link #keyOf} gives.
	 * <p>
	 * Asked only where {@link #keyOf} answered null: with a disk cache the one digest names the unit
	 * in both places, and hashing a composite's hundreds of kilobytes twice is work for nothing.
	 */
	public static @Nullable String shareKeyOf(String source, String stage, String defines,
			boolean ours) {
		return source.contains("#include") ? null : digestOf(source, stage, defines, ours);
	}

	private static String digestOf(String source, String stage, String defines, boolean ours) {
		MessageDigest digest = ModuleStore.keyStart(FORMAT);
		// Whose unit this is: a unit of the game's that shares its text with one of ours is compiled
		// with other options and walked by neither pass, so the same text is two modules.
		ModuleStore.feed(digest, ours ? "ours" : "theirs");
		ModuleStore.feed(digest, stage);
		ModuleStore.feed(digest, defines);
		ModuleStore.feed(digest, source);

		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * The words the compiler would have made of this unit, or null when it has to make them.
	 * <p>
	 * A hit costs a file read and one allocation; nothing native runs. What comes back is native
	 * memory the module made around it owns and frees at its {@code close}, holding bytes of its
	 * own so that the binding rewrite a pipeline builder makes to its module rewrites nobody else's.
	 */
	public static @Nullable ByteBuffer lookup(@Nullable String key) {
		ModuleStore.Hit hit = ModuleStore.read(key);
		if (hit == null) {
			return null;
		}

		ByteBuffer spirv = rebuild(hit.raw(), hit.length());
		if (spirv == null) {
			return null;
		}

		ModuleStore.served(hit.file());

		return spirv;
	}

	/**
	 * The words an earlier asker of this load made of the unit, copied into a buffer of their own, or
	 * null when nobody has made it yet.
	 * <p>
	 * A hit costs one allocation; nothing is read from disk and nothing native runs. What comes back
	 * is native memory the module made around it owns and frees at its {@code close}, holding bytes
	 * of its own so that the binding rewrite a pipeline builder makes rewrites nobody else's.
	 *
	 * @param unit the key {@link ModuleShare} files the unit under
	 */
	public static @Nullable ByteBuffer shared(@Nullable String unit) {
		ModuleShare.Blob blob = ModuleShare.load().find(unit);
		if (blob == null) {
			return null;
		}

		ByteBuffer spirv = rebuild(blob.raw(), blob.length());
		if (spirv != null) {
			ModuleStore.shared();
		}

		return spirv;
	}

	/**
	 * The words a stored file holds, or null when they are not SPIR-V.
	 * <p>
	 * The buffer is allocated where the game's own is, in native memory, because what frees it is
	 * the module's own {@code close} and that is a {@code memFree}. It is freed here, and only here,
	 * when the read gives up part way through: nothing else has been handed it yet.
	 */
	private static @Nullable ByteBuffer rebuild(byte[] raw, int length) {
		ByteBuffer spirv = null;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw, 0, length))) {
			byte[] words = ModuleStore.readWords(in, length);

			spirv = MemoryUtil.memAlloc(words.length);
			spirv.put(words);
			spirv.flip();

			return spirv;
		} catch (IOException | RuntimeException | OutOfMemoryError e) {
			// The Error is in the list on purpose. Everything else in here is a miss, and a length
			// this could not allocate for would otherwise be the one shape of damaged file that
			// takes the pack load down instead.
			if (spirv != null) {
				MemoryUtil.memFree(spirv);
			}

			ModuleStore.sayAboutReading("a stored module could not be read back (" + e + ")");

			return null;
		}
	}

	/**
	 * Counts a unit the compiler is about to build, said BEFORE it builds it, and names it for the
	 * line at the end of the load.
	 *
	 * @param filename the debug name the compile was given, which says whose unit it is
	 */
	public static void building(String filename) {
		ModuleStore.building(filename);
	}

	/**
	 * Keeps the words the compiler has just made, under the key of the text they were made from.
	 * <p>
	 * Called with the words of the module the caller is about to receive and before anything has
	 * reflected it, which is the one instant at which it is both finished and untouched.
	 */
	public static void store(@Nullable String key, ByteBuffer spirv) {
		if (key == null || ModuleStore.directory() == null) {
			return;
		}

		keep(key, key, spirv);
	}

	/**
	 * Keeps the words of a unit the compiler has just made, or of a served one, where the rest of this
	 * load can have a copy of them and on disk where there is a disk, from one description of them.
	 * <p>
	 * The same instant as {@link #store} takes: the words are finished and nothing has reflected them
	 * or rewritten their bindings, so both places hold them as their maker handed them over.
	 *
	 * @param unit the key {@link ModuleShare} files them under, or null for a unit that is not to be
	 *             shared, which is every unit of the game's own
	 * @param key  the key of their file, or null where there is nowhere to write one
	 */
	public static void keep(@Nullable String unit, @Nullable String key, ByteBuffer spirv) {
		if (unit == null && key == null) {
			return;
		}

		byte[] raw;
		try {
			raw = describe(spirv);
		} catch (IOException | RuntimeException e) {
			ModuleStore.sayAboutStoring("a module could not be written down (" + e + ")");

			return;
		}

		if (unit != null) {
			ModuleShare.load().offer(unit, new ModuleShare.Blob(raw, raw.length));
		}

		Path root = ModuleStore.directory();
		if (key != null && root != null) {
			ModuleStore.keep(root, key, raw);
		}
	}

	/** The words, behind their length, in the order {@link #rebuild} reads them back. */
	private static byte[] describe(ByteBuffer spirv) throws IOException {
		// A view of its own, so the caller's position and limit are left where they were.
		ByteBuffer view = spirv.duplicate();
		byte[] words = new byte[view.remaining()];
		view.get(words);
		ByteBuffer raw = ByteBuffer.allocate(Integer.BYTES + words.length);
		raw.putInt(words.length).put(words);

		return raw.array();
	}

	/**
	 * One line for the load that has just finished, in both directions and whatever happened; called
	 * at every client tick and silent until the compiler has been quiet long enough for a load to be
	 * over. {@link ModuleStore#say} says what it prints.
	 */
	public static void say() {
		ModuleStore.say("compiled");
	}

	/**
	 * Deletes what another edition left, but for the one neighbour a build carrying a commit spares.
	 * {@link ModuleStore#dropOtherEditions} says which and why.
	 */
	static String dropOtherEditions(Path root, Path mine, String family) {
		return ModuleStore.dropOtherEditions(root, mine, family);
	}

}
