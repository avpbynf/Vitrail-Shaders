package dev.vitrail.mixin;

import dev.vitrail.sodium.TerrainVertex;
import dev.vitrail.render.BlockStateIds;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Writes what a pack needs about each block a quad came from: the number
 * {@code block.properties} gave it, where it stands in its section, and what it emits.
 * <p>
 * <strong>All of it rides on the vertices, and it has to.</strong> Carrying the id in the bits
 * above the material byte is right for everything opaque and quietly wrong for everything
 * translucent: the second branch below hands a translucent quad to the sorter and returns before the
 * push is reached, and the sorter writes it out later under a constant material. Anything left on
 * the material is gone by then, and nothing says so. {@link TerrainVertex} spells out why the
 * vertices are the one place that survives.
 * <p>
 * The quad's own facing does not ride on that material byte to stand in for a normal, and nothing
 * here writes it: the mesh carries a normal taken from the corners
 * themselves, which is right for a plant, a sloped fluid and a custom model where an axis was not,
 * and which reaches a translucent quad like everything else here.
 * <p>
 * The block being meshed is read from the render context this extends, which is also why the mixin
 * declares that superclass: the field is the target's and not its own, and a shadow does not reach
 * through a class the mixin does not stand under.
 * <p>
 * The bits are written whether or not a pack is drawing the terrain, and that is deliberate. Sodium
 * ignores what it does not use and the id goes nowhere at all when the format has no element for it,
 * so what is written is never read; what the writing itself costs on a section built with no pack
 * drawing is a map lookup and eight stores a quad, and it has not been measured. What settles it is
 * the other side: making them conditional adds a second switch that has to agree with the one the
 * format already follows, and two switches that must agree are one more thing to get wrong.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin extends AbstractBlockRenderContext {

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
		// Both are written on EVERY quad, including the one that knows neither. The array is
		// Sodium's own, one instance reused for every face of every block, so a field left alone
		// keeps what the block before wrote: an offset measured against another block, which wraps
		// into its byte without a word. A quad that knows one of the two still gets that one.
		TerrainVertex.stampOrigin(vertices, this.pos == null
				? 0
				: TerrainVertex.pack(this.pos.getX(), this.pos.getY(), this.pos.getZ(),
						this.state == null ? 0 : this.state.getLightEmission()));

		return TerrainVertex.stamp(vertices,
				this.state == null ? BlockStateIds.NONE : BlockStateIds.packed(this.state));
	}
}
