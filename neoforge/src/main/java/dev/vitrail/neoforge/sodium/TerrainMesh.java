package dev.vitrail.neoforge.sodium;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.TerrainDraw;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

import org.lwjgl.system.MemoryUtil;

/**
 * The chunk mesh with a fifth element on it, holding the number {@code block.properties} gave each
 * block state.
 * <p>
 * <strong>Not one byte of Sodium's own twenty is written here.</strong> Sodium is under the PolyForm
 * Shield licence and this project is under the LGPL, so its encoder is called as a black box and
 * only the four bytes after each vertex are ours. Reimplementing the packing would be a plain copy
 * of code this project may not take, and it would also be a second thing to keep in step with every
 * release.
 * <p>
 * The cost of that is one shuffle. Sodium's encoder lays four vertices out at its own stride, so the
 * three after the first land where this format does not want them and are moved up, backwards and
 * word by word so that a vertex is never written over a word still to be read.
 * <p>
 * <strong>The new element has to stay last.</strong> An element the shader does not declare shifts
 * the location of every element AFTER it, in silence, because the pipeline counts every element of
 * the format and the shader module only counts the ones a stage declared. Sodium's own chunk shader
 * declares the first four and knows nothing of this one, so being last is the whole reason it can
 * carry on drawing through this format.
 */
public final class TerrainMesh implements ChunkVertexType {

	/**
	 * What the game will use for as long as it runs, decided the first time it is asked for, and null
	 * for Sodium's own.
	 * <p>
	 * It cannot be answered per call. Sodium sizes a region's geometry arena from the format, the
	 * chunk builder writes meshes at the format's stride, and both are built once and kept: a format
	 * that changed under them would put meshes of one stride into an arena of another, which is not
	 * an error anywhere, only a world drawn out of garbage. So the answer is latched, and turning
	 * the terrain on or off takes effect at the next start.
	 */
	private static TerrainMesh latched;
	private static boolean decided;

	private final ChunkVertexType inner = ChunkMeshFormats.COMPACT;
	private final ChunkVertexEncoder innerEncoder = this.inner.getEncoder();
	private final ChunkVertexEncoder encoder = this::encode;
	private final int innerStride = this.inner.getVertexFormat().getVertexSize();
	private final VertexFormat format = extend(this.inner.getVertexFormat());
	private final int stride = this.format.getVertexSize();

	private TerrainMesh() {
		if (this.innerStride % Integer.BYTES != 0) {
			throw new IllegalStateException("The chunk mesh is " + this.innerStride + " bytes, which "
					+ "is not a whole number of words, and this engine moves it a word at a time");
		}
	}

	/**
	 * The format the game should use, or null to leave Sodium's own alone. Decided once; see
	 * {@link #latched}.
	 * <p>
	 * Built here rather than in a static field so that a mesh this cannot extend leaves the game
	 * running on Sodium's own instead of failing to load a class in the middle of a world.
	 */
	public static synchronized ChunkVertexType current() {
		if (decided) {
			return latched;
		}

		decided = true;
		if (!TerrainDraw.asked()) {
			return null;
		}

		try {
			latched = new TerrainMesh();
		} catch (RuntimeException e) {
			Vitrail.logger().error("This engine cannot extend the chunk mesh, so the terrain keeps "
					+ "Sodium's own and no pack will draw it", e);

			return null;
		}

		Vitrail.logger().info("The chunk mesh carries {} bytes a vertex for the rest of this run "
				+ "instead of {}, the difference being the block id a pack reads as mc_Entity",
				latched.stride, latched.innerStride);

		return latched;
	}

	@Override
	public VertexFormat getVertexFormat() {
		return this.format;
	}

	@Override
	public ChunkVertexEncoder getEncoder() {
		return this.encoder;
	}

	/**
	 * Sodium's four elements at their own offsets, then ours after them.
	 * <p>
	 * Each one is placed at the offset it already had rather than laid out again from zero, so that
	 * any padding Sodium leaves between two of them survives. The builder refuses a size that is not
	 * a whole number of words, which is what makes the id four bytes wide where two would do.
	 */
	private static VertexFormat extend(VertexFormat base) {
		VertexFormat.Builder builder = VertexFormat.builder(base.getStepRate());
		for (VertexFormatElement element : base.getElements()) {
			builder.addAttribute(element.name(), element.offset(), element.format().blockSize(),
					element.format(), 1);
		}

		return builder
				.addAttribute(SodiumVertex.BLOCK_ID, base.getVertexSize(), GpuFormat.R32_UINT.blockSize(),
						GpuFormat.R32_UINT, 1)
				.build();
	}

	/**
	 * @param materialBits Sodium's own bits in the low byte and the quad's facing above them. The
	 *                     block id is NOT here: it rides on the vertices, because a translucent quad
	 *                     reaches this encoder from the sorter, under a material Sodium chose itself
	 */
	private long encode(long pointer, int materialBits, ChunkVertexEncoder.Vertex[] vertices,
			int sectionIndex) {
		this.innerEncoder.write(pointer, materialBits, vertices, sectionIndex);

		// Backwards: every vertex moves up, so the one that has not been moved yet is always the
		// source of the next move. Word by word and from the top of each vertex for the same reason,
		// the two ranges overlapping for all but the last vertex of a quad.
		for (int at = vertices.length - 1; at >= 0; at--) {
			long from = pointer + (long) at * this.innerStride;
			long to = pointer + (long) at * this.stride;
			for (int word = this.innerStride - Integer.BYTES; word >= 0; word -= Integer.BYTES) {
				MemoryUtil.memPutInt(to + word, MemoryUtil.memGetInt(from + word));
			}

			MemoryUtil.memPutInt(to + this.innerStride,
					((TerrainVertex) vertices[at]).vitrailBlockId() & BlockStateIds.PACKED_MASK);
		}

		return pointer + (long) vertices.length * this.stride;
	}
}
