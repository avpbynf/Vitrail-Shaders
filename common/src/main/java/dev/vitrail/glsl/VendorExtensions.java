package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The vendor GLSL extensions the device does not have, so that a pack testing for one reads the
 * answer the device gives and not the one the compiler gives.
 * <p>
 * The compiler defines the macro of every extension it KNOWS, whether the device has it or not,
 * and it knows the AMD, Intel and NVIDIA ones. A pack written against OpenGL gates its use of
 * them on that macro, which a GL driver only defines for what the card really has: RenderPearl
 * takes {@code max3} from {@code GL_AMD_shader_trinary_minmax} when the macro is defined and
 * falls back on two {@code max} otherwise. Compiled here, the macro is defined on every card, the
 * pack asks for the AMD instruction, and the module carries an {@code OpExtInst} of an extension
 * GeForce does not implement. The NVIDIA driver does not refuse that module: it dies with a null
 * read inside {@code vkCreateGraphicsPipelines}, on the pack's first pipeline, which is what took
 * the game down on every launch of the pack. Measured off game, one pipeline at a time, the
 * trinary maximum alone reproducing it and two nested {@code max} not.
 * <p>
 * The device answers at creation ({@link #serve}), which both records what it lacks and enables
 * what it has, since an extension a module uses must be enabled on the device and the game
 * enables none of these. Until it has answered, and off game, every vendor extension counts as
 * absent. A translation is keyed on the answer ({@link #key}), so a cache written on one card is
 * not served to another.
 * <p>
 * Five extensions are answered absent on every device, and one of them is a divergence. {@code
 * GL_NV_gpu_shader5} has no Vulkan form at all, while an NVIDIA GL driver has it, so a pack
 * gating on it takes its fallback here where under Iris on a GeForce it takes the extension.
 * Noble, the one pack of the corpus gating on it, falls to the explicit arithmetic types
 * extension, which gives it the same 16 bit types under the compiler here, and translates and
 * validates on that road. The AMD ballot needs the subgroup ballot extension beside it, which
 * is not enabled here either. The Intel integer functions, the NVIDIA
 * streaming multiprocessor builtins and the ARM core builtins each sit behind a device feature of
 * their own that this engine does not enable, so enabling the extension alone would not make a
 * module using them valid.
 */
public final class VendorExtensions {

	/** Each vendor GLSL extension with the device extension that implements it on Vulkan. */
	private static final Map<String, String> DEVICE_EXTENSIONS = deviceExtensions();

	private static volatile Set<String> absent = Collections.unmodifiableSet(
			new TreeSet<>(DEVICE_EXTENSIONS.keySet()));

	private VendorExtensions() {
	}

	/**
	 * Records what the device has and enables it, asked once at the device's creation.
	 *
	 * @param hasDeviceExtension whether the physical device offers a device extension
	 * @param enable             where a device extension to enable at creation is added
	 * @return the device extensions enabled, for the log
	 */
	public static List<String> serve(Predicate<String> hasDeviceExtension, Consumer<String> enable) {
		Set<String> missing = new TreeSet<>();
		List<String> enabled = new ArrayList<>();
		DEVICE_EXTENSIONS.forEach((glsl, device) -> {
			if (device.isEmpty() || !hasDeviceExtension.test(device)) {
				missing.add(glsl);
			} else {
				enable.accept(device);
				enabled.add(device);
			}
		});
		absent = Collections.unmodifiableSet(missing);

		return enabled;
	}

	/** Whether a pack's {@code #extension} of this name asks for something the device lacks. */
	static boolean absent(String glslExtension) {
		return absent.contains(glslExtension);
	}

	/** The absent names, joined, for a cache key. */
	public static String key() {
		return String.join(",", absent);
	}

	/** The hidden spelling of a macro the compiler would define and the device would not answer for. */
	static String hidden(String glslExtension) {
		return "OF_ABSENT_" + glslExtension;
	}

	private static Map<String, String> deviceExtensions() {
		Map<String, String> table = new LinkedHashMap<>();
		// Extensions that stand on their own: enabling the device extension is all a module
		// using them needs.
		table.put("GL_AMD_shader_trinary_minmax", "VK_AMD_shader_trinary_minmax");
		table.put("GL_AMD_gpu_shader_half_float", "VK_AMD_gpu_shader_half_float");
		table.put("GL_AMD_gpu_shader_int16", "VK_AMD_gpu_shader_int16");
		table.put("GL_AMD_gcn_shader", "VK_AMD_gcn_shader");
		table.put("GL_AMD_shader_explicit_vertex_parameter", "VK_AMD_shader_explicit_vertex_parameter");
		table.put("GL_AMD_shader_fragment_mask", "VK_AMD_shader_fragment_mask");
		table.put("GL_AMD_shader_image_load_store_lod", "VK_AMD_shader_image_load_store_lod");
		table.put("GL_AMD_texture_gather_bias_lod", "VK_AMD_texture_gather_bias_lod");
		table.put("GL_NV_shader_subgroup_partitioned", "VK_NV_shader_subgroup_partitioned");
		// Absent on every device: no Vulkan form, a second extension or a device feature of their
		// own that is not enabled here, so the module would be invalid with the extension enabled
		// alone.
		table.put("GL_NV_gpu_shader5", "");
		table.put("GL_AMD_shader_ballot", "");
		table.put("GL_INTEL_shader_integer_functions2", "");
		table.put("GL_NV_shader_sm_builtins", "");
		table.put("GL_ARM_shader_core_builtins", "");

		return Collections.unmodifiableMap(table);
	}
}
