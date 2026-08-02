package dev.vitrail.neoforge.mixin;

import dev.vitrail.glsl.SodiumVertex;

import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Writes the facing of each quad into the spare bits of its material byte, so that a pack's terrain
 * program can be given a real normal.
 * <p>
 * <strong>This is the cheap half of the data the mesh does not carry, and it is cheap by luck.</strong>
 * {@code packLightAndData} gives the material a whole byte and Sodium's own
 * {@code chunk_material.glsl} reads three bits of it, one for the mipmap and two for the alpha
 * cutoff. {@code ModelQuadFacing} has seven values, which is three more bits, and there were five
 * spare. So the normal costs no field on {@code ChunkVertexEncoder$Vertex}, no element on the vertex
 * format, and not one byte of mesh: the deep mixin the milestone was braced for is only needed for
 * the block id.
 * <p>
 * The ordinal is stored PLUS ONE so that nought keeps a meaning of its own, "nobody wrote a facing
 * here". That case is real rather than defensive: fluids go through
 * {@code DefaultFluidRenderer}'s own push, which this does not hook, and the translucent sorter
 * collects its quads before this point. Both are passes a pack's terrain program does not draw
 * today, and both would otherwise read an arbitrary facing.
 * <p>
 * The bits are written whether or not a pack is drawing the terrain, and that is deliberate. Chunk
 * meshes are cached, so writing them only when the feature is on would leave every already meshed
 * section without a facing until the player forced a reload, which reads as the feature being
 * half broken. Sodium ignores the bits it does not use, so the cost of writing them always is
 * nothing.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {

	@ModifyArg(
			method = "bufferQuad",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/"
							+ "ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/"
							+ "chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"),
			index = 1)
	private int vitrail$facing(int materialBits, @Local ModelQuadFacing facing) {
		return materialBits | (facing.ordinal() + 1) << SodiumVertex.FACING_SHIFT;
	}
}
