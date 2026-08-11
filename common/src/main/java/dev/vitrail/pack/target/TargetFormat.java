package dev.vitrail.pack.target;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The colour formats a target can be given, and what each name a pack may write becomes.
 * <p>
 * The constants are exactly the ones a device is trusted with, and their names are exactly the
 * names of {@code com.mojang.blaze3d.GpuFormat}. That is on purpose twice over: it makes the
 * translation to a real format mechanical, with nothing left to decide on the far side, and it
 * makes what this class prints comparable line for line with what the engine logs in game.
 * <p>
 * Three component formats are deliberately absent from the constants even though the game names
 * them. They exist in the enum and are all but never usable as a colour attachment on desktop
 * hardware, so they are promoted to their four component variant here, once, in a table nobody
 * can go round. Twenty six directives across five packs land on that path, so it is not an edge
 * case; a target the driver refuses to attach fails with nothing to read in the log.
 * <p>
 * The promotion has a consequence a pack cannot see and this class records: sampling a three
 * component texture in GL always yields {@code a = 1.0}, while a promoted one yields whatever
 * the clear put there. {@link Resolution#alphaAdded()} is what lets the clear be corrected.
 * <p>
 * The table is transcribed from Iris's {@code InternalTextureFormat}, which is copyright the
 * Iris contributors and licensed under the GNU LGPL version 3, the same licence as this project.
 * Read on 1 August 2026. The promotion and the replacements are ours; Iris hands the name to GL
 * and lets the driver sort it out, which is not an option here.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
public enum TargetFormat {

	R8_UNORM(1, 1, false),
	R8_SNORM(1, 1, false),
	RG8_UNORM(2, 2, false),
	RG8_SNORM(2, 2, false),
	RGBA8_UNORM(4, 4, false),
	RGBA8_SNORM(4, 4, false),
	R16_UNORM(1, 2, false),
	R16_SNORM(1, 2, false),
	RG16_UNORM(2, 4, false),
	RG16_SNORM(2, 4, false),
	RGBA16_UNORM(4, 8, false),
	RGBA16_SNORM(4, 8, false),
	R8_UINT(1, 1, true),
	R8_SINT(1, 1, true),
	RG8_UINT(2, 2, true),
	RG8_SINT(2, 2, true),
	RGBA8_UINT(4, 4, true),
	RGBA8_SINT(4, 4, true),
	R16_UINT(1, 2, true),
	R16_SINT(1, 2, true),
	RG16_UINT(2, 4, true),
	RG16_SINT(2, 4, true),
	RGBA16_UINT(4, 8, true),
	RGBA16_SINT(4, 8, true),
	R32_UINT(1, 4, true),
	R32_SINT(1, 4, true),
	RG32_UINT(2, 8, true),
	RG32_SINT(2, 8, true),
	RGBA32_UINT(4, 16, true),
	RGBA32_SINT(4, 16, true),
	R16_FLOAT(1, 2, false),
	RG16_FLOAT(2, 4, false),
	RGBA16_FLOAT(4, 8, false),
	R32_FLOAT(1, 4, false),
	RG32_FLOAT(2, 8, false),
	RGBA32_FLOAT(4, 16, false),
	RGB10A2_UNORM(4, 4, false),
	RGB10A2_UINT(4, 4, true),
	RG11B10_FLOAT(3, 4, false);

	/** What the pack gets when it writes nothing, and what an unreadable name falls back to. */
	private static final TargetFormat FALLBACK = RGBA8_UNORM;

	private static final String DEFAULT_NAME = "RGBA";

	private static final Map<String, Entry> DECLARED = table();

	private final int components;
	private final int bytesPerPixel;
	private final boolean integer;

	TargetFormat(int components, int bytesPerPixel, boolean integer) {
		this.components = components;
		this.bytesPerPixel = bytesPerPixel;
		this.integer = integer;
	}

	public enum Reason { EXACT, PROMOTED, REPLACED, UNKNOWN }

	/**
	 * What a declared format was resolved to, and what made the difference.
	 *
	 * @param declared    the name the pack wrote, as written
	 * @param alphaAdded  the declared format had no alpha channel and the allocated one does, so
	 *                    a GL sampler would have read 1.0 where a Vulkan one reads the clear value
	 */
	public record Resolution(String declared, TargetFormat used, Reason reason, boolean alphaAdded) {
	}

	/** Case insensitive, as Iris is. Never throws, never returns null. */
	public static Resolution resolve(String declared) {
		Entry entry = DECLARED.get(declared.trim().toUpperCase(Locale.ROOT));
		if (entry == null) {
			// A typo in a downloaded pack costs one line of log, never a load that fails.
			return new Resolution(declared, FALLBACK, Reason.UNKNOWN, false);
		}

		return new Resolution(declared, entry.used(), entry.reason(), entry.alphaAdded());
	}

	/** What a target with no directive gets: RGBA8_UNORM, reported as {@code RGBA}. */
	public static Resolution defaultFormat() {
		return new Resolution(DEFAULT_NAME, FALLBACK, Reason.EXACT, false);
	}

	/** Logical channels: four for RGB10A2, three for RG11B10. */
	public int components() {
		return this.components;
	}

	public int bytesPerPixel() {
		return this.bytesPerPixel;
	}

	/** Integer targets cannot be filtered. */
	public boolean integer() {
		return this.integer;
	}

	private static Map<String, Entry> table() {
		Map<String, Entry> declared = new LinkedHashMap<>();

		// Iris's implicit default, which packs may also write out.
		exact(declared, DEFAULT_NAME, RGBA8_UNORM);

		exact(declared, "R8", R8_UNORM);
		exact(declared, "RG8", RG8_UNORM);
		exact(declared, "RGBA8", RGBA8_UNORM);
		exact(declared, "R8_SNORM", R8_SNORM);
		exact(declared, "RG8_SNORM", RG8_SNORM);
		exact(declared, "RGBA8_SNORM", RGBA8_SNORM);
		exact(declared, "R16", R16_UNORM);
		exact(declared, "RG16", RG16_UNORM);
		exact(declared, "RGBA16", RGBA16_UNORM);
		exact(declared, "R16_SNORM", R16_SNORM);
		exact(declared, "RG16_SNORM", RG16_SNORM);
		exact(declared, "RGBA16_SNORM", RGBA16_SNORM);
		exact(declared, "R16F", R16_FLOAT);
		exact(declared, "RG16F", RG16_FLOAT);
		exact(declared, "RGBA16F", RGBA16_FLOAT);
		exact(declared, "R32F", R32_FLOAT);
		exact(declared, "RG32F", RG32_FLOAT);
		exact(declared, "RGBA32F", RGBA32_FLOAT);
		exact(declared, "R8I", R8_SINT);
		exact(declared, "RG8I", RG8_SINT);
		exact(declared, "RGBA8I", RGBA8_SINT);
		exact(declared, "R8UI", R8_UINT);
		exact(declared, "RG8UI", RG8_UINT);
		exact(declared, "RGBA8UI", RGBA8_UINT);
		exact(declared, "R16I", R16_SINT);
		exact(declared, "RG16I", RG16_SINT);
		exact(declared, "RGBA16I", RGBA16_SINT);
		exact(declared, "R16UI", R16_UINT);
		exact(declared, "RG16UI", RG16_UINT);
		exact(declared, "RGBA16UI", RGBA16_UINT);
		exact(declared, "R32I", R32_SINT);
		exact(declared, "RG32I", RG32_SINT);
		exact(declared, "RGBA32I", RGBA32_SINT);
		exact(declared, "R32UI", R32_UINT);
		exact(declared, "RG32UI", RG32_UINT);
		exact(declared, "RGBA32UI", RGBA32_UINT);
		exact(declared, "RGB10_A2", RGB10A2_UNORM);
		exact(declared, "RGB10_A2UI", RGB10A2_UINT);
		// Three channels and no alpha, and still exact: the packing is one thirty two bit word,
		// which every device attaches.
		exact(declared, "R11F_G11F_B10F", RG11B10_FLOAT);

		// Three components, widened. Twenty six of the corpus's declarations land here.
		promoted(declared, "RGB8", RGBA8_UNORM);
		promoted(declared, "RGB8_SNORM", RGBA8_SNORM);
		promoted(declared, "RGB16", RGBA16_UNORM);
		promoted(declared, "RGB16_SNORM", RGBA16_SNORM);
		promoted(declared, "RGB16F", RGBA16_FLOAT);
		promoted(declared, "RGB32F", RGBA32_FLOAT);
		promoted(declared, "RGB8I", RGBA8_SINT);
		promoted(declared, "RGB8UI", RGBA8_UINT);
		promoted(declared, "RGB16I", RGBA16_SINT);
		promoted(declared, "RGB16UI", RGBA16_UINT);
		promoted(declared, "RGB32I", RGBA32_SINT);
		promoted(declared, "RGB32UI", RGBA32_UINT);

		// Relics of the fixed function era with no modern equivalent at all. Every one of them
		// gains precision here, which is what the driver has been doing for them for years
		// anyway, and the corpus uses exactly one of them once.
		replaced(declared, "RGBA2", RGBA8_UNORM, false);
		replaced(declared, "RGBA4", RGBA8_UNORM, false);
		replaced(declared, "R3_G3_B2", RGBA8_UNORM, true);
		replaced(declared, "RGB5_A1", RGBA8_UNORM, false);
		replaced(declared, "RGB565", RGBA8_UNORM, true);
		// The one that loses something. Nine bits of mantissa per channel with a shared exponent,
		// so half floats keep more of it than the eleven and ten bit packing would.
		replaced(declared, "RGB9_E5", RGBA16_FLOAT, true);

		return Map.copyOf(declared);
	}

	private static void exact(Map<String, Entry> declared, String name, TargetFormat used) {
		declared.put(name, new Entry(used, Reason.EXACT, false));
	}

	private static void promoted(Map<String, Entry> declared, String name, TargetFormat used) {
		declared.put(name, new Entry(used, Reason.PROMOTED, true));
	}

	private static void replaced(Map<String, Entry> declared, String name, TargetFormat used,
			boolean alphaAdded) {
		declared.put(name, new Entry(used, Reason.REPLACED, alphaAdded));
	}

	private record Entry(TargetFormat used, Reason reason, boolean alphaAdded) {
	}
}
