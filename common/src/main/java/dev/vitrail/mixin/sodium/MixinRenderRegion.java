package dev.vitrail.mixin.sodium;

import dev.vitrail.render.TerrainDraw;

import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Gives the shadow draw cached draw batches of its own, beside the ones the camera's draw fills.
 * <p>
 * The chunk renderer keeps one filled batch per region and pass and reuses it until the region's
 * render list changes or the camera crosses a section boundary inside the region, or steps into
 * or out of it ({@code render/chunk/lists/ChunkRenderList.prepareForRender}, the camera section
 * clamped to one past the region on each axis). Which faces a batch draws is decided when it is
 * filled, from the position of whoever filled it, and the face culling {@code
 * MixinDefaultChunkRenderer} turns off for the shadow draw only lands on a fill the shadow draw
 * makes itself: a region whose list is the same for the camera and for the light is never
 * refilled between the two, so the shadow draw found the camera's batch standing and drew the map
 * with the camera's face culling in it. The faces the camera cannot see are the ones between the
 * sun and the leaves, so a canopy's self-shadow came and went with where the camera stood, region
 * by region, refilled at the section boundaries the camera crossed inside the tree's own region.
 * <p>
 * Iris keeps a second render list and a second map of batches for its shadow pass and swaps both
 * into Sodium's fields around it ({@code compat/sodium/mixin/MixinRenderRegion.java}). Here the
 * light's walk replaces the camera's lists rather than swapping them, so only the batches need a
 * second home: the shadow draw takes its batch from this map, and the map is emptied wherever
 * Sodium empties its own, pass for pass, which is what keeps a batch from outliving the list or
 * the geometry it was filled from. What it costs is one more batch per region and pass the map
 * draws, a few tens of kilobytes each, freed with the region.
 */
@Mixin(value = RenderRegion.class, remap = false)
public abstract class MixinRenderRegion {

	@Unique
	private final Map<TerrainRenderPass, MultiDrawBatch> vitrail$shadowBatches =
			new IdentityHashMap<>();

	@Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
	private void vitrail$shadowBatch(TerrainRenderPass pass,
			CallbackInfoReturnable<MultiDrawBatch> cir) {
		if (TerrainDraw.drawingShadow()) {
			cir.setReturnValue(this.vitrail$shadowBatches.computeIfAbsent(pass,
					_ -> MultiDrawBatch.newBatch(ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE + 1)));
		}
	}

	@Inject(method = "clearAllCachedBatches", at = @At("HEAD"))
	private void vitrail$clearAll(CallbackInfo ci) {
		for (MultiDrawBatch batch : this.vitrail$shadowBatches.values()) {
			batch.clear();
		}
	}

	@Inject(method = "clearCachedBatchFor", at = @At("HEAD"))
	private void vitrail$clearFor(TerrainRenderPass pass, CallbackInfo ci) {
		MultiDrawBatch batch = this.vitrail$shadowBatches.get(pass);
		if (batch != null) {
			batch.clear();
		}
	}

	@Inject(method = "delete", at = @At("HEAD"))
	private void vitrail$delete(CallbackInfo ci) {
		for (MultiDrawBatch batch : this.vitrail$shadowBatches.values()) {
			batch.delete();
		}

		this.vitrail$shadowBatches.clear();
	}
}
