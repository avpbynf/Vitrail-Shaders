package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.mixin.game.RenderPipelinesAccessor;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

/**
 * The pipelines of the game's own table that Minecraft 26.2 and 26.3 name differently or keep in
 * only one of the two, one method each, so that the tables keyed on them are shared and the
 * difference lives in one file per game.
 * <p>
 * <strong>This is the 26.3 half.</strong> One constant was renamed and three were dropped, and each
 * method says which:
 * <ul>
 * <li>{@code ARMOR_TRANSLUCENT} is {@code WOLF_ARMOR_CRACKS}, the same pipeline under a name for its
 * one caller: the location is still {@code pipeline/armor_translucent} and the builder is the same
 * line for line, the cutout at a tenth, no overlay, lit per face, translucent, not culled.</li>
 * <li>The two text backgrounds are gone. 26.3 merged {@code text_background} into {@code text}, and a
 * text display submits its box as text, so the box reaches the engine on the text rows it already
 * has and these two rows are simply not made.</li>
 * <li>The two weather pipelines are one, {@code WEATHER}, which is the old non writing one: the same
 * snippet and the same depth state, a test and no write. The one that wrote depth is gone with the
 * transparency chain that picked it; under improved transparency the weather is drawn through the
 * order independent set, which does not come in by the weather's door at all. This engine builds
 * the one that wrote depth again, out of the same snippet, for a pack asking for
 * {@code rain.depth}.</li>
 * </ul>
 */
public final class GamePipelines {

	/** The weather that writes depth, built at the first question. */
	private static @Nullable RenderPipeline weatherDepthWrite;

	private GamePipelines() {
	}

	/** The translucent armour layer, drawn over a wolf's armour where it is cracked. */
	public static RenderPipeline armorTranslucent() {
		return RenderPipelines.WOLF_ARMOR_CRACKS;
	}

	/** Null: this game draws a text display's box with the text pipeline. */
	public static @Nullable RenderPipeline textBackground() {
		return null;
	}

	/** Null, for the same reason as {@link #textBackground()}. */
	public static @Nullable RenderPipeline textBackgroundSeeThrough() {
		return null;
	}

	/** The weather the game draws in the ordinary case, tested against depth and writing none. */
	public static RenderPipeline weather() {
		return RenderPipelines.WEATHER;
	}

	/**
	 * The weather that writes depth, which this game no longer keeps: built as 26.2 built
	 * {@code WEATHER_DEPTH_WRITE}, from the weather's own snippet and the depth state it carries, a
	 * test and a write, under a location of this engine's. The game never draws with it; it is the
	 * pipeline a pack's weather program is made from where the pack asks for {@code rain.depth}.
	 */
	public static RenderPipeline weatherDepthWrite() {
		RenderPipeline built = weatherDepthWrite;
		if (built == null) {
			built = RenderPipeline.builder(RenderPipelinesAccessor.vitrail$weatherSnippet())
					.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID,
							"pipeline/weather_depth_write"))
					.build();
			weatherDepthWrite = built;
		}

		return built;
	}
}
