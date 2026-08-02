package dev.vitrail.neoforge.mixin;

import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.neoforge.sodium.TerrainMesh;
import dev.vitrail.render.BlockStateIds;

import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Writes what a pack needs about each quad into the one int the encoder is handed: the facing of the
 * quad, and the number {@code block.properties} gave the block it came from.
 * <p>
 * Two names of a pack come out of this one argument, and they cost the mesh very differently. The
 * facing is free, for the reason below; the block id is not, and rides here only to reach the
 * encoder, which puts it in the fifth element of the format.
 * <p>
 * <strong>The facing is cheap by luck.</strong> {@code packLightAndData} gives the material a whole
 * byte and Sodium's own {@code chunk_material.glsl} reads three bits of it, one for the mipmap and
 * two for the alpha cutoff. {@code ModelQuadFacing} has seven values, which is three more bits, and
 * there were five spare. So the normal costs no field on {@code ChunkVertexEncoder$Vertex}, no
 * element on the vertex format, and not one byte of mesh. The id had no such luck and is an element
 * of its own; this argument is only how it reaches the encoder, Sodium masking the material to its
 * low eight bits and never looking at the rest.
 * <p>
 * The block being meshed is read from the render context this extends, which is also why the mixin
 * declares that superclass: the field is the target's and not its own, and a shadow does not reach
 * through a class the mixin does not stand under.
 * <p>
 * The ordinal is stored PLUS ONE so that nought keeps a meaning of its own, "nobody wrote a facing
 * here", and the block id is packed the same way round for the same reason. That case is real rather
 * than defensive: fluids go through {@code DefaultFluidRenderer}'s own push, which this does not
 * hook, and the translucent sorter collects its quads before this point. Both are passes a pack's
 * terrain program does not draw today, so a fluid reads as facing nought and as no block id, and
 * Iris would give it the id of its own block.
 * <p>
 * The bits are written whether or not a pack is drawing the terrain, and that is deliberate. Chunk
 * meshes are cached, so writing them only when the feature is on would leave every already meshed
 * section without a facing until the player forced a reload, which reads as the feature being
 * half broken. Sodium ignores the bits it does not use, and the id goes nowhere at all when the
 * format has no element for it, so writing them always costs nothing.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin extends AbstractBlockRenderContext {

	@ModifyArg(
			method = "bufferQuad",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/"
							+ "ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/"
							+ "chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"),
			index = 1)
	private int vitrail$quad(int materialBits, @Local ModelQuadFacing facing) {
		int id = this.state == null ? BlockStateIds.NONE : BlockStateIds.packed(this.state);

		return materialBits
				| (facing.ordinal() + 1) << SodiumVertex.FACING_SHIFT
				| id << TerrainMesh.ID_SHIFT;
	}
}
