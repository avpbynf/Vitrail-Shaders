package dev.vitrail.mixin.game;

import dev.vitrail.render.GeometryHold;
import dev.vitrail.render.GraphicsApi;
import dev.vitrail.render.LevelPass;
import dev.vitrail.render.SkyDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Draws the pieces of the sky a pack serves with the pack's programs, into the pack's own colour
 * targets, and tilts the path of the sun, the moon and the stars.
 * <p>
 * <strong>Where the 26.2 twin could not simply be renamed.</strong> 26.2 opens a pass per piece of
 * sky and labels it, and its twin recognises a piece by that label and replaces the whole pass. 26.3
 * records the sky in ONE pass opened at the head of {@code render}, and each piece is a method
 * handed that pass, which pushes a debug group, sets its pipeline, binds by name and draws. So the
 * pass the game opened becomes a {@link LevelPass}, which steps aside whenever something is recorded
 * that a pass may not hold and opens again, without emptying anything, at the game's next call; and
 * a piece the pack serves is handed the pack's pass in its place, at the first line of the piece
 * that reaches the pass. A piece the pack does not serve draws into the game's pass as before, so
 * the two kinds can follow one another in any order.
 * <p>
 * <strong>What a piece prepares happens between the two passes.</strong> {@link SkyDraw#element}
 * opens the frame, empties the pack's targets on the first frame they exist, compiles the program
 * and fills the horizon cone's vertices, every one of which the encoder refuses inside a pass. It is
 * asked where the piece is about to reach its pass, and the game's pass has already stepped aside
 * for the first of those commands by then.
 * <p>
 * The rest is the 26.2 twin's, point for point: the matrix and the colour of a piece are read off
 * the transform the game writes for it, the image of the sun, the moon and the End's sky is read off
 * the name it binds, the pack's own values are bound once the vertices are, and the horizon cone is
 * drawn after the disc. A pack that writes {@code sun=false} or {@code moon=false} has the game's own
 * body taken away, since its sky program draws its own.
 * <p>
 * <strong>The tilt</strong> is a property of the pack's light rather than of its sky programs, and
 * is applied whether a sky program runs or not: the shadow matrices turn by it too, and a sun left
 * where the game put it would light the world from one place and show itself in another.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {

	/** The matrix the game wrote for the piece about to be drawn. */
	@Unique
	private @Nullable Matrix4f vitrail$modelView;

	/** The colour the game wrote for the piece about to be drawn. */
	@Unique
	private @Nullable Vector4f vitrail$colour;

	/** The pack's pipeline for the piece being drawn, or null where the game draws it. */
	@Unique
	private @Nullable RenderPipeline vitrail$pipeline;

	/** The pack's pass the piece being drawn was handed, closed when the piece returns. */
	@Unique
	private @Nullable RenderPass vitrail$opened;

	/**
	 * Tilts the path the sun, the moon and the stars travel by what the pack asked for, at the
	 * place the 26.2 twin does and with the same rotation: right after the game has turned the
	 * celestial space and before it turns for the hour, so that it tilts the whole path rather than
	 * the body of one moment. 26.3 turns that space with {@code rotateDegrees} where 26.2 multiplied
	 * a quaternion in, and it is the first such call either way.
	 */
	@Inject(method = "renderSunMoonAndStars",
			at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V"))
	private void vitrail$tilt(RenderPass pass, PoseStack poseStack, float sunAngle, float moonAngle,
			float starAngle, MoonPhase moonPhase, float rainBrightness, float starBrightness,
			CallbackInfo callback) {
		float tilt = SkyDraw.sunPathRotation();
		if (tilt != 0.0F) {
			poseStack.rotate(Axis.ZP.rotationDegrees(tilt));
		}
	}

	/**
	 * Makes the game's sky pass one that can step aside, while a pack serves the sky. Otherwise the
	 * pass is the game's own and nothing below reaches it.
	 */
	@WrapOperation(method = "render", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass("
							+ "Ljava/util/function/Supplier;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
							+ "Ljava/util/Optional;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
							+ "Ljava/util/OptionalDouble;"
							+ ")Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private RenderPass vitrail$skyPass(CommandEncoder encoder, Supplier<String> label,
			GpuTextureView colour, Optional<?> clearColour, @Nullable GpuTextureView depth,
			OptionalDouble clearDepth, Operation<RenderPass> original) {
		RenderPass first = original.call(encoder, label, colour, clearColour, depth, clearDepth);
		if (!SkyDraw.serves()) {
			return first;
		}

		// Opened again with nothing emptied, which is what the first opening did too.
		return LevelPass.open(first, colour, depth, () -> original.call(encoder, label, colour,
				Optional.empty(), depth, OptionalDouble.empty()));
	}

	/**
	 * Takes the pack's own body away where its sky program draws its own sun, as the 26.2 twin does.
	 */
	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$sun(RenderPass pass, float rainBrightness, PoseStack poseStack,
			CallbackInfo callback) {
		if (!SkyDraw.draws("Sky sun")) {
			callback.cancel();
		}
	}

	/** The same for the moon. */
	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true, require = 1)
	private void vitrail$moon(RenderPass pass, MoonPhase moonPhase, float rainBrightness,
			PoseStack poseStack, CallbackInfo callback) {
		if (!SkyDraw.draws("Sky moon")) {
			callback.cancel();
		}
	}

	/**
	 * Reads the matrix and the colour the game writes for a piece. Every piece writes its transform
	 * before it reaches its pass, the sun, the moon and the End's flash in the method that then hands
	 * the pass on to the one that draws them.
	 */
	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset",
					"renderSun", "renderMoon", "renderEndFlash"},
			require = 7,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform("
							+ "Lorg/joml/Matrix4f;Lorg/joml/Vector4f;"
							+ ")Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice vitrail$transform(DynamicGpuData uniforms, Matrix4f modelView,
			Vector4f colour, Operation<GpuBufferSlice> original) {
		this.vitrail$modelView = modelView;
		this.vitrail$colour = colour;

		return original.call(uniforms, modelView, colour);
	}

	/** The End's sky writes a matrix alone, and its colour is white. */
	@WrapOperation(method = "renderEndSky", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform("
							+ "Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice vitrail$endTransform(DynamicGpuData uniforms, Matrix4f modelView,
			Operation<GpuBufferSlice> original) {
		this.vitrail$modelView = modelView;
		this.vitrail$colour = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);

		return original.call(uniforms, modelView);
	}

	@ModifyVariable(method = "renderSkyDisc", require = 1, argsOnly = true,
			at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$disc(RenderPass pass) {
		return vitrail$piece(pass, "Sky disc");
	}

	@ModifyVariable(method = "renderDarkDisc", require = 1, argsOnly = true,
			at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$dark(RenderPass pass) {
		return vitrail$piece(pass, "Sky dark");
	}

	@ModifyVariable(method = "renderStars", require = 1, argsOnly = true,
			at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$stars(RenderPass pass) {
		return vitrail$piece(pass, "Stars");
	}

	@ModifyVariable(method = "renderSunriseAndSunset", require = 1, argsOnly = true,
			at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$sunrise(RenderPass pass) {
		return vitrail$piece(pass, "Sunrise sunset");
	}

	@ModifyVariable(method = "renderEndSky", require = 1, argsOnly = true,
			at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$endSky(RenderPass pass) {
		return vitrail$piece(pass, "End sky");
	}

	/**
	 * The sun, the moon and the End's flash are drawn by one method, told which it is by the label
	 * of the debug group it pushes, which is not the label 26.2 gave their passes.
	 */
	@ModifyVariable(method = "drawCelestialBody", require = 1, argsOnly = true,
			at = @At(value = "LOAD", ordinal = 0))
	private RenderPass vitrail$body(RenderPass pass, Supplier<String> label) {
		return vitrail$piece(pass, switch (label.get()) {
			case "Sun" -> "Sky sun";
			case "Moon" -> "Sky moon";
			default -> label.get();
		});
	}

	@Inject(method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset",
			"renderEndSky", "drawCelestialBody"}, require = 6, at = @At("RETURN"))
	private void vitrail$done(CallbackInfo callback) {
		RenderPass opened = this.vitrail$opened;
		this.vitrail$opened = null;
		this.vitrail$pipeline = null;
		if (opened != null) {
			// Kept rather than ended where the next piece the pack serves names the same images,
			// which is what GeometryHold is for; the game's pass ends it where the next piece is
			// the game's own.
			opened.close();
		}
	}

	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset",
					"renderEndSky", "drawCelestialBody"},
			require = 6,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setPipeline("
							+ "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
	private void vitrail$setPipeline(RenderPass pass, CompiledRenderPipeline compiled,
			Operation<Void> original) {
		RenderPipeline ours = this.vitrail$pipeline;
		CompiledRenderPipeline chosen = ours == null ? null : GraphicsApi.compiledFor(ours);
		original.call(pass, chosen == null ? compiled : chosen);
	}

	@WrapOperation(method = {"renderEndSky", "drawCelestialBody"}, require = 2,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform("
							+ "Ljava/lang/String;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuTextureView;"
							+ "Lcom/mojang/renderpearl/api/textures/GpuSampler;)V"))
	private void vitrail$texture(RenderPass pass, String name, @Nullable GpuTextureView view,
			@Nullable GpuSampler sampler, Operation<Void> original) {
		original.call(pass, name, view, sampler);
		if (this.vitrail$pipeline != null && view != null && sampler != null) {
			SkyDraw.texture(view, sampler);
		}
	}

	@WrapOperation(
			method = {"renderSkyDisc", "renderDarkDisc", "renderStars", "renderSunriseAndSunset",
					"renderEndSky", "drawCelestialBody"},
			require = 6,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setVertexBuffer("
							+ "ILcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V"))
	private void vitrail$bind(RenderPass pass, int slot, GpuBufferSlice buffer,
			Operation<Void> original) {
		original.call(pass, slot, buffer);
		RenderPipeline ours = this.vitrail$pipeline;
		if (ours != null) {
			SkyDraw.bind(pass, ours);
		}
	}

	@WrapOperation(method = "renderSkyDisc", require = 1,
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V"))
	private void vitrail$horizon(RenderPass pass, int vertices, int instances, int firstVertex,
			int firstInstance, Operation<Void> original) {
		original.call(pass, vertices, instances, firstVertex, firstInstance);
		RenderPipeline ours = this.vitrail$pipeline;
		if (ours != null) {
			SkyDraw.horizon(pass, ours);
		}
	}

	/**
	 * Hands a piece the pack's pass where the pack serves it and its program is ready, and the
	 * game's pass otherwise. Only the game's pass made into a {@link LevelPass} above is ever
	 * replaced: a sky drawn with no pack serving it never reaches this with one.
	 */
	@Unique
	private RenderPass vitrail$piece(RenderPass pass, String label) {
		this.vitrail$pipeline = null;
		this.vitrail$opened = null;
		Matrix4f modelView = this.vitrail$modelView;
		Vector4f colour = this.vitrail$colour;
		if (!(pass instanceof LevelPass level) || modelView == null || colour == null) {
			return pass;
		}

		RenderPipeline ours = SkyDraw.element(label, modelView, colour);
		if (ours == null || GraphicsApi.compiledFor(ours) == null) {
			return pass;
		}

		RenderPassDescriptor descriptor = SkyDraw.descriptor(level.colour(), level.depth());
		if (descriptor == null) {
			return pass;
		}

		this.vitrail$pipeline = ours;
		this.vitrail$opened = GeometryHold.open(RenderSystem.getDevice().createCommandEncoder(),
				descriptor);

		return this.vitrail$opened;
	}
}
