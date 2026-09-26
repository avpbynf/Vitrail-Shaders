package dev.vitrail.mixin.access;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * The vertex formats a pipeline DECLARES, as the field spells them, which
 * {@code getVertexFormatBindings} no longer answers with while {@code RenderPipelineMixin} rewrites
 * that getter for the entity mesh this engine builds.
 * <p>
 * The 26.3 half: the field is a list here where 26.2 kept an array, and the question asked of it is
 * the same, whose pipeline this is.
 */
@Mixin(RenderPipeline.class)
public interface RenderPipelineAccessor {

	@Accessor("vertexFormatPerBuffer")
	List<@Nullable VertexFormat> vitrail$declaredFormats();
}
