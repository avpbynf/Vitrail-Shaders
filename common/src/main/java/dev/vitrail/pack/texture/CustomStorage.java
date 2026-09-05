package dev.vitrail.pack.texture;

import dev.vitrail.pack.model.BufferObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The storage buffers the loaded pack declared, keyed by the GLSL block name a program writes.
 * <p>
 * Complementary Ultra plus world-space reflections sizes {@code bufferObject.0} at hundreds of
 * megabytes and writes {@code layout(std430, binding = 0) buffer blockDataBuffer}. The Java bind
 * group has no storage-buffer arm, so the name is recorded here and the Vulkan mixins swap the
 * descriptor type once {@link dev.vitrail.render.StorageBuffers} has allocated the bytes.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris ShaderStorageBuffer, LGPL-3.0</a>
 */
public final class CustomStorage {

	private static volatile BufferObject.Reading declared = BufferObject.Reading.empty();

	/**
	 * The block names the pack's own programs declare, which is the one half of this class that does
	 * not replace itself. {@link #install} hands the whole of {@link #declared} over at each load,
	 * while this is filled a name at a time and would otherwise carry every name of every pack read
	 * this session. Emptied by {@link #clear} at the head of a load, and the answers below say what
	 * that buys.
	 */
	private static final Map<String, Integer> bindings = new ConcurrentHashMap<>();

	private CustomStorage() {
	}

	/** Records the live {@code bufferObject.N} lines of the pack about to be translated. */
	public static void install(BufferObject.Reading reading) {
		declared = reading;
	}

	/**
	 * Forgets the pack that was read before, both halves of it.
	 * <p>
	 * Called at the head of a load and not with the chain that is going. The two answers below sit
	 * on either side of one descriptor type decision, the bind group layout and the descriptor
	 * write, and a layout outlives a release in the pipeline cache. A road that releases a chain
	 * without replacing it would then draw a frame with the two disagreeing, and
	 * {@code PackChain.load} carries the whole of why.
	 */
	public static void clear() {
		declared = BufferObject.Reading.empty();
		bindings.clear();
	}

	/**
	 * Records a storage block one of the pack's programs declares, with the
	 * {@code layout(..., binding = N)} it carried, or {@code -1} when the pack wrote none.
	 * <p>
	 * Called for every program handed out and not for every text read, so that a program restored
	 * from the translation store files its blocks exactly like one that was just translated.
	 */
	public static void declare(String name, int binding) {
		if (name == null || name.isEmpty()) {
			return;
		}

		bindings.put(name, binding);
	}

	/**
	 * Whether this engine has a {@code bufferObject} for the name a program declared. True is what
	 * keeps the program in the chain instead of refusing it for a block nothing would bind.
	 */
	public static boolean named(String name) {
		if (declared.hasName(name)) {
			return true;
		}

		Integer index = bindings.get(name);
		return index != null && index >= 0 && declared.hasIndex(index);
	}

	public static BufferObject.Reading reading() {
		return declared;
	}

	/** The {@code bufferObject} index this GLSL name maps to, or {@code -1}. */
	public static int indexOf(String name) {
		for (BufferObject buffer : declared.buffers()) {
			if (buffer.name().filter(name::equals).isPresent()) {
				return buffer.index();
			}
		}

		Integer index = bindings.get(name);
		return index == null ? -1 : index;
	}
}
