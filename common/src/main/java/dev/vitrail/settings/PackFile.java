package dev.vitrail.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * What {@code vitrail/pack.txt} carries: which pack was chosen, whether shaders are on at all, how
 * far the player asked the light to reach, what fraction of the window the world renders at, and
 * how much of the shadow map the pack asked for is actually drawn.
 * <p>
 * <b>The last three are not about a pack, and they are here for the reason the other two are.</b>
 * Iris keeps its own in the same properties file as {@code shaderPack} and {@code enableShaders},
 * {@code maxShadowRenderDistance} in {@code config/IrisConfig.java:178,206}, because it is the same
 * kind of thing: one number the player set once, that outlives whichever pack is loaded and has to
 * be there before one is. A file of its own would be a second file to find, to write and to keep in
 * step. The render scale and the shadow map scale have no counterpart in Iris and follow the same
 * rule for the same reason.
 * <p>
 * <b>Two facts and not one, and that is what the screen's toggle needs.</b> Iris keeps
 * {@code shaderPack} and {@code enableShaders} apart in its own properties file, so turning shaders
 * off there leaves the pack chosen and turning them back on returns to it. A single line cannot do
 * that: the moment {@code none} is written, which pack the player had is gone, and coming back lands
 * on whatever the list happens to show first.
 * <p>
 * <b>The old spelling still reads.</b> A file whose content is one bare word with no {@code =} in it
 * is that word, which is every file this mod has written until now, and the bare word {@code none}
 * is shaders off with no pack remembered, which is what it has always meant. So a player who had
 * either keeps what they had, and nothing has to be migrated.
 * <p>
 * Nothing here touches Minecraft, which is the rule for this whole package: it is what lets the
 * settings be run against the pack corpus without starting the game.
 */
public record PackFile(String name, boolean enabled, int shadowDistance, int renderScale,
		int shadowMapScale) {

	/** The word that means no pack rather than the name of one, kept from the one line format. */
	public static final String NONE = "none";

	private static final String NAME_KEY = "pack";
	private static final String ENABLED_KEY = "enabled";
	private static final String SHADOW_DISTANCE_KEY = "shadowdistance";
	private static final String RENDER_SCALE_KEY = "renderscale";
	private static final String SHADOW_MAP_SCALE_KEY = "shadowmapscale";

	/**
	 * The range the shadow distance is offered and stored over, in CHUNKS, and Iris's own: zero to
	 * thirty-two, thirty-two being both the default and the largest render distance the game has
	 * ({@code gui/option/IrisVideoSettings.java:15,50}). At the top the setting bounds nothing,
	 * which is what makes an untouched one free; at the bottom the light gathers nothing at all.
	 */
	public static final int MIN_SHADOW_DISTANCE = 0;
	public static final int MAX_SHADOW_DISTANCE = 32;
	public static final int DEFAULT_SHADOW_DISTANCE = MAX_SHADOW_DISTANCE;

	/**
	 * The range the render scale is offered and stored over, as a percentage of the window on each
	 * axis, applied while a pack draws. At the top the world renders at the window's own size and
	 * nothing of the scale runs, which is what makes an untouched one free; the floor is where the
	 * upscale stops reading as a picture and starts reading as a broken pack.
	 */
	public static final int MIN_RENDER_SCALE = 25;
	public static final int MAX_RENDER_SCALE = 100;
	public static final int DEFAULT_RENDER_SCALE = MAX_RENDER_SCALE;

	/**
	 * The range the shadow map scale is offered and stored over, as a percentage of the square
	 * resolution the pack declared, on each axis. At the top the map is exactly the one the pack
	 * asked for and nothing of the setting runs, which is what makes an untouched one free; the
	 * floor is where a shadow edge stops being an edge.
	 * <p>
	 * <b>Iris has no counterpart, and this is a setting rather than something the render scale
	 * drags along with it.</b> Iris allocates the map at the pack's own directive and at no other
	 * number ({@code shadows/ShadowRenderTargets.java:65}), and its video settings carry two player
	 * facts and no third, the shadow distance and the colour space
	 * ({@code gui/option/IrisVideoSettings.java:15-16}). So the case for this one is the render
	 * scale's and not the distance's: it belongs to the machine rather than to the pack, it changes
	 * nothing at all while it sits at its default, and a player has to reach for it.
	 * <p>
	 * <b>What it moves is the number the PACK IS TOLD, and that is the whole of why it is
	 * safe.</b> The declaration the pack makes of its map size is rewritten before a line of it is
	 * translated, so the pack computes its filter radius, its depth bias and its texel coordinates
	 * against the map it really gets. Where the pack smooths its shadows a smaller map is then a
	 * wider penumbra rather than a blockier one, the radius being a fraction of the map; where it
	 * takes a single sample there is no radius to widen and the edge simply coarsens, which some
	 * quality profiles of the corpus do. Either is an image the pack's author never saw and a
	 * coherent one. Allocating smaller
	 * while leaving the declaration alone is the other thing entirely, and it is a wrong picture:
	 * the corpus reads that map with texel coordinates taken from the declared number.
	 */
	public static final int MIN_SHADOW_MAP_SCALE = 25;
	public static final int MAX_SHADOW_MAP_SCALE = 100;
	public static final int DEFAULT_SHADOW_MAP_SCALE = MAX_SHADOW_MAP_SCALE;

	/** Nothing chosen. Shaders are on, so choosing a pack is all it takes to draw one. */
	public static final PackFile EMPTY = new PackFile("", true, DEFAULT_SHADOW_DISTANCE,
			DEFAULT_RENDER_SCALE, DEFAULT_SHADOW_MAP_SCALE);

	public PackFile {
		name = name.trim();
		// Clamped here rather than where the file is read, so that a number typed by hand and a
		// number arriving from a screen are held to the same range: the value is served to the
		// culling as a distance and a negative one there means something else entirely. Both scales
		// under the same rule, their floors being where the picture stops being one.
		shadowDistance = Math.clamp(shadowDistance, MIN_SHADOW_DISTANCE, MAX_SHADOW_DISTANCE);
		renderScale = Math.clamp(renderScale, MIN_RENDER_SCALE, MAX_RENDER_SCALE);
		shadowMapScale = Math.clamp(shadowMapScale, MIN_SHADOW_MAP_SCALE, MAX_SHADOW_MAP_SCALE);
	}

	/**
	 * What the file says, or {@link #EMPTY} when it is not there. A line that is neither a comment nor
	 * one of the five keys is ignored rather than refused: this file is edited by hand.
	 * <p>
	 * Decoded through the {@code String} constructor rather than through {@code Files.readString},
	 * which THROWS on a byte that is not UTF-8. The rule the lines below are read under, that
	 * one bad character must not cost the player the pack they had chosen, does not hold if the read
	 * itself cannot survive one: what is unreadable is one character of one value, and every other
	 * line still says what it said. A byte order mark is taken off for the same reason it is in
	 * {@code SettingsLayers}: nothing throws on it, and left where it is it rides on the first key,
	 * which here is the pack's own name.
	 * <p>
	 * Handed the file rather than the game directory, so that where it lives stays the render layer's
	 * business: this package names no folder of the installation and imports nothing that does.
	 */
	public static PackFile read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return EMPTY;
		}

		String content = text(file);
		// The one line format, which is what every file written before this class looks like. Told
		// apart by having no key at all rather than by counting lines, so that a stray blank line or a
		// trailing newline does not change which format a file is read as.
		if (!content.contains("=")) {
			String bare = content.trim();

			return NONE.equalsIgnoreCase(bare)
					? new PackFile("", false, DEFAULT_SHADOW_DISTANCE, DEFAULT_RENDER_SCALE,
							DEFAULT_SHADOW_MAP_SCALE)
					: new PackFile(bare, true, DEFAULT_SHADOW_DISTANCE, DEFAULT_RENDER_SCALE,
							DEFAULT_SHADOW_MAP_SCALE);
		}

		String name = "";
		boolean enabled = true;
		int shadowDistance = DEFAULT_SHADOW_DISTANCE;
		int renderScale = DEFAULT_RENDER_SCALE;
		int shadowMapScale = DEFAULT_SHADOW_MAP_SCALE;
		for (String line : content.lines().toList()) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}

			int split = trimmed.indexOf('=');
			if (split < 0) {
				continue;
			}

			String key = trimmed.substring(0, split).trim().toLowerCase(Locale.ROOT);
			String value = trimmed.substring(split + 1).trim();
			switch (key) {
				case NAME_KEY -> name = value;
				// Anything that is not the word for true is false, which is how the pack format's own
				// booleans read and what keeps a typo from turning shaders on by accident.
				case ENABLED_KEY -> enabled = Boolean.parseBoolean(value);
				// A number that does not parse keeps the default rather than refusing the file, which
				// is how every other line here is read: this file is edited by hand, and one bad
				// character must not cost the player the pack they had chosen.
				case SHADOW_DISTANCE_KEY -> shadowDistance = number(value, shadowDistance);
				case RENDER_SCALE_KEY -> renderScale = number(value, renderScale);
				case SHADOW_MAP_SCALE_KEY -> shadowMapScale = number(value, shadowMapScale);
				default -> {
				}
			}
		}

		return new PackFile(name, enabled, shadowDistance, renderScale, shadowMapScale);
	}

	/** The file's text, decoded rather than refused, with any byte order mark taken off. */
	private static String text(Path file) throws IOException {
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);

		return text.startsWith("\uFEFF") ? text.substring(1) : text;
	}

	private static int number(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * Writes every key, always, so that the file a player opens says what state it is in rather than
	 * leaving any of them to be inferred from its absence.
	 * <p>
	 * <b>Two things write this file and neither owns all of it</b>, the settings screen picking a
	 * pack and the video settings moving one of its three numbers. Whichever writes has to carry the
	 * other's lines through, which is why both go through a read of the file first rather than
	 * building a record out of what they happen to hold.
	 * <p>
	 * Through a temporary and an {@code ATOMIC_MOVE} in the same folder, so that a crash between the
	 * two writes cannot leave a truncated file: what this one holds is the pack the player chose,
	 * and half a line of it reads as no pack at all. {@code SettingsFile.write} has carried the same
	 * pair since it was written, over a file that matters less.
	 * <p>
	 * In LF and without a byte order mark, like every other file this mod writes.
	 */
	public static void write(Path file, PackFile chosen) throws IOException {
		Files.createDirectories(file.getParent());

		Path temporary = file.resolveSibling(file.getFileName() + ".part");
		Files.writeString(temporary,
				NAME_KEY + "=" + chosen.name() + "\n"
						+ ENABLED_KEY + "=" + chosen.enabled() + "\n"
						+ SHADOW_DISTANCE_KEY + "=" + chosen.shadowDistance() + "\n"
						+ RENDER_SCALE_KEY + "=" + chosen.renderScale() + "\n"
						+ SHADOW_MAP_SCALE_KEY + "=" + chosen.shadowMapScale() + "\n",
				StandardCharsets.UTF_8);
		try {
			try {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			// A move that failed for any other reason would leave the .part beside the file for
			// ever, and nothing else ever looks at it.
			try {
				Files.deleteIfExists(temporary);
			} catch (IOException swallowed) {
				e.addSuppressed(swallowed);
			}
			throw e;
		}
	}

	/**
	 * Whether there is anything to look for: shaders on, and a name at all.
	 * <p>
	 * Deliberately not a test for {@link #NONE}, which belongs where the name is matched against the
	 * folder rather than here: that word is read after the whole names and before the fragments, so
	 * that a folder really called {@code none} stays reachable. Testing it here would take that
	 * folder away.
	 */
	public boolean wantsPack() {
		return this.enabled && !this.name.isEmpty();
	}

	/** Whether the name is the word that means no pack rather than the name of one. */
	public boolean namesNone() {
		return NONE.equalsIgnoreCase(this.name);
	}

	public PackFile withName(String name) {
		return new PackFile(name, this.enabled, this.shadowDistance, this.renderScale,
				this.shadowMapScale);
	}

	public PackFile withEnabled(boolean enabled) {
		return new PackFile(this.name, enabled, this.shadowDistance, this.renderScale,
				this.shadowMapScale);
	}

	/** Both halves of a pack choice at once, leaving whatever else the file carries where it is. */
	public PackFile withChoice(String name, boolean enabled) {
		return new PackFile(name, enabled, this.shadowDistance, this.renderScale,
				this.shadowMapScale);
	}

	public PackFile withShadowDistance(int chunks) {
		return new PackFile(this.name, this.enabled, chunks, this.renderScale, this.shadowMapScale);
	}

	public PackFile withRenderScale(int percent) {
		return new PackFile(this.name, this.enabled, this.shadowDistance, percent,
				this.shadowMapScale);
	}

	public PackFile withShadowMapScale(int percent) {
		return new PackFile(this.name, this.enabled, this.shadowDistance, this.renderScale, percent);
	}
}
