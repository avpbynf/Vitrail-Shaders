package dev.vitrail.neoforge.sodium;

import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * The chunk mesh with the elements a pack reads and Sodium does not carry, appended after its own.
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
 * <strong>The new elements have to stay last, and after Sodium's four.</strong> An element the shader
 * does not declare shifts the location of every element AFTER it, in silence, because the pipeline
 * counts every element of the format and the shader module only counts the ones a stage declared.
 * Sodium's own chunk shader declares the first four and knows nothing of ours, so being last is the
 * whole reason it can carry on drawing through this format. Their order among themselves is
 * {@link Extra}'s and is read back by {@link SodiumVertex}, which has to agree with it.
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

	/**
	 * What a texture coordinate is multiplied by before it is stored, which is Sodium's own
	 * {@code 1 << 15} and therefore also the number the prologue divides by.
	 */
	private static final float TEXTURE_SCALE = 32768.0F;

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
			// Said out loud, and it is the one branch here that used to be silent. This decision is
			// taken once for the whole run: the block id stays off the vertex until the game is
			// restarted, and turning the terrain back on afterwards, from the screen or from the
			// file, cannot put it there. Silence would read as the line having done nothing.
			//
			// Without naming a cause, because there are two and this cannot tell them apart: a
			// terrain= line, and no pack chosen yet, which is every first launch of a fresh
			// instance. Blaming the line there would send a reader looking for one they never wrote.
			Vitrail.logger().info("The pack's own terrain program is not wanted where the chunk mesh "
					+ "format is settled, so the mesh keeps the format Sodium gave it for the rest "
					+ "of this run and carries no block id");

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
				+ "instead of {}, the difference being what a pack reads and Sodium does not carry: {}",
				latched.stride, latched.innerStride,
				Arrays.stream(Extra.values()).map(Extra::attribute).toList());

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

		int at = base.getVertexSize();
		for (Extra extra : Extra.values()) {
			builder.addAttribute(extra.attribute(), at, extra.format().blockSize(), extra.format(), 1);
			at += extra.format().blockSize();
		}

		return builder.build();
	}

	/**
	 * The elements this engine adds after Sodium's own, in the order they are laid out.
	 * <p>
	 * Each is four bytes and each is named by {@link SodiumVertex}, which is the side that decodes
	 * them. They are all appended rather than chosen per pack, and that is the one place this engine
	 * cannot follow Iris: {@code FormatAnalyzer} builds a format out of the names a pack's compiled
	 * programs really reference, which Iris can do because it settles the format when a pack loads.
	 * Here the format is settled before any pack is chosen, {@link #latched} says why, so the choice
	 * is between carrying them always and carrying them never. What a conditional would save is also
	 * small: seven packs of the corpus read {@code mc_midTexCoord} and eight read {@code at_tangent}.
	 */
	private enum Extra {

		/** The number {@code block.properties} gave the block state, which a pack reads as {@code mc_Entity}. */
		BLOCK_ID(SodiumVertex.BLOCK_ID, GpuFormat.R32_UINT),

		/**
		 * The middle of the sprite this quad is mapped to, quantised exactly as Sodium quantises the
		 * corner coordinate, so that the two divide down by the same number in the prologue.
		 */
		MID_TEX_COORD(SodiumVertex.MID_TEX_COORD, GpuFormat.RG16_UINT);

		private final String attribute;
		private final GpuFormat format;

		Extra(String attribute, GpuFormat format) {
			this.attribute = attribute;
			this.format = format;
		}

		String attribute() {
			return this.attribute;
		}

		GpuFormat format() {
			return this.format;
		}
	}

	/**
	 * @param materialBits Sodium's own bits in the low byte and the quad's facing above them. The
	 *                     block id is NOT here: it rides on the vertices, because a translucent quad
	 *                     reaches this encoder from the sorter, under a material Sodium chose itself
	 */
	private long encode(long pointer, int materialBits, ChunkVertexEncoder.Vertex[] vertices,
			int sectionIndex) {
		this.innerEncoder.write(pointer, materialBits, vertices, sectionIndex);

		// One value for the whole quad, taken before anything moves: it is the middle of the sprite
		// and not a property of a corner, so the four vertices carry the same number. Iris does the
		// same and packs it the same way, which is what lets a pack divide it by the number it
		// already divides its own texture coordinate by.
		int middle = midTexCoord(vertices);

		// Backwards: every vertex moves up, so the one that has not been moved yet is always the
		// source of the next move. Word by word and from the top of each vertex for the same reason,
		// the two ranges overlapping for all but the last vertex of a quad.
		for (int at = vertices.length - 1; at >= 0; at--) {
			long from = pointer + (long) at * this.innerStride;
			long to = pointer + (long) at * this.stride;
			for (int word = this.innerStride - Integer.BYTES; word >= 0; word -= Integer.BYTES) {
				MemoryUtil.memPutInt(to + word, MemoryUtil.memGetInt(from + word));
			}

			long extra = to + this.innerStride;
			MemoryUtil.memPutInt(extra,
					((TerrainVertex) vertices[at]).vitrailBlockId() & BlockStateIds.PACKED_MASK);
			MemoryUtil.memPutInt(extra + Integer.BYTES, middle);
		}

		return pointer + (long) vertices.length * this.stride;
	}

	/**
	 * The middle of the sprite this quad is mapped to, both axes in one word.
	 * <p>
	 * The mean of the corners, quantised by the same {@code 1 << 15} Sodium quantises a corner with,
	 * and masked to sixteen bits so that the pair reads as the {@code uvec2} the format declares.
	 * Iris packs it identically, {@code XHFPModelVertexType.encodeOld}, and a pack divides it by
	 * 32768 exactly as it divides its own texture coordinate.
	 * <p>
	 * The mean is taken over however many vertices the encoder was handed rather than over four. It
	 * is always four today, the chunk renderer meshing quads and nothing else, but a hardcoded
	 * quarter would answer a quarter of the truth rather than fail if that ever stopped being so.
	 */
	private static int midTexCoord(ChunkVertexEncoder.Vertex[] vertices) {
		if (vertices.length == 0) {
			return 0;
		}

		float u = 0.0F;
		float v = 0.0F;
		for (ChunkVertexEncoder.Vertex vertex : vertices) {
			u += vertex.u;
			v += vertex.v;
		}

		u /= vertices.length;
		v /= vertices.length;

		return (Math.round(u * TEXTURE_SCALE) & 0xFFFF) | ((Math.round(v * TEXTURE_SCALE) & 0xFFFF) << 16);
	}
}
