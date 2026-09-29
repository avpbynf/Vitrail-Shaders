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
	 * What the pack had published the last time the log announced anything, and null until the first
	 * settle of a run.
	 * <p>
	 * What the pack published and not what {@link #settle()} decided out of it, because the two part
	 * on the road where a pack's programs declare Sodium's own elements and none of ours. The
	 * decision is empty there, and it is empty again where no pack wants the terrain at all, so a key
	 * taken from the decision would let one of those two roads follow the other without a word. They
	 * are different lines and one of them is the answer to a question a log has to be able to answer.
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
	 * {@link TerrainGeometry#handedness}, which measures the corners it is handed; it reaches this constant.
	 * <p>
	 * The handedness of a rectangle is the product of the signs of its two axes, so a degenerate one
	 * leaves one factor with no value and this constant assumes it forward. The measurement backs
	 * the assumption without making it a measurement: all 160 degenerate rectangles of those models
	 * keep the axis that survives forward, and a model this engine has not seen may not.
	 */
	private static final float UNREVERSED_AREA = -1.0F;

	/**
	 * The seven floats {@link #frame} works in, one array per thread.
	 * <p>
	 * A quad's frame is read by {@link TangentFrame#pack} and kept by nobody, so one array serves
	 * every quad a thread ever meshes rather than one per quad. Per THREAD and not per instance:
	 * this class is remade whenever the carried list moves, the workers outlive any one of it, and
	 * Sodium meshes on as many of them as it was given, so an array of the instance would still be
	 * four builders writing seven floats over each other.
	 */
	private static final ThreadLocal<float[]> FRAMES = ThreadLocal.withInitial(() -> new float[7]);

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
	 * <strong>"Already" is an ORDER, and it is a contract with {@code TerrainDraw.read} that nothing
	 * in this class can enforce.</strong> That method is what fills the answer in, and it runs where
	 * the pack is loaded; this runs where Sodium builds its chunk renderer. Read first, the mesh
	 * follows the pack. Settled first, {@code TerrainDraw.carried} answers empty, the mesh stays at
	 * Sodium's own twenty bytes, and the pack is not put away here but a world later: the first chunk
	 * pass compares the format it was handed against what the programs declare, in
	 * {@code TerrainProgram.carries}, and puts the whole pack away with an error naming two lists and
	 * not the order that made them differ. That is why the empty branch below names the order among
	 * its causes rather than leaving the log to be read backwards from the error.
	 * <p>
	 * What holds the order is two facts and neither is a check. The first load runs at client setup,
	 * before any level exists, and Sodium reaches {@code initRenderer} only with a level. A reload
	 * asked for by the settings screen or by the key runs on the render thread and asks for the
	 * rebuild through {@code allChanged} rather than performing it, so the reading has returned
	 * before the rebuild begins.
	 * <p>
	 * <strong>A dimension change runs the two the other way round, and what saves it is the second
	 * rebuild.</strong> {@code Minecraft.setLevel} reaches {@code LevelExtractor.setLevel}, which
	 * calls {@code allChanged} itself, so the next {@code extract} invalidates the compiled geometry
	 * and this settles before that frame's level is drawn; the pack of the new dimension is not read
	 * until {@code PackChain.beforeLevel}, which stands in {@code GameRenderer.render} and therefore
	 * AFTER {@code GameRenderer.extract} in the same tick. Nothing is bound wrongly in between, and
	 * the reason is that the reversal is complete: the pack in force while this settles is still the
	 * previous dimension's, and its programs declare exactly the list this settled from. What keeps
	 * the frame after the read out of the same question is that the frame which reads a pack again
	 * does not draw the level at all: {@code beforeLevel} says so and the wrap it rides on obeys it.
	 * When the new reading moves the list, {@code carries} asks for a rebuild of its own and this
	 * runs again on the frame after, which is the hitch at the portal.
	 * <p>
	 * Built here rather than in a static field so that a mesh this cannot extend leaves the game
	 * running on Sodium's own instead of failing to load a class in the middle of a world.
	 */
	public static synchronized void settle() {
		// What the pack published, kept apart from what is decided out of it: a list that arrives
		// empty is a pack with nothing to say, and one emptied below is a pack that asked for none of
		// ours. The two decide the same layout and are not the same event, and the log parts them.
		List<String> asked = broken ? List.of() : TerrainDraw.carried();
		List<String> carried = asked;
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

		if (asked.equals(said)) {
			return;
		}

		said = asked;
		if (carried.isEmpty()) {
			if (broken) {
				// Nothing, and before the roads below rather than among them. The error above has
				// named the one cause this road has, and every line below it would be a guess at a
				// question already answered: the pack is still wanted and still published its
				// elements, so both of them would read as true and neither would be.
				return;
			}

			List<String> ours = Arrays.stream(Extra.values()).map(Extra::attribute).toList();
			if (!asked.isEmpty()) {
				// The pack was read and wants none of the five, so Sodium's own layout is what its
				// programs were translated against and there is nothing to append. A normal event
				// and not a failure, which is why it is said at this level; it is said at all
				// because it decides the stride of every vertex in the world and would otherwise be
				// indistinguishable in the log from the pack below that decides the same stride.
				Vitrail.logger().info("This pack's chunk programs read none of {}, so nothing is "
						+ "appended and they draw from the format Sodium gave the mesh", ours);

				return;
			}

			if (TerrainDraw.asked()) {
				// THREE roads share this one and nothing here can part them, so all three are named
				// rather than the likeliest one guessed at. The commonest by far is a load that
				// never reached the pack's chunk programs at all: PackChain raises this flag off the
				// options and only opens the pack afterwards, so a required flag it does not serve,
				// a chain with no final, a refusal and an archive that will not open all leave the
				// flag standing with nothing behind it. Then a pack that was read and serves no
				// chunk program, where the world keeps the game's own shader and the pack's sky and
				// chain go on being drawn. Then this having run before TerrainDraw.read published
				// anything, which is the order this method depends on and cannot check.
				//
				// Said at this level and not louder because the first road is the one that arrives,
				// and every way into it has already said what went wrong in its own words. What this
				// adds is the consequence for the mesh, which none of them mentions.
				Vitrail.logger().info("The pack's own terrain program is wanted and nothing has been "
						+ "published for it, so the mesh keeps the format Sodium gave it and carries "
						+ "none of {}. Either this pack's load never reached its chunk programs, or "
						+ "it serves none of its own, or they had not been read when the chunk "
						+ "renderer was built", ours);

				return;
			}

			// Said out loud, this being the branch that would otherwise be silent. Without naming a
			// cause, because there are three and this cannot tell them apart: a terrain= line, no
			// pack chosen yet, which is every first launch of a fresh instance, and a terrain
			// program that threw.
			Vitrail.logger().info("The pack's own terrain program is not wanted, so the mesh keeps the "
					+ "format Sodium gave it and carries none of {}", ours);

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
		 * {@link TerrainGeometry#orthogonalise} leaves is at a right angle to the normal, so once the normal is known
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
			// The first vertex does not move, both strides placing it at the pointer.
			for (int word = at == 0 ? -1 : this.innerStride - Integer.BYTES; word >= 0;
					word -= Integer.BYTES) {
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
	 * their texture coordinates, and then squared up against the normal by {@link TerrainGeometry#orthogonalise}. It
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
		float[] frame = FRAMES.get();

		// Emptied rather than allocated, and only the three Newell sums into: the tangent's three are
		// written outright on every road out of here, the last of them being perpendicular's, and the
		// handedness on the line below.
		frame[0] = 0.0F;
		frame[1] = 0.0F;
		frame[2] = 0.0F;

		// The answer for a quad no triangle of which has an area to measure. A triangle that has one
		// overwrites it below, whether or not it goes on to yield a direction.
		frame[6] = TerrainGeometry.handedness(UNREVERSED_AREA);

		// Newell: every edge of the loop contributes, so a quad whose four corners are not in one
		// plane still answers the plane they are closest to instead of the plane of its first three.
		for (int at = 0; at < vertices.length; at++) {
			ChunkVertexEncoder.Vertex current = vertices[at];
			ChunkVertexEncoder.Vertex next = vertices[(at + 1) % vertices.length];
			frame[0] += (current.y - next.y) * (current.z + next.z);
			frame[1] += (current.z - next.z) * (current.x + next.x);
			frame[2] += (current.x - next.x) * (current.y + next.y);
		}

		TerrainGeometry.normalise(frame, 0);

		// The second triangle when the first has nothing to say, which is Iris's own retry in
		// computeTangentForQuad: three corners of a quad can share a texture coordinate while the
		// fourth does not, and taking the perpendicular there would throw away a tangent the other
		// half of the same quad holds. Four corners is a contract on both roads in: push refuses any
		// other length before the encoder is called, and writeExternal, which checks nothing, is fed
		// the sorter's own array, allocated by ChunkVertexEncoder.Vertex.uninitializedQuad.
		if (!tangent(frame, vertices[0], vertices[1], vertices[2])
				&& !tangent(frame, vertices[2], vertices[3], vertices[0])) {
			TerrainGeometry.perpendicular(frame);
		}

		TerrainGeometry.orthogonalise(frame);

		return frame;
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
	 * caller fall back on {@link TerrainGeometry#perpendicular}.
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
		frame[6] = TerrainGeometry.handedness(area);

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

		TerrainGeometry.normalise(frame, 3);

		return true;
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

		return (TerrainGeometry.offset(TerrainVertex.origin(origin, 0), vertex.x) & 0xFF)
				| ((TerrainGeometry.offset(TerrainVertex.origin(origin, 1), vertex.y) & 0xFF) << 8)
				| ((TerrainGeometry.offset(TerrainVertex.origin(origin, 2), vertex.z) & 0xFF) << 16)
				| (TerrainVertex.emission(origin) << 24);
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
