package dev.vitrail.neoforge.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;

/**
 * A block id carried by one vertex of the chunk mesh, put on {@code ChunkVertexEncoder$Vertex} by a
 * mixin.
 * <p>
 * <strong>It travels on the vertex because the material does not survive.</strong> The block id used
 * to ride in the high bits of the int handed to {@code push}, which Sodium masks to its low byte and
 * ignores the rest of, and that works for everything opaque. It does not work for anything
 * translucent: {@code BlockRenderer.bufferQuad} and {@code DefaultFluidRenderer.writeQuad} both hand
 * a translucent quad to the sorter and return before that push is ever reached, and the sorter
 * writes it out later with {@code FullTQuad.writeToBuffer}, which passes the constant
 * {@code DefaultMaterials.TRANSLUCENT} as the material. Whatever was in those high bits is gone by
 * then, and nothing says so.
 * <p>
 * The vertices themselves do survive: the sorter copies them field by field and hands the copies to
 * the encoder. One more field on them, carried by that copy, therefore reaches the encoder whichever
 * of the three paths the quad took.
 * <p>
 * What this costs BSL, and what it buys: {@code gbuffers_water} decides what is water with
 * {@code mat}, which it sets from {@code blockID == 200}, and with no id every water surface fell
 * through as an ordinary translucent one. The tell was that it looked right from under the surface
 * and wrong from above, the pack's underwater branch being the one that fires on the absence of the
 * flag rather than on its presence.
 */
public interface TerrainVertex {

	/** The packed block id, nought when nothing wrote one. */
	int vitrailBlockId();

	void vitrailBlockId(int id);

	/**
	 * Puts one id on every vertex of a quad and hands the array back, which is the shape an argument
	 * modifier wants.
	 * <p>
	 * The array is written into rather than replaced: it is Sodium's own scratch quad, reused for
	 * every face of every block, and building another would be an allocation per quad in the hottest
	 * loop there is.
	 * <p>
	 * It lives here and not on a mixin because a mixin may hold no static method that is not private,
	 * which the class transformer refuses outright and at load time.
	 */
	static ChunkVertexEncoder.Vertex[] stamp(ChunkVertexEncoder.Vertex[] vertices, int id) {
		for (ChunkVertexEncoder.Vertex vertex : vertices) {
			((TerrainVertex) vertex).vitrailBlockId(id);
		}

		return vertices;
	}
}
