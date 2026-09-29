package dev.vitrail.pack.texture;

import dev.vitrail.pack.model.BufferObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The storage buffers the loaded pack declared, keyed by the GLSL block name a program writes.
 * <p>
 * Complementary Ultra plus world-space reflections sizes {@code bufferObject.0} at hundreds of
 * megabytes and writes {@code layout(std430, binding = 0) buffer blockDataBuffer}. The Java bind
 * group has no storage-buffer arm, so the name is recorded here and the Vulkan mixins swap the
 * descriptor type once {@link dev.vitrail.render.storage.StorageBuffers} has allocated the bytes.
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

	/**
	 * Every answer {@link #indexOf} can give, rebuilt by whichever of {@link #install},
	 * {@link #clear} and {@link #declare} changed one of the two halves above.
	 * <p>
	 * A table rather than the walk it replaces because of who asks: {@code StorageBuffers.bound},
	 * once per descriptor of every pass of the game, Sodium's included, as soon as the loaded pack
	 * declares a storage buffer at all, and nearly every name it is handed is one of the game's own
	 * that the walk said no to one lambda at a time. Rebuilt here rather than beside the
	 * allocation, because the bindings go on being filed after the buffers exist: an on-demand
	 * family the pack-load worker is still translating hands its programs out after the first frame
	 * has allocated them.
	 */
	private static volatile Map<String, Integer> indices = Map.of();

	private CustomStorage() {
	}

	/** Records the live {@code bufferObject.N} lines of the pack about to be translated. */
	public static synchronized void install(BufferObject.Reading reading) {
		declared = reading;
		reindex();
	}

	/**
	 * Forgets the pack that was read before, both halves of it.
	 * <p>
	 * Called at the head of a load and not with the chain that is going. The two answers below sit
	 * on either side of one descriptor type decision, the bind group layout and the descriptor
	 * write, and a layout outlives a release in the pipeline cache. A road that releases a chain
	 * without replacing it would then draw a frame with the two disagreeing, and
	 * {@code PackChoice.load} carries the whole of why.
	 */
	public static synchronized void clear() {
		declared = BufferObject.Reading.empty();
		bindings.clear();
		reindex();
	}

	/**
	 * Records a storage block one of the pack's programs declares, with the
	 * {@code layout(..., binding = N)} it carried, or {@code -1} when the pack wrote none.
	 * <p>
	 * Called for every program handed out and not for every text read, so that a program restored
	 * from the translation store files its blocks exactly like one that was just translated.
	 */
	public static synchronized void declare(String name, int binding) {
		if (name == null || name.isEmpty()) {
			return;
		}

		// Most calls file again what an earlier program of the same pack filed, and those leave the
		// table as it stands.
		Integer filed = bindings.put(name, binding);
		if (filed == null || filed != binding) {
			reindex();
		}
	}

	/**
	 * Builds {@link #indices} over both halves, in the order the walk it replaces answered: a name
	 * a {@code bufferObject} line declares before a binding a program filed under the same name,
	 * and the first line declaring a name before any later one.
	 * <p>
	 * Called with the class held, so that two threads filing at once cannot publish their tables
	 * out of order: whichever builds last has seen what both of them filed.
	 */
	private static void reindex() {
		Map<String, Integer> declaredNames = new HashMap<>();
		for (BufferObject buffer : declared.buffers()) {
			buffer.name().ifPresent(name -> declaredNames.putIfAbsent(name, buffer.index()));
		}

		Map<String, Integer> table = new HashMap<>(bindings);
		table.putAll(declaredNames);
		indices = Map.copyOf(table);
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
		Integer index = indices.get(name);
		return index == null ? -1 : index;
	}
}
