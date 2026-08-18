package dev.vitrail.mixin;

import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.EntityIdentifiers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says, for the length of one dispatch, that what is being submitted belongs to a block entity.
 * <p>
 * <strong>This is the only moment in the frame the answer exists</strong>, and everything else about
 * the block entities hangs off it. What a block entity renderer hands the collector is a submission
 * node like any other, drawn later with the same pipelines and out of the same batch as a mob, so
 * the mark has to be taken here and carried; {@link BlockEntityGeometry} says how far.
 * <p>
 * Around the dispatcher and not around the renderer, which is where Iris puts it too
 * ({@code mixin/entity_render_context/MixinBlockEntityRenderDispatcher.java:52} and {@code :71}):
 * one method covers every block entity in the game and no renderer of them has to know.
 * <p>
 * Both required. Only the first applying leaves the mark up for the whole frame and every mob after
 * a chest would be lit as a block; only the second applying does nothing at all. Neither says
 * anything on screen, which is the reason this file refuses to start rather than draw a picture that
 * can be looked at and believed.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

	@Inject(method = "submit", at = @At("HEAD"), require = 1)
	private void vitrail$begin(BlockEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callback) {
		BlockEntityGeometry.submitting(true);

		// The number is the pack's for the BLOCK STATE this thing stands in, out of block.properties
		// and not out of a table of its own, which is Iris's answer as well
		// (MixinBlockEntityRenderDispatcher.java:61). Raw, where the terrain mesh carries the same
		// table packed: the shader unpacks the terrain's own word and reads this one as it stands.
		EntityIdentifiers.blockEntity(
				BlockStateIds.id(((BlockEntityRenderStateAccessor) state).vitrail$blockState()));
	}

	@Inject(method = "submit", at = @At("RETURN"), require = 1)
	private void vitrail$end(BlockEntityRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, CameraRenderState camera, CallbackInfo callback) {
		BlockEntityGeometry.submitting(false);
		EntityIdentifiers.blockEntity(0);
	}
}
