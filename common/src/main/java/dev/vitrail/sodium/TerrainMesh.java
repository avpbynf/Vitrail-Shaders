package dev.vitrail.sodium;

import dev.vitrail.glsl.SodiumVertex;
import dev.vitrail.glsl.TangentFrame;
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
import java.util.List;

/**
 * The chunk mesh with the elements a pack reads and Sodium does not carry, appended after its own.
 * <p>
 * <strong>Not one byte of Sodium's own twenty is written here.</strong> Sodium is under the PolyForm
 * Shield licence and this project is under the LGPL, so its encoder is called as a black box and
 * the bytes after each vertex are ours. Reimplementing the packing would be a plain copy of code
 * this project may not take, and it would also be a second thing to keep in step with every release.
 * <p>
 * <strong>The licence is only half of it: the game's own chunk shader goes on drawing this
 * mesh.</strong> It draws it on every road where this engine hands a pass back - a program that
 * would not compile, a pass the pack serves nothing for, targets that could not be opened - and,
 * oftener than any of those, during the warm up that follows every load and every resource reload,
 * where the chain compiles one program a frame and the world is drawn meanwhile. That shader reads
 * {@code a_Color}, multiplies it into the texture and alpha tests the product, so a word rewritten
 * here to mean something else punches holes through every cutout block on screen. Anything a pack
 * wants that one of those twenty bytes already answers differently gets an element of its own
 * instead, which is what {@link Extra#TINT_AND_AO} is for: each side then reads its own word and
 * neither has to know which of the two is drawing.
 * <p>
 * The cost of that is one shuffle. Sodium's encoder lays four vertices out at its own stride, so the
 * three after the first land where this format does not want them and are moved up, backwards and
 * word by word so that a vertex is never written over a word still to be read.
 * <p>
 * <strong>How many of the five are appended follows the pack.</strong> {@code TerrainDraw} translates
 * the pack's six chunk programs far enough to know which names they read, and hands that list here;
 * a vertex is then anything from twenty-four bytes to forty. The ones left out close the gap
 * rather than leaving a hole, so an element's offset is where it lands in this pack's mesh and not
 * where it lands in {@link Extra}.
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
	 * The format in force, or null to leave Sodium's own alone. Held rather than rebuilt because it
	 * holds no state of its own.
	 * <p>
	 * <strong>Which answer is served is not settled for the run, and that is why a pack picked in a
	 * running game draws the world.</strong> It moves only in {@link #settle()}, and
	 * {@code TerrainDraw} is what asks for that to happen: the elements it publishes change, it has
	 * the world rebuilt, and everything meshed at the old stride is thrown out on the way.
	 */
	private static TerrainMesh built;

	/**
	 * Set when this format cannot be built at all, so the failure is reported once rather than at
	 * every reload. Nothing clears it: the reason is the shape of Sodium's own format and no reload
	 * moves that.
	 */
	private static boolean broken;

	/**
	 * What the log last announced, and null until the first settle of a run.
	 * <p>
	 * Null and not the empty list, because the two would otherwise share their value: without the
	 * difference, the first settle of a game nobody picked a pack in would find nothing changed and
	 * stay silent, which is the case that most needs the line.
	 */
	private static List<String> said;

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

	private final ChunkVertexType inner = ChunkMeshFormats.COMPACT;
	private final ChunkVertexEncoder innerEncoder = this.inner.getEncoder();
	private final ChunkVertexEncoder encoder = this::encode;
	private final int innerStride = this.inner.getVertexFormat().getVertexSize();

	/** The whole format this was asked for, Sodium's own names first, kept to answer a second ask. */
	private final List<String> carried;

	/** The ones of {@link Extra} that list names, in the order they are laid out. */
	private final List<Extra> extras;

	/**
	 * Where each element of {@link Extra} starts, counted from the end of Sodium's own bytes, and
	 * {@link #ABSENT} for one this pack does not carry. Indexed by ordinal, which is how the layout
	 * and the encoder read the same table rather than each counting for itself.
	 */
	private final int[] offsets;

	private final VertexFormat format;
	private final int stride;

	/** What {@link #offsets} holds for an element the pack was not asked to carry. */
	private static final int ABSENT = -1;

	private TerrainMesh(List<String> carried) {
		if (this.innerStride % Integer.BYTES != 0) {
			throw new IllegalStateException("The chunk mesh is " + this.innerStride + " bytes, which "
					+ "is not a whole number of words, and this engine moves it a word at a time");
		}

		// What holds the layout and the encoder together. Both take their offsets from the table
		// below, which spends one word per element, and the encoder writes each of them with a
		// single memPutInt. An element of any other width would put the two on different bytes
		// without either of them saying so, and the pack would read the neighbour's.
		for (Extra extra : Extra.values()) {
			if (extra.format().blockSize() != Integer.BYTES) {
				throw new IllegalStateException(extra.attribute() + " takes "
						+ extra.format().blockSize() + " bytes, and this engine lays out and writes "
						+ "one word for each of the elements it appends");
			}
		}

		this.carried = List.copyOf(carried);
		this.extras = Arrays.stream(Extra.values())
				.filter(extra -> this.carried.contains(extra.attribute()))
				.toList();
		this.offsets = new int[Extra.values().length];
		Arrays.fill(this.offsets, ABSENT);
		for (int at = 0; at < this.extras.size(); at++) {
			this.offsets[this.extras.get(at).ordinal()] = at * Integer.BYTES;
		}

		this.format = extend(this.inner.getVertexFormat(), this.extras);
		this.stride = this.format.getVertexSize();
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
		return built;
	}

	/**
	 * Takes the format the loaded pack now asks for, at the one instant it is safe to change it.
	 * <p>
	 * That instant is the head of Sodium's {@code initRenderer}, the only place its section manager is
	 * built, and {@code MixinSodiumWorldRendererInit} is what calls this from there. What makes it
	 * safe is measured and narrower than it looks: nothing between here and that constructor asks for
	 * the format, so no two askers can end up disagreeing. Everything that will ask is built
	 * afterwards, and every section is meshed again after that.
	 * <p>
	 * <strong>The elements are the pack's and not this class's</strong>, which is what makes a stride
	 * that follows what the pack reads possible at all: {@code TerrainDraw} has already translated
	 * the pack's chunk programs far enough to know, and has already asked for the world to be rebuilt
	 * if the answer moved. Nothing is decided here beyond turning that list into a layout.
	 * <p>
	 * Built here rather than in a static field so that a mesh this cannot extend leaves the game
	 * running on Sodium's own instead of failing to load a class in the middle of a world.
	 */
	public static synchronized void settle() {
		List<String> carried = broken ? List.of() : TerrainDraw.carried();
		// Sodium's own four and nothing of ours is the same layout Sodium already binds, so it is
		// answered with Sodium's own rather than with a copy of it. No pack of the corpus is here,
		// every one of them reading at least the block id, but a pack that read none of the five
		// would be, and wrapping a format to change nothing about it is one more thing to be wrong.
		if (carried.stream().noneMatch(TerrainMesh::ours)) {
			carried = List.of();
		}

		if (!carried.isEmpty() && (built == null || !built.carried.equals(carried))) {
			try {
				built = new TerrainMesh(carried);
			} catch (RuntimeException e) {
				broken = true;
				carried = List.of();
				Vitrail.logger().error("This engine cannot extend the chunk mesh, so the terrain keeps "
						+ "Sodium's own and no pack will draw it", e);
			}
		}

		if (carried.isEmpty()) {
			built = null;
		}

		if (carried.equals(said)) {
			return;
		}

		said = carried;
		if (carried.isEmpty()) {
			// Said out loud, this being the branch that would otherwise be silent. Without naming a
			// cause, because there are three and this cannot tell them apart: a terrain= line, no
			// pack chosen yet, which is every first launch of a fresh instance, and a terrain
			// program that threw.
			Vitrail.logger().info("The pack's own terrain program is not wanted, so the mesh keeps the "
					+ "format Sodium gave it and carries none of {}",
					Arrays.stream(Extra.values()).map(Extra::attribute).toList());

			return;
		}

		Vitrail.logger().info("The chunk mesh carries {} bytes a vertex instead of {}, the difference "
				+ "being what this pack reads and Sodium does not carry: {}",
				built.stride, built.innerStride,
				built.extras.stream().map(Extra::attribute).toList());
	}

	/** Whether an element of the format is one this engine appends rather than one of Sodium's. */
	private static boolean ours(String attribute) {
		return Arrays.stream(Extra.values()).anyMatch(extra -> extra.attribute().equals(attribute));
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
	 * <p>
	 * Ours are laid out one word after another in the order they are handed in, which is also where
	 * the encoder writes them: both read {@code offsets}, rather than a walk over the sizes here and
	 * a literal offset for each there, which would agree because every element happens to be one
	 * word and for no other reason.
	 *
	 * @param extras the ones of {@link Extra} this pack asked for, in {@link Extra}'s own order.
	 *               An element left out closes the gap rather than leaving a hole: the shader
	 *               declares this list and no more, so there is nothing to keep a place for
	 */
	private static VertexFormat extend(VertexFormat base, List<Extra> extras) {
		VertexFormat.Builder builder = VertexFormat.builder(base.getStepRate());
		for (VertexFormatElement element : base.getElements()) {
			builder.addAttribute(element.name(), element.offset(), element.format().blockSize(),
					element.format(), 1);
		}

		int at = base.getVertexSize();
		for (Extra extra : extras) {
			builder.addAttribute(extra.attribute(), at, extra.format().blockSize(), extra.format(), 1);
			at += Integer.BYTES;
		}

		return builder.build();
	}

	/**
	 * Where one of our elements starts, counted from the end of Sodium's own bytes, or
	 * {@link #ABSENT} for one this pack does not carry.
	 */
	private int offset(Extra extra) {
		return this.offsets[extra.ordinal()];
	}

	/**
	 * The elements this engine adds after Sodium's own, in the order they are laid out.
	 * <p>
	 * Each is four bytes and each is named by {@link SodiumVertex}, which is the side that decodes
	 * them. <strong>Which of them a mesh really carries is the pack's answer</strong>, taken from
	 * what its six chunk programs read, exactly as Iris takes it from what its transformed programs
	 * reference, {@code FormatAnalyzer}. The ones left out close the gap rather than leaving a hole,
	 * so this order decides which words move and not where any of them lands.
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
		 * The quad's own normal, the tangent of its texture mapping and the sign that says which way
		 * the third axis of that frame points, in one word.
		 * <p>
		 * <strong>One word and not two, which is Iris's own bargain</strong>: what
		 * {@link #orthogonalise} leaves is at a right angle to the normal, so once the normal is known
		 * the tangent is one angle in the normal's plane and one sign. {@link TangentFrame} is where
		 * the bits are shared out and where that is argued against the reference; the prologue reads
		 * the word back with text the same class writes.
		 * <p>
		 * What it costs against the two words of signed bytes it replaces is measured by the off-game
		 * harness rather than argued, and {@link TangentFrame} carries the figures. The short of it: the
		 * normal comes back four times closer, the tangent about twice as far but exactly
		 * perpendicular, and the handedness never turns over.
		 */
		TANGENT_FRAME(SodiumVertex.TANGENT_FRAME, GpuFormat.R32_UINT),

		/**
		 * The block's tint undivided, with the ambient occlusion in the alpha rather than multiplied
		 * into the other three, which is what a pack's {@code separateAo} asks to read as its vertex
		 * colour.
		 * <p>
		 * <strong>Iris writes that pair over Sodium's own colour word</strong>, picking between
		 * {@code ColorABGR.withAlpha(color, ao)} and {@code ColorARGB.mulRGB(color, ao)} on one
		 * global, {@code WorldRenderingSettings.INSTANCE.shouldUseSeparateAo()}
		 * ({@code XHFPTerrainVertex.java:152}). It sits inside the loop over the four vertices and
		 * nothing about it varies from one vertex to the next: what it reads is the pack's directive,
		 * which is settled long before a quad reaches that encoder. Here it is an element BESIDE
		 * that word and never instead of it, for the reason the class comment gives: the game's own
		 * shader draws this mesh too and reads the word. Iris has no such
		 * window to cover, nothing of its own warming up over several frames.
		 * <p>
		 * <strong>The one element here whose presence is not a question about the pack's BODY.</strong>
		 * The four above it are carried when a chunk program names them; this one is carried exactly
		 * when the pack wrote {@code separateAo}, which is what makes every one of its vertex stages
		 * read it. Two packs of the corpus write nothing, and their meshes carry one colour.
		 */
		TINT_AND_AO(SodiumVertex.TINT_AND_AO, GpuFormat.RGBA8_UNORM);

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
	 * @param materialBits Sodium's own, untouched. Nothing of this engine rides there:
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
		//
		// Guarded like the frame below it, and for a plainer reason than the frame's: an element the
		// mesh does not carry has nowhere to be written, so working it out would be arithmetic on
		// every quad of the world for a word that is then thrown away.
		int middle = offset(Extra.MID_TEX_COORD) == ABSENT ? 0 : midTexCoord(vertices);

		// A property of the QUAD and not of a corner, like the middle above: the four vertices carry
		// the same word, and a pack that reads a normal per vertex on chunk geometry is reading what
		// the face is, not what the corner is. The normal and the tangent share it, so a pack reading
		// one of the two pays for both and a pack reading neither pays for neither.
		int frame = offset(Extra.TANGENT_FRAME) == ABSENT ? 0 : TangentFrame.pack(frame(vertices));

		// One of the two that are a property of the CORNER, so it is asked for inside the loop and
		// only the question is hoisted out of it.
		boolean blockMiddle = offset(Extra.MID_BLOCK) != ABSENT;

		// Backwards over the vertices, because a vertex moves up by the difference of the two strides
		// times its own index, and one that has not been moved yet is the source of the move before
		// it: at twenty bytes of difference the second vertex lands on [40, 60) and the third is
		// still to be read from [40, 60). Word by word from the top of each vertex costs nothing and
		// is what keeps the move right at any pair of strides, which is what this now needs: the
		// difference is four bytes for a pack that reads one of the five and twenty for one that
		// reads them all, and at four the two ranges of one vertex DO overlap.
		for (int at = vertices.length - 1; at >= 0; at--) {
			long from = pointer + (long) at * this.innerStride;
			long to = pointer + (long) at * this.stride;
			for (int word = this.innerStride - Integer.BYTES; word >= 0; word -= Integer.BYTES) {
				MemoryUtil.memPutInt(to + word, MemoryUtil.memGetInt(from + word));
			}

			long extra = to + this.innerStride;
			write(extra, Extra.BLOCK_ID,
					((TerrainVertex) vertices[at]).vitrailBlockId() & BlockStateIds.PACKED_MASK);
			write(extra, Extra.MID_TEX_COORD, middle);
			write(extra, Extra.MID_BLOCK, blockMiddle ? midBlock(vertices[at]) : 0);
			write(extra, Extra.TANGENT_FRAME, frame);
			// The two fields the encoder was handed, kept apart instead of multiplied together, out
			// of Sodium's own published helper. Sodium's word beside it keeps the product, so this
			// one is read by a pack that asked for it and by nothing else.
			write(extra, Extra.TINT_AND_AO, ColorABGR.withAlpha(vertices[at].color, vertices[at].ao));
		}

		return pointer + (long) vertices.length * this.stride;
	}

	/**
	 * One of our words onto one vertex, or nothing at all where this pack does not carry it.
	 * <p>
	 * The one place the encoder asks whether an element is there, so that the writes above read as
	 * the five they are and the question is answered once for each.
	 */
	private void write(long extra, Extra element, int value) {
		int at = offset(element);
		if (at != ABSENT) {
			MemoryUtil.memPutInt(extra + at, value);
		}
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
	 * is what every normal map on the terrain is read through, and handing back a constant instead
	 * tilts every one of them the same wrong way. Its handedness says which way the
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
	 * <strong>Iris does this to every tangent of the chunk mesh, and to that mesh
	 * alone</strong>: {@code NormalHelper.packDiamondByte} lines 489 to 510 subtracts
	 * {@code n * dot(n, tangent)}, and what it stores is an angle in the plane that leaves, which its
	 * own patched vertex stage turns back into {@code normalize(p.x * t1 + p.y * t2)} of a basis built
	 * from the normal ({@code SodiumTransformer} lines 191 to 198). That packing is reached through
	 * {@code NormalHelper.encodeNormalTangent} lines 512 to 520 and through nothing else, and its
	 * only callers are {@code XHFPTerrainVertex} lines 26 and 132. The entity, text and glyph
	 * tangents take the other road, {@code NormalHelper.computeTangent} straight into
	 * {@code NormI8.pack} ({@code NormalHelper.java:246}, {@code :333} and {@code :414}), which
	 * stores the direction the mapping gave and never projects it onto the normal's plane at all.
	 * <p>
	 * A pack reading the terrain under Iris therefore always reads a unit vector exactly
	 * perpendicular to the normal, and <strong>it is the tangent itself, not the
	 * bitangent, that carries the defect</strong>: the first column of the frame a normal map is
	 * read through is {@code at_tangent.xyz}, and the packs that normalise it normalise its length
	 * and not its direction. A vector leaning towards the normal is still leaning afterwards, and it
	 * tilts the whole frame with it.
	 * <p>
	 * The bitangent is not the argument, and it is worth saying because it looks as though it should
	 * be. Four packs write {@code cross(at_tangent.xyz, gl_Normal.xyz) * at_tangent.w} word for word,
	 * measured over the eight, and all four scale what comes out of it to unit length before using
	 * it - two in a {@code normalize} and two in an {@code inversesqrt(max(dot(b, b), 1e-8))} of
	 * their own - so none of them depends on its length. Nor does this change its direction: what is
	 * subtracted is a multiple of the normal, and the normal crossed with itself is nought. What the
	 * normalising here does is give that cross product a length of one as well, and the handedness
	 * bit goes on meaning what {@link #handedness} says it means.
	 * <p>
	 * <strong>Nothing of that difference is left in the quantisation.</strong> Storing the vector
	 * itself as three rounded bytes brings the dot product with the normal back at a few thousandths
	 * rather than at nought, and the work below is then undone by the rounding.
	 * {@link Extra#TANGENT_FRAME} stores the angle in the plane, which is what Iris stores, so
	 * what a pack reads is perpendicular to the last bit on both engines and this projection is what
	 * decides the angle rather than a step on the way to it.
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
	 * describes: when the first triangle has no area but its mapping still points somewhere, Iris
	 * does not retry - it substitutes, finds a direction, and its own handedness test lands on
	 * nothing, which it reads as {@code +1} - where this refuses, retries, and takes the second
	 * triangle's sign, which can be the other one. That is the handedness bit and not the
	 * direction. A rectangle collapsed along {@code v} is the one degenerate shape Iris does retry:
	 * there the substitution leaves nothing at all, its zero test fires, and the second triangle is
	 * tried as it is here.
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
	 * starts from. {@code encodeNormalTangent} takes the normal's component out of every tangent it
	 * packs, not only a carried one, but in a facing bucket that projection changes nothing: the
	 * carried tangent lies at a right angle to the shared normal already, so the carried direction
	 * is exactly what the pack reads. Only a carried tangent parallel to the normal leaves nothing,
	 * and there an axis of a basis built from that normal takes its place: with the starting value
	 * above, that is the first quads of the two vertical buckets, not every quad that gets here.
	 * <p>
	 * So the difference is real twice over: this answer depends on the quad alone where that one
	 * depends on the order the bucket was filled in, and on the quads where both engines do
	 * substitute, the axis is not the same axis, Frisvad's basis against a cross with the less
	 * aligned of the first two axes. Nothing in 26.2 makes the carry-over impossible, so it is a
	 * divergence rather than a choice.
	 */
	private static void perpendicular(float[] frame) {
		// Crossed with the less aligned of the x and y axes, so the result is never a zero.
		boolean upright = Math.abs(frame[1]) < Math.abs(frame[0]);
		frame[3] = upright ? -frame[2] : 0.0F;
		frame[4] = upright ? 0.0F : frame[2];
		frame[5] = upright ? frame[0] : -frame[1];
		normalise(frame, 3);
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
	 * <strong>Iris subtracts from the WORLD position, and the two agree on the whole
	 * blocks.</strong> {@code MixinChunkMeshBuildTask.iris$onRenderModel} hands
	 * {@code blockPos.getX()} straight in, {@code ExtendedDataHelper.computeMidBlock} masks it to
	 * sixteen bits, and Sodium's vertex is section local, so the difference the two arguments carry
	 * is a whole number of sixteens; sixty-four times that is a whole number of two hundred and
	 * fifty-sixes, and the mask to a byte takes it away.
	 * <p>
	 * <strong>That difference also decides the ROUNDING, and there is one slab per axis where it
	 * does not.</strong> Both sides cast to {@code int}, which truncates towards zero. Sixteen blocks
	 * is a thousand and twenty-four sixty-fourths and the offset itself never leaves plus or minus
	 * sixty-four, so wherever the masked coordinate reaches sixteen Iris's argument is a positive
	 * number and its cast is a floor. Where the mask leaves the coordinate under sixteen, its
	 * argument is this engine's own: small, and negative for half the corners of every block, and
	 * there the same cast rounds the other way. That is sixteen blocks per axis at every wrap of a
	 * sixteen bit mask, the corner of the world at nought among them. This engine floors everywhere,
	 * which is what Iris does everywhere its own argument is positive, and the two part by one
	 * sixty-fourth inside that slab.
	 * <p>
	 * What parts there is narrower than a rounding rule sounds. The offset is {@code 32 - 64f} for a
	 * vertex at a fraction {@code f} of its own block, so it comes out whole for every {@code f}
	 * that is a multiple of a sixty-fourth, and floor and truncation agree on all of them: the whole
	 * sixteenth grid the block models are drawn on, slabs and stairs included. What is left is
	 * geometry off that grid - a cross plant's rotated quads, a fluid surface.
	 */
	private static int midBlock(ChunkVertexEncoder.Vertex vertex) {
		int origin = ((TerrainVertex) vertex).vitrailBlockOrigin();

		return (offset(TerrainVertex.origin(origin, 0), vertex.x) & 0xFF)
				| ((offset(TerrainVertex.origin(origin, 1), vertex.y) & 0xFF) << 8)
				| ((offset(TerrainVertex.origin(origin, 2), vertex.z) & 0xFF) << 16)
				| (TerrainVertex.emission(origin) << 24);
	}

	/**
	 * One axis of that offset, from the corner of the block to the vertex, in sixty-fourths. Floored
	 * and not truncated, which is the rounding Iris's own cast performs on its own argument
	 * everywhere that argument is positive; the javadoc above says where the two part all the same.
	 */
	private static int offset(int block, float vertex) {
		return (int) Math.floor((block + 0.5F - vertex) * 64.0F);
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
