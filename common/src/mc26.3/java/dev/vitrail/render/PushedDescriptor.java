package dev.vitrail.render;

import dev.vitrail.render.storage.StorageBuffers;
import dev.vitrail.render.storage.StorageImages;

import org.jspecify.annotations.Nullable;

/**
 * The descriptor the game's pass is writing while it pushes a draw's set, resolved once per entry:
 * its name, and the storage image or buffer this engine serves under that name, if any.
 * <p>
 * The 26.3 half. On 26.2 the entry was the game's {@code VulkanBindGroupLayout.Entry}; here it is
 * the name of the uniform the pipeline declares, which is what every question asked of it reads.
 * Per thread for the reason the 26.2 half gives: a push runs to its end on the thread that began
 * it, and nothing else may see its entry.
 */
public final class PushedDescriptor {

	private static final ThreadLocal<PushedDescriptor> CURRENT =
			ThreadLocal.withInitial(PushedDescriptor::new);

	private @Nullable String name;
	private StorageImages.@Nullable Bound image;
	private StorageBuffers.@Nullable Bound buffer;

	private PushedDescriptor() {
	}

	/** Starts the entry named {@code name}. */
	public static void begin(String name) {
		PushedDescriptor current = CURRENT.get();
		current.name = name;
		current.image = StorageImages.bound(name);
		current.buffer = StorageBuffers.bound(name);
	}

	/** The entry the calling thread is writing. */
	public static PushedDescriptor current() {
		return CURRENT.get();
	}

	/** The uniform's name, or null before the first entry. */
	public @Nullable String name() {
		return this.name;
	}

	/** The storage image served under the name, or null. */
	public StorageImages.@Nullable Bound image() {
		return this.image;
	}

	/** The storage buffer served under the name, or null. */
	public StorageBuffers.@Nullable Bound buffer() {
		return this.buffer;
	}
}
