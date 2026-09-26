package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The calls into the game's own renderer that Minecraft 26.2 and 26.3 spell differently, one method
 * each, so the code making them is shared and the difference lives in one file per game.
 * {@link GraphicsApi} is the same thing one level down, for the graphics API the renderer is built
 * on.
 * <p>
 * <strong>This is the 26.2 half, and every method is the call the engine made before there were
 * two games,</strong> unchanged: a 26.2 jar runs exactly what it ran before this class existed. The
 * 26.3 half beside it under {@code src/mc26.3/} is where a method has to do more than rename, and
 * its javadoc says what and why.
 */
public final class GameRender {

	private GameRender() {
	}

	/**
	 * Whether the game's translucent features can be sent into an image of the engine's choosing,
	 * which on this game they always can: {@code RenderSystem} carries an output override for colour
	 * and one for depth, and every immediate draw of the game reads both.
	 */
	public static boolean redirectsFeatures() {
		return true;
	}

	/**
	 * Sends every draw of the game's features into these two images until
	 * {@link #endFeatureRedirect()}, through the game's own overrides.
	 */
	public static void redirectFeatures(GpuTextureView colour, GpuTextureView depth) {
		RenderSystem.outputColorTextureOverride = colour;
		RenderSystem.outputDepthTextureOverride = depth;
	}

	/** Puts the game's draws back on the targets they name. Safe where nothing was redirected. */
	public static void endFeatureRedirect() {
		RenderSystem.outputColorTextureOverride = null;
		RenderSystem.outputDepthTextureOverride = null;
	}

	/**
	 * Whether a draw of this render type lands on the game's main target, asked of what its output
	 * target resolves to rather than of which target it names.
	 */
	@SuppressWarnings("ReferenceEquality")
	public static boolean drawsOnMainTarget(PreparedRenderType prepared) {
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();

		return main != null && prepared.outputTarget().getRenderTarget() == main;
	}

	/** Where a draw of this render type is sent, for a line of the log. */
	public static String drawTargetName(PreparedRenderType prepared) {
		return String.valueOf(prepared.outputTarget());
	}

	/**
	 * The colour image a draw of this render type would land in, worked out as
	 * {@code PreparedRenderType} works it out: the override where one stands, the output target's
	 * own otherwise.
	 */
	public static GpuTextureView drawColour(PreparedRenderType prepared) {
		RenderTarget target = prepared.outputTarget().getRenderTarget();

		return RenderSystem.outputColorTextureOverride != null
				? RenderSystem.outputColorTextureOverride
				: target.getColorTextureView();
	}

	/** The same for depth, which is none where the output target carries no depth. */
	public static @Nullable GpuTextureView drawDepth(PreparedRenderType prepared) {
		RenderTarget target = prepared.outputTarget().getRenderTarget();

		return !target.useDepth ? null
				: RenderSystem.outputDepthTextureOverride != null
						? RenderSystem.outputDepthTextureOverride
						: target.getDepthTextureView();
	}

	/**
	 * Draws everything a storage of the engine's own holds, on a dispatcher of the engine's own. On
	 * this game each draw opens its own pass, on the target its render type names, so there is no
	 * pass to open here and the label is not used.
	 */
	public static void renderAllFeatures(FeatureRenderDispatcher dispatcher,
			SubmitNodeStorage submits, Supplier<String> label) {
		dispatcher.renderAllFeatures(submits);
	}

	/**
	 * Submits the player's own hands and whatever they hold, as the game's own late call submits
	 * them.
	 */
	public static void submitHands(GameRenderer gameRenderer, float partialTicks, PoseStack pose,
			SubmitNodeCollector into, LocalPlayer player, int light) {
		gameRenderer.itemInHandRenderer.submitHandsWithItems(partialTicks, pose, into, player, light);
	}

	/**
	 * Fills a camera render state from the camera, as the game's own extraction does, with the
	 * partial tick of the entity the camera rides.
	 */
	public static void extractCamera(Camera camera, CameraRenderState into, DeltaTracker delta) {
		camera.extractRenderState(into, camera.getCameraEntityPartialTicks(delta));
	}

	/**
	 * The game's own frustum test for one entity, asked of the renderer that draws it. The partial
	 * tick is not read on this game.
	 */
	public static boolean shouldRender(EntityRenderDispatcher entities, Entity entity,
			Frustum frustum, Vec3 at, float partialTicks) {
		return entities.shouldRender(entity, frustum, at.x, at.y, at.z);
	}

	/**
	 * Whether the section a block stands in has a mesh and has faded in far enough to be drawn,
	 * which is the game's own second test on an entity it is about to extract.
	 */
	public static boolean sectionShown(LevelRenderer level, BlockPos block) {
		return level.isSectionCompiledAndVisible(block);
	}

	/**
	 * The sky's colour at this partial tick, read through the camera's attribute probe, packed as
	 * the attribute holds it on this game: eight bits a channel, red in the third byte.
	 */
	public static int skyColor(Camera camera, float partialTick) {
		return camera.attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partialTick);
	}

	/**
	 * Whether the game is drawing its translucent particles into a target of its own that it
	 * composes onto the main one afterwards, which is what its improved transparency does. Asked
	 * inside the frame: the target is allocated per frame and only while the chain runs.
	 */
	public static boolean particlesApart() {
		return Minecraft.getInstance().levelRenderer.particlesTarget() != null;
	}

	/** The same question about the weather, which the same chain gives a target of its own. */
	public static boolean weatherApart() {
		return Minecraft.getInstance().levelRenderer.weatherTarget() != null;
	}
}
