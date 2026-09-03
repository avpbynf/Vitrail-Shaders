package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.VulkanBindGroupEntryType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Compacts {@code VulkanRenderPass.pushDescriptors} after the first push of a geometry program into
 * a pass, so pass-stable sampled names are not rebuilt from Java on every draw.
 * <p>
 * The game's graphics layout is one descriptor set. {@code VulkanRenderPipeline.compile} creates it
 * with a single {@code VkDescriptorSetLayout}, and the push always writes set 0. Extra
 * {@code BindGroupLayout}s on the Java pipeline flatten into that one set; they do not become a
 * second set the atlas could live in, and a raw {@code vkCreatePipelineLayout} with two sets would
 * leave Sodium's push-constant patch writing into a layout the game never compiled. So the walk
 * shrinks instead: uniforms still go out every time (a Distant Horizons section block and the
 * game's per-draw transforms move), and only the sampled names that follow the DRAW are written
 * again. Bindings that this step leaves out stay as the last push left them.
 * <p>
 * Substitutions in {@code VulkanRenderPassMixin} still run on every name that is written. A
 * pipeline this class has not prepared is walked in full, which is the leftover draws a hold
 * adopts: their names are not ours.
 */
public final class SettledDescriptors {

	private static RenderPipeline pipeline;

	/** Sampled names that follow the draw, or null to write every entry. */
	private static Set<String> moving;

	/** Applied after the first full push of a freshly settled program. */
	private static Set<String> pending;

	/**
	 * Set when a pipeline that is not the prepared one has been bound into the pass, which drops
	 * everything left standing until one full push has gone out again.
	 */
	private static boolean disturbed;

	private static List<?> cachedEntries;

	private static int[] mapped;

	private SettledDescriptors() {
	}

	/**
	 * Called from {@link GeometryProgram#bind} before any name is written into the pass.
	 *
	 * @param bound  the pipeline about to record, compared by identity at the push
	 * @param moving sampled names whose image follows the draw
	 * @param settle whether this is the first bind of this program into this pass
	 */
	static void prepare(RenderPipeline bound, Set<String> moving, boolean settle) {
		pipeline = bound;
		cachedEntries = null;
		mapped = null;
		if (settle || disturbed) {
			SettledDescriptors.moving = null;
			pending = moving;
			disturbed = false;
		} else {
			SettledDescriptors.moving = moving;
			pending = null;
		}
	}

	/**
	 * A pipeline has been bound into the pass. Binding one whose layout is not compatible with the
	 * layout the descriptors were pushed under discards them, so nothing this class left standing
	 * can be assumed to stand any more, and the next push of ours goes out at full width. The game
	 * answers the same fact its own way, marking every descriptor dirty after each
	 * {@code vkCmdBindPipeline} and pushing all of them again.
	 * <p>
	 * The passes a hold adopts are what makes this reachable every frame: the game's immediate
	 * draws and a distant-terrain renderer record into the pass a geometry program opened, and
	 * each of them binds a pipeline of its own between two of our draws.
	 */
	@SuppressWarnings("ReferenceEquality")
	public static void bound(RenderPipeline pipeline) {
		if (SettledDescriptors.pipeline != pipeline) {
			disturbed = true;
			moving = null;
			pending = null;
			cachedEntries = null;
			mapped = null;
		}
	}

	/** Drops the compacting, which {@link GeometryProgram#resolve} and a release both need. */
	static void clear() {
		pipeline = null;
		moving = null;
		pending = null;
		cachedEntries = null;
		mapped = null;
		disturbed = false;
	}

	/** After a push that really went out, so a settle's first walk is complete before the rest shrink. */
	@SuppressWarnings("ReferenceEquality")
	public static void afterPush(RenderPipeline bound) {
		if (pipeline != bound || pending == null) {
			return;
		}

		moving = pending;
		pending = null;
		cachedEntries = null;
		mapped = null;
	}

	/**
	 * The number of writes this push should allocate, which is every entry until this pipeline has
	 * been prepared and has something to leave standing.
	 */
	@SuppressWarnings("ReferenceEquality")
	public static int size(List<?> entries, int n, RenderPipeline bound) {
		if (!applies(bound)) {
			return n;
		}

		if (mapped == null || cachedEntries != entries) { // identity: the layout's list does not move
			mapped = scan(entries, n);
			cachedEntries = entries;
		}

		return mapped.length;
	}

	/** The original binding of the compacted write at {@code index}. */
	public static int index(int compacted, RenderPipeline bound) {
		if (!applies(bound) || mapped == null) {
			return compacted;
		}

		return mapped[compacted];
	}

	@SuppressWarnings("ReferenceEquality")
	private static boolean applies(RenderPipeline bound) {
		return pipeline != null && pipeline == bound && moving != null;
	}

	private static int[] scan(List<?> entries, int n) {
		int[] indices = new int[n];
		int written = 0;
		int binding = 0;
		for (Object one : entries) {
			if (binding >= n) {
				break;
			}

			if (keep(one)) {
				indices[written++] = binding;
			}

			binding++;
		}

		return Arrays.copyOf(indices, written);
	}

	private static boolean keep(Object one) {
		if (!(one instanceof VulkanBindGroupLayout.Entry entry)) {
			return true;
		}

		if (entry.type() != VulkanBindGroupEntryType.SAMPLED_IMAGE) {
			return true;
		}

		return moving.contains(entry.name());
	}
}
