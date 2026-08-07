package dev.vitrail.neoforge.mixin;

import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.neoforge.sodium.TerrainVertex;
import dev.vitrail.render.BlockStateIds;

import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Writes what a pack needs about each quad: the facing of the quad, and the number
 * {@code block.properties} gave the block it came from.
 * <p>
 * The two travel differently, and they have to. <strong>The facing is cheap by luck.</strong>
 * {@code packLightAndData} gives the material a whole byte and Sodium's own
 * {@code chunk_material.glsl} reads three bits of it, one for the mipmap and two for an alpha cutoff
 * it no longer uses. {@code ModelQuadFacing} has seven values, and there were five bits spare. So the
 * normal costs no field on {@code ChunkVertexEncoder$Vertex}, no element on the vertex format, and
 * not one byte of mesh.
 * <p>
 * <strong>The block id had no such luck, and it cannot ride there at all.</strong> It used to, in the
 * bits above the material byte, and that is right for everything opaque and quietly wrong for
 * everything translucent: the branch below hands a translucent quad to the sorter and returns before
 * the push is reached, and the sorter writes it out later under a constant material. So the id goes
 * on the vertices, which the sorter does carry, and {@link TerrainVertex} spells out why.
 * <p>
 * The facing still rides on the material and is therefore still lost for a translucent quad. That is
 * a known gap rather than an oversight: it costs a wrong normal on water and glass, where the id
 * costs the pack knowing what water is at all.
 * <p>
 * The block being meshed is read from the render context this extends, which is also why the mixin
 * declares that superclass: the field is the target's and not its own, and a shadow does not reach
 * through a class the mixin does not stand under.
 * <p>
 * The bits are written whether or not a pack is drawing the terrain, and that is deliberate. Chunk
 * meshes are cached, so writing them only when the feature is on would leave every already meshed
 * section without them until the player forced a reload, which reads as the feature being half
 * broken. Sodium ignores what it does not use, and the id goes nowhere at all when the format has no
 * element for it, so writing them always costs nothing.
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
	private int vitrail$facing(int materialBits, @Local ModelQuadFacing facing) {
		// Stored PLUS ONE so that nought keeps a meaning of its own, "nobody wrote a facing here".
		// That case is real rather than defensive: a fluid goes through its own renderer, and a
		// translucent quad goes through the sorter, and neither reaches this push.
		return materialBits | (facing.ordinal() + 1) << SodiumVertex.FACING_SHIFT;
	}

	/**
	 * The opaque path, where the quad goes straight into the mesh buffer.
	 * <p>
	 * The array is modified and handed back rather than replaced: it is Sodium's own scratch quad,
	 * reused for every face of every block, and returning anything else would be a second allocation
	 * per quad in the hottest loop there is.
	 */
	@ModifyArg(
			method = "bufferQuad",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/"
							+ "ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/"
							+ "chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"),
			index = 0, require = 1)
	private ChunkVertexEncoder.Vertex[] vitrail$id(ChunkVertexEncoder.Vertex[] vertices) {
		return vitrail$stamp(vertices);
	}

	/** The translucent path, where the sorter takes the quad and writes it out later. */
	@ModifyArg(
			method = "bufferQuad",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/"
							+ "TranslucentGeometryCollector;appendQuad([Lnet/caffeinemc/mods/sodium/"
							+ "client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;"
							+ "Lnet/caffeinemc/mods/sodium/client/model/quad/properties/"
							+ "ModelQuadFacing;I)Z"),
			index = 0, require = 1)
	private ChunkVertexEncoder.Vertex[] vitrail$sortedId(ChunkVertexEncoder.Vertex[] vertices) {
		return vitrail$stamp(vertices);
	}

	/**
	 * Everything this quad carries about the block it came from: the number the pack gave it, where
	 * it stands in its section, and what it emits.
	 * <p>
	 * The position is masked to the section rather than kept whole. That is what the offset to the
	 * middle of the block is measured against, the mesh being written in a section's own
	 * coordinates, and it is also what makes it fit in a byte.
	 */
	@Unique
	private ChunkVertexEncoder.Vertex[] vitrail$stamp(ChunkVertexEncoder.Vertex[] vertices) {
		if (this.state == null || this.pos == null) {
			return TerrainVertex.stamp(vertices, BlockStateIds.NONE);
		}

		TerrainVertex.stampOrigin(vertices, TerrainVertex.pack(this.pos.getX(), this.pos.getY(),
				this.pos.getZ(), this.state.getLightEmission()));

		return TerrainVertex.stamp(vertices, BlockStateIds.packed(this.state));
	}
}
