package dev.vitrail.pack.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds what each colour format name a pack may write becomes: exact, widened to four channels,
 * replaced by a modern format, or unknown and given the default.
 */
class TargetFormatTest {

	/** Every name that maps onto a format as it is, written out from the GL enumerants. */
	private static final Map<String, TargetFormat> EXACT = new LinkedHashMap<>();

	static {
		EXACT.put("RGBA", TargetFormat.RGBA8_UNORM);
		EXACT.put("R8", TargetFormat.R8_UNORM);
		EXACT.put("RG8", TargetFormat.RG8_UNORM);
		EXACT.put("RGBA8", TargetFormat.RGBA8_UNORM);
		EXACT.put("R8_SNORM", TargetFormat.R8_SNORM);
		EXACT.put("RG8_SNORM", TargetFormat.RG8_SNORM);
		EXACT.put("RGBA8_SNORM", TargetFormat.RGBA8_SNORM);
		EXACT.put("R16", TargetFormat.R16_UNORM);
		EXACT.put("RG16", TargetFormat.RG16_UNORM);
		EXACT.put("RGBA16", TargetFormat.RGBA16_UNORM);
		EXACT.put("R16_SNORM", TargetFormat.R16_SNORM);
		EXACT.put("RG16_SNORM", TargetFormat.RG16_SNORM);
		EXACT.put("RGBA16_SNORM", TargetFormat.RGBA16_SNORM);
		EXACT.put("R16F", TargetFormat.R16_FLOAT);
		EXACT.put("RG16F", TargetFormat.RG16_FLOAT);
		EXACT.put("RGBA16F", TargetFormat.RGBA16_FLOAT);
		EXACT.put("R32F", TargetFormat.R32_FLOAT);
		EXACT.put("RG32F", TargetFormat.RG32_FLOAT);
		EXACT.put("RGBA32F", TargetFormat.RGBA32_FLOAT);
		EXACT.put("R8I", TargetFormat.R8_SINT);
		EXACT.put("RG8I", TargetFormat.RG8_SINT);
		EXACT.put("RGBA8I", TargetFormat.RGBA8_SINT);
		EXACT.put("R8UI", TargetFormat.R8_UINT);
		EXACT.put("RG8UI", TargetFormat.RG8_UINT);
		EXACT.put("RGBA8UI", TargetFormat.RGBA8_UINT);
		EXACT.put("R16I", TargetFormat.R16_SINT);
		EXACT.put("RG16I", TargetFormat.RG16_SINT);
		EXACT.put("RGBA16I", TargetFormat.RGBA16_SINT);
		EXACT.put("R16UI", TargetFormat.R16_UINT);
		EXACT.put("RG16UI", TargetFormat.RG16_UINT);
		EXACT.put("RGBA16UI", TargetFormat.RGBA16_UINT);
		EXACT.put("R32I", TargetFormat.R32_SINT);
		EXACT.put("RG32I", TargetFormat.RG32_SINT);
		EXACT.put("RGBA32I", TargetFormat.RGBA32_SINT);
		EXACT.put("R32UI", TargetFormat.R32_UINT);
		EXACT.put("RG32UI", TargetFormat.RG32_UINT);
		EXACT.put("RGBA32UI", TargetFormat.RGBA32_UINT);
		EXACT.put("RGB10_A2", TargetFormat.RGB10A2_UNORM);
		EXACT.put("RGB10_A2UI", TargetFormat.RGB10A2_UINT);
		EXACT.put("R11F_G11F_B10F", TargetFormat.RG11B10_FLOAT);
	}

	@Test
	void everyExactNameResolvesToItsFormatWithNothingAdded() {
		for (Map.Entry<String, TargetFormat> entry : EXACT.entrySet()) {
			TargetFormat.Resolution resolution = TargetFormat.resolve(entry.getKey());

			assertEquals(entry.getValue(), resolution.used(), entry.getKey());
			assertEquals(TargetFormat.Reason.EXACT, resolution.reason(), entry.getKey());
			assertFalse(resolution.alphaAdded(), entry.getKey());
			assertEquals(entry.getKey(), resolution.declared());
		}
	}

	@Test
	void everyFormatOfTheEnumIsReachableByExactlyItsOwnName() {
		assertEquals(39, TargetFormat.values().length);
		assertEquals(EnumSet.allOf(TargetFormat.class), Set.copyOf(EXACT.values()));
	}

	@Test
	void aNameIsReadInAnyCaseAndWithoutItsSurroundingSpace() {
		assertEquals(TargetFormat.RGBA16_FLOAT, TargetFormat.resolve("rgba16f").used());
		assertEquals(TargetFormat.RGBA16_FLOAT, TargetFormat.resolve("Rgba16F").used());
		assertEquals(TargetFormat.R8_UNORM, TargetFormat.resolve(" R8\t").used());
		assertEquals(TargetFormat.RG11B10_FLOAT, TargetFormat.resolve("r11f_g11f_b10f").used());
		assertEquals(TargetFormat.R8_UINT, TargetFormat.resolve("r8ui").used());
	}

	@Test
	void theDeclaredNameIsKeptAsWrittenEvenWhenItIsTrimmedToLookItUp() {
		assertEquals(" rgba16f ", TargetFormat.resolve(" rgba16f ").declared());
	}

	@Test
	void aThreeComponentFormatIsWidenedToFourAndSaysSo() {
		Map<String, TargetFormat> promoted = new LinkedHashMap<>();
		promoted.put("RGB8", TargetFormat.RGBA8_UNORM);
		promoted.put("RGB8_SNORM", TargetFormat.RGBA8_SNORM);
		promoted.put("RGB16", TargetFormat.RGBA16_UNORM);
		promoted.put("RGB16_SNORM", TargetFormat.RGBA16_SNORM);
		promoted.put("RGB16F", TargetFormat.RGBA16_FLOAT);
		promoted.put("RGB32F", TargetFormat.RGBA32_FLOAT);
		promoted.put("RGB8I", TargetFormat.RGBA8_SINT);
		promoted.put("RGB8UI", TargetFormat.RGBA8_UINT);
		promoted.put("RGB16I", TargetFormat.RGBA16_SINT);
		promoted.put("RGB16UI", TargetFormat.RGBA16_UINT);
		promoted.put("RGB32I", TargetFormat.RGBA32_SINT);
		promoted.put("RGB32UI", TargetFormat.RGBA32_UINT);

		for (Map.Entry<String, TargetFormat> entry : promoted.entrySet()) {
			TargetFormat.Resolution resolution = TargetFormat.resolve(entry.getKey());

			assertEquals(entry.getValue(), resolution.used(), entry.getKey());
			assertEquals(TargetFormat.Reason.PROMOTED, resolution.reason(), entry.getKey());
			assertTrue(resolution.alphaAdded(), entry.getKey());
			assertEquals(4, resolution.used().components(), entry.getKey());
			// The widened format keeps the integer-ness and the channel width of the one asked for.
			assertEquals(entry.getKey().endsWith("I"), resolution.used().integer(), entry.getKey());
		}
	}

	@Test
	void theRelicsOfTheFixedFunctionEraAreReplacedByAModernFormat() {
		assertReplaced("RGBA2", TargetFormat.RGBA8_UNORM, false);
		assertReplaced("RGBA4", TargetFormat.RGBA8_UNORM, false);
		assertReplaced("RGB5_A1", TargetFormat.RGBA8_UNORM, false);
		assertReplaced("R3_G3_B2", TargetFormat.RGBA8_UNORM, true);
		assertReplaced("RGB565", TargetFormat.RGBA8_UNORM, true);
		assertReplaced("RGB9_E5", TargetFormat.RGBA16_FLOAT, true);
	}

	private static void assertReplaced(String name, TargetFormat used, boolean alphaAdded) {
		TargetFormat.Resolution resolution = TargetFormat.resolve(name);

		assertEquals(used, resolution.used(), name);
		assertEquals(TargetFormat.Reason.REPLACED, resolution.reason(), name);
		assertEquals(alphaAdded, resolution.alphaAdded(), name);
	}

	@Test
	void anUnreadableNameIsTheDefaultFormatAndUnknownNeverAnError() {
		for (String name : new String[] {"", "FOO", "RGB", "RGBA32", "RGBA8_UINT", "R11F_G11F_B10", "RGB8 A", "\u0130"}) {
			TargetFormat.Resolution resolution = TargetFormat.resolve(name);

			assertEquals(TargetFormat.RGBA8_UNORM, resolution.used(), name);
			assertEquals(TargetFormat.Reason.UNKNOWN, resolution.reason(), name);
			assertFalse(resolution.alphaAdded(), name);
			assertEquals(name, resolution.declared());
		}
	}

	@Test
	void whatAPackWritesWhenItWritesNothingIsRgbaEightUnorm() {
		TargetFormat.Resolution resolution = TargetFormat.defaultFormat();

		assertEquals("RGBA", resolution.declared());
		assertEquals(TargetFormat.RGBA8_UNORM, resolution.used());
		assertEquals(TargetFormat.Reason.EXACT, resolution.reason());
		assertFalse(resolution.alphaAdded());
	}

	@Test
	void theSizesFollowTheNameOfEachFormat() {
		for (TargetFormat format : TargetFormat.values()) {
			String name = format.name();
			if (name.startsWith("RGB10A2")) {
				assertEquals(4, format.components(), name);
				assertEquals(4, format.bytesPerPixel(), name);
			} else if (name.equals("RG11B10_FLOAT")) {
				assertEquals(3, format.components(), name);
				assertEquals(4, format.bytesPerPixel(), name);
			} else {
				int components = name.startsWith("RGBA") ? 4 : name.startsWith("RG") ? 2 : 1;
				int bits = Integer.parseInt(name.replaceAll("^[A-Z]+(\\d+)_.*$", "$1"));

				assertEquals(components, format.components(), name);
				assertEquals(components * bits / 8, format.bytesPerPixel(), name);
			}

			assertEquals(name.endsWith("_UINT") || name.endsWith("_SINT"), format.integer(), name);
		}
	}
}
