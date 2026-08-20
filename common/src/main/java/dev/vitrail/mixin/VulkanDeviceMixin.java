package dev.vitrail.mixin;

import dev.vitrail.render.StalePipelines;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanRenderPipeline;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Lets the game's compiled entity pipelines leave the cache without dying, which is what
 * {@code EntityMesh.settle} needs when its answer moves in a running world: the pipelines standing
 * in {@code pipelineCache} were compiled against the bindings of the OTHER answer, their stride is
 * baked ({@code VulkanRenderPipeline:99}), and every draw they serve reads the mesh at the wrong
 * offsets from then on. Giant triangles off the hand and every mob, for as long as the session
 * lasts, is what that looks like.
 * <p>
 * <strong>Dropped from the map, never destroyed here.</strong> Destruction is the half of issue
 * 111 that lives in this engine: the cache's own emptying waits the device idle and there is no
 * instant of a running session where that is safe. What leaves the map cannot be looked up again,
 * so no NEW draw binds it; whatever an already-recorded frame still names stays alive in
 * {@code vitrail$setAside} until {@code clearPipelineCache}, which the game only reaches after
 * quiescing rendering, frees the whole cache anyway, and now frees these with it.
 */
@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin implements StalePipelines {

	@Shadow
	@Final
	private Map<RenderPipeline, VulkanRenderPipeline> pipelineCache;

	/**
	 * Compiled pipelines dropped from the cache and owed a destroy, which only the safe purge pays.
	 * A list and not a set: one pipeline cannot be dropped twice, leaving the map with the drop as
	 * it does.
	 * <p>
	 * It grows one set per answer move and empties only at the next resource reload, and that
	 * bound is accepted rather than missed: the same move that adds a set also rebuilds the whole
	 * world mesh and compiles or drops a pack's entire chain, so what waits here is a sliver of
	 * what the move already paid, and the only earlier instant to free it is the unsafe one this
	 * class exists to avoid.
	 */
	@Unique
	private final List<VulkanRenderPipeline> vitrail$setAside = new ArrayList<>();

	@Override
	public List<RenderPipeline> vitrail$dropEntityPipelines() {
		List<RenderPipeline> dropped = new ArrayList<>();
		Iterator<Map.Entry<RenderPipeline, VulkanRenderPipeline>> held =
				this.pipelineCache.entrySet().iterator();
		while (held.hasNext()) {
			Map.Entry<RenderPipeline, VulkanRenderPipeline> entry = held.next();
			if (!declaresGameEntity(entry.getKey())) {
				continue;
			}

			this.vitrail$setAside.add(entry.getValue());
			dropped.add(entry.getKey());
			held.remove();
		}

		return dropped;
	}

	/**
	 * Whether this is one of the game's entity pipelines, read off the DECLARED formats and not the
	 * getter: {@code RenderPipelineMixin} rewrites the getter while the mesh carries, which would
	 * make a pack's own pipelines answer yes here. Those follow their chain, not this cache walk.
	 * By identity, the same question {@code EntityMesh.binding} asks.
	 */
	@Unique
	private static boolean declaresGameEntity(RenderPipeline pipeline) {
		@Nullable VertexFormat[] declared = ((RenderPipelineAccessor) pipeline).vitrail$declaredFormats();
		for (VertexFormat format : declared) {
			@SuppressWarnings("ReferenceEquality")
			boolean entity = format == DefaultVertexFormat.ENTITY;
			if (entity) {
				return true;
			}
		}

		return false;
	}

	@Inject(method = "clearPipelineCache", at = @At("TAIL"), require = 1)
	private void vitrail$destroySetAside(CallbackInfo callback) {
		this.vitrail$setAside.forEach(VulkanRenderPipeline::destroy);
		this.vitrail$setAside.clear();
	}
}
