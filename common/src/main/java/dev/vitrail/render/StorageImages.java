package dev.vitrail.render;

import dev.vitrail.mixin.access.CommandEncoderAccessor;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.mixin.access.VulkanCommandEncoderAccessor;
import dev.vitrail.pack.target.TargetFormat;
import dev.vitrail.pack.texture.ImageInformation;
import dev.vitrail.pack.texture.PackTexture;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The storage images a pack declared with {@code image.NAME}, allocated on the Vulkan device.
 * <p>
 * The Java texture facade has no storage bit and no three-dimensional texture, so these go through
 * VMA the way the compute probe did: {@code VulkanConst.textureUsageToVk} never sets
 * {@code VK_IMAGE_USAGE_STORAGE_BIT}. Mixins on the game's bind-group walk swap the 3D view in
 * for both {@code imageStore} names and the sampler hanging off the same directive.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris GlImage, LGPL-3.0</a>
 */
public final class StorageImages implements AutoCloseable {

	private static volatile StorageImages current = none();

	private final ImageInformation.Reading declared;
	private final List<Allocated> allocated = new ArrayList<>();

	/**
	 * Every name a descriptor push may ask for, resolved to the view it gets, rebuilt whenever
	 * {@link #allocated} changes. The push asks once per descriptor of every pass of the game,
	 * Sodium's included, so the answer is a map read and never a walk.
	 */
	private Map<String, Bound> bindings = Map.of();
	private int lastWidth;
	private int lastHeight;
	private boolean laidOut;

	/**
	 * Whether an image whose size does not follow the screen was refused. Another screen size is
	 * another question for the images that follow it and for the colour targets, and not for
	 * this one: asked again at every size the window passes through, it would fail at each with a
	 * full allocation and a full stack trace behind it.
	 */
	private boolean refusedForGood;

	StorageImages(ImageInformation.Reading declared) {
		this.declared = declared;
	}

	static StorageImages none() {
		return new StorageImages(ImageInformation.Reading.empty());
	}

	/**
	 * The image currently allocated for this name, whether the pack wrote it as the image uniform
	 * or as the sampler on the same {@code image.} line. Mixins look it up while pushing
	 * descriptors.
	 */
	public static Bound bound(String name) {
		return current.lookup(name);
	}

	public static boolean storageBinding(String name) {
		Bound bound = bound(name);
		return bound != null && bound.storage();
	}

	void install() {
		current = this;
	}

	private Bound lookup(String name) {
		return this.bindings.get(name);
	}

	/**
	 * The first image declaring a name answers for it, the image uniform before the sampler on
	 * the same line, which is the order the walk this replaces read them in.
	 */
	private void rebind() {
		Map<String, Bound> bound = new HashMap<>();
		for (Allocated image : this.allocated) {
			boolean integer = image.declared.internalFormat().used().integer();
			bound.putIfAbsent(image.declared.name(), new Bound(image.view, true, integer));
			image.declared.sampler().ifPresent(sampler ->
					bound.putIfAbsent(sampler, new Bound(image.view, false, integer)));
		}

		this.bindings = Map.copyOf(bound);
	}

	/**
	 * One bound view. {@code storage} is the image uniform ({@code voxel_img}); a sampler name
	 * hanging off the same directive is sampled, not stored.
	 */
	public record Bound(long view, boolean storage, boolean integer) {
	}

	/** Whether the last refusal was of an image no screen size can change, see {@link #ensure}. */
	boolean refusedForGood() {
		return this.refusedForGood;
	}

	/**
	 * Allocates every absolute image once, and rebuilds the relative ones when the screen moves.
	 * A failure is the whole set given back and the frame refused, see {@link #refused}.
	 */
	void ensure(int screenWidth, int screenHeight) {
		install();
		if (this.declared.images().isEmpty()) {
			return;
		}

		boolean first = this.allocated.isEmpty();
		boolean resized = screenWidth != this.lastWidth || screenHeight != this.lastHeight;
		if (!first && !resized) {
			return;
		}

		VulkanDevice vulkan = vulkan();
		if (vulkan == null) {
			return;
		}

		// Everything or nothing: a refusal halfway through gives back what was allocated, so the
		// next screen size the colour targets try again at starts from the first image rather
		// than skipping the one that failed as already dealt with.
		try {
			allocate(vulkan, first, resized, screenWidth, screenHeight);
		} catch (RuntimeException e) {
			this.allocated.forEach(image -> image.destroy(vulkan));
			this.allocated.clear();
			this.laidOut = false;
			rebind();
			throw e;
		}

		this.lastWidth = screenWidth;
		this.lastHeight = screenHeight;
		rebind();
		layoutIfNeeded();
	}

	private void allocate(VulkanDevice vulkan, boolean first, boolean resized, int screenWidth,
			int screenHeight) {
		if (first) {
			for (ImageInformation image : this.declared.images()) {
				if (image.relative()) {
					continue;
				}

				boolean movable = movable(image);
				try {
					this.allocated.add(Allocated.create(vulkan, image, image.width(), image.height(),
							Math.max(image.depth(), 1), movable));
					// Which volumes follow the camera and which do not, said once per pack rather
					// than left to be guessed from the picture: a volume left behind keeps a frame
					// of lag at every block crossed, and a volume that follows costs a second
					// image of its own size. Neither shows as itself on screen.
					Vitrail.logger().info("storage image {}{}", image.describe(), movable
							? ", moved onto each frame's camera block for " + scratchCost(image)
									+ " more"
							: image.clear() && image.depth() > 1
									? ", left on the frame that filled it"
									: "");
				} catch (RuntimeException e) {
					this.refusedForGood = true;
					throw refused(image, e);
				}
			}
		}

		if (first || resized) {
			List<Allocated> kept = new ArrayList<>();
			for (Allocated image : this.allocated) {
				if (image.relative) {
					image.destroy(vulkan);
					this.laidOut = false;
				} else {
					kept.add(image);
				}
			}

			this.allocated.clear();
			this.allocated.addAll(kept);
			for (ImageInformation image : this.declared.images()) {
				if (!image.relative()) {
					continue;
				}

				int width = Math.max(1, (int) (screenWidth * image.relativeWidth()));
				int height = Math.max(1, (int) (screenHeight * image.relativeHeight()));
				try {
					// Never movable: a relative image is a screen and not a volume, and it goes
					// back and is built again on every resize.
					this.allocated.add(Allocated.create(vulkan, image, width, height, 1, false));
					Vitrail.logger().info("storage image {} at {}x{}", image.describe(), width,
							height);
				} catch (RuntimeException e) {
					throw refused(image, e);
				}
			}
		}
	}

	/**
	 * An image the device would not give is the whole frame refused, and not a name skipped.
	 * <p>
	 * The bind group layout of every program naming the image was built off the pack's
	 * declaration, as a storage image, before anything was allocated; the descriptor write
	 * answers off the allocation. A name declared and not allocated would therefore be written
	 * as a sampled image under a layout that says storage, two types under one binding, which
	 * no driver has to survive and none reports. Thrown to the colour targets, which refuse the
	 * frame at this size the way they do for a colour target that could not be allocated.
	 */
	private static IllegalStateException refused(ImageInformation image, RuntimeException cause) {
		return new IllegalStateException("storage image " + image.describe()
				+ " could not be allocated, and a program declaring it cannot be drawn without it",
				cause);
	}

	/**
	 * Empties every image the pack asked to clear, which Complementary's voxel volume is. Called
	 * at the top of the shadow stage, before geometry writes, matching Iris clearing custom images
	 * before the shadow map is drawn.
	 */
	void clearMarked(CommandEncoder encoder) {
		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		if (commands == null) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			// The volume being emptied was sampled by the previous frame's gbuffers and stored by
			// the previous dispatch, and nothing else orders those against a transfer write.
			GpuRecording.beforeTransfer(commands, stack);
			for (Allocated image : this.allocated) {
				if (!image.declared.clear()) {
					continue;
				}

				clearImage(commands, stack, image);
			}

			GpuRecording.afterTransfer(commands, stack);
		}
	}

	/**
	 * What a second image of this one's shape costs, for the line that says a volume follows. In
	 * mebibytes where there is a whole one and in kibibytes below that: the smallest volume the
	 * corpus declares is half a mebibyte, and a line reading "0 MiB more" says the move is free.
	 */
	private static String scratchCost(ImageInformation image) {
		long texels = (long) Math.max(image.width(), 1) * Math.max(image.height(), 1)
				* Math.max(image.depth(), 1);
		long bytes = texels * image.internalFormat().used().bytesPerPixel();

		return bytes >= 1024L * 1024L
				? bytes / (1024L * 1024L) + " MiB"
				: bytes / 1024L + " KiB";
	}

	/**
	 * Whether a volume may be moved by a count of BLOCKS, which is the one thing this class has to
	 * settle before {@link #reanchor} may touch anything: a custom image is a grid whose scale the
	 * pack keeps to itself, and the engine sees only three extents.
	 * <p>
	 * The rule is a volume the pack CLEARS whose three extents match a volume it does NOT clear.
	 * What that buys: an uncleared volume survives frames, so it is the pack that carries it
	 * forward, and it does so by the blocks the camera crossed and nothing else,
	 * {@code pos - (floor(previousCameraPosition) - floor(cameraPosition))} in Complementary's
	 * {@code program/shadowcomp.glsl}. One texel a block, and a cleared volume declared at that same
	 * extent is the identity half of the same grid. Three packs of the corpus are built that way,
	 * each with its identity volume beside the light volumes it feeds: Complementary, BSL and Bliss,
	 * and all three anchor on the fractional part of the camera position plus half the volume, which
	 * only the first of them writes under the {@code cameraPositionBestFract} name.
	 * <p>
	 * <strong>Anything else is left where it is, and the counter-example is in the same pack.</strong>
	 * Complementary's coarse reflection volume is stored at a QUARTER of the voxel position
	 * ({@code lib/voxelization/reflectionVoxelization.glsl}), one cell per four blocks, and it is
	 * cleared each stage exactly like the identity volume. Moved by a block count it would land four
	 * cells out in the direction of travel, which is worse than the frame of lag it carries today,
	 * and nothing in an {@code image.} directive tells the two apart. It has no uncleared twin, so
	 * the rule excludes it.
	 * <p>
	 * <strong>The rule is sufficient and not necessary, and the log says so.</strong> The same
	 * pack's full-scale reflection volume IS one texel a block, and it is left behind at every
	 * setting but the smallest, being the only one where its height happens to equal the floodfill's.
	 * Widening the rule to catch it means guessing from two extents out of three, which is the guess
	 * that would have caught the quarter-scale volume as well.
	 */
	private boolean movable(ImageInformation image) {
		if (!image.clear() || image.relative() || image.depth() <= 1) {
			return false;
		}

		for (ImageInformation other : this.declared.images()) {
			if (!other.clear() && !other.relative() && other.width() == image.width()
					&& other.height() == image.height() && other.depth() == image.depth()) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Moves every volume {@link #movable} accepts onto the anchor of the frame about to read it, by
	 * the whole blocks the camera has crossed since it was filled.
	 * <p>
	 * <strong>What Iris does.</strong> It empties the custom images at the head of the level render
	 * ({@code pipeline/IrisRenderingPipeline.java:892}), draws the shadow geometry that stores block
	 * identities into them, and dispatches {@code shadowcomp} at the foot of that stage
	 * ({@code shadows/ShadowRenderer.java:632}). Three points of ONE frame, so writer and reader
	 * share a {@code cameraPosition}, and the volume Complementary indexes as
	 * {@code scenePos + cameraPositionBestFract + half} is anchored on the same block for both.
	 * <p>
	 * <strong>What prevents it here.</strong> This engine draws the shadow stage at the END of a
	 * frame for the next one ({@link dev.vitrail.sodium.ShadowTerrain}, and that placement is not a
	 * preference: Sodium's per region lists reset on the FIRST walk of a frame, so the light's walk
	 * has to follow the camera's rather than precede it). The identities are therefore written under
	 * the previous frame's anchor, and {@code shadowcomp} runs at the head of this one, where it has
	 * to run or the floodfill ping-pong lands on the half this frame's gbuffers do not read.
	 * <p>
	 * <strong>What it costs the image without this.</strong> The compute reads the floodfill at
	 * {@code pos - (floor(previousCameraPosition) - floor(cameraPosition))}, so the light it carries
	 * forward IS reprojected; the identities at {@code pos} are not. One frame per block crossed,
	 * every block is read as its neighbour, and the cost is not symmetric: crossing UPWARDS the
	 * reader takes each block for the one below it, so the layer of air just over the floor reads as
	 * {@code voxel == 1u}, which the pack answers with {@code light = 0}. The coloured light around
	 * the player collapses for that frame (a halo on a jump, continuous while climbing). Crossing
	 * DOWNWARDS the same error makes a solid block read as air, which merely lights a cell buried in
	 * the floor and shows nothing. That asymmetry is the signature the defect was reported under.
	 * <p>
	 * The {@code |d|} planes at the leading face keep what they held rather than being emptied. They
	 * sit at the far edge of the volume, they are rewritten by the shadow stage at the end of this
	 * same frame, and a stale identity there blocks light where an emptied one would leak it.
	 */
	void reanchor(CommandEncoder encoder, int dx, int dy, int dz) {
		if (dx == 0 && dy == 0 && dz == 0) {
			return;
		}

		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		if (commands == null) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			GpuRecording.beforeTransfer(commands, stack);
			for (Allocated image : this.allocated) {
				if (image.scratch == 0L) {
					continue;
				}

				// Past the extent nothing of the volume survives the move, which is a teleport
				// rather than a step. Emptied and not moved: the pack's own floodfill reprojection
				// is just as lost there, and identities from the world the player has left would
				// block light all over the one they arrived in.
				if (Math.abs(dx) >= image.width || Math.abs(dy) >= image.height
						|| Math.abs(dz) >= image.depth) {
					clearImage(commands, stack, image);
					continue;
				}

				move(commands, stack, image, dx, dy, dz);
			}

			GpuRecording.afterTransfer(commands, stack);
		}
	}

	/**
	 * The move itself, in two copies through the image's own scratch because Vulkan leaves a copy
	 * whose source and destination regions overlap undefined, and a shift of one plane overlaps
	 * everywhere.
	 * <p>
	 * Both copies carry the SAME region, the one the second reads, and the scratch keeps whatever an
	 * earlier move left outside it: no reader of any kind ever names the scratch, so a texel there
	 * that the second copy will not read is a texel the first one has no reason to write. That is
	 * {@code |d|} planes an axis, a plane at a walking pace and a large part of the volume at speed.
	 */
	private static void move(VkCommandBuffer commands, MemoryStack stack, Allocated image,
			int dx, int dy, int dz) {
		// The reader wants index p to hold what index p + d holds, d being the blocks the camera has
		// crossed since the write: so the source starts d planes in where the camera moved forward,
		// and the destination does where it moved back. Written once and used by both copies, which
		// is what makes the pair agree by construction rather than by two readings of the same
		// arithmetic.
		int fromX = Math.max(dx, 0);
		int fromY = Math.max(dy, 0);
		int fromZ = Math.max(dz, 0);
		int spanX = image.width - Math.abs(dx);
		int spanY = image.height - Math.abs(dy);
		int spanZ = image.depth - Math.abs(dz);

		VkImageCopy.Buffer kept = VkImageCopy.calloc(1, stack);
		VkImageCopy carried = kept.get(0);
		layers(carried);
		carried.srcOffset().set(fromX, fromY, fromZ);
		carried.dstOffset().set(fromX, fromY, fromZ);
		carried.extent().set(spanX, spanY, spanZ);
		VK12.vkCmdCopyImage(commands, image.image, VK12.VK_IMAGE_LAYOUT_GENERAL,
				image.scratch, VK12.VK_IMAGE_LAYOUT_GENERAL, kept);

		GpuRecording.betweenTransfers(commands, stack);

		VkImageCopy.Buffer shifted = VkImageCopy.calloc(1, stack);
		VkImageCopy region = shifted.get(0);
		layers(region);
		region.srcOffset().set(fromX, fromY, fromZ);
		region.dstOffset().set(Math.max(-dx, 0), Math.max(-dy, 0), Math.max(-dz, 0));
		region.extent().set(spanX, spanY, spanZ);
		VK12.vkCmdCopyImage(commands, image.scratch, VK12.VK_IMAGE_LAYOUT_GENERAL,
				image.image, VK12.VK_IMAGE_LAYOUT_GENERAL, shifted);
	}

	private static void layers(VkImageCopy region) {
		region.srcSubresource().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
		region.dstSubresource().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1);
	}

	private void layoutIfNeeded() {
		if (this.laidOut || this.allocated.isEmpty()) {
			return;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return;
		}

		CommandEncoder encoder = device.createCommandEncoder();
		GpuRecording.endPass(encoder);
		VkCommandBuffer commands = commands(encoder);
		if (commands == null) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (Allocated image : this.allocated) {
				// Only the images created since the last pass here. An UNDEFINED transition
				// DISCARDS contents, so re-running it over a surviving absolute volume on a
				// window resize would throw away the floodfill the frames carry forward.
				if (image.laidOut) {
					continue;
				}

				image.laidOut = true;
				// The scratch beside its image and in the same breath: it is a transfer end of the
				// same volume, so it has to leave UNDEFINED before the first copy names it, and a
				// copy is the only thing that ever will.
				int count = image.scratch == 0L ? 1 : 2;
				VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(count, stack)
						.sType$Default();
				for (int at = 0; at < count; at++) {
					VkImageMemoryBarrier barrier = barriers.get(at);
					barrier.sType$Default();
					barrier.oldLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
					barrier.newLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
					barrier.srcAccessMask(0);
					barrier.dstAccessMask(VK12.VK_ACCESS_SHADER_READ_BIT
							| VK12.VK_ACCESS_SHADER_WRITE_BIT
							| VK12.VK_ACCESS_TRANSFER_WRITE_BIT
							| VK12.VK_ACCESS_TRANSFER_READ_BIT);
					barrier.srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
					barrier.dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED);
					barrier.image(at == 0 ? image.image : image.scratch);
					barrier.subresourceRange().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
				}

				VK12.vkCmdPipelineBarrier(commands, VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
						VK12.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, barriers);
			}

			// Only the images the pack marked clear. Complementary's two floodfill volumes are
			// 500 MiB each at Ultra: zeroing them here with the 773 MiB SSBO fill on the same
			// command buffer is what killed the device two seconds later (Windows TDR). Voxel
			// is clear=true and is emptied each shadow frame by clearMarked. Floodfill is
			// written by shadowcomp on this same first dispatch.
		}

		this.laidOut = true;
	}

	private static void clearImage(VkCommandBuffer commands, MemoryStack stack, Allocated image) {
		VkClearColorValue colour = VkClearColorValue.calloc(stack);
		if (image.declared.internalFormat().used().integer()) {
			IntBuffer ints = colour.int32();
			ints.put(0, 0).put(1, 0).put(2, 0).put(3, 0);
		} else {
			colour.float32().put(0, 0.0F).put(1, 0.0F).put(2, 0.0F).put(3, 0.0F);
		}

		VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack);
		range.set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
		VK12.vkCmdClearColorImage(commands, image.image, VK12.VK_IMAGE_LAYOUT_GENERAL, colour, range);
	}

	private static VkCommandBuffer commands(CommandEncoder encoder) {
		return ((CommandEncoderAccessor) encoder).vitrail$backend() instanceof VulkanCommandEncoder vulkan
				? ((VulkanCommandEncoderAccessor) vulkan).vitrail$commandBuffer()
				: null;
	}

	int count() {
		return this.allocated.size();
	}

	@Override
	public void close() {
		if (current == this) {
			current = none();
		}

		VulkanDevice vulkan = vulkan();
		if (vulkan != null) {
			this.allocated.forEach(image -> image.destroy(vulkan));
		}

		this.allocated.clear();
		this.bindings = Map.of();
		this.lastWidth = 0;
		this.lastHeight = 0;
		this.laidOut = false;
	}

	private static VulkanDevice vulkan() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor) device).vitrail$backend();
		return backend instanceof VulkanDevice vulkan ? vulkan : null;
	}

	/**
	 * One VMA image. The view is what a storage binding and a sampler will both name, once those
	 * roads exist.
	 */
	private static final class Allocated {

		private final ImageInformation declared;
		private final boolean relative;
		private final int width;
		private final int height;
		private final int depth;
		private long image;
		private long allocation;
		private long view;

		/**
		 * A second image of the same shape, for the volumes {@link #reanchor} moves, and nought for
		 * every other. It carries no view: nothing samples it and nothing stores into it, it is one
		 * end of a copy and no more.
		 */
		private long scratch;
		private long scratchAllocation;

		/** Whether the one UNDEFINED-to-GENERAL transition of this image's life has been recorded. */
		private boolean laidOut;

		private Allocated(ImageInformation declared, boolean relative, int width, int height,
				int depth, long image, long allocation, long view) {
			this.declared = declared;
			this.relative = relative;
			this.width = width;
			this.height = height;
			this.depth = depth;
			this.image = image;
			this.allocation = allocation;
			this.view = view;
		}

		private static Allocated create(VulkanDevice vulkan, ImageInformation declared, int width,
				int height, int depth, boolean movable) {
			int vkFormat = vkFormat(declared.internalFormat().used());
			int type = imageType(declared.shape());
			int viewType = viewType(declared.shape());
			int extentWidth = Math.max(width, 1);
			int extentHeight = Math.max(height, 1);
			int extentDepth = Math.max(depth, 1);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default();
				imageInfo.imageType(type);
				imageInfo.extent().set(extentWidth, extentHeight, extentDepth);
				imageInfo.mipLevels(1);
				imageInfo.arrayLayers(1);
				imageInfo.format(vkFormat);
				imageInfo.tiling(VK12.VK_IMAGE_TILING_OPTIMAL);
				imageInfo.initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
				imageInfo.usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT
						| VK12.VK_IMAGE_USAGE_SAMPLED_BIT
						| VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
						| VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
				imageInfo.sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
				imageInfo.samples(VK12.VK_SAMPLE_COUNT_1_BIT);
				VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack);
				allocationInfo.usage(8);
				LongBuffer imagePtr = stack.callocLong(1);
				PointerBuffer allocationPtr = stack.callocPointer(1);
				VulkanUtils.crashIfFailure(vulkan,
						Vma.vmaCreateImage(vulkan.vma(), imageInfo, allocationInfo, imagePtr,
								allocationPtr, null),
						"storage image " + declared.name());
				long image = imagePtr.get(0);
				long allocation = allocationPtr.get(0);

				VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default();
				viewInfo.image(image);
				viewInfo.viewType(viewType);
				viewInfo.format(vkFormat);
				viewInfo.subresourceRange().set(VK12.VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1);
				LongBuffer viewPtr = stack.callocLong(1);
				try {
					VulkanUtils.crashIfFailure(vulkan,
							VK12.vkCreateImageView(vulkan.vkDevice(), viewInfo, null, viewPtr),
							"storage image view " + declared.name());
				} catch (RuntimeException e) {
					Vma.vmaDestroyImage(vulkan.vma(), image, allocation);
					throw e;
				}

				Allocated allocated = new Allocated(declared, declared.relative(), extentWidth,
						extentHeight, extentDepth, image, allocation, viewPtr.get(0));

				// The scratch only where the volume may be moved at all, which movable settles. It
				// is a second image of the same shape, so it is not owed to a volume nothing will
				// ever copy.
				//
				// A failure here is not the image's failure, and that is why it is caught rather
				// than raised. The volume itself is allocated and bound; with no scratch it simply
				// keeps the frame of lag it carried before, which is a worse picture. Raising here
				// would drop the volume outright and take the pack's coloured light with it.
				if (movable) {
					try {
						VulkanUtils.crashIfFailure(vulkan,
								Vma.vmaCreateImage(vulkan.vma(), imageInfo, allocationInfo, imagePtr,
										allocationPtr, null),
								"storage image scratch " + declared.name());
						allocated.scratch = imagePtr.get(0);
						allocated.scratchAllocation = allocationPtr.get(0);
					} catch (RuntimeException e) {
						Vitrail.logger().warn("storage image {} keeps a frame of lag, its scratch "
								+ "could not be allocated: {}", declared.name(), e.toString());
					}
				}

				return allocated;
			}
		}

		/**
		 * Frees the handles through the game's deferred queue, never inline: up to two frames are
		 * still in flight with descriptors naming this view, and freeing under them is the device
		 * loss a settings change to a bigger volume turned from latent into certain. Same rule as
		 * {@code StalePipelines}: destruction has no safe instant in a running session, only a
		 * deferred one.
		 */
		private void destroy(VulkanDevice vulkan) {
			long view = this.view;
			long image = this.image;
			long allocation = this.allocation;
			long scratch = this.scratch;
			long scratchAllocation = this.scratchAllocation;
			this.view = 0L;
			this.image = 0L;
			this.allocation = 0L;
			this.scratch = 0L;
			this.scratchAllocation = 0L;
			GpuRecording.destroyLater(() -> {
				if (view != 0L) {
					VK12.vkDestroyImageView(vulkan.vkDevice(), view, null);
				}

				if (image != 0L) {
					Vma.vmaDestroyImage(vulkan.vma(), image, allocation);
				}

				if (scratch != 0L) {
					Vma.vmaDestroyImage(vulkan.vma(), scratch, scratchAllocation);
				}
			});
		}
	}

	private static int imageType(PackTexture.Shape shape) {
		return switch (shape) {
			case TEXTURE_1D -> VK12.VK_IMAGE_TYPE_1D;
			case TEXTURE_3D -> VK12.VK_IMAGE_TYPE_3D;
			case TEXTURE_2D, TEXTURE_RECTANGLE -> VK12.VK_IMAGE_TYPE_2D;
		};
	}

	private static int viewType(PackTexture.Shape shape) {
		return switch (shape) {
			case TEXTURE_1D -> VK12.VK_IMAGE_VIEW_TYPE_1D;
			case TEXTURE_3D -> VK12.VK_IMAGE_VIEW_TYPE_3D;
			case TEXTURE_2D, TEXTURE_RECTANGLE -> VK12.VK_IMAGE_VIEW_TYPE_2D;
		};
	}

	private static int vkFormat(TargetFormat format) {
		return switch (format) {
			case R8_UNORM -> VK12.VK_FORMAT_R8_UNORM;
			case R8_SNORM -> VK12.VK_FORMAT_R8_SNORM;
			case RG8_UNORM -> VK12.VK_FORMAT_R8G8_UNORM;
			case RG8_SNORM -> VK12.VK_FORMAT_R8G8_SNORM;
			case RGBA8_UNORM -> VK12.VK_FORMAT_R8G8B8A8_UNORM;
			case RGBA8_SNORM -> VK12.VK_FORMAT_R8G8B8A8_SNORM;
			case R16_UNORM -> VK12.VK_FORMAT_R16_UNORM;
			case R16_SNORM -> VK12.VK_FORMAT_R16_SNORM;
			case RG16_UNORM -> VK12.VK_FORMAT_R16G16_UNORM;
			case RG16_SNORM -> VK12.VK_FORMAT_R16G16_SNORM;
			case RGBA16_UNORM -> VK12.VK_FORMAT_R16G16B16A16_UNORM;
			case RGBA16_SNORM -> VK12.VK_FORMAT_R16G16B16A16_SNORM;
			case R8_UINT -> VK12.VK_FORMAT_R8_UINT;
			case R8_SINT -> VK12.VK_FORMAT_R8_SINT;
			case RG8_UINT -> VK12.VK_FORMAT_R8G8_UINT;
			case RG8_SINT -> VK12.VK_FORMAT_R8G8_SINT;
			case RGBA8_UINT -> VK12.VK_FORMAT_R8G8B8A8_UINT;
			case RGBA8_SINT -> VK12.VK_FORMAT_R8G8B8A8_SINT;
			case R16_UINT -> VK12.VK_FORMAT_R16_UINT;
			case R16_SINT -> VK12.VK_FORMAT_R16_SINT;
			case RG16_UINT -> VK12.VK_FORMAT_R16G16_UINT;
			case RG16_SINT -> VK12.VK_FORMAT_R16G16_SINT;
			case RGBA16_UINT -> VK12.VK_FORMAT_R16G16B16A16_UINT;
			case RGBA16_SINT -> VK12.VK_FORMAT_R16G16B16A16_SINT;
			case R32_UINT -> VK12.VK_FORMAT_R32_UINT;
			case R32_SINT -> VK12.VK_FORMAT_R32_SINT;
			case RG32_UINT -> VK12.VK_FORMAT_R32G32_UINT;
			case RG32_SINT -> VK12.VK_FORMAT_R32G32_SINT;
			case RGBA32_UINT -> VK12.VK_FORMAT_R32G32B32A32_UINT;
			case RGBA32_SINT -> VK12.VK_FORMAT_R32G32B32A32_SINT;
			case R16_FLOAT -> VK12.VK_FORMAT_R16_SFLOAT;
			case RG16_FLOAT -> VK12.VK_FORMAT_R16G16_SFLOAT;
			case RGBA16_FLOAT -> VK12.VK_FORMAT_R16G16B16A16_SFLOAT;
			case R32_FLOAT -> VK12.VK_FORMAT_R32_SFLOAT;
			case RG32_FLOAT -> VK12.VK_FORMAT_R32G32_SFLOAT;
			case RGBA32_FLOAT -> VK12.VK_FORMAT_R32G32B32A32_SFLOAT;
			case RGB10A2_UNORM -> VK12.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
			case RGB10A2_UINT -> VK12.VK_FORMAT_A2B10G10R10_UINT_PACK32;
			case RG11B10_FLOAT -> VK12.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
		};
	}
}
