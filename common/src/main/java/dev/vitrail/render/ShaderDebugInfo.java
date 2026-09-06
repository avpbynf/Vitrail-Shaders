package dev.vitrail.render;

import dev.vitrail.Vitrail;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether shaderc is asked to write debug information into every module it compiles.
 * <p>
 * The game asks for it in the compiler's constructor, once for the session. What it costs is in
 * every module: the whole GLSL text is carried in {@code OpSource} and an {@code OpLine} stands in
 * front of nearly every instruction, which on the corpus is a module two to three times the size it
 * needs to be. That size is paid again at every warm load, in bytes read off the store, in the
 * digest that answers for them, and in what the driver walks at {@code vkCreateShaderModule}.
 * <p>
 * <strong>What it buys is line information INSIDE the module</strong>, which is what a validation
 * layer or a driver message points at when it names a place in a shader. A compile error is not
 * that: shaderc's own message comes from the frontend that read the text and carries its line
 * whatever this is set to, so a pack whose GLSL will not compile is named as precisely either way.
 * <p>
 * <strong>It is off by default, and it cannot be scoped to a pack's own units.</strong> shaderc
 * has a call that turns the option on and none that turns it off again, so the only place to
 * decide is the constructor, and the compiler there is the one the game, Sodium and this engine
 * all compile through.
 * <p>
 * {@code -Dvitrail.shaderDebugInfo=true} asks for it back, and the two states keep two sets of
 * blobs: the switch changes the bytes a compile produces without changing the text it was handed,
 * so it goes into the module cache's key exactly as {@link RawLocals} does. Read once at start,
 * because the option it decides is set once at start.
 */
public final class ShaderDebugInfo {

	private static final boolean ASKED = Boolean.getBoolean("vitrail.shaderDebugInfo");

	/**
	 * Whether the line has been said. An atomic and not a plain field: a compiler is built per
	 * pack-load worker and the pool holds three, so the announcement below is reached from several
	 * threads at once, and two of them reading a plain false would each print it.
	 */
	private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

	private ShaderDebugInfo() {
	}

	/** Whether the compiler is to be told to write it. */
	public static boolean asked() {
		return ASKED;
	}

	/** The word the module cache's key carries for the state, since the state decides the bytes. */
	public static String cacheWord() {
		return ASKED ? "debug-info" : "no-debug-info";
	}

	/**
	 * Said once, at the compiler this decides, and said BOTH WAYS: a line that only appeared on one
	 * road would leave every reading taken on the other unable to name the jar it came from, and
	 * both roads come out of the same jar.
	 */
	public static void announce() {
		if (!ANNOUNCED.compareAndSet(false, true)) {
			return;
		}

		if (ASKED) {
			Vitrail.logger().warn("Modules compiled WITH debug info, asked for by "
					+ "vitrail.shaderDebugInfo: two to three times the bytes, and a place a "
					+ "validation layer can name inside a shader");

			return;
		}

		Vitrail.logger().info("Modules compiled without debug info, property="
				+ "vitrail.shaderDebugInfo");
	}
}
