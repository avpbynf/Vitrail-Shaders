package dev.vitrail.render;

import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;

/**
 * The bind group entry a descriptor push is writing, with what the pack's storage answers for
 * its name, resolved once per entry and read by every field of the descriptor.
 * <p>
 * A push writes the view, the sampler, the buffer, its range and the descriptor type through as
 * many wrapped calls, and each of them used to ask {@link StorageImages} and
 * {@link StorageBuffers} for the entry's name again: five lookups per descriptor of every pass of
 * the game and of Sodium, with or without a pack loaded. The entry is resolved where it is read
 * off the layout, and the fields read the answer.
 * <p>
 * Held per thread rather than in a static, as the entry was before it: a push recorded off the
 * render thread cannot read another push's entry, whatever the backend does later.
 */
public final class PushedDescriptor {

	private static final ThreadLocal<PushedDescriptor> CURRENT =
			ThreadLocal.withInitial(PushedDescriptor::new);

	private VulkanBindGroupLayout.Entry entry;
	private StorageImages.Bound image;
	private StorageBuffers.Bound buffer;

	private PushedDescriptor() {
	}

	/** The entry the push is about to write, and the pack's answers for its name. */
	public static void begin(VulkanBindGroupLayout.Entry entry) {
		PushedDescriptor current = CURRENT.get();
		current.entry = entry;
		current.image = StorageImages.bound(entry.name());
		current.buffer = StorageBuffers.bound(entry.name());
	}

	public static PushedDescriptor current() {
		return CURRENT.get();
	}

	/** The entry being written, or null before the first entry of the thread. */
	public VulkanBindGroupLayout.Entry entry() {
		return this.entry;
	}

	/** The pack image under the entry's name, or null where the pack declares none. */
	public StorageImages.Bound image() {
		return this.image;
	}

	/** The pack buffer under the entry's name, or null where the pack declares none. */
	public StorageBuffers.Bound buffer() {
		return this.buffer;
	}
}
