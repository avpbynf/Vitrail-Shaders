package dev.vitrail.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.List;

/**
 * What {@code VulkanDeviceMixin} lets {@link EntityMesh#settle()} do about the game's compiled
 * entity pipelines when the mesh's answer moves: take them out of the device's cache without
 * destroying anything.
 * <p>
 * Iris never needs the gesture, and the difference says why it exists here.
 * {@code mixin/MixinRenderPipeline.iris$change} swaps the format live at every call and touches no
 * cache, because its backend rebuilds the vertex array from the pipeline's bindings at the draw.
 * This backend bakes the stride into the compiled pipeline ({@code VulkanRenderPipeline:99}) and
 * caches the result by identity, so here a moved answer leaves compiled objects behind that no
 * live swap can reach, and they have to leave the cache instead.
 * <p>
 * The two halves of that sentence carry the whole design. <strong>Out of the cache</strong>, so
 * the next bind compiles afresh against the bindings now reported and no draw ever pairs the new
 * mesh with a pipeline compiled under the old one. <strong>Without destroying</strong>, because
 * destruction has no safe instant in a running session, which was this engine's half of issue 111:
 * this backend records continuously, so at any positional hook something already recorded still
 * names what a purge would free. The compiled pipelines taken out here wait in the mixin and are
 * freed inside {@code clearPipelineCache}, after the {@code waitIdle} the game's own emptying
 * already pays for.
 */
public interface StalePipelines {

	/**
	 * Takes every cached pipeline that declares the game's entity format out of the cache, and
	 * answers with their keys so the caller can compile them again under the answer now in force.
	 * The compiled objects themselves are kept aside and freed at the next safe purge.
	 */
	List<RenderPipeline> vitrail$dropEntityPipelines();
}
