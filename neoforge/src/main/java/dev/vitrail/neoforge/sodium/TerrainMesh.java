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
		MID_TEX_COORD(SodiumVertex.MID_TEX_COORD, GpuFormat.RG16_UINT),

		/**
		 * The offset from this vertex to the middle of its block in sixty-fourths, and the light the
		 * block gives off in the fourth byte.
		 * <p>
		 * Signed bytes, unscaled, which is Iris's own shape: the packs divide by 64 themselves, four
		 * of them writing {@code at_midBlock.xyz / 64.0} word for word, so handing them anything
		 * already divided would move every block they voxelise by that factor again.
		 */
		MID_BLOCK(SodiumVertex.MID_BLOCK, GpuFormat.RGBA8_SINT),

		/**
		 * The quad's own normal, and the tangent of its texture mapping with the sign that says which
		 * way the third axis of that frame points.
		 * <p>
		 * Signed and normalised, so the shader reads them as a {@code vec4} in minus one to one with
		 * nothing to undo. Iris packs the pair into one word of its own and unpacks it in the patched
		 * text; two elements cost four bytes more and no arithmetic, and this engine has no patched
		 * text to unpack them in.
		 */
		NORMAL(SodiumVertex.NORMAL, GpuFormat.RGBA8_SNORM),

		TANGENT(SodiumVertex.TANGENT, GpuFormat.RGBA8_SNORM);

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
	 * @param materialBits Sodium's own, untouched. Nothing of this engine rides there any more:
	 *                     everything it adds is on the vertices, because a translucent quad reaches
	 *                     this encoder from the sorter, under a material Sodium chose itself
	 */
	private long encode(long pointer, int materialBits, ChunkVertexEncoder.Vertex[] vertices,
			int sectionIndex) {
		this.innerEncoder.write(pointer, materialBits, vertices, sectionIndex);

		// One value for the whole quad, taken before anything moves: it is the middle of the sprite
		// and not a property of a corner, so the four vertices carry the same number. Iris does the
		// same and packs it the same way, which is what lets a pack divide it by the number it
		// already divides its own texture coordinate by.
		int middle = midTexCoord(vertices);

		// Both are properties of the QUAD and not of a corner, like the middle above: the four
		// vertices carry the same pair, and a pack that reads a normal per vertex on chunk geometry
		// is reading what the face is, not what the corner is.
		float[] frame = frame(vertices);
		int normal = packAxis(frame[0], frame[1], frame[2], 0.0F);
		int tangent = packAxis(frame[3], frame[4], frame[5], frame[6]);

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
			MemoryUtil.memPutInt(extra + 2L * Integer.BYTES, midBlock(vertices[at]));
			MemoryUtil.memPutInt(extra + 3L * Integer.BYTES, normal);
			MemoryUtil.memPutInt(extra + 4L * Integer.BYTES, tangent);
		}

		return pointer + (long) vertices.length * this.stride;
	}

	/**
	 * The quad's normal and the tangent of its texture mapping, as seven floats: three, then three,
	 * then the handedness.
	 * <p>
	 * <strong>The normal comes from the geometry and not from the facing.</strong> The facing this
	 * engine used before is one of six axes, so a plant drawn as a cross, a sloped fluid surface
	 * and every model that is not a box got one of six wrong answers or the eighth value, which had
	 * no answer at all. Newell's sum over the corners is right for a quad that is not planar either,
	 * and it costs the same.
	 * <p>
	 * The tangent is the direction the texture's own U axis points in, taken from the two edges and
	 * their texture coordinates. It is what every normal map on the terrain is read through, and this
	 * engine used to hand back a constant, which tilts every one of them the same wrong way. Its
	 * handedness says which way the third axis of that frame goes and is the difference between a
	 * bump and a dent.
	 * <p>
	 * A quad whose texture coordinates are degenerate, the two edges mapping to the same direction,
	 * has no tangent to find. It gets an axis perpendicular to the normal rather than a zero, for the
	 * reason {@code VertexPrologue} gives about the constant it replaces: a pack normalises what it
	 * reads, and normalising a zero puts a NaN in the colour.
	 */
	private static float[] frame(ChunkVertexEncoder.Vertex[] vertices) {
		float[] frame = new float[7];
		if (vertices.length < 3) {
			frame[1] = 1.0F;
			frame[3] = 1.0F;
			frame[6] = 1.0F;

			return frame;
		}

		// Newell: every edge of the loop contributes, so a quad whose four corners are not in one
		// plane still answers the plane they are closest to instead of the plane of its first three.
		for (int at = 0; at < vertices.length; at++) {
			ChunkVertexEncoder.Vertex current = vertices[at];
			ChunkVertexEncoder.Vertex next = vertices[(at + 1) % vertices.length];
			frame[0] += (current.y - next.y) * (current.z + next.z);
			frame[1] += (current.z - next.z) * (current.x + next.x);
			frame[2] += (current.x - next.x) * (current.y + next.y);
		}

		normalise(frame, 0);

		// The second triangle when the first has nothing to say, which is Iris's own retry in
		// computeTangentForQuad: three corners of a quad can share a texture coordinate while the
		// fourth does not, and taking the perpendicular there would throw away a tangent the other
		// half of the same quad holds.
		if (!tangent(frame, vertices[0], vertices[1], vertices[2])
				&& (vertices.length < 4 || !tangent(frame, vertices[2], vertices[3], vertices[0]))) {
			perpendicular(frame);
		}

		return frame;
	}

	/**
	 * The tangent of one triangle into the frame, or false when its texture coordinates are
	 * degenerate and there is none to find.
	 */
	private static boolean tangent(float[] frame, ChunkVertexEncoder.Vertex a,
			ChunkVertexEncoder.Vertex b, ChunkVertexEncoder.Vertex c) {
		float dv1 = b.v - a.v;
		float dv2 = c.v - a.v;
		float area = (b.u - a.u) * dv2 - (c.u - a.u) * dv1;
		if (Math.abs(area) < 1.0E-9F) {
			return false;
		}

		float scale = 1.0F / area;
		frame[3] = ((b.x - a.x) * dv2 - (c.x - a.x) * dv1) * scale;
		frame[4] = ((b.y - a.y) * dv2 - (c.y - a.y) * dv1) * scale;
		frame[5] = ((b.z - a.z) * dv2 - (c.z - a.z) * dv1) * scale;
		normalise(frame, 3);

		// Which way the third axis of the frame turns. MINUS the sign of the texture area, and the
		// minus is the whole of it: every pack builds its bitangent as
		// cross(at_tangent.xyz, gl_Normal.xyz) * at_tangent.w, which is Iris's convention, and that
		// cross product is the true bitangent NEGATED. Iris arrives at the same place from the other
		// end, sign(dot(bitangent, tangent x normal)) in NormalHelper.computeTangent. Written the
		// other way round it is not a subtle error: every normal map on the terrain has its green
		// channel inverted and lights a bump as a dent.
		frame[6] = area < 0.0F ? 1.0F : -1.0F;

		return true;
	}

	/** Three of those floats to unit length, or to the up axis when there is no length to speak of. */
	private static void normalise(float[] frame, int at) {
		float length = (float) Math.sqrt(frame[at] * frame[at] + frame[at + 1] * frame[at + 1]
				+ frame[at + 2] * frame[at + 2]);
		if (length < 1.0E-9F) {
			frame[at] = 0.0F;
			frame[at + 1] = 1.0F;
			frame[at + 2] = 0.0F;

			return;
		}

		frame[at] /= length;
		frame[at + 1] /= length;
		frame[at + 2] /= length;
	}

	/** Any unit vector at a right angle to the normal already in the frame. */
	private static void perpendicular(float[] frame) {
		// Crossed with whichever axis the normal is least aligned to, so the result is never a zero.
		boolean upright = Math.abs(frame[1]) < Math.abs(frame[0]);
		frame[3] = upright ? -frame[2] : 0.0F;
		frame[4] = upright ? 0.0F : frame[2];
		frame[5] = upright ? frame[0] : -frame[1];
		normalise(frame, 3);
		frame[6] = 1.0F;
	}

	/** Three components and a fourth into one word of signed bytes, as the format declares them. */
	private static int packAxis(float x, float y, float z, float w) {
		return (byteOf(x) & 0xFF) | ((byteOf(y) & 0xFF) << 8) | ((byteOf(z) & 0xFF) << 16)
				| ((byteOf(w) & 0xFF) << 24);
	}

	/**
	 * One component of a unit vector as a signed byte.
	 * <p>
	 * Scaled by 127 and not by 128, so that one comes back as one: the backend divides a signed byte
	 * by 127, and a value stored at 128 would have wrapped to the other end of the range anyway.
	 */
	private static int byteOf(float value) {
		return Math.round(Math.clamp(value, -1.0F, 1.0F) * 127.0F);
	}

	/**
	 * How far this vertex is from the middle of its own block, per axis, plus the light that block
	 * gives off.
	 * <p>
	 * In sixty-fourths of a block and signed, which is the unit the packs divide by and the range a
	 * byte holds: a vertex is at most one block from a middle in each axis, so the value stays inside
	 * plus or minus sixty-four. Iris packs it the same way, {@code ExtendedDataHelper.packMidBlock},
	 * and the emission goes in the fourth byte there too.
	 * <p>
	 * The position is the section's own, which is what the mesh is written in and what
	 * {@link TerrainVertex#pack} reduced the block's world position to.
	 */
	private static int midBlock(ChunkVertexEncoder.Vertex vertex) {
		int origin = ((TerrainVertex) vertex).vitrailBlockOrigin();

		return (offset(TerrainVertex.origin(origin, 0), vertex.x) & 0xFF)
				| ((offset(TerrainVertex.origin(origin, 1), vertex.y) & 0xFF) << 8)
				| ((offset(TerrainVertex.origin(origin, 2), vertex.z) & 0xFF) << 16)
				| (TerrainVertex.emission(origin) << 24);
	}

	/** One axis of that offset, from the corner of the block to the vertex, in sixty-fourths. */
	private static int offset(int block, float vertex) {
		return (int) ((block + 0.5F - vertex) * 64.0F);
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
