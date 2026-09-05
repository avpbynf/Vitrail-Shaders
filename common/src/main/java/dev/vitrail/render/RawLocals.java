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
 * of being given the zero they read under Iris.
 * <p>
 * The default is the zero, and {@link LocalZeroes} says why. This is the switch that turns it
 * off, and it exists for the same reason {@link DriverTrig} does: a cost is measured rather than
 * assumed, and it is measured in one jar, a pack load apart, so that the one variable under test
 * is the only thing between two readings. It is also what tells a picture apart: a face or a
 * shore that goes black with the file in place and lights up without it is a pack reading a
 * variable it never wrote, and the variable is then found in the module rather than guessed at.
 * <p>
 * A file {@code vitrail/raw-locals} in the game directory, read again at every pack load, or
 * {@code -Dvitrail.rawLocals=true}, read once at start. It changes the bytes every compiled
 * module is made of, so it is part of the module cache's key: the two states keep two sets of
 * blobs and neither is ever served for the other. The state a compile runs under is taken once,
 * at the head of the compile, and both the key and the patch read that one snapshot: a worker
 * still compiling for the outgoing chain when a load flips the switch would otherwise store a
 * module of one state under the key of the other, and serve it for as long as the key lives.
 * <p>
 * The pass runs between shaderc and the reflection, on the buffer the game copied the compiler's
 * output into, and hands the reflection a buffer of its own allocation when anything was changed:
 * the module frees whichever buffer it was built on, so the copy the game made is freed here in
 * that case and nothing is freed twice.
 */
public final class RawLocals {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.rawLocals");

	private static final String ARM_FILE = "raw-locals";

	private static volatile boolean armed;

	/** The state the compile on this thread runs under, taken at its head; null between compiles. */
	private static final ThreadLocal<Boolean> SNAPSHOT = new ThreadLocal<>();

	/** What the compiles since the last report did, for the line the module cache prints. */
	private static final AtomicLong WALKED = new AtomicLong();
	private static final AtomicLong ZEROED = new AtomicLong();
	private static final AtomicLong VALUES = new AtomicLong();

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

	/**
	 * Takes the state one compile runs under, for the key and the patch alike. Paired with
	 * {@link #end} in a finally, so a compile that throws leaves nothing behind on its thread.
	 */
	public static void begin() {
		SNAPSHOT.set(armed);
	}

	public static void end() {
		SNAPSHOT.remove();
	}

	private static boolean raw() {
		Boolean snapshot = SNAPSHOT.get();

		return snapshot != null ? snapshot : armed;
	}

	/** The word the module cache's key carries for the state, since the state decides the bytes. */
	public static String cacheWord() {
		return raw() ? "raw-locals" : "zeroed-locals";
	}

	/**
	 * The module the reflection should read: the one handed in, or one with its bare variables
	 * zeroed, in which case the one handed in has been freed.
	 * <p>
	 * Only what this engine hands the compiler, which is a pack's programs and its own few
	 * shaders. The game's own shaders and Sodium's go through the same compiler and are left as
	 * they are: the zero reproduces what packs were written against, and neither of those was
	 * written against anything but itself. This engine's own shaders come through too, because
	 * their names share the prefix, and lose nothing by it: a shader that writes before it reads
	 * comes out unchanged.
	 *
	 * @param filename the debug name the compile was given, which says whose module it is
	 * @param spirv    the compiler's output as the game copied it, native order, position at nought
	 */
	public static ByteBuffer patch(String filename, ByteBuffer spirv) {
		if (raw() || !ours(filename)) {
			return spirv;
		}

		WALKED.incrementAndGet();
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
		ZEROED.incrementAndGet();
		VALUES.addAndGet(result.variables() + result.undefs());

		return zeroed;
	}

	/**
	 * Whether a debug name is one this engine hands the compiler: a geometry or fullscreen
	 * program's identifier flattened by the game ({@code vitrail_pack_...},
	 * {@code vitrail_sodium_pack_...}, and this engine's own {@code vitrail_...}), or the label
	 * {@link PackCompute} gives a compute ({@code pack/<load>/...}).
	 */
	private static boolean ours(String filename) {
		return filename.startsWith(Vitrail.MOD_ID + "_") || filename.startsWith("pack/");
	}

	/**
	 * One line beside the module cache's, at the same quiet moment, said in BOTH states and
	 * whether or not this load compiled anything: a load served whole from the store ran under the
	 * state its blobs were built under, which the key guarantees, and a reading taken under it has
	 * to be able to name that state. {@link DriverTrig#announce} follows the same rule.
	 *
	 * @param compiled how many modules the compiler built this load, this engine's and the rest
	 */
	public static void say(long compiled) {
		long walked = WALKED.getAndSet(0L);
		long zeroed = ZEROED.getAndSet(0L);
		long values = VALUES.getAndSet(0L);
		if (armed) {
			Vitrail.logger().warn("Uninitialised variables left RAW, asked for by vitrail/{} or by "
					+ "-Dvitrail.rawLocals ({} modules built this load, none walked): a pack reading "
					+ "one before writing it gets whatever the register held here and zero under "
					+ "Iris; remove the file to go back", ARM_FILE, compiled);

			return;
		}

		Vitrail.logger().info("Uninitialised variables given their zero: {} values in {} of the {} "
				+ "pack modules walked ({} modules built this load in all, the rest served already "
				+ "zeroed), which is the default. vitrail/{} would leave them raw, and this line is "
				+ "said either way so that a reading can name the state it was taken under",
				values, zeroed, walked, compiled, ARM_FILE);
	}
}
