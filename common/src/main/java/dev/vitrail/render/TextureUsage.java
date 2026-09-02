package dev.vitrail.render;

/**
 * Whether the texture about to be created has to be writable from a compute shader.
 * <p>
 * The game's texture facade has no such usage: {@code VulkanConst.textureUsageToVk} maps the four
 * bits {@code GpuTexture} knows and never sets {@code VK_IMAGE_USAGE_STORAGE_BIT}, and a usage bit
 * the facade does not know would be dropped on the same road. So a colour target a pack's compute
 * writes as {@code colorimgN} is asked for here, in the instant between deciding to allocate it
 * and the call that does, and the mixin on that conversion reads the flag and adds the bit. The
 * flag is held per thread, so the one creation that raised it is the only one that reads it, and
 * a texture another thread creates in that same instant stays what it asked for. Iris allocates
 * every render target with GL, where a texture is a storage image the moment
 * {@code glBindImageTexture} says so.
 */
public final class TextureUsage {

	private static final ThreadLocal<Boolean> STORAGE_REQUESTED = ThreadLocal.withInitial(() -> false);

	private TextureUsage() {
	}

	/** Read by the mixin on {@code VulkanConst.textureUsageToVk}, on the creating thread. */
	public static boolean storageRequested() {
		return STORAGE_REQUESTED.get();
	}

	/** Raised for one creation and lowered right after it, by the surface that asked. */
	static void requestStorage(boolean requested) {
		STORAGE_REQUESTED.set(requested);
	}
}
