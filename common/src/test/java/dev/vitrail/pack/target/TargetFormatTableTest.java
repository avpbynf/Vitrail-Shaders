package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.model.TargetFormat.Reason;
import dev.vitrail.pack.model.TargetFormat.Resolution;

import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds the table a colour target's format is allocated from to the names the OptiFine format
 * defines, the way the docs describe it: what a pack may write, what each name becomes, and what the
 * plan estimates it to cost.
 * <p>
 * The rows below are written out by hand from that list and from the rule in
 * {@code docs/internals/render-targets.md}: the three component formats are promoted to their four
 * component variant with the alpha channel noted, the relics of the fixed function era are
 * replaced by the nearest modern format, and everything else is exact. The bytes a pixel takes are not
 * in the rows: they are derived here from the name, as a second table that has to agree with the one
 * the plan carries.
 */
class TargetFormatTableTest {

	private static final Pattern PLAIN = Pattern.compile("^(R|RG|RGBA)(8|16|32)_(UNORM|SNORM|UINT|SINT|FLOAT)$");

	private record Row(String written, TargetFormat used, Reason reason, boolean alphaAdded) {
	}

	private static Row exact(String written, TargetFormat used) {
		return new Row(written, used, Reason.EXACT, false);
	}

	private static Row promoted(String written, TargetFormat used) {
		return new Row(written, used, Reason.PROMOTED, true);
	}

	private static Row replaced(String written, TargetFormat used, boolean alpha) {
		return new Row(written, used, Reason.REPLACED, alpha);
	}

	private static final List<Row> TABLE = List.of(
			exact("RGBA", TargetFormat.RGBA8_UNORM),
			exact("R8", TargetFormat.R8_UNORM),
			exact("RG8", TargetFormat.RG8_UNORM),
			exact("RGBA8", TargetFormat.RGBA8_UNORM),
			exact("R8_SNORM", TargetFormat.R8_SNORM),
			exact("RG8_SNORM", TargetFormat.RG8_SNORM),
			exact("RGBA8_SNORM", TargetFormat.RGBA8_SNORM),
			exact("R16", TargetFormat.R16_UNORM),
			exact("RG16", TargetFormat.RG16_UNORM),
			exact("RGBA16", TargetFormat.RGBA16_UNORM),
			exact("R16_SNORM", TargetFormat.R16_SNORM),
			exact("RG16_SNORM", TargetFormat.RG16_SNORM),
			exact("RGBA16_SNORM", TargetFormat.RGBA16_SNORM),
			exact("R16F", TargetFormat.R16_FLOAT),
			exact("RG16F", TargetFormat.RG16_FLOAT),
			exact("RGBA16F", TargetFormat.RGBA16_FLOAT),
			exact("R32F", TargetFormat.R32_FLOAT),
			exact("RG32F", TargetFormat.RG32_FLOAT),
			exact("RGBA32F", TargetFormat.RGBA32_FLOAT),
			exact("R8I", TargetFormat.R8_SINT),
			exact("RG8I", TargetFormat.RG8_SINT),
			exact("RGBA8I", TargetFormat.RGBA8_SINT),
			exact("R8UI", TargetFormat.R8_UINT),
			exact("RG8UI", TargetFormat.RG8_UINT),
			exact("RGBA8UI", TargetFormat.RGBA8_UINT),
			exact("R16I", TargetFormat.R16_SINT),
			exact("RG16I", TargetFormat.RG16_SINT),
			exact("RGBA16I", TargetFormat.RGBA16_SINT),
			exact("R16UI", TargetFormat.R16_UINT),
			exact("RG16UI", TargetFormat.RG16_UINT),
			exact("RGBA16UI", TargetFormat.RGBA16_UINT),
			exact("R32I", TargetFormat.R32_SINT),
			exact("RG32I", TargetFormat.RG32_SINT),
			exact("RGBA32I", TargetFormat.RGBA32_SINT),
			exact("R32UI", TargetFormat.R32_UINT),
			exact("RG32UI", TargetFormat.RG32_UINT),
			exact("RGBA32UI", TargetFormat.RGBA32_UINT),
			exact("RGB10_A2", TargetFormat.RGB10A2_UNORM),
			exact("RGB10_A2UI", TargetFormat.RGB10A2_UINT),
			exact("R11F_G11F_B10F", TargetFormat.RG11B10_FLOAT),

			// Three components and nothing else: widened to four, and the alpha is said to be new.
			promoted("RGB8", TargetFormat.RGBA8_UNORM),
			promoted("RGB8_SNORM", TargetFormat.RGBA8_SNORM),
			promoted("RGB16", TargetFormat.RGBA16_UNORM),
			promoted("RGB16_SNORM", TargetFormat.RGBA16_SNORM),
			promoted("RGB16F", TargetFormat.RGBA16_FLOAT),
			promoted("RGB32F", TargetFormat.RGBA32_FLOAT),
			promoted("RGB8I", TargetFormat.RGBA8_SINT),
			promoted("RGB8UI", TargetFormat.RGBA8_UINT),
			promoted("RGB16I", TargetFormat.RGBA16_SINT),
			promoted("RGB16UI", TargetFormat.RGBA16_UINT),
			promoted("RGB32I", TargetFormat.RGBA32_SINT),
			promoted("RGB32UI", TargetFormat.RGBA32_UINT),

			// Relics with no modern equivalent, each landing on a format that holds at least as much.
			replaced("RGBA2", TargetFormat.RGBA8_UNORM, false),
			replaced("RGBA4", TargetFormat.RGBA8_UNORM, false),
			replaced("RGB5_A1", TargetFormat.RGBA8_UNORM, false),
			replaced("R3_G3_B2", TargetFormat.RGBA8_UNORM, true),
			replaced("RGB565", TargetFormat.RGBA8_UNORM, true),
			replaced("RGB9_E5", TargetFormat.RGBA16_FLOAT, true));

	@Test
	void everyNameAPackMayWriteBecomesWhatTheDocsSay() {
		for (Row row : TABLE) {
			Resolution resolved = TargetFormat.resolve(row.written());

			assertEquals(row.used(), resolved.used(), row.written());
			assertEquals(row.reason(), resolved.reason(), row.written());
			assertEquals(row.alphaAdded(), resolved.alphaAdded(), row.written());
			assertEquals(row.written(), resolved.declared(), row.written());
		}
	}

	/** Nothing in the enum is unreachable, and nothing outside it is handed out. */
	@Test
	void everyFormatOfTheDeviceIsTheAnswerToSomeName() {
		EnumSet<TargetFormat> reached = EnumSet.noneOf(TargetFormat.class);
		TABLE.forEach(row -> reached.add(row.used()));

		assertEquals(EnumSet.allOf(TargetFormat.class), reached);
		assertEquals(39, TargetFormat.values().length);
	}

	/**
	 * The three component formats are not among the constants at all, and only a promotion or a
	 * replacement ever adds an alpha channel the pack did not ask for.
	 */
	@Test
	void aFormatThatGainsAnAlphaChannelIsNeverAnExactOne() {
		for (Row row : TABLE) {
			if (row.alphaAdded()) {
				assertTrue(row.reason() == Reason.PROMOTED || row.reason() == Reason.REPLACED, row.written());
			}

			if (row.reason() == Reason.EXACT) {
				assertFalse(row.alphaAdded(), row.written());
			}
		}
	}

	@Test
	void theNameIsReadWithoutRegardToCaseOrPaddingAndAnUnknownOneCostsALineNotAPack() {
		assertEquals(TargetFormat.RGBA16_FLOAT, TargetFormat.resolve("rgba16f").used());
		assertEquals(TargetFormat.RGBA16_FLOAT, TargetFormat.resolve("  RgBa16F ").used());
		assertEquals(Reason.EXACT, TargetFormat.resolve("rgba16f").reason());

		for (String name : List.of("", "RGBA16X", "RGB", "GL_RGBA16F", "RGBA16F;", "16F")) {
			Resolution resolved = TargetFormat.resolve(name);
			assertEquals(TargetFormat.RGBA8_UNORM, resolved.used(), name);
			assertEquals(Reason.UNKNOWN, resolved.reason(), name);
			assertFalse(resolved.alphaAdded(), name);
			assertEquals(name, resolved.declared(), name);
		}

		assertEquals(new Resolution("RGBA", TargetFormat.RGBA8_UNORM, Reason.EXACT, false),
				TargetFormat.defaultFormat());
	}

	/**
	 * The bytes a pixel takes are read off a table and never recomputed from the channel widths, and
	 * the table is checked here against the widths for every format the widths can answer for: a packed
	 * word is four bytes whatever its channels suggest.
	 */
	@Test
	void theBytesAPixelTakesAgreeWithTheChannelWidthsWhereThereAreChannels() {
		for (TargetFormat format : TargetFormat.values()) {
			String name = format.name();
			Matcher plain = PLAIN.matcher(name);
			if (plain.matches()) {
				int channels = switch (plain.group(1)) {
					case "R" -> 1;
					case "RG" -> 2;
					default -> 4;
				};

				assertEquals(channels, format.components(), name);
				assertEquals(channels * Integer.parseInt(plain.group(2)) / 8, format.bytesPerPixel(), name);
				assertEquals(name.endsWith("UINT") || name.endsWith("SINT"), format.integer(), name);
				continue;
			}

			// The packed ones: one thirty two bit word, the logical channels four and three.
			assertEquals(4, format.bytesPerPixel(), name);
			assertEquals(name.startsWith("RGB10A2") ? 4 : 3, format.components(), name);
			assertEquals(name.endsWith("UINT"), format.integer(), name);
		}
	}
}
