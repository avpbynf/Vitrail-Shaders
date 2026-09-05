package dev.vitrail.mixin.sodium;

import dev.vitrail.sodium.TerrainVertex;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the block id on the vertex itself, and carries it through the copy the translucent sorter
 * makes. {@link TerrainVertex} says why it cannot ride on the material.
 * <p>
 * The copy is the whole reason this mixin exists rather than a bare field.
 * {@code FullTQuad.initVertices} does not keep the vertices it is handed, it copies them one field
 * at a time through {@code copyVertexTo} into an array of its own, so a field this class adds and
 * that method does not know about is dropped in silence. The sorter then writes those copies out,
 * and every translucent quad reaches the encoder with an id of nought.
 * <p>
 * Source first, destination second: {@code copyVertexTo(from, to)}, read off the caller in
 * {@code FullTQuad.initVertices}, which loads the argument array's element before its own.
 */
@Mixin(value = ChunkVertexEncoder.Vertex.class, remap = false)
public abstract class ChunkVertexMixin implements TerrainVertex {

	@Unique
	private int vitrail$blockId;

	@Unique
	private int vitrail$blockOrigin;

	@Override
	public int vitrailBlockId() {
		return this.vitrail$blockId;
	}

	@Override
	public void vitrailBlockId(int id) {
		this.vitrail$blockId = id;
	}

	@Override
	public int vitrailBlockOrigin() {
		return this.vitrail$blockOrigin;
	}

	@Override
	public void vitrailBlockOrigin(int packed) {
		this.vitrail$blockOrigin = packed;
	}

	@Inject(method = "copyVertexTo", at = @At("TAIL"), require = 1)
	private static void vitrail$copyId(ChunkVertexEncoder.Vertex from, ChunkVertexEncoder.Vertex to,
			CallbackInfo callback) {
		((TerrainVertex) to).vitrailBlockId(((TerrainVertex) from).vitrailBlockId());
		((TerrainVertex) to).vitrailBlockOrigin(((TerrainVertex) from).vitrailBlockOrigin());
	}
}
