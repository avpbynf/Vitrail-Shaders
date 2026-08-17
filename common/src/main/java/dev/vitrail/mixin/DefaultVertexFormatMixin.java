package dev.vitrail.mixin;

import dev.vitrail.render.EntityMesh;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Appends the identifiers to the entity format, at the one moment the answer can still be given.
 * <p>
 * That moment is this class initialiser and no other, which is what makes this a mixin on a field
 * rather than a decision taken when a pack is picked: {@code RenderPipelines} builds every entity
 * pipeline out of {@code DefaultVertexFormat.ENTITY} at its own class init, and
 * {@code BufferBuilder} compares against the same field by identity. Everything downstream of here
 * therefore agrees without being told, and nothing downstream could be changed afterwards without
 * the mesh and the pipelines disagreeing for a frame. {@link EntityMesh} carries what that costs.
 * <p>
 * <strong>Every {@code build} of the initialiser is offered and one is taken</strong>, by what the
 * format holds rather than by counting the sixteen of them: the entity format is the only one of
 * this class carrying the overlay, so {@code EntityMesh.lengthen} recognises it and hands the other
 * fifteen straight back. An ordinal would be the same answer written as a number nobody can check.
 */
@Mixin(DefaultVertexFormat.class)
public abstract class DefaultVertexFormatMixin {

	@ModifyExpressionValue(method = "<clinit>", require = 1,
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexFormat$Builder;"
					+ "build()Lcom/mojang/blaze3d/vertex/VertexFormat;"))
	private static VertexFormat vitrail$lengthen(VertexFormat built) {
		return EntityMesh.lengthen(built);
	}
}
