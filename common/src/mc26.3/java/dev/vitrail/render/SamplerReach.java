package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.renderpearl.backend.api.SpvModule;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps out of a module's reflected descriptors every sampled image its entry point never touches,
 * so the layout built from them carries a binding for the samplers the shader really reads and for
 * no others.
 * <p>
 * The 26.3 half, and the why is the 26.2 half's: a pack's shared include declares every sampler any
 * of its programs might want, shaderc keeps the declarations nothing samples, and a layout carrying
 * all of them numbers a stage past Metal's sixteen sampler slots, which then needs an allocated set
 * and an argument buffer for a shader that reads far fewer. On 26.3 the list is the one
 * {@code SPIRVModule} reflects on demand, and it is the whole of the layout: {@code PipelineBuilder}
 * builds the pipeline's uniforms from the descriptors every stage reflects and nothing else. So the
 * reach is read off the SPIR-V as the module is made, kept on the module, and the sampled images it
 * leaves out are dropped from that reflection when it is first asked for. Without it, the Khronos
 * validation layer refuses every wide pipeline layout on Apple hardware
 * ({@code VUID-VkPipelineLayoutCreateInfo-descriptorType-03016}), where 26.2 draws the same pack
 * with none.
 * <p>
 * Only this engine's own compiles, only sampled images, and {@code -Dvitrail.declaredSamplers=true}
 * puts the whole declared list back, as on 26.2. A name dropped here is still bound at draw time,
 * which costs nothing: a pass looks a bound name up in the pipeline's own uniforms and skips one the
 * pipeline does not carry.
 */
public final class SamplerReach {

	/** {@code SPVC_RESOURCE_TYPE_SAMPLED_IMAGE}, the type the game's own sampler list comes from. */
	private static final int SAMPLED_IMAGE = Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE;

	private static final boolean DECLARED = Boolean.getBoolean("vitrail.declaredSamplers");

	private static final AtomicLong WALKED = new AtomicLong();
	private static final AtomicLong NARROWED = new AtomicLong();
	private static final AtomicLong DROPPED = new AtomicLong();

	private SamplerReach() {
	}

	/** A module this engine can hand the names its reflection is to leave out. */
	public interface Narrowable {

		/** The sampled images the reflection of this module leaves out, set once as it is made. */
		void vitrail$unreached(Set<String> unreached);
	}

	/** The word a module store's key would carry, since the state decides the tables it stores. */
	public static String cacheWord() {
		return DECLARED ? "declared-samplers" : "reached-samplers";
	}

	/**
	 * Reads what the module about to be made reaches, and hands it the names to leave out.
	 *
	 * @param filename the debug name the compile was given, which says whose module it is
	 * @param spirv    the words the module is made of, read before the module owns them
	 * @param module   the module just made from them
	 */
	public static void narrow(String filename, ByteBuffer spirv, Object module) {
		if (DECLARED || !(module instanceof Narrowable narrowable) || !RawLocals.ours(filename)) {
			return;
		}

		WALKED.incrementAndGet();
		Set<String> unreached = unreached(spirv);
		if (!unreached.isEmpty()) {
			narrowable.vitrail$unreached(unreached);
		}
	}

	/**
	 * The descriptors of one type a module's reflection keeps: all of them but the sampled images
	 * the module was handed as unreached.
	 */
	public static List<SpvModule.Reflection.Descriptor> keep(
			List<SpvModule.Reflection.Descriptor> reflected, int type, Set<String> unreached) {
		if (type != SAMPLED_IMAGE || unreached.isEmpty()) {
			return reflected;
		}

		List<SpvModule.Reflection.Descriptor> kept = new ArrayList<>(reflected.size());
		for (SpvModule.Reflection.Descriptor descriptor : reflected) {
			if (!unreached.contains(descriptor.name())) {
				kept.add(descriptor);
			}
		}

		int gone = reflected.size() - kept.size();
		if (gone > 0) {
			NARROWED.incrementAndGet();
			DROPPED.addAndGet(gone);
		}

		return kept;
	}

	/**
	 * One line beside the module cache's, said in BOTH states: a load served whole from the store
	 * was built under the state its blobs carry, and a reading taken on it has to be able to name
	 * that state.
	 *
	 * @param compiled how many modules the compiler built this load
	 */
	public static void say(long compiled) {
		long walked = WALKED.getAndSet(0L);
		long narrowed = NARROWED.getAndSet(0L);
		long dropped = DROPPED.getAndSet(0L);
		if (DECLARED) {
			Vitrail.logger().warn("Every DECLARED sampler given a binding, asked for by "
					+ "-Dvitrail.declaredSamplers ({} modules built this load, none walked): a pack "
					+ "whose shared include declares more samplers than a stage reads is numbered "
					+ "past Metal's sixteen slots, which on Apple hardware takes an allocated set or is "
					+ "refused",
					compiled);

			return;
		}

		Vitrail.logger().info("Samplers bound from what a module reaches: {} dropped across {} of "
						+ "the {} pack modules walked ({} modules built this load in all, the "
						+ "game's and Sodium's among them)", dropped, narrowed, walked, compiled);
	}

	/**
	 * The sampled images the module declares and never reaches.
	 * <p>
	 * Two readings of one module through one compiler: the resource list the game itself asks for,
	 * then the same list restricted to the entry point's active interface variables. The second is
	 * the module's static reach, which is what Vulkan means by a descriptor a shader uses and what
	 * SPIRV-Cross carries into the shader it writes.
	 * <p>
	 * A failure at any step returns nothing to drop, so a module this cannot read keeps the layout
	 * it would have had, which is the layout of every build before this one. Silently, and on
	 * purpose: there is nothing to say about a module the reflection would not read twice that the
	 * refusal it may earn on Apple will not say better.
	 */
	private static Set<String> unreached(ByteBuffer spirv) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer pointer = stack.callocPointer(1);
			if (Spvc.spvc_context_create(pointer) != 0) {
				return Set.of();
			}

			long context = pointer.get(0);
			try {
				if (Spvc.spvc_context_parse_spirv(context, spirv.asIntBuffer(),
						spirv.remaining() / 4, pointer) != 0) {
					return Set.of();
				}

				long ir = pointer.get(0);
				if (Spvc.spvc_context_create_compiler(context, 0, ir, 1, pointer) != 0) {
					return Set.of();
				}

				long compiler = pointer.get(0);
				if (Spvc.spvc_compiler_create_shader_resources(compiler, pointer) != 0) {
					return Set.of();
				}

				List<String> declared = sampledImages(stack, pointer.get(0));
				if (declared.isEmpty()) {
					return Set.of();
				}

				if (Spvc.spvc_compiler_get_active_interface_variables(compiler, pointer) != 0) {
					return Set.of();
				}

				long active = pointer.get(0);
				if (Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler,
						pointer, active) != 0) {
					return Set.of();
				}

				Set<String> unreached = new HashSet<>(declared);
				unreached.removeAll(sampledImages(stack, pointer.get(0)));

				return unreached;
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
	}

	private static List<String> sampledImages(MemoryStack stack, long resources) {
		PointerBuffer list = stack.callocPointer(1);
		PointerBuffer count = stack.callocPointer(1);
		if (Spvc.spvc_resources_get_resource_list_for_type(resources, SAMPLED_IMAGE, list,
				count) != 0) {
			return List.of();
		}

		int found = (int) count.get(0);
		if (found == 0) {
			return List.of();
		}

		List<String> names = new ArrayList<>(found);
		SpvcReflectedResource.Buffer reflected = SpvcReflectedResource.create(list.get(0), found);
		for (int index = 0; index < found; index++) {
			// The same string the game's own reflection puts on the record, taken from the same
			// call, so the two spellings of one resource cannot differ.
			names.add(reflected.get(index).nameString());
		}

		return names;
	}
}
