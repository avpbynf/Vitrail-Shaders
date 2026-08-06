package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The door the game's entity geometry comes in by, and the one place a pack's entity programs are
 * read.
 * <p>
 * <strong>The door is a group of draws and not a draw.</strong> The game hands its immediate
 * geometry to {@code RenderTypeFeatureRenderer.executeGroup}, which walks the draws of one group and
 * asks {@code PreparedRenderType.drawFromBuffer} for each. That call is not the door and cannot be:
 * it opens a render pass per draw, in a try-with-resources, with one colour attachment, so nothing
 * multi-target can be written from inside it. Its caller has no pass open at all and holds the whole
 * group, which is what lets this open one pass over a run of draws and hand the pack every draw
 * buffer it asked for.
 * <p>
 * <strong>A run and not the group</strong>, and the difference is the whole of what is recorded
 * here. One pass carries one set of attachments and one pipeline's worth of state, so the pass lasts
 * exactly as long as consecutive draws keep asking for the same program of ours; anything else -
 * another program, geometry this engine does not serve, the end of the group - closes it, because
 * the game opens its own pass for what it draws itself and two passes may not be open at once.
 * Nothing is reordered to make runs longer: the order the game walks its draws in is the order
 * things overlap in.
 * <p>
 * <strong>What decides which program serves a draw is the {@code RenderPipeline}</strong>, not the
 * render type and not the texture. That is Iris's answer too, keyed the same way in
 * {@code IrisPipelines}: a render type is made per texture, so there are as many of them as there
 * are mobs on screen, while the pipelines are a fixed table the game builds once.
 * <p>
 * <strong>Only the geometry that writes outright is served</strong>, which is the game's opaque
 * feature phase and the half of the table below. The blending half is drawn between
 * {@code openFeatures} and {@code closeFeatures}, where {@link FeatureLayer} is already carrying the
 * game's own translucent features into the pack's picture: that is a second road into the same
 * target, and answering the same question twice in one frame is how two answers start to differ.
 * The eyes, the beacon beam, the glint, the hand and the shadow map are each a family of their own
 * and none of them is here yet.
 * <p>
 * <strong>The block entities are not one of those and come in by this same door</strong>, because
 * that is where the game brings them: a chest and a mob are submitted into one phase and drawn with
 * one set of pipelines. What tells them apart is carried rather than read,
 * {@link BlockEntityGeometry} saying how, and what it buys is the program name, {@code gbuffers_block}
 * instead of {@code gbuffers_entities}, on every piece whose row Iris sends there.
 */
public final class EntityDraw {

	/** Off unless {@code options.txt} asks otherwise, and read again at every load. */
	private static volatile boolean wanted;

	/**
	 * Whether the game is drawing the level's own opaque features at this instant, which is the only
	 * moment anything here may be served.
	 * <p>
	 * <strong>The feature renderers are not the level's.</strong> One dispatcher draws three things
	 * through the same {@code executeGroup}: the level's features, the hand, and the screen, the last
	 * two out of a submit storage of their own that {@code GameRenderer} hands it after the level is
	 * finished. Every one of them reaches this class with the same pipelines and the same main
	 * target, so nothing about a draw says which of the three it belongs to; only the moment does.
	 * <p>
	 * Measured rather than reasoned about, and it cost a session: with this open all the time, every
	 * item in the inventory was drawn with {@code gbuffers_entities} under the world's own camera
	 * matrix, so an inventory came out empty and the item in hand swayed with the walk.
	 * <p>
	 * <strong>One thing about that session is observed and NOT explained, and it is left standing
	 * rather than given a reason it has not earned.</strong> What was drawn as a block model came out
	 * untouched. Nothing in the source accounts for that: a block item takes
	 * {@code Sheets.cutoutBlockItemSheet}, which is {@code ITEM_CUTOUT} and a row of the table below,
	 * and {@code BlockModelFeatureRenderer} extends the very class this engine wraps, so those draws
	 * came through this door like the others and should have broken with them. Either the
	 * observation has a cause nobody has found or it was less complete than it looked. The window
	 * below closes both cases and does not depend on which it was, which is why the answer was not
	 * waited for.
	 */
	private static volatile boolean opaqueFeatures;

	/** The pass this engine opens for a run of draws, when the pack has nothing more to say. */
	private static final Supplier<String> LABEL = () -> "Vitrail entity";

	/** What the game binds its own entity image under, and what a pack reads as {@code gtexture}. */
	private static final String TEXTURE = "Sampler0";

	/**
	 * One piece of the game's entity geometry: which pipeline it is drawn with, which program of the
	 * pack answers for it, and what it discards at.
	 *
	 * @param pipeline  the game's own pipeline, which is both how a draw is recognised and where the
	 *                  blend, the depth window, the culling and the topology are read from. Held
	 *                  rather than copied for exactly that reason: everything the pack does not
	 *                  decide is the game's, and a table of our own would be a second copy of it
	 * @param element   one word for the log and for the shader identifier, which has to tell two
	 *                  pieces served by one file apart. It lands in an {@code Identifier} path, so it
	 *                  is lowercase and has no space in it
	 * @param program   the bare name the pack is asked for
	 * @param alphaTest what this piece discards at when the pack says nothing. It is the game's own
	 *                  {@code ALPHA_CUTOUT} define for that pipeline, a tenth where there is one and
	 *                  no test at all where there is none, and Iris gives the entity programs the
	 *                  same tenth
	 * @param layering  the depth nudge the game gives this piece before it draws it, and the one
	 *                  thing in this record that is tabulated rather than read off the pipeline.
	 *                  {@code RenderType.prepare} takes it from the render type and not from the
	 *                  pipeline, and applies it to the matrix it writes into the draw's dynamic
	 *                  transforms, which is a buffer nothing can read back. The association below is
	 *                  therefore ours; the transform itself is the game's own constant, so what it
	 *                  does to the matrix stays the game's answer
	 * @param stage     what the pack is told it is drawing. {@code NONE} for a mob, which is not a
	 *                  reading of what the pass is but Iris's answer, and {@link EntityProgram} has
	 *                  the four places it was read from; {@code BLOCK_ENTITIES} for a block entity,
	 *                  which Iris really does pose and which is the very phase its own table branches
	 *                  on to reach {@code gbuffers_block}
	 */
	record Element(RenderPipeline pipeline, String element, String program, AlphaTest alphaTest,
			LayeringTransform layering, RenderStage stage) {

		/** A piece the game draws where the depth says, which is most of the table below. */
		Element(RenderPipeline pipeline, String element, String program, AlphaTest alphaTest) {
			this(pipeline, element, program, alphaTest, LayeringTransform.NO_LAYERING);
		}

		/** A piece of the mob half, which is every row of the table above. */
		Element(RenderPipeline pipeline, String element, String program, AlphaTest alphaTest,
				LayeringTransform layering) {
			this(pipeline, element, program, alphaTest, layering, RenderStage.NONE);
		}

		/** What the pack has to be read for to serve this piece, in terms the translation knows. */
		private PackProgram.EntityElement asked() {
			return new PackProgram.EntityElement(this.element, this.program, this.alphaTest);
		}

		/**
		 * The model view the game would have handed this draw, or null for the frame's own camera.
		 * <p>
		 * Taken from {@code RenderSystem} and modified here exactly as {@code RenderType.prepare}
		 * does it, rather than reproduced. The formula belongs to the projection in force and is not
		 * one formula: under a perspective it scales the matrix by {@code 1 - bias/4096}, so about a
		 * quarter of a per mille, and under an orthographic it TRANSLATES by {@code bias/512}. A copy
		 * of either here would be a second answer to drift from. The stack holds the level's own view
		 * for the whole of the level render, {@code LevelRenderer.render} pushing it before the
		 * features are prepared and popping it after they have executed, so what is read at the draw
		 * is what was read at the prepare.
		 * <p>
		 * What it is for: the pieces below that carry a transform are moved a hair along the view axis
		 * so that they do not fight the skin they cover, and <strong>they do not all move the same
		 * way</strong>.
		 * The three that carry {@code VIEW_OFFSET_Z_LAYERING} pass a bias of {@code +1} and come
		 * towards the viewer; {@code ENTITY_SOLID_Z_OFFSET_FORWARD} passes {@code -1} and goes away
		 * from it, its name being about the geometry it is meant to sit behind. Without any of it,
		 * every armour piece on every player and every mob is drawn at the depth of the body
		 * underneath, which is the one thing in this family that is visible from across a room.
		 */
		private Matrix4fc modelView() {
			Consumer<Matrix4f> modifier = this.layering.getModifier();
			if (modifier == null) {
				return null;
			}

			Matrix4f matrix = RenderSystem.getModelViewMatrixCopy();
			modifier.accept(matrix);

			return matrix;
		}
	}

	/** The bare name every piece below asks the pack for. Its fallback tree is walked like any other. */
	private static final String ENTITIES = "gbuffers_entities";

	/**
	 * What a block entity asks for instead, and the reason it is a name and not a flag: it falls back
	 * on the TERRAIN where {@code gbuffers_entities} does not, so a chest is lit as the block it is
	 * rather than as a mob even on a pack that ships no such file.
	 */
	private static final String BLOCK = "gbuffers_block";

	/**
	 * What a cutout entity discards at, which is a tenth and not the half the terrain uses. Named
	 * here so that the table below reads as a table.
	 */
	private static final AlphaTest CUTOUT = AlphaTest.ONE_TENTH;

	/**
	 * Every pipeline the game draws opaque entity geometry with, and nothing else.
	 * <p>
	 * The list is the pipelines of {@code RenderPipelines} that bind
	 * {@code DefaultVertexFormat.ENTITY} and declare no blend function. Each of them is a piece of
	 * its own even where two ask for the same program at the same threshold, which is how the sky is
	 * six pieces out of three files: they differ in what {@link Element} reads off them, and a piece
	 * is one compiled module.
	 * <p>
	 * All of them ask for {@code gbuffers_entities}, and that is only half the answer:
	 * <strong>a conduit, a skull, a chest and every other block entity drawing with an entity render
	 * type that does not blend comes through this very door</strong>, and most of them are Iris's
	 * {@code gbuffers_block} rather than this name. {@link #BLOCK_ELEMENTS} is that half, row for row
	 * against Iris's own table, and {@link BlockEntityGeometry} is how a draw is known to belong to
	 * it. Not every block entity is concerned: a PLAYER head that carries an owner profile takes
	 * {@code entityTranslucent}, which blends and is no row of this table.
	 * <p>
	 * Iris keys the same table by the same pipelines, and most of these rows reach a function of its
	 * own rather than a constant: that function answers {@code gbuffers_block} while a block entity is
	 * being drawn, and {@code gbuffers_hand} or {@code gbuffers_hand_water} while the hand is, by its
	 * half. The hand half cannot arise here, the window above being closed by then. Only the end
	 * crystal beam and the offset cutout are {@code gbuffers_entities} unconditionally, and that holds
	 * of its MAIN table alone: its shadow table sends both to {@code shadow_entities}.
	 * <p>
	 * Three families are arguably somebody else's and are served here all the same: the armour pieces
	 * are the entity wearing them, the end crystal beam is drawn with the entity format and the
	 * entity snippet, and an item entity lying on the ground is an entity.
	 */
	private static final Map<RenderPipeline, Element> ELEMENTS = new LinkedHashMap<>();

	static {
		put(new Element(RenderPipelines.ENTITY_SOLID, "solid", ENTITIES, AlphaTest.OFF));
		put(new Element(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, "solid_offset", ENTITIES,
				AlphaTest.OFF, LayeringTransform.VIEW_OFFSET_Z_LAYERING_FORWARD));
		put(new Element(RenderPipelines.ENTITY_CUTOUT, "cutout", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ENTITY_CUTOUT_CULL, "cutout_cull", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, "cutout_offset", ENTITIES, CUTOUT,
				LayeringTransform.VIEW_OFFSET_Z_LAYERING));
		put(new Element(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, "cutout_dissolve", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ARMOR_CUTOUT_NO_CULL, "armor", ENTITIES, CUTOUT,
				LayeringTransform.VIEW_OFFSET_Z_LAYERING));
		put(new Element(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, "armor_decal", ENTITIES, CUTOUT,
				LayeringTransform.VIEW_OFFSET_Z_LAYERING));
		put(new Element(RenderPipelines.END_CRYSTAL_BEAM, "crystal_beam", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ITEM_CUTOUT, "item", ENTITIES, CUTOUT));
	}

	/**
	 * The same pieces asked of {@code gbuffers_block}, for the draws a block entity renderer put
	 * there.
	 * <p>
	 * <strong>The program is a question about the pipeline and the stage is a question about the
	 * draw, and keeping those two apart is the whole of this table.</strong> Iris answers them in two
	 * different places. The program comes from its per pipeline table, where the end crystal beam and
	 * the offset cutout are given {@code ENTITIES_CUTOUT} outright and every other row of ours goes
	 * through {@code getSolid} or {@code getCutout} to {@code BLOCK_ENTITY} or
	 * {@code BLOCK_ENTITY_DIFFUSE}, both {@code ProgramId.Block}
	 * ({@code pipeline/IrisPipelines.java:25-83}). The phase comes from nowhere in that table: what is
	 * raised around the submission is a flag, and the phase itself goes up at the DRAW, in the
	 * wrapper the flag made Iris put on the render type
	 * ({@code layer/BlockEntityRenderStateShard.java:10-12} into {@code layer/GbufferPrograms.java:59}).
	 * Either way it does not consult the pipeline, so a pipeline that answers with the entity program
	 * can still be drawn under the block entity phase.
	 * <p>
	 * <strong>A skull is where those two answers really part company</strong>, and it is not a corner
	 * case: it is a block entity, it draws with {@code ENTITY_CUTOUT_Z_OFFSET}, and so it takes the
	 * entity program and the block entity phase at once. <strong>Almost every head in the world
	 * does</strong>, and the exception is narrower than it looks: the owner profile is consulted for
	 * the PLAYER type alone, so a skeleton, wither, zombie, creeper, dragon or piglin head draws here
	 * whether it carries one or not, and only a player head with a profile goes to
	 * {@code entityTranslucent} and out of this table.
	 * <p>
	 * <strong>The threshold follows the program and not the phase.</strong> Whatever asks for the
	 * block program discards at a tenth, the solid rows included, because Iris's {@code getSolid}
	 * under that phase answers {@code BLOCK_ENTITY} and not a solid key, and {@code BLOCK_ENTITY}
	 * carries {@code ONE_TENTH_ALPHA} ({@code pipeline/programs/ShaderKey.java:66}) where
	 * {@code ENTITIES_SOLID} carries {@code OFF} ({@code :38}). The two rows that keep the entity
	 * program keep the entity threshold with it.
	 * <p>
	 * <strong>Where the phase is posed is narrower than "a block entity is drawing", and the
	 * difference is a known gap rather than a subtlety.</strong> Iris raises it on the render types
	 * of {@code submitModel}, {@code submitCustomGeometry}, {@code submitModelPart} and the glyphs
	 * ({@code mixin/entity_render_context/MixinModelStorageTrigger.java:39,48,57} and
	 * {@code MixinGlyphRenderType.java:19}), and on nothing else. What that leaves out is narrower
	 * than it sounds: an item on a shelf reaches those same wrapped calls whenever it has a model of
	 * its own, a chest or a shield among them, and only the plain quad road stays at {@code NONE}.
	 * Both engines answer alike there.
	 * <p>
	 * <strong>The glyphs are where they really differ, and it is a gap rather than a divergence.</strong>
	 * Iris draws a sign's text under the block entity phase and with a program of its own; this engine
	 * draws no text at all, so the sign's text is the game's, unshaded, and no stage of ours is wrong
	 * because none is supplied. It is a family of its own and it is named as one above.
	 * <p>
	 * A piece here is a compiled module of its own, like every row above, so it carries a name of its
	 * own. The name is the entity one with a word in front, because it lands in an identifier the
	 * device caches a shader under and two pieces sharing one name would hand the second whatever the
	 * first compiled to.
	 */
	private static final Map<RenderPipeline, Element> BLOCK_ELEMENTS = new LinkedHashMap<>();

	static {
		// Derived from the table above, which Java has already run: static initialisers go in source
		// order. One row per row and no filter, so this table cannot be missing the pipeline some
		// block entity turns out to use. Two of the rows it makes are unreachable by any block entity
		// renderer of the game, the item and the end crystal beam, and they are made anyway: a row
		// too many is a compiled module nobody selects, a row too few is the skull defect again, and
		// only one of those two is visible on screen.
		ELEMENTS.values().stream()
				.map(EntityDraw::blockTwin)
				.forEach(element -> BLOCK_ELEMENTS.put(element.pipeline(), element));
	}

	/**
	 * The block entity half of one mob piece: the same pipeline under the phase Iris poses, asking
	 * for whichever program Iris's own table gives that pipeline while the phase is up.
	 * <p>
	 * The end crystal beam and the offset cutout keep the entity program and its threshold, because
	 * that is what their row answers there whatever the phase; everything else takes the block
	 * program and the tenth that comes with it. The phase itself is not a row of any table and does
	 * not vary: it is what the origin of the draw says.
	 */
	private static Element blockTwin(Element mob) {
		boolean ownProgram = mob.pipeline() != RenderPipelines.END_CRYSTAL_BEAM
				&& mob.pipeline() != RenderPipelines.ENTITY_CUTOUT_Z_OFFSET;

		return new Element(mob.pipeline(), "block_" + mob.element(),
				ownProgram ? BLOCK : mob.program(), ownProgram ? CUTOUT : mob.alphaTest(),
				mob.layering(), RenderStage.BLOCK_ENTITIES);
	}

	private static void put(Element element) {
		ELEMENTS.put(element.pipeline(), element);
	}

	/**
	 * Which piece answers for a draw of this pipeline, which is the block entity one only where the
	 * draw came from a block entity renderer AND that pipeline has one. Null for a pipeline this
	 * engine does not serve at all, which is most of them.
	 */
	private static Element element(RenderPipeline pipeline) {
		// One table or the other and no falling between them: the block table is built from every row
		// of the mob one, so a pipeline either has both halves or neither.
		return BlockEntityGeometry.drawing()
				? BLOCK_ELEMENTS.get(pipeline)
				: ELEMENTS.get(pipeline);
	}

	private final PackChain owner;
	private final Path packPath;
	private final String place;
	private final Map<String, OptionValue> chosen;
	private final String profile;
	private final PackValues values;
	private final int load;
	private final ChainPlan plan;
	private final TargetPlan chainTargets;
	private final boolean chainRuns;

	/** Whether the game's finished frame is painted in under the chain, which this family rides on. */
	private final boolean seeded;
	private final ColorTargets targets;

	/** One program per piece the pack serves. Empty until the pack has been read, and it stays empty
	 * for a pack this place can serve no entity with at all. */
	private final Map<String, EntityProgram> programs = new LinkedHashMap<>();

	/** Whether the pack has been read for its entities. A reading that served nothing is still one. */
	private boolean read;

	/** The reasons a draw has already been handed back to the game. One line each, not one a frame. */
	private final Set<String> refused = new LinkedHashSet<>();

	/** The pass a run of draws is being recorded into, or null between runs. */
	private RenderPass open;

	/** The program that pass was opened for, and the pipeline it was prepared with. */
	private EntityProgram drawing;
	private RenderPipeline bound;

	EntityDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
			String profile, PackValues values, int load, ChainPlan plan, TargetPlan chainTargets,
			boolean chainRuns, boolean seeded, ColorTargets targets) {
		this.owner = owner;
		this.packPath = packPath;
		this.place = place;
		this.chosen = Map.copyOf(chosen);
		this.profile = profile;
		this.values = values;
		this.load = load;
		this.plan = plan;
		this.chainTargets = chainTargets;
		this.chainRuns = chainRuns;
		this.seeded = seeded;
		this.targets = targets;
	}

	/** Whether a pack's own entity programs take over the game's, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/**
	 * The same answer, for the one thing outside this class that has to know it before the window
	 * opens: the draws of a frame are grouped while the features are prepared, which is earlier than
	 * anything here runs, and keeping a block entity's geometry out of a mob's draw is a change to
	 * what the game itself does. With this off nothing of ours may touch that grouping.
	 */
	public static boolean wanted() {
		return wanted;
	}

	/**
	 * Opens and closes the one window of the frame this family is served in, which the caller brackets
	 * with the game's own two events: the opaque chunks are finished at the first and the opaque
	 * features are finished at the second.
	 * <p>
	 * A window and not a test on what is being drawn, because there is nothing to test: the hand and
	 * the screen are drawn by the same renderers with the same pipelines, out of a submit storage of
	 * the game's own choosing, and the draw carries no word about which it came from.
	 * <p>
	 * Closed again at the frame boundary whatever happens, so that a frame that threw between the two
	 * events cannot leave the hand and the inventory being drawn as entities for the rest of the
	 * session.
	 */
	public static void opaqueFeatures(boolean drawing) {
		opaqueFeatures = drawing;
		if (!drawing) {
			// The three marks of the block entities go with it, and this is their frame boundary.
			// They are raised and lowered around calls rather than switched, so an unbalanced one is
			// a mob lit as a chest, and it would otherwise last the rest of the session.
			BlockEntityGeometry.clear();

			// Closing the window closes the pass, and that is not the same safety net as the one at
			// the end of a group. The next thing the game does after this is called is to copy a
			// depth between targets, which the encoder refuses while a pass is open, so a pass that
			// only the frame boundary closed would already have cost the frame by then.
			endGroup();
		}
	}

	/**
	 * Records one draw of a feature group with the pack's own program, or answers no and leaves it to
	 * the game.
	 * <p>
	 * No is the ordinary answer and covers everything from text to particles: the table holds ten
	 * pipelines and the game has a hundred. What matters about a no is that it closes the pass this
	 * was recording into, since the caller is about to open one of its own for the same draw and the
	 * two would overlap.
	 *
	 * @return whether this engine drew it, in which case the caller must not
	 */
	public static boolean draw(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info) {
		EntityDraw draw = PackChain.entities();
		if (draw == null) {
			return false;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		Element element = element(prepared.pipeline());
		if (!wanted || !opaqueFeatures || device == null || element == null
				|| prepared.outputTarget() != OutputTarget.MAIN_TARGET) {
			draw.end();

			return false;
		}

		try {
			return draw.record(device, element, prepared, info);
		} catch (RuntimeException e) {
			// Said before the pass is closed and not after: closing one the failure left in a bad
			// state can throw in its turn, and the second throw would carry away the only line that
			// says what went wrong first.
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the entities after an error", e);
			draw.end();

			return false;
		}
	}

	/**
	 * Closes the pass a group left open, at the end of that group.
	 * <p>
	 * Owed even though every no already closes one: a group whose last draw was ours ends without
	 * another draw ever being offered, and the pass would then stay open across whatever the game
	 * does next. What that costs is not a leak but a refusal, the encoder allowing one pass at a
	 * time, so the next thing the game draws would throw rather than be drawn.
	 */
	public static void endGroup() {
		EntityDraw draw = PackChain.entities();
		if (draw != null) {
			draw.end();
		}
	}

	/**
	 * Whether what this family writes still reaches the screen this frame, which is the question
	 * {@code TerrainDraw.shown} and {@code SkyDraw.shown} both ask and the same answer: the chain
	 * draws nothing at all while it is still compiling, so a frame that wrote the pack's targets then
	 * would be a frame with no entity in it.
	 */
	private boolean shown() {
		return !this.chainRuns || this.owner.drawable();
	}

	private boolean record(GpuDevice device, Element element, PreparedRenderType prepared,
			StagedVertexBuffer.ExecuteInfo info) {
		if (!this.read) {
			read();
		}

		EntityProgram program = this.programs.get(element.element());
		if (program == null) {
			end();

			// The reason that was silent, and one of the two settled by the LOAD rather than by the
			// frame.
			//
			// It names the PIECE and claims nothing about the others, which took two reviews to get
			// right: the map really can hold one half and not the other. The two names of this family
			// walk disjoint chains, gbuffers_block through the terrain and gbuffers_entities through
			// the textured pair, so a pack that ships the first road and not the second resolves
			// every chest and no mob, and the load drops the mob pieces one at a time rather than
			// giving up on the family. What that paints is every chest lit by the pack and every mob
			// beside it lit by the game, for as long as the pack is loaded.
			//
			// No pack of the corpus is in that position, measured over its twenty five places. The
			// line is written for the pack that will be, because this is the one shape of failure
			// that looks like a decision rather than a fault.
			return refuse("missing:" + element.element(), true,
					"the load left no program for the " + element.element() + " piece");
		}

		if (this.drawing != program && !begin(device, element, program, prepared)) {
			return false;
		}

		// The image belongs to the DRAW and not to the pass: one pipeline draws every mob on screen
		// and each of them brings its own skin, so this is set again for every draw recorded.
		PreparedRenderType.Texture texture = image(prepared);
		program.texture(texture == null ? null : texture.textureView(),
				texture == null ? null : texture.sampler());

		this.open.setPipeline(this.bound);
		scissor(prepared.scissorState());
		program.bind(this.open);
		this.open.setVertexBuffer(0, info.vertexBuffer().slice());
		this.open.setIndexBuffer(info.indexBuffer(), info.indexType());
		this.open.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);

		return true;
	}

	/**
	 * Opens the pass a run of draws is recorded into, having closed whatever came before it.
	 * <p>
	 * Everything that cannot happen inside a render pass happens here and in this order: the previous
	 * pass closed, the frame opened, the colour targets allocated and cleared, the pipeline compiled
	 * and this frame's uniform block written. All of them allocate or copy, and the encoder refuses
	 * both while a pass is open.
	 *
	 * @return whether the pass was opened, and false leaves the draw to the game
	 */
	private boolean begin(GpuDevice device, Element element, EntityProgram program,
			PreparedRenderType prepared) {
		end();
		this.owner.beginFrame();
		if (!this.owner.openTargets(device)) {
			return refuse("targets", "the colour targets could not be opened this frame");
		}

		if (!shown()) {
			return refuse("warming", "the chain is still compiling, so nothing written to the pack's targets "
					+ "would reach the screen yet");
		}

		// The matrix belongs to the RUN and not to the draw, which is what makes it settleable here:
		// the depth nudge is the render type's and every draw of a run is one piece of the table, so
		// the whole run shares the one the piece carries. Written into the block a few lines down.
		RenderPipeline pipeline = program.prepare(device, element.modelView());
		if (pipeline == null) {
			// Lasting, and it took a review to see it: a program that would not compile, or that
			// declares a storage block, latches broken in GeometryProgram and never unlatches, so
			// this answers null for the rest of the load rather than for this frame.
			// Keyed by PROGRAM NAME and not by reason alone, unlike the frame's three: the two halves
			// of the family are usually two files, so one key would name whichever failed first and
			// leave the other silent. Not by piece either, or a file that will not compile would say
			// so once per piece it serves, which is ten lines for one fault.
			return refuse("prepare:" + element.program(), true,
					"the " + element.element() + " program refused to prepare, which it says on its "
							+ "own line above");
		}

		// The two images the game would have drawn into, worked out as PreparedRenderType works them
		// out: the overrides are the game's own way of sending a phase somewhere else, and the layer
		// that carries its translucent features is one of them.
		RenderTarget target = prepared.outputTarget().getRenderTarget();
		GpuTextureView colour = RenderSystem.outputColorTextureOverride != null
				? RenderSystem.outputColorTextureOverride
				: target.getColorTextureView();
		GpuTextureView depth = !target.useDepth ? null
				: RenderSystem.outputDepthTextureOverride != null
						? RenderSystem.outputDepthTextureOverride
						: target.getDepthTextureView();

		RenderPassDescriptor descriptor = program.descriptor(colour, depth);
		if (descriptor == null && !program.plain()) {
			// The colour targets are not there yet, which is the first frame or two and the frames
			// after a resize. A plain pass would carry one attachment against a pipeline holding a
			// state per target the pack asked for, and setPipeline refuses that by name.
			return refuse("unallocated", "one of the pack's colour targets has no image yet");
		}

		CommandEncoder encoder = device.createCommandEncoder();
		this.open = descriptor == null
				? encoder.createRenderPass(LABEL, colour, Optional.empty(), depth,
						OptionalDouble.empty())
				: encoder.createRenderPass(descriptor);
		this.drawing = program;
		this.bound = pipeline;

		return true;
	}

	/**
	 * Hands one draw back to the game and says why, once per reason and per load.
	 * <p>
	 * <strong>Every one of these used to be silent, and that is what made the defect they cause
	 * undiagnosable.</strong> A draw handed back is drawn by the game's own shader, so an entity is
	 * lit by the game where everything around it is lit by the pack, with nothing anywhere to say
	 * which reason it was. That is the exact shape of failure this engine exists to refuse, and it
	 * was found by somebody looking at a chest rather than by any instrument of ours.
	 * <p>
	 * <strong>How long it lasts is not the same for all of them, and the line says which.</strong>
	 * The reasons that answer a question about this FRAME come and go, so the same entity is the
	 * pack's on one frame and the game's on the next, and that reads as a flicker. The ones settled
	 * by the LOAD cannot come back: they hold until the pack is read again, so what they paint is
	 * steady rather than flickering, and steady is the worse of the two to look at because it looks
	 * deliberate. Which is which is not obvious from the call site and one of them was got wrong for
	 * a whole review: it is not "did the pack ship it" against "is the frame ready", it is whether
	 * anything downstream latches, and {@code GeometryProgram} latches broken.
	 * <p>
	 * Once per reason and keyed on the reason rather than on the sentence, because the alternative
	 * is a line a frame at sixty frames a second and the sentences carry the piece's name.
	 *
	 * @param reason  what this is, for the dedup and for nothing else
	 * @param lasting whether it holds for the whole load rather than for this frame
	 * @return false always, so that a caller can hand this straight back
	 */
	private boolean refuse(String reason, boolean lasting, String why) {
		if (this.refused.add(reason)) {
			Vitrail.logger().warn("An entity draw went back to the game's own shader because {}. {}",
					why, lasting
							? "It is settled for as long as this pack is loaded, so this geometry is "
									+ "drawn by the game on every frame until the pack is read again, "
									+ "steadily rather than as a flicker"
							: "It is then lit by the game for that frame and by the pack on the frames "
									+ "it is served, which reads on screen as a flicker");
		}

		return false;
	}

	/** The ordinary kind, which answers a question about this frame alone. */
	private boolean refuse(String reason, String why) {
		return refuse(reason, false, why);
	}

	/**
	 * Closes the pass a run was being recorded into, if there is one.
	 * <p>
	 * The fields are cleared before the close and not after: a close that throws must not leave this
	 * holding a pass nobody can record into and nobody will ever close again, which would turn one
	 * failed draw into a frame that cannot be finished.
	 */
	private void end() {
		RenderPass pass = this.open;
		this.open = null;
		this.drawing = null;
		this.bound = null;
		if (pass != null) {
			pass.close();
		}
	}

	/**
	 * The scissor the game set for this draw, said again for every draw of a run.
	 * <p>
	 * Both ways round and not only the enabling one: the state belongs to the draw and the pass
	 * outlives it, so a rectangle left standing from the draw before would cut whatever comes next
	 * down to it.
	 */
	private void scissor(ScissorState state) {
		if (state.enabled()) {
			this.open.enableScissor(state.x(), state.y(), state.width(), state.height());
		} else {
			this.open.disableScissor();
		}
	}

	/** The image the game was going to draw this piece with, or null where it binds none. */
	private static PreparedRenderType.Texture image(PreparedRenderType prepared) {
		for (PreparedRenderType.Texture texture : prepared.textures()) {
			if (TEXTURE.equals(texture.name())) {
				return texture;
			}
		}

		return null;
	}

	/**
	 * Reads the pack for every piece at once, at the first entity the game draws, and settles where
	 * the outputs of each of them go.
	 * <p>
	 * All of them and not the one being asked for, for the reason the sky reads all six: the moment
	 * a piece is first drawn is the world's to choose, and some of them wait a long time. Nothing
	 * asks for the armour decal until somebody wears armour that carries one, and read one at a time
	 * the pack would be opened, expanded and translated inside that frame, on the render thread and
	 * in the middle of the world.
	 */
	private void read() {
		this.read = true;

		// Measured rather than assumed, and it is the one assumption of this family that would fail
		// in silence. The prologue declares the six elements of DefaultVertexFormat.ENTITY by name,
		// and an element the stage does not declare shifts the location of every one after it without
		// a word: the picture stays a picture and reads its texture coordinates out of the light map.
		List<Element> served = ELEMENTS.values().stream()
				.filter(element -> {
					VertexFormat format = element.pipeline().getVertexFormatBinding(0);
					if (DefaultVertexFormat.ENTITY.equals(format)) {
						return true;
					}

					Vitrail.logger().warn("The game draws the {} of an entity with {} and this engine "
							+ "decodes the entity format, so the game keeps its own shader for it",
							element.element(), format);

					return false;
				})
				.toList();

		// The block entity half of each served piece, added here rather than filtered again: the two
		// share a pipeline, so a format this engine cannot decode has already been reported once and
		// saying it twice would read as two defects.
		List<Element> asked = Stream.concat(served.stream(),
						served.stream().map(element -> BLOCK_ELEMENTS.get(element.pipeline())))
				.toList();

		try {
			Map<String, PackProgram.Loaded> loaded = PackProgram.loadEntities(this.packPath, this.place,
					asked.stream().map(Element::asked).toList(), this.chosen, this.profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for the entities, so the game keeps its "
						+ "own shader for them", this.packPath.getFileName(),
						this.place.isEmpty() ? "its root" : this.place);

				return;
			}

			// Asked once per serving FILE and not once per piece: the pieces are two program names at
			// most, so they are one or two files, and the plan would answer for each of them ten
			// times over.
			//
			// All of them or none of them, which is what the return in the middle is, and it holds
			// across the two names rather than per name. These programs write the pack's targets and
			// reach its colour target through the scene seed, so a piece whose answer could not be
			// settled would be drawn by the game into the same picture, and a chest and the mob beside
			// it would disagree about what lights them.
			Map<String, List<ChainPlan.Attachment>> byFile = new LinkedHashMap<>();
			for (PackProgram.Loaded one : loaded.values()) {
				String servedBy = servedBy(one);
				if (byFile.containsKey(servedBy)) {
					continue;
				}

				List<ChainPlan.Attachment> writes = writes(servedBy);
				if (writes == null) {
					return;
				}

				byFile.put(servedBy, writes);
			}

			asked.stream()
					.filter(element -> loaded.containsKey(element.element()))
					.forEach(element -> this.programs.put(element.element(), EntityProgram.of(
							loaded.get(element.element()), element, this.values, this.load,
							byFile.get(servedBy(loaded.get(element.element()))), this.chainTargets,
							this.targets, this.chainRuns)));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the entity programs of "
					+ this.packPath.getFileName() + ", so the game keeps its own shader for them", e);
		}
	}

	/**
	 * Where the outputs of one file that serves a piece belong, in draw buffer order and each on the
	 * half the schedule gives it, or null when this place cannot answer for it.
	 * <p>
	 * Empty is not a refusal and is the ordinary case: a pack that declares no draw buffer on its
	 * entity program writes one output, which goes to the game's target and reaches the pack's
	 * picture through the scene seed. <strong>Where it lands is the seed's answer and not
	 * OptiFine's</strong>, and the two differ: OptiFine infers colortex0 for a program that declares
	 * nothing, while the seed paints the first draw buffer of the TERRAIN, which is colortex1 on two
	 * packs of the corpus and colortex4 on a third. Two places of the corpus really are in that
	 * case, Body Camera's {@code world1} and {@code world-1}, whose entities fall back on a
	 * {@code gbuffers_textured} that declares no draw buffer at all; what keeps it from mattering
	 * there is not this branch but the one above it, those same two places having no seed either.
	 * <p>
	 * Null is a refusal, and there are three of them. The scene seed switched off, which takes the
	 * only road the first output has. A place whose entity targets are not the size of
	 * the screen cannot share a pass with the game's own target, one render pass having one render
	 * area. And a first draw buffer that is not the one the scene seed paints is the one refusal
	 * particular to this family: what the first output writes goes to the game's target and the seed
	 * carries it into the target it was taken for, which is the terrain's first draw buffer. Where
	 * the two agree, which is every pack of the corpus, the output lands where the pack asked for it;
	 * where they do not, it would land in somebody else's, and the picture would be a pack's albedo
	 * read as its normals.
	 */
	private List<ChainPlan.Attachment> writes(String servedBy) {
		// Before the plan is even asked, because it is not the plan's to answer: the scene seed is
		// the ONLY road this family's first output has into the pack's picture, so a run with the
		// seed switched off would write every other draw buffer and no albedo at all. What that
		// paints is a mob shaped hole of normals and specular over the terrain's own colours, which
		// is exactly the plausible and wrong picture the switch exists to rule out.
		// Only where the chain runs, and the condition is not a refinement. With chain=off nothing
		// of the pack reaches the screen through a final, so draw buffer nought stays on the game's
		// own target and is the picture: the seed has nothing to carry and is never even drawn.
		// Refusing the family there would take away the one configuration that tells a wrong
		// gbuffer from a wrong composite, which is what these switches exist for.
		if (this.chainRuns && !this.seeded) {
			Vitrail.logger().info("The scene seed is off, and it is the only way the first output of "
					+ "an entity reaches the pack's picture, so the game keeps its own shader for the "
					+ "entities: served, they would write every other draw buffer and no colour");

			return null;
		}

		Optional<ChainPlan.Pass> geometry = this.plan.geometryOf(servedBy, false);
		if (geometry.isEmpty()) {
			return List.of();
		}

		ChainPlan.Pass pass = geometry.get();
		if (!pass.size().equals(TargetSize.ofScreen())) {
			Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so they cannot share "
					+ "a pass with the game's own target and the game keeps its own shader for the "
					+ "entities", servedBy);

			return null;
		}

		ChainPlan.Attachment first = pass.attachments().get(0);
		Optional<ChainPlan.Seed> seed = this.plan.seed();
		if (seed.isEmpty() || seed.get().target() != first.target()
				|| seed.get().side() != first.side()) {
			Vitrail.logger().warn("{} writes {} first and the scene seed paints {}, so the first output "
					+ "of an entity would be carried into a target the pack did not ask for: the game "
					+ "keeps its own shader for the entities", servedBy,
					TargetName.canonical(first.target()),
					seed.map(where -> TargetName.canonical(where.target())).orElse("nothing"));

			return null;
		}

		return pass.attachments();
	}

	/** The bare name of the file behind a loaded program, which is what the plan is keyed by. */
	private static String servedBy(PackProgram.Loaded loaded) {
		return loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
	}

	/** The programs once the entities have been read, for the decoded dump. Empty until then. */
	Collection<EntityProgram> programs() {
		return this.programs.values();
	}

	/**
	 * Rotates the ring buffers, and closes a pass no group closed. Called once the frame's draws have
	 * been recorded.
	 */
	void rotate() {
		end();
		opaqueFeatures(false);
		this.programs.values().forEach(EntityProgram::rotate);
	}

	void release() {
		end();
		this.programs.values().forEach(EntityProgram::release);
		this.programs.clear();
		// With the programs, or "once per load" would mean once per session: what is read again after
		// this can refuse again, for the same reason or for another, and a reader watching a portal
		// would see the first reading's lines and nothing after them.
		this.refused.clear();
		this.read = false;
	}
}
