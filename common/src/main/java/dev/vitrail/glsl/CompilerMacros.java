package dev.vitrail.glsl;

import dev.vitrail.pack.model.ProgramStage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The macros the compiler defines before the first line of a translated stage, as a pack's
 * conditionals get to see them.
 * <p>
 * glslang writes a preamble ahead of every shader it compiles ({@code TParseVersions::getPreamble}
 * in {@code MachineIndependent/Versions.cpp}), one {@code #define} per extension it supports and a
 * few more, and a pack tests them the way it tests a GL driver's: RenderPearl turns its subgroup
 * lighting on where {@code GL_KHR_shader_subgroup_basic} is defined and includes the buffer that
 * lighting reads under the same test. The include expander decides those tests before the compiler
 * does, and with a table missing these names it dropped the include on a device where the compiler
 * then took the branch.
 * <p>
 * <strong>The list is the shaderc the game ships, asked rather than read off glslang's
 * source.</strong> The preamble depends on the version and profile of the header and on the stage,
 * and every translated stage carries the same header ({@code #version 460 core}, {@link Emitter}),
 * so what is left to vary is the stage. Asked by compiling, in each of the six stages, a unit
 * testing every name the shaderc library carries in a define string, the answer is one set shared
 * by all six, the same under either Vulkan target, plus the one macro naming the stage. It has to
 * be asked through {@code shaderc_compile_into_spv}: the preprocessed text road preprocesses as a
 * vertex stage whatever kind it is handed, and answers {@code GL_VERTEX_SHADER} in every stage. A
 * newer shaderc can add a name, and asking it the same way is how this list is brought level.
 * <p>
 * The table for a stage leaves out what the translator hides from the compiler in that stage
 * ({@link VendorExtensions#absent}), since the compiler then reads those names undefined.
 */
public final class CompilerMacros {

	/** Defined to 1 in every stage. */
	private static final List<String> PREAMBLE = List.of(
			"GL_AMD_gcn_shader", "GL_AMD_gpu_shader_half_float",
			"GL_AMD_gpu_shader_half_float_fetch", "GL_AMD_gpu_shader_int16",
			"GL_AMD_shader_ballot", "GL_AMD_shader_explicit_vertex_parameter",
			"GL_AMD_shader_fragment_mask", "GL_AMD_shader_image_load_store_lod",
			"GL_AMD_shader_trinary_minmax", "GL_AMD_texture_gather_bias_lod",
			"GL_ARB_compute_shader", "GL_ARB_conservative_depth", "GL_ARB_derivative_control",
			"GL_ARB_draw_instanced", "GL_ARB_enhanced_layouts", "GL_ARB_explicit_attrib_location",
			"GL_ARB_explicit_uniform_location", "GL_ARB_fragment_coord_conventions",
			"GL_ARB_fragment_shader_interlock", "GL_ARB_gpu_shader5", "GL_ARB_gpu_shader_fp64",
			"GL_ARB_gpu_shader_int64", "GL_ARB_post_depth_coverage", "GL_ARB_sample_shading",
			"GL_ARB_separate_shader_objects", "GL_ARB_shader_atomic_counters",
			"GL_ARB_shader_ballot", "GL_ARB_shader_bit_encoding", "GL_ARB_shader_draw_parameters",
			"GL_ARB_shader_group_vote", "GL_ARB_shader_image_load_store",
			"GL_ARB_shader_image_size", "GL_ARB_shader_stencil_export",
			"GL_ARB_shader_storage_buffer_object", "GL_ARB_shader_texture_image_samples",
			"GL_ARB_shader_texture_lod", "GL_ARB_shading_language_420pack",
			"GL_ARB_shading_language_packing", "GL_ARB_sparse_texture2",
			"GL_ARB_sparse_texture_clamp", "GL_ARB_tessellation_shader",
			"GL_ARB_texture_cube_map_array", "GL_ARB_texture_gather", "GL_ARB_texture_multisample",
			"GL_ARB_texture_query_lod", "GL_ARB_texture_rectangle", "GL_ARB_uniform_buffer_object",
			"GL_ARB_vertex_attrib_64bit", "GL_ARB_viewport_array", "GL_EXT_bfloat16",
			"GL_EXT_buffer_reference", "GL_EXT_buffer_reference2", "GL_EXT_buffer_reference_uvec2",
			"GL_EXT_control_flow_attributes", "GL_EXT_control_flow_attributes2",
			"GL_EXT_debug_printf", "GL_EXT_demote_to_helper_invocation", "GL_EXT_descriptor_heap",
			"GL_EXT_device_group", "GL_EXT_float_e4m3", "GL_EXT_float_e5m2",
			"GL_EXT_fragment_invocation_density", "GL_EXT_fragment_shader_barycentric",
			"GL_EXT_fragment_shading_rate", "GL_EXT_integer_dot_product",
			"GL_EXT_maximal_reconvergence", "GL_EXT_mesh_shader", "GL_EXT_multiview",
			"GL_EXT_nontemporal_keyword", "GL_EXT_nonuniform_qualifier", "GL_EXT_null_initializer",
			"GL_EXT_post_depth_coverage", "GL_EXT_ray_cull_mask",
			"GL_EXT_ray_flags_primitive_culling", "GL_EXT_ray_query", "GL_EXT_ray_tracing",
			"GL_EXT_ray_tracing_position_fetch", "GL_EXT_samplerless_texture_functions",
			"GL_EXT_scalar_block_layout", "GL_EXT_shader_16bit_storage",
			"GL_EXT_shader_64bit_indexing", "GL_EXT_shader_8bit_storage",
			"GL_EXT_shader_atomic_float", "GL_EXT_shader_atomic_float2",
			"GL_EXT_shader_atomic_int64", "GL_EXT_shader_explicit_arithmetic_types",
			"GL_EXT_shader_explicit_arithmetic_types_float16",
			"GL_EXT_shader_explicit_arithmetic_types_float32",
			"GL_EXT_shader_explicit_arithmetic_types_float64",
			"GL_EXT_shader_explicit_arithmetic_types_int16",
			"GL_EXT_shader_explicit_arithmetic_types_int32",
			"GL_EXT_shader_explicit_arithmetic_types_int64",
			"GL_EXT_shader_explicit_arithmetic_types_int8", "GL_EXT_shader_image_int64",
			"GL_EXT_shader_image_load_formatted", "GL_EXT_shader_integer_mix",
			"GL_EXT_shader_invocation_reorder", "GL_EXT_shader_non_constant_global_initializers",
			"GL_EXT_shader_quad_control", "GL_EXT_shader_realtime_clock",
			"GL_EXT_shader_subgroup_extended_types_float16",
			"GL_EXT_shader_subgroup_extended_types_int16",
			"GL_EXT_shader_subgroup_extended_types_int64",
			"GL_EXT_shader_subgroup_extended_types_int8", "GL_EXT_shared_memory_block",
			"GL_EXT_spec_constant_composites", "GL_EXT_spirv_intrinsics",
			"GL_EXT_subgroup_uniform_control_flow", "GL_EXT_terminate_invocation",
			"GL_EXT_texture_array", "GL_EXT_texture_offset_non_const",
			"GL_EXT_uniform_buffer_unsized_array", "GL_FRAGMENT_PRECISION_HIGH",
			"GL_GOOGLE_cpp_style_line_directive", "GL_GOOGLE_include_directive",
			"GL_INTEL_shader_integer_functions2", "GL_KHR_blend_equation_advanced",
			"GL_KHR_cooperative_matrix", "GL_KHR_shader_subgroup_arithmetic",
			"GL_KHR_shader_subgroup_ballot", "GL_KHR_shader_subgroup_basic",
			"GL_KHR_shader_subgroup_clustered", "GL_KHR_shader_subgroup_quad",
			"GL_KHR_shader_subgroup_shuffle", "GL_KHR_shader_subgroup_shuffle_relative",
			"GL_KHR_shader_subgroup_vote", "GL_NV_compute_shader_derivatives",
			"GL_NV_conservative_raster_underestimation", "GL_NV_cooperative_matrix",
			"GL_NV_cooperative_matrix2", "GL_NV_fragment_shader_barycentric",
			"GL_NV_geometry_shader_passthrough", "GL_NV_gpu_shader5",
			"GL_NV_integer_cooperative_matrix", "GL_NV_mesh_shader", "GL_NV_ray_tracing",
			"GL_NV_ray_tracing_motion_blur", "GL_NV_sample_mask_override_coverage",
			"GL_NV_shader_atomic_int64", "GL_NV_shader_invocation_reorder",
			"GL_NV_shader_sm_builtins", "GL_NV_shader_subgroup_partitioned",
			"GL_NV_shader_texture_footprint", "GL_NV_shading_rate_image", "GL_NV_viewport_array2",
			"GL_OVR_multiview", "GL_OVR_multiview2", "GL_QCOM_cooperative_matrix_conversion",
			"GL_QCOM_image_processing", "GL_QCOM_image_processing2", "GL_QCOM_tile_shading",
			"GL_core_profile");

	private CompilerMacros() {
	}

	/**
	 * Every macro the compiler has defined when it reads the first line of a stage, with its value,
	 * less the ones hidden from it in that stage because the device lacks the extension there.
	 */
	public static Map<String, String> definedIn(ProgramStage stage) {
		Map<String, String> defined = new LinkedHashMap<>();
		for (String name : PREAMBLE) {
			if (!VendorExtensions.absent(name, stage)) {
				defined.put(name, "1");
			}
		}

		// The Vulkan GLSL version the compiler targets, which it writes whatever the target's own
		// version is.
		defined.put("VULKAN", "100");
		// glslang's names for the stages are the enum's, GL_FRAGMENT_SHADER for FRAGMENT.
		defined.put("GL_" + stage.name() + "_SHADER", "1");

		return defined;
	}
}
