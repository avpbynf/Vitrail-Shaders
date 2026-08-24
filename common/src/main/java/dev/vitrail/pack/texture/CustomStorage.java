package dev.vitrail.pack.texture;

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
	private static final Map<String, Integer> bindings = new ConcurrentHashMap<>();

	private CustomStorage() {
	}

	/** Records the live {@code bufferObject.N} lines of the pack about to be translated. */
	public static void install(BufferObject.Reading reading) {
		declared = reading;
	}

	public static void clear() {
		declared = BufferObject.Reading.empty();
		bindings.clear();
	}

	/**
	 * Records a storage block the translator just saw, with the {@code layout(..., binding = N)}
	 * it carried, or {@code -1} when the pack wrote none.
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
