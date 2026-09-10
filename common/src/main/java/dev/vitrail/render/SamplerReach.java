package dev.vitrail.render;

import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import dev.vitrail.Vitrail;
import dev.vitrail.mixin.access.IntermediaryShaderModuleAccessor;
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
 * Keeps out of a module's reflected sampler list every sampled image its entry point never
 * touches, so the layout built from that list carries a binding for the samplers the shader
 * really reads and for no others.
 * <p>
 * <strong>What this is for.</strong> The bind group layout of a pipeline is built by the game out
 * of the modules themselves: {@code GlslCompiler.compile} walks the vertex stage's samplers, then
 * the fragment stage's, and every name it has not already seen becomes an entry. The entry's INDEX
 * in that list is the Vulkan binding the module is then rebound onto. On desktop drivers a binding
 * number is a name and nothing more, and the numbering can be as sparse as it likes. On Apple it is
 * not: MoltenVK hands each descriptor of a set a Metal slot counted off its place among the
 * descriptors of its own kind, and Metal has sixteen sampler slots. So a stage that reads thirteen
 * samplers out of a layout carrying forty-eight is given indices running past sixteen and the
 * pipeline is refused, for a shader wanting three slots fewer than the hardware has.
 * <p>
 * <strong>Why the list held more than the shader reads.</strong> A pack's programs are a handful of
 * files over a shared include, and that include declares every sampler any of them might want. The
 * reflection the game asks SPIRV-Cross for is {@code spvc_compiler_create_shader_resources}, which
 * lists the resources of the MODULE, and shaderc is run at optimisation level nought, so a
 * declaration nothing samples survives into the SPIR-V and into that list. Measured over the corpus
 * the gap is wide: the worst module declares forty-eight sampled images and reaches seventeen.
 * <p>
 * <strong>Why the reached set is exact and the text is not.</strong> The one other place the
 * question could be asked is the translated GLSL, and the answer there is not safe: this engine
 * leaves every {@code #if} standing for the game's compiler to evaluate
 * ({@code glsl/GlslTranslator.java:87-92}), so the text carries the declarations and the reads of
 * branches that will be dropped, and a name can only be called unused when it appears nowhere at
 * all. That criterion over-keeps by about a factor of three. The module is past the preprocessor
 * and past the dead branches, and what it says is what the driver will see.
 * <p>
 * <strong>What a dropped sampler leaves behind, said in full because it looks worse than it is.</strong>
 * Its {@code OpVariable} stays in the module holding whatever binding shaderc assigned it, since
 * {@code rebind} only rewrites the names the entry list carries. So the module ends up with two
 * variables ALIASED on one binding whenever that stale number lands on a live entry's index, which
 * over a dense layout it usually does. What makes that harmless is not the numbering, it is that
 * Vulkan asks a pipeline layout to cover the descriptors a shader STATICALLY USES and nothing here
 * uses the dropped one: SPIRV-Cross's active set is that same static reach, and both aliases are
 * sampled images, so nothing is read through the wrong type either. It does not reach Apple's
 * compiler at all, that backend writing only the active set into the Metal source, which is read
 * off the MSL a refusal quoted rather than deduced: the stage Metal turned down named thirteen
 * samplers in {@code main0} out of the forty-eight its module declares. And the aliasing is not
 * argued from the specification alone: a launch under the Khronos validation layer reports the same
 * rules broken as the build before this one and not one rule more, none of them about a descriptor.
 * <p>
 * This engine also goes on binding every DECLARED name at draw time, and that is left alone
 * deliberately. It costs nothing: the descriptor writes walk the layout's entries, so a texture
 * bound under a name no entry carries is never looked up.
 * <p>
 * <strong>Only this engine's own compiles</strong>, on the same rule as {@link RawLocals} and
 * {@link PackNames} and asked of the first of them: the game's shaders and Sodium's go through the
 * same compiler and are left alone. A storage image is never dropped whatever it reaches, because
 * the list it is dropped from is the one {@link ComputeShader} appends storage images to and only
 * names the reflection gave as sampled images are candidates.
 * <p>
 * {@code -Dvitrail.declaredSamplers=true} puts the whole declared list back, which is what every
 * layout carried before this existed. It is the A/B this pass is measured with, one jar and two
 * launches, and like the other two switches over the same bytes it goes into the module cache's
 * key: the two states reflect the same text into different tables, so a blob built under one must
 * never be served under the other.
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

	/** The word the module cache's key carries, since the state decides the tables it stores. */
	public static String cacheWord() {
		return DECLARED ? "declared-samplers" : "reached-samplers";
	}

	/**
	 * Drops from the module's sampler list every sampled image the entry point does not reach.
	 * Called on the module the reflection has just built, before anything has read its tables.
	 *
	 * @param filename the debug name the compile was given, which says whose module it is
	 * @param module   the module to narrow, whose lists are the ones the reflection filled
	 */
	@SuppressWarnings({"rawtypes", "unchecked"})
	public static void narrow(String filename, IntermediaryShaderModule module) {
		if (DECLARED || module == null || module.spirv() == null || !RawLocals.ours(filename)) {
			return;
		}

		WALKED.incrementAndGet();
		Set<String> unreached = unreached(module.spirv());
		if (unreached.isEmpty()) {
			return;
		}

		List samplers = ((IntermediaryShaderModuleAccessor) (Object) module).vitrail$samplers();
		int before = samplers.size();
		try {
			// By NAME and not by rank. The two readings list the module's resources in the same
			// order today, but a rank is only right for as long as that holds and for as long as
			// nothing has been appended to the list in between, where a name is right either way.
			samplers.removeIf(sampler -> unreached.contains(ComputeShader.samplerName(sampler)));
		} catch (RuntimeException e) {
			// A narrowing is not worth a pack. Reading that name is reflection over a record of
			// the game's: not over a shape this build never found, which ComputeShader's own
			// initialiser refuses long before anything compiles, but the invoke can still throw,
			// and thrown from here it would come out of the compiler's own method and take every
			// pack on the machine down rather than cost one layout its density. Said at WARN,
			// because a load that lost this quietly is a load whose Apple refusals come back with
			// nothing in the log to explain them.
			Vitrail.logger().warn("Could not read a module's sampler names, so its layout keeps a "
					+ "binding for every declared sampler: a pack over Metal's sixteen slots is "
					+ "refused on Apple hardware again", e);

			return;
		}

		int gone = before - samplers.size();
		if (gone > 0) {
			NARROWED.incrementAndGet();
			DROPPED.addAndGet(gone);
		}
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
					+ "past Metal's sixteen slots and refused on Apple hardware", compiled);

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
