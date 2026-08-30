package dev.vitrail.settings;

import java.util.Locale;

/**
 * How often the shadow map is re-recorded.
 * <p>
 * The default is every frame, which is what Iris does. The other two keep the last map and skip
 * the walk: an optional extra delay the player chooses, on top of the map already being one frame
 * late. Standing still under either of those is the same picture; moving adds lag the pack's TAA
 * already smears.
 */
public enum ShadowRefresh {

	/** Re-record every frame. Iris parity, and the default. */
	EVERY_FRAME("every"),

	/** Re-record every other frame. */
	EVERY_TWO_FRAMES("2"),

	/** Re-record only when the camera has moved. */
	WHEN_CAMERA_MOVES("moved");

	/** What a missing or unreadable line becomes. */
	public static final ShadowRefresh DEFAULT = EVERY_FRAME;

	private final String word;

	ShadowRefresh(String word) {
		this.word = word;
	}

	/** What {@code pack.txt} stores. */
	public String word() {
		return this.word;
	}

	/**
	 * A word or a number from a hand-edited file. Anything else is the default, like the other
	 * lines of that file: one bad character must not cost the player the pack they had chosen.
	 */
	public static ShadowRefresh parse(String value) {
		String text = value.trim().toLowerCase(Locale.ROOT);
		return switch (text) {
			case "2", "two" -> EVERY_TWO_FRAMES;
			case "0", "moved", "camera" -> WHEN_CAMERA_MOVES;
			case "1", "every" -> EVERY_FRAME;
			default -> DEFAULT;
		};
	}
}
