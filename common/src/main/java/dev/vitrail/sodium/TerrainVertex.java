package dev.vitrail.sodium;

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
	 * Where the block this vertex came from sits in its section, and how much light it gives off,
	 * packed one per byte. Nought when nothing wrote it.
	 * <p>
	 * On the vertex for the same reason the id is: the sorter copies vertices and not materials, so
	 * anything a translucent quad has to carry has to be here. What the encoder makes of it is the
	 * offset from the vertex to the middle of its block, which is a subtraction it cannot do without
	 * knowing where the block was.
	 */
	int vitrailBlockOrigin();

	void vitrailBlockOrigin(int packed);

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

	/**
	 * The same for where the block stands and what it emits, in the section's own coordinates.
	 * <p>
	 * Four bytes and not three fields: it is copied vertex by vertex by the sorter and written out
	 * as one word, so one number is one field to add, one line in the copy, and one store.
	 */
	static ChunkVertexEncoder.Vertex[] stampOrigin(ChunkVertexEncoder.Vertex[] vertices, int packed) {
		for (ChunkVertexEncoder.Vertex vertex : vertices) {
			((TerrainVertex) vertex).vitrailBlockOrigin(packed);
		}

		return vertices;
	}

	/**
	 * The three coordinates and the light in one word, taking the block's position in the world and
	 * reducing it to the section it is meshed in.
	 * <p>
	 * <strong>Masked to sixteen and not to a byte.</strong> A mesh is written in its section's own
	 * frame, so that is the frame the middle of a block has to be measured in. Masked to a byte the
	 * value would keep four bits that name the section rather than the block, and put the middle of
	 * the block up to fifteen SECTIONS away.
	 * <p>
	 * One rule and one caller of it per path, rather than the same four shifts written twice: the
	 * block renderer and the fluid renderer both feed this, and a disagreement between them would be
	 * an offset computed against the wrong corner on water alone.
	 */
	static int pack(int x, int y, int z, int emission) {
		return (x & 15) | ((y & 15) << 8) | ((z & 15) << 16) | ((emission & 0xFF) << 24);
	}

	/** One axis back out of {@link #stampOrigin}'s word. */
	static int origin(int packed, int axis) {
		return (packed >> (axis * 8)) & 0xFF;
	}

	/** The light the block gives off, out of the same word. */
	static int emission(int packed) {
		return (packed >>> 24) & 0xFF;
	}
}
