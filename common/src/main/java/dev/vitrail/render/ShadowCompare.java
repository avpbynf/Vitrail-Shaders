package dev.vitrail.render;

import dev.vitrail.glsl.GlslTranslator;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The comparison sampler a {@code sampler2DShadow} lookup runs on, and which pipelines owe it to
 * which names.
 * <p>
 * The translation leaves a comparison sampler its spelling wherever the map's own names are behind
 * it, so the lookup compiles to a depth-reference sample; what the hardware then needs is a sampler
 * with the comparison enabled, and {@code GpuSampler} cannot describe one. So the sampler is made
 * here, once, in Vulkan's own terms, and {@code VulkanRenderPassMixin} puts its handle into the
 * descriptor wherever the pipeline being drawn declared the name a comparison. That substitution
 * road already exists for the storage images, and this rides it rather than growing a second one.
 * <p>
 * The pair it carries is the pair Iris binds when a pack asks for its hardware shadow filtering
 * ({@code ShadowRenderTargets.getSamplerFor}, under {@code shadowHardwareFiltering}), {@code
 * GL_LINEAR} plus {@code GL_COMPARE_REF_TO_TEXTURE}; every pack of the corpus that declares the
 * type writes that directive, and without it Iris leaves what such a declaration reads undefined.
 * The sense is LEQUAL: OptiFine sets that on a shadow texture, so it is what every pack is written
 * against, and the map stores the forward window where nearer is smaller. Filtered, the hardware
 * compares each of the four texels and blends the RESULTS with the bilinear weights, which is
 * exactly the arithmetic the translation writes on its other road; the two roads answer the same
 * fraction. The level of detail is pinned to the base: nothing here ever fills a chain on the
 * shadow map, where Iris can mip it under {@code shadowtexMipmap}, and that gap is the map's and
 * not this sampler's.
 * <p>
 * The registry is weak on the pipeline, because that is the lifetime being described: a pipeline
 * dropped on a pack change takes its entry with it, and a reload registers the new ones as they
 * are built.
 */
public final class ShadowCompare {

	private static final String ARM_FILE = "soft-shadow-compare";

	private static final Map<RenderPipeline, Set<String>> COMPARED =
			Collections.synchronizedMap(new WeakHashMap<>());

	/** Whether anything is filed at all, so the walk over every descriptor asks one flag first. */
	private static volatile boolean noted;

	private static boolean announced;

	private static long sampler;

	private ShadowCompare() {
	}

	/**
	 * Puts the translation on the arithmetic road when somebody asked for it, and back off it when
	 * they stopped asking: a file {@code vitrail/soft-shadow-compare} in the game directory, or
	 * {@code -Dvitrail.softShadowCompare=true}. Called before a pack is read, every time one is,
	 * so removing the file and reloading undoes it without a restart. The trade cannot be watched
	 * from inside, a comparison bound wrong handing back a credible fraction rather than an error,
	 * so an image that comes right with this on has named the comparison sampler in one launch,
	 * the same bargain the pass barrier's file makes.
	 */
	public static void armIfAsked() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameDirectory == null) {
			return;
		}

		boolean asked = Files.isRegularFile(minecraft.gameDirectory.toPath()
				.resolve("vitrail").resolve(ARM_FILE));
		GlslTranslator.askSoftCompare(asked);
		if (asked && !announced) {
			announced = true;
			Vitrail.logger().warn("Every shadow comparison is made in shader arithmetic, asked for "
					+ "by vitrail/{}. The image should not move; the shadow lookups are slower. "
					+ "Remove it and reload the pack to put the comparison back on the sampler",
					ARM_FILE);
		}
	}

	/**
	 * Files which sampler names this pipeline reads through a comparison, which is the question the
	 * descriptor substitution asks back at every push. Nothing is filed for a program with none,
	 * which is what keeps the common case behind {@link #noted()}.
	 * <p>
	 * The sampler follows the declaration, wherever that leads, because the shader's side is
	 * already settled: the lookup compiles to a depth-reference sample, and a comparison sampler
	 * against a wrong image and no comparison sampler under a depth-reference sample are both
	 * undefined, so withholding it would repair nothing. What this can add is the word nothing on
	 * screen would say: a pack that has put something that is not the shadow map behind a compared
	 * name, or that spells one name two ways across the stages of one program, is named here. The
	 * sampler serves the whole pipeline, so the stage that spelled the name ordinary reads through
	 * the comparison all the same, undefined exactly as under Iris, where the sampler sits on the
	 * texture unit both stages share.
	 */
	static void note(RenderPipeline pipeline, String path, PackProgram.Loaded loaded) {
		Set<String> names = new LinkedHashSet<>();
		for (TranslatedUnit unit : loaded.program().stages().values()) {
			names.addAll(unit.notes().hardwareCompared());
		}

		if (names.isEmpty()) {
			return;
		}

		for (String name : names) {
			if (loaded.samplers().binding(name).kind() != SamplerPlan.Kind.SHADOW_DEPTH) {
				Vitrail.logger().warn("{} declares {} as a comparison sampler, and the pack has "
						+ "put something that is not the shadow map behind the name: the "
						+ "comparison runs against it all the same, which is undefined here as "
						+ "it is under Iris", path, name);
			}
		}

		for (TranslatedUnit unit : loaded.program().stages().values()) {
			for (String name : names) {
				if (!unit.notes().hardwareCompared().contains(name)
						&& unit.samplers().stream().anyMatch(one -> one.name().equals(name))) {
					Vitrail.logger().warn("{} declares {} as a comparison sampler in one stage and "
							+ "an ordinary one in another. The sampler is the pipeline's, so the "
							+ "ordinary read goes through the comparison too, which is undefined "
							+ "here as it is under Iris", path, name);
				}
			}
		}

		COMPARED.put(pipeline, Set.copyOf(names));
		noted = true;
	}

	/**
	 * Files a rebuilt variant beside the pipeline it was rebuilt from. A reshape swaps the vertex
	 * layout and nothing a comparison depends on, so the names are the base's, shared rather than
	 * copied; nothing is filed where the base filed nothing. The warnings stay with the base's
	 * filing: they speak of the pack's text, which the variant has not changed.
	 */
	static void noteBeside(RenderPipeline variant, RenderPipeline base) {
		Set<String> names = COMPARED.get(base);
		if (names != null) {
			COMPARED.put(variant, names);
		}
	}

	/** Whether any pipeline has filed anything, asked before the per-name question is worth asking. */
	public static boolean noted() {
		return noted;
	}

	/** Whether this pipeline reads this name through a comparison, asked while pushing descriptors. */
	public static boolean compared(RenderPipeline pipeline, String name) {
		Set<String> names = COMPARED.get(pipeline);

		return names != null && names.contains(name);
	}

	/**
	 * The comparison sampler itself, made on the first ask and kept for the device's life. Must run
	 * on the render thread, which pushing descriptors always is.
	 */
	public static long sampler(VulkanDevice device) {
		if (sampler != 0L) {
			return sampler;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
					.sType$Default()
					.magFilter(VK12.VK_FILTER_LINEAR)
					.minFilter(VK12.VK_FILTER_LINEAR)
					.mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.compareEnable(true)
					.compareOp(VK12.VK_COMPARE_OP_LESS_OR_EQUAL)
					// Level nought and no further, which is what a lookup's level of detail came
					// to on the other road as well: nothing ever fills a chain on the shadow map.
					.minLod(0.0F)
					.maxLod(0.0F);
			LongBuffer handle = stack.mallocLong(1);
			int result = VK12.vkCreateSampler(device.vkDevice(), info, null, handle);
			if (result != VK12.VK_SUCCESS) {
				throw new IllegalStateException("vkCreateSampler answered " + result);
			}

			sampler = handle.get(0);
		}

		return sampler;
	}

	/** Called when the client shuts down, while the device is still alive. */
	static void close() {
		COMPARED.clear();
		noted = false;
		if (sampler == 0L) {
			return;
		}

		VulkanDevice device = vulkan();
		if (device != null) {
			VK12.vkDestroySampler(device.vkDevice(), sampler, null);
		}

		sampler = 0L;
	}

	private static VulkanDevice vulkan() {
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}

		GpuDeviceBackend backend = ((GpuDeviceAccessor) device).vitrail$backend();
		return backend instanceof VulkanDevice vulkan ? vulkan : null;
	}
}
