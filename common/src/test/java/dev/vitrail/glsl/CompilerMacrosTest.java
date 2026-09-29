package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ProgramStage;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link CompilerMacros} and {@link VendorExtensions} to their javadoc: the macros glslang
 * defines before a stage's first line, less the vendor extensions the device does not have and, per
 * stage, the subgroup ones the device does not run there.
 * <p>
 * Both keep their answers in statics that the device sets once at its creation, and a translation is
 * keyed on them, so every test starts and ends with the state the class has before any device has
 * answered: every vendor extension absent, subgroups in every stage, and not MoltenVK.
 */
class CompilerMacrosTest {

	private static final List<String> VENDOR_NAMES = List.of("GL_AMD_gcn_shader", "GL_AMD_gpu_shader_half_float",
			"GL_AMD_gpu_shader_int16", "GL_AMD_shader_ballot", "GL_AMD_shader_explicit_vertex_parameter",
			"GL_AMD_shader_fragment_mask", "GL_AMD_shader_image_load_store_lod", "GL_AMD_shader_trinary_minmax",
			"GL_AMD_texture_gather_bias_lod", "GL_ARM_shader_core_builtins", "GL_INTEL_shader_integer_functions2",
			"GL_NV_gpu_shader5", "GL_NV_shader_sm_builtins", "GL_NV_shader_subgroup_partitioned");

	/** The five that no device has: no Vulkan form, or a second extension, or a feature this engine never enables. */
	private static final List<String> NEVER = List.of("GL_AMD_shader_ballot", "GL_ARM_shader_core_builtins",
			"GL_INTEL_shader_integer_functions2", "GL_NV_gpu_shader5", "GL_NV_shader_sm_builtins");

	@BeforeEach
	void startAsBeforeAnyDeviceAnswered() {
		reset();
	}

	@AfterEach
	void leaveItAsBeforeAnyDeviceAnswered() {
		reset();
	}

	private static void reset() {
		VendorExtensions.serve(device -> false, device -> {
		});
		VendorExtensions.serveSubgroupStages(EnumSet.allOf(ProgramStage.class));
		VendorExtensions.serveMoltenVk(false);
	}

	/** A device that has every extension it could, and enables each one it is asked for. */
	private static List<String> deviceWithEverything() {
		List<String> enabled = new ArrayList<>();

		return VendorExtensions.serve(device -> true, enabled::add);
	}

	// -------------------------------------------------------------------------- the key

	@Test
	void beforeAnyDeviceAnswersEveryVendorExtensionIsAbsentAndTheKeyIsTheirSortedNames() {
		// The list above is written in the sorted order the key uses.
		assertEquals(VENDOR_NAMES, List.copyOf(new TreeSet<>(VENDOR_NAMES)));
		assertEquals(String.join(",", VENDOR_NAMES), VendorExtensions.key());
		for (ProgramStage stage : ProgramStage.values()) {
			for (String name : VENDOR_NAMES) {
				assertTrue(VendorExtensions.absent(name, stage), name + " in " + stage);
			}
		}
	}

	@Test
	void aDeviceWithEverythingEnablesTheNineThatHaveADeviceExtensionAndStillLacksTheFive() {
		List<String> enabled = deviceWithEverything();

		assertEquals(List.of("VK_AMD_shader_trinary_minmax", "VK_AMD_gpu_shader_half_float", "VK_AMD_gpu_shader_int16",
				"VK_AMD_gcn_shader", "VK_AMD_shader_explicit_vertex_parameter", "VK_AMD_shader_fragment_mask",
				"VK_AMD_shader_image_load_store_lod", "VK_AMD_texture_gather_bias_lod",
				"VK_NV_shader_subgroup_partitioned"), enabled);
		assertEquals(String.join(",", NEVER), VendorExtensions.key());
		for (String name : NEVER) {
			assertTrue(VendorExtensions.absent(name, ProgramStage.FRAGMENT), name);
		}

		assertFalse(VendorExtensions.absent("GL_AMD_shader_trinary_minmax", ProgramStage.FRAGMENT));
	}

	@Test
	void aDeviceEnablesOnlyWhatItOffersAndLacksTheRest() {
		List<String> enabled = new ArrayList<>();

		VendorExtensions.serve(device -> device.equals("VK_AMD_gcn_shader"), enabled::add);

		assertEquals(List.of("VK_AMD_gcn_shader"), enabled);
		assertFalse(VendorExtensions.absent("GL_AMD_gcn_shader", ProgramStage.VERTEX));
		assertTrue(VendorExtensions.absent("GL_AMD_gpu_shader_int16", ProgramStage.VERTEX));
		assertFalse(VendorExtensions.key().contains("GL_AMD_gcn_shader"));
		assertTrue(VendorExtensions.key().contains("GL_AMD_gpu_shader_int16"));
	}

	@Test
	void aNameThatIsNotAVendorExtensionIsNeverAbsent() {
		for (ProgramStage stage : ProgramStage.values()) {
			assertFalse(VendorExtensions.absent("GL_ARB_gpu_shader5", stage));
			assertFalse(VendorExtensions.absent("", stage));
			assertFalse(VendorExtensions.absent("GL_KHR_blend_equation_advanced", stage));
		}
	}

	@Test
	void theSubgroupExtensionsAreAbsentOnlyInTheStagesTheDeviceDoesNotRunThemIn() {
		Set<ProgramStage> supported = EnumSet.of(ProgramStage.FRAGMENT, ProgramStage.COMPUTE);
		Set<ProgramStage> lacking = VendorExtensions.serveSubgroupStages(supported);

		assertEquals(EnumSet.complementOf(EnumSet.copyOf(supported)), lacking);
		for (String name : List.of("GL_KHR_shader_subgroup_basic", "GL_KHR_shader_subgroup_vote",
				"GL_EXT_shader_subgroup_extended_types_int8")) {
			for (ProgramStage stage : ProgramStage.values()) {
				assertEquals(lacking.contains(stage), VendorExtensions.absent(name, stage), name + " in " + stage);
			}
		}

		// A name that merely begins alike, or is not a subgroup extension at all, is not touched by stages.
		assertTrue(lacking.contains(ProgramStage.VERTEX));
		assertFalse(VendorExtensions.absent("GL_KHR_shader_subgroups", ProgramStage.VERTEX));
		assertFalse(VendorExtensions.absent("GL_EXT_shader_realtime_clock", ProgramStage.VERTEX));
	}

	@Test
	void aDeviceRunningSubgroupsEverywhereKeepsTheKeyItHadBeforeStagesWereAsked() {
		String before = VendorExtensions.key();

		VendorExtensions.serveSubgroupStages(EnumSet.allOf(ProgramStage.class));

		assertEquals(before, VendorExtensions.key());
		assertFalse(VendorExtensions.key().contains("subgroups absent"));
	}

	@Test
	void theKeyNamesTheStagesTheSubgroupsAreAbsentFromInTheStagesOwnOrder() {
		String names = VendorExtensions.key();
		Set<ProgramStage> lacking = VendorExtensions.serveSubgroupStages(EnumSet.of(ProgramStage.FRAGMENT));

		StringBuilder stages = new StringBuilder();
		for (ProgramStage stage : ProgramStage.values()) {
			if (lacking.contains(stage)) {
				stages.append(stages.isEmpty() ? "" : ",").append(stage.name());
			}
		}

		assertEquals(names + ";subgroups absent in " + stages, VendorExtensions.key());
	}

	@Test
	void theKeyEndsInMoltenVkOnlyWhereTheDriverIsAndThatDriverAloneMovesItOnceAsked() {
		String plain = VendorExtensions.key();
		assertFalse(VendorExtensions.moltenVk());

		VendorExtensions.serveMoltenVk(true);

		assertTrue(VendorExtensions.moltenVk());
		assertEquals(plain + ";MoltenVK", VendorExtensions.key());

		VendorExtensions.serveMoltenVk(false);
		assertEquals(plain, VendorExtensions.key());
	}

	@Test
	void theHiddenSpellingOfAMacroIsTheNameBehindAPrefix() {
		assertEquals("OF_ABSENT_GL_AMD_gcn_shader", VendorExtensions.hidden("GL_AMD_gcn_shader"));
		assertEquals("OF_ABSENT_", VendorExtensions.hidden(""));
	}

	// --------------------------------------------------------------------- the macros

	@Test
	void everyStageDefinesTheVulkanVersionAndTheOneMacroNamingItselfAndNoOther() {
		for (ProgramStage stage : ProgramStage.values()) {
			Map<String, String> defined = CompilerMacros.definedIn(stage);

			assertEquals("100", defined.get("VULKAN"), stage.name());
			assertEquals("1", defined.get("GL_" + stage.name() + "_SHADER"), stage.name());
			for (ProgramStage other : ProgramStage.values()) {
				if (other != stage) {
					assertNull(defined.get("GL_" + other.name() + "_SHADER"), stage + " defines " + other);
				}
			}
		}
	}

	@Test
	void everyValueIsOneExceptTheVulkanVersion() {
		for (Map.Entry<String, String> entry : CompilerMacros.definedIn(ProgramStage.FRAGMENT).entrySet()) {
			assertEquals(entry.getKey().equals("VULKAN") ? "100" : "1", entry.getValue(), entry.getKey());
		}
	}

	@Test
	void theTwoLastMacrosAreTheVersionAndTheStageInThatOrderAfterEveryExtension() {
		List<String> names = List.copyOf(CompilerMacros.definedIn(ProgramStage.GEOMETRY).keySet());

		assertEquals("VULKAN", names.get(names.size() - 2));
		assertEquals("GL_GEOMETRY_SHADER", names.getLast());
		assertEquals(names.size(), Set.copyOf(names).size(), "a macro defined twice");
	}

	@Test
	void theExtensionsTheShadercLibraryCarriesAreDefinedAndTheVendorOnesAreNotBeforeADeviceAnswers() {
		Map<String, String> defined = CompilerMacros.definedIn(ProgramStage.FRAGMENT);

		for (String name : List.of("GL_core_profile", "GL_KHR_shader_subgroup_basic", "GL_EXT_ray_tracing",
				"GL_ARB_shader_ballot", "GL_GOOGLE_include_directive", "GL_OVR_multiview2")) {
			assertEquals("1", defined.get(name), name);
		}

		for (String name : VENDOR_NAMES) {
			assertFalse(defined.containsKey(name), name);
		}
	}

	@Test
	void aVendorExtensionTheDeviceHasIsDefinedInEveryStageAndTheOnesItLacksStayOut() {
		deviceWithEverything();

		for (ProgramStage stage : ProgramStage.values()) {
			Map<String, String> defined = CompilerMacros.definedIn(stage);
			assertEquals("1", defined.get("GL_AMD_shader_trinary_minmax"), stage.name());
			assertEquals("1", defined.get("GL_NV_shader_subgroup_partitioned"), stage.name());
			assertFalse(defined.containsKey("GL_NV_gpu_shader5"), stage.name());
		}
	}

	@Test
	void theStagesTheDeviceRunsNoSubgroupsInLoseThoseTwelveMacrosAndNoOthers() {
		int all = CompilerMacros.definedIn(ProgramStage.VERTEX).size();
		VendorExtensions.serveSubgroupStages(EnumSet.complementOf(EnumSet.of(ProgramStage.VERTEX)));

		Map<String, String> vertex = CompilerMacros.definedIn(ProgramStage.VERTEX);
		Map<String, String> fragment = CompilerMacros.definedIn(ProgramStage.FRAGMENT);

		// Four extended-type extensions and the eight KHR ones: what the compiler's preamble carries.
		assertEquals(all - 12, vertex.size());
		assertEquals(all, fragment.size());
		assertFalse(vertex.containsKey("GL_KHR_shader_subgroup_basic"));
		assertFalse(vertex.containsKey("GL_KHR_shader_subgroup_shuffle_relative"));
		assertFalse(vertex.containsKey("GL_EXT_shader_subgroup_extended_types_float16"));
		assertTrue(fragment.containsKey("GL_KHR_shader_subgroup_basic"));
		assertTrue(vertex.containsKey("GL_EXT_shader_realtime_clock"));
	}

	@Test
	void aDeviceWithEverythingDefinesNineMoreMacrosThanOneWithNothing() {
		int none = CompilerMacros.definedIn(ProgramStage.COMPUTE).size();
		deviceWithEverything();

		assertEquals(none + 9, CompilerMacros.definedIn(ProgramStage.COMPUTE).size());
	}

	@Test
	void everyCallGivesAFreshTableTheCallerMayChange() {
		Map<String, String> first = CompilerMacros.definedIn(ProgramStage.FRAGMENT);
		first.put("MINE", "1");

		assertFalse(CompilerMacros.definedIn(ProgramStage.FRAGMENT).containsKey("MINE"));
	}
}
