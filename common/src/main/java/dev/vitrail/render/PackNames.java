package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.DebugNames;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs {@link DebugNames} over the modules this engine compiles, so that a pack's own identifiers
 * do not reach Apple's Metal compiler and collide with its namespace. {@link DebugNames} carries
 * what it drops, what it keeps, and which of the packs that were dying were dying of this.
 * <p>
 * Placed exactly where {@link RawLocals} is placed, between shaderc and the reflection, and it
 * follows the same rule about the buffer: whoever replaces a buffer frees the one they were handed,
 * so the module ends up closing one buffer and one only.
 * <p>
 * Only this engine's own compiles. The game's shaders and Sodium's go through the same compiler and
 * are left alone: they are written by people who can be told to rename a variable, and a name of
 * theirs the game reads back is not this engine's business.
 */
public final class PackNames {

	private static final AtomicLong WALKED = new AtomicLong();
	private static final AtomicLong STRIPPED = new AtomicLong();
	private static final AtomicLong NAMES = new AtomicLong();

	private PackNames() {
	}

	/** The word the module cache's key carries, since what this drops changes the bytes. */
	public static String cacheWord() {
		return DebugNames.VERSION;
	}

	/**
	 * The module the reflection should read: the one handed in, or one without the names of
	 * anything the outside never asks for, in which case the one handed in has been freed.
	 *
	 * @param filename the debug name the compile was given, which says whose module it is
	 * @param spirv    the compiler's output, native order, position at nought
	 */
	public static ByteBuffer patch(String filename, ByteBuffer spirv) {
		// The same set of compiles RawLocals claims, asked of RawLocals rather than restated: two
		// copies of that rule would answer differently the day one of them is widened.
		if (ShaderDebugInfo.asked() || !RawLocals.ours(filename)) {
			return spirv;
		}

		WALKED.incrementAndGet();
		int[] words = new int[spirv.remaining() / 4];
		spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
		DebugNames.Result result = DebugNames.strip(words);
		if (!result.changed()) {
			return spirv;
		}

		ByteBuffer stripped = MemoryUtil.memCalloc(result.words().length * 4);
		stripped.order(ByteOrder.nativeOrder()).asIntBuffer().put(result.words());
		MemoryUtil.memFree(spirv);
		STRIPPED.incrementAndGet();
		NAMES.addAndGet(result.dropped());

		return stripped;
	}

	/**
	 * One line beside the module cache's, said in BOTH states: a load served whole from the store
	 * was built under the state its blobs carry, and a reading taken on it has to be able to name
	 * that state.
	 *
	 * @param compiled how many modules the compiler built this load
	 */
	public static void say(long compiled) {
		long walked = WALKED.getAndSet(0L);
		long stripped = STRIPPED.getAndSet(0L);
		long names = NAMES.getAndSet(0L);
		if (ShaderDebugInfo.asked()) {
			Vitrail.logger().warn("Debug names LEFT IN every module, asked for by "
					+ "-Dvitrail.shaderDebugInfo ({} modules built this load, none walked): a pack "
					+ "identifier that collides with Metal's namespace refuses its pipeline on "
					+ "Apple hardware, which is what dropping them prevents", compiled);

			return;
		}

		Vitrail.logger().info("Debug names dropped off what the outside never asks for: {} names in "
						+ "{} of the {} pack modules walked ({} modules built this load in all, the "
						+ "rest served already stripped), which is the default. "
						+ "-Dvitrail.shaderDebugInfo would leave them in, and this line is said "
						+ "either way so that a reading can name the state it was taken under",
				names, stripped, walked, compiled);
	}
}
