package dev.vitrail.neoforge.sodium;

import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;

import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * The chunk mesh with the elements a pack reads and Sodium does not carry, appended after its own.
 * <p>
 * <strong>Sodium's own twenty bytes are not packed here.</strong> Sodium is under the PolyForm
 * Shield licence and this project is under the LGPL, so its encoder is called as a black box and
 * the bytes after each vertex are ours. Reimplementing the packing would be a plain copy of code
 * this project may not take, and it would also be a second thing to keep in step with every release.
 * <p>
 * One word of those twenty is written again all the same, and only where a pack asked: the colour,
 * under {@code separateAo}. It is not repacked, it is replaced, out of the same two fields the
 * encoder was handed and through Sodium's own published {@code ColorABGR}. {@link #separating} says
 * what that means and why the encoder cannot be left to answer it.
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
	 * This format, built the first time one is wanted and kept because it holds no state of its own.
	 * Null until then, and null for good once {@link #broken} is set.
	 * <p>
	 * <strong>Which of the two answers is served is not settled for the run, and that is why a pack
	 * picked in a running game draws the world.</strong> It moves only in {@link #settle()}, and
	 * {@code TerrainDraw.wanted} is what asks for that to happen: it changes what the options say and
	 * has the world rebuilt, and everything meshed at the old stride is thrown out on the way.
	 */
	private static TerrainMesh built;

	/**
	 * Set when this format cannot be built at all, so the failure is reported once rather than at
	 * every reload. Nothing clears it: the reason is the shape of Sodium's own format and no reload
	 * moves that.
	 */
	private static boolean broken;

	/** The answer in force, which only {@link #settle()} moves. */
	private static boolean carrying;

	/**
	 * Whether the ambient occlusion is being kept out of the vertex colour, {@code separateAo} in a
	 * pack's {@code shaders.properties}. Read from {@code TerrainDraw.separateAo} and moved only by
	 * {@link #settle()}, like {@link #carrying} and for the same reason.
	 * <p>
	 * <strong>Iris makes this a property of the MESH and so does this</strong>,
	 * {@code XHFPTerrainVertex.java:152}: it writes {@code ColorABGR.withAlpha(color, ao)} where it
	 * otherwise writes {@code ColorARGB.mulRGB(color, ao)}, so the pack reads the block's own tint
	 * in {@code gl_Color.rgb} and the occlusion in {@code gl_Color.a} rather than the two already
	 * multiplied together. Six packs of the corpus ask for it. Left unread, the occlusion goes into
	 * the albedo and is then reflected, exposed and graded by everything downstream of it, which is
	 * an image that looks right and is not.
	 * <p>
	 * Per settle and not per quad because the answer must not move under the chunk builder: workers
	 * mesh sections over many frames, and a flag that turned in the middle would leave one region's
	 * alpha meaning the occlusion and its neighbour's meaning one. A load that moves it asks for the
	 * world to be built again, {@code TerrainDraw.separateAoSettled}, and that rebuild is what runs
	 * this method.
	 * <p>
	 * Volatile where {@link #carrying} is not, and the difference is who reads them: that one is
	 * only ever read inside this class's synchronized methods, this one is read by every chunk
	 * builder worker in the encoder.
	 */
	private static volatile boolean separating;

	/**
	 * Whether the answer has ever been said out loud. Kept apart from {@link #carrying} because the
	 * two share their initial value: without it, the first settle of a game nobody picked a pack in
	 * would find nothing changed and stay silent, which is the case that most needs the line.
	 */
	private static boolean said;

	/**
	 * What a texture coordinate is multiplied by before it is stored, which is Sodium's own
	 * {@code 1 << 15} and therefore also the number the prologue divides by.
	 */
	private static final float TEXTURE_SCALE = 32768.0F;

	/**
	 * The sign of the texture area of a face whose {@code uv} rectangle is written the usual way
	 * round, which is what {@link #frame} starts from and keeps only when no triangle of the quad
	 * has an area to measure at all.
	 * <p>
	 * A face lays its corners out as {@code (minU,minV)}, {@code (minU,maxV)}, {@code (maxU,maxV)},
	 * {@code (maxU,minV)}, {@code CuboidFace.UVs.getVertexU} and {@code getVertexV}, and the area
	 * comes out {@code -(maxU-minU)(maxV-minV)}. A face may also declare a rotation, which shifts
	 * that order cyclically, {@code Quadrant.rotateVertexIndex}: it moves which corner is first and
	 * leaves the area exactly where it was.
	 * <p>
	 * <strong>It is the usual way round and not the only one</strong>, so this is a majority and not
	 * a law. {@code CuboidFace.UVs} keeps the JSON numbers unsorted and the corners are read from
	 * them raw: of the 3696 {@code uv} rectangles in the block models 26.2 ships, 391 have one axis
	 * reversed and therefore the opposite handedness. Sodium reflects the winding of some quads as
	 * well, {@code DefaultFluidRenderer}, and a reflection turns the sign too. None of that reaches
	 * {@link #handedness}, which measures the corners it is handed; it reaches this constant.
	 * <p>
	 * The handedness of a rectangle is the product of the signs of its two axes, so a degenerate one
	 * leaves one factor with no value and this constant assumes it forward. The measurement backs
	 * the assumption without making it a measurement: all 160 degenerate rectangles of those models
	 * keep the axis that survives forward, and a model this engine has not seen may not.
	 */
	private static final float UNREVERSED_AREA = -1.0F;

	/**
	 * The squared length under which {@link #orthogonalise} takes a tangent to have had nothing left
	 * of it once the normal was subtracted, which is Iris's own {@code NormalHelper.EPS} weighed the
	 * way Iris weighs it, on the square and not on the length.
	 */
	private static final float FLATTENED = 1.0E-20F;

	/** What Sodium calls the element the block's tint rides in, and {@link SodiumVertex} with it. */
	private static final String COLOUR = "a_Color";

	private final ChunkVertexType inner = ChunkMeshFormats.COMPACT;
	private final ChunkVertexEncoder innerEncoder = this.inner.getEncoder();
	private final ChunkVertexEncoder encoder = this::encode;
	private final int innerStride = this.inner.getVertexFormat().getVertexSize();
	private final VertexFormat format = extend(this.inner.getVertexFormat());
	private final int stride = this.format.getVertexSize();
	private final int colour = colourOffset(this.inner.getVertexFormat());

	private TerrainMesh() {
		if (this.innerStride % Integer.BYTES != 0) {
			throw new IllegalStateException("The chunk mesh is " + this.innerStride + " bytes, which "
					+ "is not a whole number of words, and this engine moves it a word at a time");
		}
	}

	/**
	 * Where the colour sits in Sodium's own vertex, taken from the format rather than written down.
	 * <p>
	 * A number in the source would be a second copy of a fact only Sodium holds, and one that no
	 * build would catch moving. Refused rather than guessed when the name is not there, which
	 * {@link #settle()} turns into a game that goes on running on Sodium's own mesh: writing the
	 * wrong word of a vertex is a world drawn out of the position or the light map.
	 */
	private static int colourOffset(VertexFormat base) {
		for (VertexFormatElement element : base.getElements()) {
			if (element.name().equals(COLOUR) && element.format().blockSize() == Integer.BYTES) {
				return element.offset();
			}
		}

		throw new IllegalStateException("The chunk mesh carries no four byte " + COLOUR + ", so this "
				+ "engine cannot say where a pack's separateAo would put the ambient occlusion");
	}

	/**
	 * The format in force, or null to leave Sodium's own alone. <strong>Answers what
	 * {@link #settle()} last decided and never decides anything itself</strong>, which is the whole
	 * of the safety here.
	 * <p>
	 * The reason is that this is not read once per reload. Two of the three readers are in
	 * {@code RenderSectionManager}'s constructor, but the third is
	 * {@code RenderRegion$DeviceResources}, built by {@code RenderRegion.createResources} at a
	 * region's first upload and again after {@code update} has dropped it, which is to say all
	 * through an ordinary session as the player moves. An answer that moved on its own would size
	 * one region's geometry arena at a stride the living chunk builder is not writing, and neither
	 * side reports it: the arena multiplies segment offsets by its stride, so the uploads land in
	 * the wrong place and the world draws out of garbage.
	 */
	public static synchronized ChunkVertexType current() {
		return carrying ? built : null;
	}

	/**
	 * Takes the answer the options now ask for, at the one instant it is safe to change it.
	 * <p>
	 * That instant is the head of Sodium's {@code initRenderer}, the only place its section manager is
	 * built, and {@code MixinSodiumWorldRendererInit} is what calls this from there. What makes it
	 * safe is measured and narrower than it looks: nothing between here and that constructor asks for
	 * the format, so no two askers can end up disagreeing. Everything that will ask is built
	 * afterwards, and every section is meshed again after that.
	 * <p>
	 * Built here rather than in a static field so that a mesh this cannot extend leaves the game
	 * running on Sodium's own instead of failing to load a class in the middle of a world.
	 */
	public static synchronized void settle() {
		boolean asked = TerrainDraw.asked() && !broken;
		if (asked && built == null) {
			try {
				built = new TerrainMesh();
			} catch (RuntimeException e) {
				broken = true;
				asked = false;
				Vitrail.logger().error("This engine cannot extend the chunk mesh, so the terrain keeps "
						+ "Sodium's own and no pack will draw it", e);
			}
		}

		// Ahead of the guard below and not after it, because the two answers do not move together: a
		// pack swapped for another that also wants the terrain leaves the format exactly where it
		// was and can still turn this over.
		boolean apart = asked && TerrainDraw.separateAo();
		if (separating != apart) {
			separating = apart;
			Vitrail.logger().info("The terrain's ambient occlusion {}",
					apart ? "rides in the alpha of the vertex colour rather than multiplied into it, "
							+ "which is what a pack's separateAo asks for"
							: "is multiplied into the vertex colour again, as the game does it");
		}

		if (said && asked == carrying) {
			return;
		}

		said = true;
		carrying = asked;
		if (!asked) {
			// Said out loud, and it used to be the silent branch. Without naming a cause, because
			// there are three and this cannot tell them apart: a terrain= line, no pack chosen yet,
			// which is every first launch of a fresh instance, and a terrain program that threw.
			Vitrail.logger().info("The pack's own terrain program is not wanted, so the mesh keeps the "
					+ "format Sodium gave it and carries none of {}",
					Arrays.stream(Extra.values()).map(Extra::attribute).toList());

			return;
		}

		Vitrail.logger().info("The chunk mesh carries {} bytes a vertex instead of {}, the difference "
				+ "being what a pack reads and Sodium does not carry: {}",
				built.stride, built.innerStride,
				Arrays.stream(Extra.values()).map(Extra::attribute).toList());
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
	 * them. They are all appended rather than chosen per pack, where Iris keeps only the ones a pack's
	 * compiled programs really reference, {@code FormatAnalyzer}. <strong>The reason this engine could
	 * not do the same has gone</strong>: it was that the format was settled before any pack was
	 * chosen, and the format follows the pack now. What is left is a plain difference in what a vertex
	 * costs, and it is not closed here. What it would save is small either way: seven packs of the
	 * corpus read {@code mc_midTexCoord} and eight read {@code at_tangent}.
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

			// After the move and not before it, so that what is written stands whether this vertex was
			// the source of the next move or not. The occlusion goes in the alpha and the tint comes
			// back out of the albedo undivided, which is Iris's own pair of writes.
			if (separating) {
				MemoryUtil.memPutInt(to + this.colour,
						ColorABGR.withAlpha(vertices[at].color, vertices[at].ao));
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
	 * engine used before is one of the six axes of {@code ModelQuadFacing}, so a plant drawn as a
	 * cross, a sloped fluid surface and every model that is not a box got one of six wrong answers
	 * or the seventh value, {@code UNASSIGNED}, which had no answer at all. Newell's sum over the
	 * corners is right for a quad that is not planar either, and it costs the same.
	 * <p>
	 * The tangent is the direction the texture's own U axis points in, taken from the two edges and
	 * their texture coordinates, and then squared up against the normal by {@link #orthogonalise}. It
	 * is what every normal map on the terrain is read through, and this engine used to hand back a
	 * constant, which tilts every one of them the same wrong way. Its handedness says which way the
	 * third axis of that frame goes and is the difference between a bump and a dent.
	 * <p>
	 * A quad whose texture coordinates are degenerate, the two edges mapping to the same direction,
	 * has no tangent to find. It gets an axis perpendicular to the normal rather than a zero, for the
	 * reason {@code VertexPrologue} gives about the constant it replaces: a pack normalises what it
	 * reads, and normalising a zero puts a NaN in the colour.
	 */
	private static float[] frame(ChunkVertexEncoder.Vertex[] vertices) {
		float[] frame = new float[7];

		// The answer for a quad no triangle of which has an area to measure. A triangle that has one
		// overwrites it below, whether or not it goes on to yield a direction.
		frame[6] = handedness(UNREVERSED_AREA);

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
		// half of the same quad holds. Four corners is a contract on both roads in: push refuses any
		// other length before the encoder is called, and writeExternal, which checks nothing, is fed
		// the sorter's own array, allocated by ChunkVertexEncoder.Vertex.uninitializedQuad.
		if (!tangent(frame, vertices[0], vertices[1], vertices[2])
				&& !tangent(frame, vertices[2], vertices[3], vertices[0])) {
			perpendicular(frame);
		}

		orthogonalise(frame);

		return frame;
	}

	/**
	 * Takes the normal's own direction out of the tangent, so that what a pack reads is at a right
	 * angle to {@code gl_Normal} on every quad and not only where the mapping happens to be affine.
	 * <p>
	 * <strong>Iris does this to every tangent it packs</strong>, {@code NormalHelper.packDiamondByte}
	 * lines 489 to 510: it subtracts {@code n * dot(n, tangent)}, and what it stores is an angle in
	 * the plane that leaves, which its own patched vertex stage turns back into
	 * {@code normalize(p.x * t1 + p.y * t2)} of a basis built from the normal
	 * ({@code SodiumTransformer} lines 191 to 198). A pack under Iris therefore always reads a unit
	 * vector exactly perpendicular to the normal, and the idiom seven of the eight packs write,
	 * {@code cross(at_tangent.xyz, gl_Normal.xyz) * at_tangent.w}, comes out unit as they assume.
	 * Packed raw, that cross product is short by the sine of the angle between the two and points
	 * along a bitangent the pack never asked for.
	 * <p>
	 * The direction of that cross product does not move: what is subtracted is a multiple of the
	 * normal, and the normal crossed with itself is nought. So this lengthens the bitangent back to
	 * one and leaves the handedness bit meaning what {@link #handedness} says it means.
	 * <p>
	 * <strong>What is left of the difference is the quantisation.</strong> Iris spends one byte on an
	 * angle in a plane, so its tangent is perpendicular to the last bit; this spends three on the
	 * vector itself and rounds each of them, so the dot product with the normal comes back at a few
	 * thousandths rather than at nought. Three bytes for three components is what
	 * {@link Extra#TANGENT} declares and this engine has no patched vertex text to unpack an angle
	 * in.
	 */
	private static void orthogonalise(float[] frame) {
		float along = frame[0] * frame[3] + frame[1] * frame[4] + frame[2] * frame[5];
		frame[3] -= frame[0] * along;
		frame[4] -= frame[1] * along;
		frame[5] -= frame[2] * along;
		if (frame[3] * frame[3] + frame[4] * frame[4] + frame[5] * frame[5] > FLATTENED) {
			normalise(frame, 3);

			return;
		}

		basis(frame);
	}

	/**
	 * The first axis of Frisvad's basis for the normal already in the frame, which is what Iris
	 * substitutes when the projection above leaves nothing, {@code NormalHelper.onbFromUnitNormal}
	 * lines 468 to 479.
	 * <p>
	 * Written out rather than reasoned out, because the point of that construction is that it holds
	 * at both poles without a branch on which axis the normal is nearest. It is not the same axis as
	 * {@link #perpendicular}'s and the two are not interchangeable: this one is reached with a
	 * tangent that came out parallel to the normal, where that one is reached with no tangent at all.
	 */
	private static void basis(float[] frame) {
		float side = frame[2] >= 0.0F ? 1.0F : -1.0F;
		float scale = -1.0F / (side + frame[2]);
		frame[3] = 1.0F + side * frame[0] * frame[0] * scale;
		frame[4] = side * frame[0] * frame[1] * scale;
		frame[5] = -side * frame[0];
		normalise(frame, 3);
	}

	/**
	 * The tangent of one triangle into the frame, or false when its texture coordinates are
	 * degenerate and there is none to find. The handedness is written from the area whenever there
	 * is one to measure, direction or no direction, so only a quad no triangle of which has an area
	 * keeps the frame's starting sign.
	 * <p>
	 * <strong>The two refusals below both part from the reference, and differently.</strong> Iris
	 * does not refuse on a texture area of nought at all: the copy of
	 * {@code NormalHelper.computeTangent} that the terrain feeds substitutes {@code f = 1} for the
	 * reciprocal and carries on with the direction the mapping still implies - and it does that on
	 * an exact zero, where this refuses anything under a threshold, so a quad whose area is small
	 * but real is refused here and served there. The second refusal, a tangent that comes out as
	 * nothing, is one that copy does make, but not on the same terms: it tests an exact zero after
	 * the normalise, this tests a sum of components before it.
	 * <p>
	 * A quad refused here is retried on its other triangle, and only when that refuses too does the
	 * caller fall back on {@link #perpendicular}.
	 * <p>
	 * <strong>Neither refusal is a decision.</strong> Nothing in the API of 26.2 stands in the way of
	 * answering as Iris answers, so both are divergences, and the terrain page says what they cost
	 * and how little is known of how far they reach. The worst of it is not the rotated frame it
	 * describes: when the first triangle has no area, Iris does not retry at all - it substitutes,
	 * finds a direction, and its own handedness test lands on nothing, which it reads as {@code +1}
	 * - where this refuses, retries, and takes the second triangle's sign, which can be the other
	 * one. That is the handedness bit and not the direction.
	 */
	private static boolean tangent(float[] frame, ChunkVertexEncoder.Vertex a,
			ChunkVertexEncoder.Vertex b, ChunkVertexEncoder.Vertex c) {
		float dv1 = b.v - a.v;
		float dv2 = c.v - a.v;
		float area = (b.u - a.u) * dv2 - (c.u - a.u) * dv1;
		if (Math.abs(area) < 1.0E-9F) {
			return false;
		}

		// Before the direction, because this triangle can fail to yield one and its area is measured
		// all the same: the sign is the mapping's answer either way, and the fallback below has none.
		frame[6] = handedness(area);

		float scale = 1.0F / area;
		frame[3] = ((b.x - a.x) * dv2 - (c.x - a.x) * dv1) * scale;
		frame[4] = ((b.y - a.y) * dv2 - (c.y - a.y) * dv1) * scale;
		frame[5] = ((b.z - a.z) * dv2 - (c.z - a.z) * dv1) * scale;

		// A determinant that is not zero does not promise a direction: three corners on one line
		// with a mapping that is not affine give one, and the tangent still comes out as nothing.
		// Iris refuses this case too, and it is what makes its retry fire at all. On what terms it
		// refuses is not the same, and the javadoc above says how. The sum below is of absolute
		// values, so two components cannot cancel each other into a false refusal.
		if (Math.abs(frame[3]) + Math.abs(frame[4]) + Math.abs(frame[5]) < 1.0E-9F) {
			return false;
		}

		normalise(frame, 3);

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

	/**
	 * Which way the third axis of the frame turns, which is MINUS the sign of the texture area.
	 * <p>
	 * Seven of the corpus's eight packs read that sign, four of them written exactly as
	 * {@code cross(at_tangent.xyz, gl_Normal.xyz) * at_tangent.w}, which is Iris's convention: that
	 * cross product is the true bitangent for {@code w = +1} and its opposite for {@code w = -1}, so
	 * {@code w} is what corrects the chirality rather than a fixed negation. Body Camera is the
	 * eighth: it takes {@code .xyz} alone and crosses it with the normal unscaled, which is the
	 * {@code +1} chirality applied to every quad whatever this answers.
	 * <p>
	 * Iris reaches the same value from the other end,
	 * {@code sign(dot(bitangent, tangent x normal))} in {@code NormalHelper.computeTangent}, which
	 * reduces to this for a quad whose corners are in one plane. Written the other way round it is
	 * not a subtle error: every normal map on the terrain has its green channel inverted and lights
	 * a bump as a dent.
	 * <p>
	 * <strong>Every place that writes the sign goes through here, and one lies outside this
	 * file</strong>: the {@code at_tangent} entry of {@code VertexPrologue.BETTER_DEFAULTS}, which
	 * answers the same question for a mesh carrying no tangent at all. Nothing checks that the two
	 * agree, so a hand that turns one has to turn the other.
	 */
	private static float handedness(float textureArea) {
		return textureArea < 0.0F ? 1.0F : -1.0F;
	}

	/**
	 * Any unit vector at a right angle to the normal already in the frame. The direction only: the
	 * sign is whatever a triangle of this quad measured, or the frame's starting value when none
	 * could. Being at a right angle already, it is what {@link #orthogonalise} leaves alone.
	 * <p>
	 * <strong>Iris answers this case with whatever tangent it last managed to compute.</strong> When
	 * both triangles refuse, {@code NormalHelper.computeTangent} returns before it writes its output
	 * vector, so {@code XHFPTerrainVertex} keeps the one it already held. That field belongs to the
	 * encoder, and Sodium builds one per pass and facing and reuses it for every section a worker
	 * meshes, so what is carried in is an earlier quad of the same bucket and need not belong to
	 * this mesh at all. Before any tangent has been computed it holds {@code (0,1,0)} with a
	 * handedness of {@code +1}, which is the one place the reference states the value this file
	 * starts from. What the pack reads is not the carried direction either: {@code encodeNormalTangent}
	 * takes the normal's component out of it - out of every tangent it packs, not only a carried one
	 * - and when nothing is left it substitutes an axis of a basis built from that normal, which is
	 * a case this reaches for every quad that gets here.
	 * <p>
	 * So the difference is narrower than a synthesized axis against a carried one, and it is real
	 * twice over: this answer depends on the quad alone where that one depends on the order the
	 * bucket was filled in, and the axis is not the same axis, Frisvad's basis against a cross with
	 * whichever axis the normal is least aligned to. Nothing in 26.2 makes the carry-over
	 * impossible, so it is a divergence rather than a choice.
	 */
	private static void perpendicular(float[] frame) {
		// Crossed with whichever axis the normal is least aligned to, so the result is never a zero.
		boolean upright = Math.abs(frame[1]) < Math.abs(frame[0]);
		frame[3] = upright ? -frame[2] : 0.0F;
		frame[4] = upright ? 0.0F : frame[2];
		frame[5] = upright ? frame[0] : -frame[1];
		normalise(frame, 3);
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
	 * <p>
	 * <strong>Iris subtracts from the WORLD position and gets the same byte</strong>, which is worth
	 * writing down so that nobody closes a gap that is not one.
	 * {@code MixinChunkMeshBuildTask.iris$onRenderModel} hands {@code blockPos.getX()} straight in,
	 * {@code ExtendedDataHelper.computeMidBlock} masks it to sixteen bits, and Sodium's vertex is
	 * section local, so the difference it carries is a whole number of blocks. Sixty-four times a
	 * whole number of sixteens is a whole number of two hundred and fifty-sixes, and the mask to a
	 * byte at the end of the packing takes it away. What does not survive is the last bit of
	 * precision: a float holds {@code 65535.5} exactly and then loses half a unit at the multiply,
	 * so far from the origin Iris's own answer can land one sixty-fourth off where this one does not.
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
	 * The mean is taken over however many vertices the encoder was handed rather than over four,
	 * which costs nothing and reads the same. Four is a contract all the same, and {@link #frame}
	 * relies on it outright: both roads into this encoder hand over a quad and one of them refuses
	 * anything else.
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
