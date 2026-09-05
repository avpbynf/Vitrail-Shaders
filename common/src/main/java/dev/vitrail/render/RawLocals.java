package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.LocalZeroes;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Whether the variables a pack leaves uninitialised are left raw, as shaderc emits them, instead
 * of being given the zero the OpenGL driver gives them under Iris.
 * <p>
 * The default is the zero, and {@link LocalZeroes} says why. This is the switch that turns it
 * off, and it exists for the same reason {@link DriverTrig} does: a cost is measured rather than
 * assumed, and it is measured in one jar, a pack load apart, so that the one variable under test
 * is the only thing between two readings. It is also what tells a picture apart: a face or a
 * shore that goes black with the file in place and lights up without it is a pack reading a
 * variable it never wrote, and the variable is then found in the module rather than guessed at.
 * <p>
 * A file {@code vitrail/raw-locals} in the game directory, or {@code -Dvitrail.rawLocals=true}.
 * Read again at every pack load. It changes the bytes every compiled module is made of, so it is
 * part of the module cache's key: the two states keep two sets of blobs and neither is ever
 * served for the other.
 * <p>
 * The pass runs between shaderc and the reflection, on the buffer the game copied the compiler's
 * output into, and hands the reflection a buffer of its own allocation when anything was added:
 * the module frees whichever buffer it was built on, so the copy the game made is freed here in
 * that case and nothing is freed twice.
 */
public final class RawLocals {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.rawLocals");

	private static final String ARM_FILE = "raw-locals";

	private static volatile boolean armed;

	/** What the compiles since the last report added, for the line the module cache prints. */
	private static final AtomicLong MODULES = new AtomicLong();
	private static final AtomicLong VARIABLES = new AtomicLong();

	private RawLocals() {
	}

	/**
	 * Read at the head of a pack load, before one module of it has been compiled, since what it
	 * decides is what every compile of the load emits.
	 */
	public static void read(Path gameDirectory) {
		armed = PROPERTY
				|| Files.isRegularFile(gameDirectory.resolve(Vitrail.MOD_ID).resolve(ARM_FILE));
	}

	/** The word the module cache's key carries for the state, since the state decides the bytes. */
	public static String cacheWord() {
		return armed ? "raw-locals" : "zeroed-locals";
	}

	/**
	 * The module the reflection should read: the one handed in, or one with its bare variables
	 * zeroed, in which case the one handed in has been freed.
	 * <p>
	 * Only what this engine hands the compiler. The game's own shaders and Sodium's go through the
	 * same compiler and are left as they are: the zero reproduces what packs were written against,
	 * and nothing else compiled here was written against anything but itself.
	 *
	 * @param filename the debug name the compile was given, which says whose module it is
	 * @param spirv    the compiler's output as the game copied it, native order, position at nought
	 */
	public static ByteBuffer patch(String filename, ByteBuffer spirv) {
		if (armed || !ours(filename)) {
			return spirv;
		}

		int[] words = new int[spirv.remaining() / 4];
		spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
		LocalZeroes.Result result = LocalZeroes.apply(words);
		if (!result.changed()) {
			return spirv;
		}

		// The same allocator and the same zeroing as the game's own copy, since the module closes
		// this buffer exactly as it would have closed that one.
		ByteBuffer zeroed = MemoryUtil.memCalloc(result.words().length * 4);
		zeroed.order(ByteOrder.nativeOrder()).asIntBuffer().put(result.words());
		MemoryUtil.memFree(spirv);
		MODULES.incrementAndGet();
		VARIABLES.addAndGet(result.variables());

		return zeroed;
	}

	/**
	 * Whether a debug name is one of this engine's: a geometry or fullscreen program's identifier
	 * flattened by the game ({@code vitrail_pack_...}, {@code vitrail_sodium_pack_...}), or the
	 * label {@link PackCompute} gives a compute ({@code pack/<load>/...}).
	 */
	private static boolean ours(String filename) {
		return filename.startsWith(Vitrail.MOD_ID + "_") || filename.startsWith("pack/");
	}

	/**
	 * One line beside the module cache's, at the same quiet moment, and only when the compiler
	 * built something: a module served from disk was zeroed the day it was built and says nothing
	 * new. Both states print, so that a reading can name the one it was taken under.
	 */
	public static void say(long compiled) {
		if (compiled == 0L) {
			return;
		}

		long modules = MODULES.getAndSet(0L);
		long variables = VARIABLES.getAndSet(0L);
		if (armed) {
			Vitrail.logger().warn("Uninitialised variables left RAW in the {} modules just built, "
					+ "asked for by vitrail/{} or by -Dvitrail.rawLocals: a pack reading one before "
					+ "writing it gets leftover register contents here and zero under Iris; remove "
					+ "the file to go back", compiled, ARM_FILE);

			return;
		}

		Vitrail.logger().info("Uninitialised variables given the OpenGL driver's zero: {} over {} "
				+ "of the {} modules just built, which is the default. vitrail/{} would leave them "
				+ "raw, and this line is said either way so that a reading can name the state it "
				+ "was taken under", variables, modules, compiled, ARM_FILE);
	}
}
