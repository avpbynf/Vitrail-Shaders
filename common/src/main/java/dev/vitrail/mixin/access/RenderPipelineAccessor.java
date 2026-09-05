package dev.vitrail.mixin.access;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import org.jspecify.annotations.Nullable;

/**
 * The vertex formats a pipeline DECLARES, as the field spells them, which
 * {@code getVertexFormatBindings} no longer answers with: {@code RenderPipelineMixin} rewrites that
 * getter while the entity mesh carries, and it has to, that being how the game's own pipelines
 * compile against the mesh this engine really builds.
 * <p>
 * {@code VulkanDeviceMixin} asks a different question, WHOSE pipeline this is: one that names the
 * game's entity format was written by the game and is recompiled when the mesh's answer moves, one
 * that names the extended format was built by a pack's program and follows its chain instead. The
 * rewritten getter folds those two into one answer, so telling them apart takes the raw field.
 */
@Mixin(RenderPipeline.class)
public interface RenderPipelineAccessor {

	@Accessor("vertexFormatPerBuffer")
	@Nullable VertexFormat[] vitrail$declaredFormats();
}
