package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;

import org.jspecify.annotations.Nullable;

/**
 * The pipelines of the game's own table that Minecraft 26.2 and 26.3 name differently or keep in
 * only one of the two, one method each, so that the tables keyed on them are shared and the
 * difference lives in one file per game.
 * <p>
 * <strong>This is the 26.2 half, and every method answers the constant the engine named before
 * there were two games.</strong> None of them is null here. The 26.3 half beside it under
 * {@code src/mc26.3/} says which ones that game renamed and which it dropped, and a caller treats a
 * null as a row this game has no pipeline for.
 */
public final class GamePipelines {

	private GamePipelines() {
	}

	/** The translucent armour layer, drawn over a wolf's armour where it is cracked. */
	public static RenderPipeline armorTranslucent() {
		return RenderPipelines.ARMOR_TRANSLUCENT;
	}

	/** The box a text display draws behind its lines, depth tested. */
	public static @Nullable RenderPipeline textBackground() {
		return RenderPipelines.TEXT_BACKGROUND;
	}

	/** The same box for a text display seen through walls. */
	public static @Nullable RenderPipeline textBackgroundSeeThrough() {
		return RenderPipelines.TEXT_BACKGROUND_SEE_THROUGH;
	}

	/** The weather the game draws in the ordinary case, tested against depth and writing none. */
	public static RenderPipeline weather() {
		return RenderPipelines.WEATHER_NO_DEPTH_WRITE;
	}

	/** The weather that writes depth, which the game picks under its improved transparency. */
	public static @Nullable RenderPipeline weatherDepthWrite() {
		return RenderPipelines.WEATHER_DEPTH_WRITE;
	}
}
