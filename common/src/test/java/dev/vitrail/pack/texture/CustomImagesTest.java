package dev.vitrail.pack.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.model.TargetFormat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the storage images the loaded pack declared and the words that name them and their typed
 * views. The registry is process-global, so every test starts and ends with it empty.
 */
class CustomImagesTest {

	private static final String VOLUME = "volSampler RGBA RGBA16F HALF_FLOAT true false 64 64 64";
	private static final String COUNTER = "none RED R32UI UNSIGNED_INT false false 512 512";

	@BeforeEach
	@AfterEach
	void empty() {
		CustomImages.clear();
	}

	private static ImageInformation.Reading reading(String... nameAndValue) {
		List<ImageInformation> images = new ArrayList<>();
		for (int i = 0; i < nameAndValue.length; i += 2) {
			assertNull(ImageInformation.parse(nameAndValue[i], nameAndValue[i + 1], Map.of(), images),
					nameAndValue[i]);
		}

		return new ImageInformation.Reading(images, List.of());
	}

	// ---- the registry ------------------------------------------------------------------------

	@Test
	void nothingIsNamedBeforeAPackIsInstalled() {
		assertFalse(CustomImages.named("vol"));
		assertFalse(CustomImages.storage("vol"));
		assertEquals(Set.of(), CustomImages.names());
		assertEquals("", CustomImages.key());
		assertTrue(CustomImages.image("vol").isEmpty());
		assertTrue(CustomImages.viewFormat("vol").isEmpty());
		assertTrue(CustomImages.layoutFormat("vol").isEmpty());
	}

	@Test
	void anImageAndTheSamplerThatReadsItAreBothNamedButOnlyTheImageIsStorage() {
		CustomImages.install(reading("vol", VOLUME, "cnt", COUNTER));

		assertTrue(CustomImages.named("vol"));
		assertTrue(CustomImages.named("volSampler"));
		assertTrue(CustomImages.named("cnt"));
		assertFalse(CustomImages.named("other"));
		assertFalse(CustomImages.named("none"), "none is the word for no sampler, not a name");
		assertEquals(Set.of("vol", "volSampler", "cnt"), CustomImages.names());

		assertTrue(CustomImages.storage("vol"));
		assertFalse(CustomImages.storage("volSampler"));
		assertTrue(CustomImages.storage("cnt"));
		assertFalse(CustomImages.storage("other"));
	}

	@Test
	void theSamplerAnswersTheSameImageAsItsName() {
		CustomImages.install(reading("vol", VOLUME));

		assertEquals("vol", CustomImages.image("volSampler").orElseThrow().name());
		assertEquals("vol", CustomImages.image("vol").orElseThrow().name());
		assertTrue(CustomImages.image("other").isEmpty());
	}

	@Test
	void theLayoutFormatIsTheWordAStorageImageDeclaresWithAndOnlyForTheImageItself() {
		CustomImages.install(reading("vol", VOLUME, "cnt", COUNTER));

		assertEquals(Optional.of("rgba16f"), CustomImages.layoutFormat("vol"));
		assertEquals(Optional.of("r32ui"), CustomImages.layoutFormat("cnt"));
		assertTrue(CustomImages.layoutFormat("volSampler").isEmpty());
		assertTrue(CustomImages.layoutFormat("other").isEmpty());
		assertEquals(Optional.of(TargetFormat.RGBA16_FLOAT), CustomImages.viewFormat("vol"));
		assertEquals(Optional.of(TargetFormat.RGBA16_FLOAT), CustomImages.viewFormat("volSampler"));
	}

	@Test
	void aTypedViewIsNamedByItsOriginalAndAnswersItsOwnFormat() {
		CustomImages.install(reading("vol", VOLUME));

		String view = "ofCustomImageView_r32ui_vol";

		assertEquals("vol", CustomImages.originalName(view));
		assertEquals("vol", CustomImages.originalName("vol"));
		assertEquals("other", CustomImages.originalName("other"));
		assertTrue(CustomImages.named(view));
		assertTrue(CustomImages.storage(view));
		assertEquals(Optional.of(TargetFormat.R32_UINT), CustomImages.viewFormat(view));
		assertEquals(Optional.of("r32ui"), CustomImages.layoutFormat(view));
		assertEquals("vol", CustomImages.image(view).orElseThrow().name());
		assertFalse(CustomImages.named("ofCustomImageView_r32ui_other"));
	}

	@Test
	void theKeyListsEveryNameWithTheFormatItIsAllocatedAsSortedByName() {
		CustomImages.install(reading("vol", VOLUME, "cnt", COUNTER));

		assertEquals("cnt:R32_UINT;vol:RGBA16_FLOAT;volSampler:RGBA16_FLOAT", CustomImages.key());
	}

	@Test
	void theKeyFollowsTheFormatAPromotedNameIsAllocatedAs() {
		CustomImages.install(reading("rgb", "none RGB RGB16F HALF_FLOAT false false 4 4 4"));

		assertEquals("rgb:RGBA16_FLOAT", CustomImages.key());
	}

	@Test
	void installingAnotherPackReplacesTheFirstAndClearForgetsBoth() {
		CustomImages.install(reading("vol", VOLUME));
		CustomImages.install(reading("cnt", COUNTER));

		assertFalse(CustomImages.named("vol"));
		assertTrue(CustomImages.named("cnt"));
		assertEquals("cnt:R32_UINT", CustomImages.key());

		CustomImages.clear();

		assertFalse(CustomImages.named("cnt"));
		assertEquals(Set.of(), CustomImages.names());
		assertEquals("", CustomImages.key());
	}

	@Test
	void anImageNamedTheSameAsAnotherImagesSamplerKeepsItsOwnNameWhicheverIsReadFirst() {
		for (boolean sameOrder : new boolean[] {true, false}) {
			CustomImages.install(sameOrder
					? reading("a", "none RGBA RGBA8 UNSIGNED_BYTE false false 4", "b",
							"a RGBA RGBA16F HALF_FLOAT false false 4")
					: reading("b", "a RGBA RGBA16F HALF_FLOAT false false 4", "a",
							"none RGBA RGBA8 UNSIGNED_BYTE false false 4"));

			assertEquals("a", CustomImages.image("a").orElseThrow().name());
			assertTrue(CustomImages.storage("a"));
			assertTrue(CustomImages.storage("b"));
		}
	}

	@Test
	void aNameDeclaredTwiceKeepsTheLastDeclaration() {
		CustomImages.install(reading("x", "none RGBA RGBA8 UNSIGNED_BYTE false false 4", "x",
				"none RED R32UI UNSIGNED_INT false false 8"));

		assertEquals(TargetFormat.R32_UINT, CustomImages.viewFormat("x").orElseThrow());
	}

	@Test
	void theNamesOfAReadingAreAnswerableBeforeItIsInstalledAndAreACopy() {
		ImageInformation.Reading reading = reading("vol", VOLUME, "cnt", COUNTER);

		Set<String> names = CustomImages.namesOf(reading);

		assertEquals(Set.of("vol", "volSampler", "cnt"), names);
		assertFalse(CustomImages.named("vol"), "asking does not install");
		assertThrows(UnsupportedOperationException.class, () -> names.add("x"));
		CustomImages.install(reading);
		assertEquals(names, CustomImages.names());
	}

	// ---- the format words --------------------------------------------------------------------

	private static final Map<TargetFormat, String> LAYOUT = new LinkedHashMap<>();

	static {
		LAYOUT.put(TargetFormat.R8_UNORM, "r8");
		LAYOUT.put(TargetFormat.R8_SNORM, "r8_snorm");
		LAYOUT.put(TargetFormat.RG8_UNORM, "rg8");
		LAYOUT.put(TargetFormat.RG8_SNORM, "rg8_snorm");
		LAYOUT.put(TargetFormat.RGBA8_UNORM, "rgba8");
		LAYOUT.put(TargetFormat.RGBA8_SNORM, "rgba8_snorm");
		LAYOUT.put(TargetFormat.R16_UNORM, "r16");
		LAYOUT.put(TargetFormat.R16_SNORM, "r16_snorm");
		LAYOUT.put(TargetFormat.RG16_UNORM, "rg16");
		LAYOUT.put(TargetFormat.RG16_SNORM, "rg16_snorm");
		LAYOUT.put(TargetFormat.RGBA16_UNORM, "rgba16");
		LAYOUT.put(TargetFormat.RGBA16_SNORM, "rgba16_snorm");
		LAYOUT.put(TargetFormat.R8_UINT, "r8ui");
		LAYOUT.put(TargetFormat.R8_SINT, "r8i");
		LAYOUT.put(TargetFormat.RG8_UINT, "rg8ui");
		LAYOUT.put(TargetFormat.RG8_SINT, "rg8i");
		LAYOUT.put(TargetFormat.RGBA8_UINT, "rgba8ui");
		LAYOUT.put(TargetFormat.RGBA8_SINT, "rgba8i");
		LAYOUT.put(TargetFormat.R16_UINT, "r16ui");
		LAYOUT.put(TargetFormat.R16_SINT, "r16i");
		LAYOUT.put(TargetFormat.RG16_UINT, "rg16ui");
		LAYOUT.put(TargetFormat.RG16_SINT, "rg16i");
		LAYOUT.put(TargetFormat.RGBA16_UINT, "rgba16ui");
		LAYOUT.put(TargetFormat.RGBA16_SINT, "rgba16i");
		LAYOUT.put(TargetFormat.R32_UINT, "r32ui");
		LAYOUT.put(TargetFormat.R32_SINT, "r32i");
		LAYOUT.put(TargetFormat.RG32_UINT, "rg32ui");
		LAYOUT.put(TargetFormat.RG32_SINT, "rg32i");
		LAYOUT.put(TargetFormat.RGBA32_UINT, "rgba32ui");
		LAYOUT.put(TargetFormat.RGBA32_SINT, "rgba32i");
		LAYOUT.put(TargetFormat.R16_FLOAT, "r16f");
		LAYOUT.put(TargetFormat.RG16_FLOAT, "rg16f");
		LAYOUT.put(TargetFormat.RGBA16_FLOAT, "rgba16f");
		LAYOUT.put(TargetFormat.R32_FLOAT, "r32f");
		LAYOUT.put(TargetFormat.RG32_FLOAT, "rg32f");
		LAYOUT.put(TargetFormat.RGBA32_FLOAT, "rgba32f");
		LAYOUT.put(TargetFormat.RGB10A2_UNORM, "rgb10_a2");
		LAYOUT.put(TargetFormat.RGB10A2_UINT, "rgb10_a2ui");
		LAYOUT.put(TargetFormat.RG11B10_FLOAT, "r11f_g11f_b10f");
	}

	@Test
	void everyFormatHasItsOwnGlslLayoutWordFromTheSpecification() {
		assertEquals(TargetFormat.values().length, LAYOUT.size());
		for (TargetFormat format : TargetFormat.values()) {
			assertEquals(LAYOUT.get(format), CustomImages.glslLayout(format), format.name());
		}
		assertEquals(39, new HashSet<>(LAYOUT.values()).size());
	}

	@Test
	void aLayoutWordIsAFormatAndNothingElseALayoutCarries() {
		for (String word : LAYOUT.values()) {
			assertTrue(CustomImages.isLayoutFormat(word), word);
		}

		for (String word : List.of("", "binding", "set", "std140", "std430", "location", "rgba", "R8", "RGBA8",
				"r8 ", "rgba8f", "r64f", "0", "rgb10")) {
			assertFalse(CustomImages.isLayoutFormat(word), "'" + word + "'");
		}
	}
}
