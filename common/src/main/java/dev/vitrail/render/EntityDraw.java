package dev.vitrail.render;

import dev.vitrail.glsl.LegacyGlsl;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.VertexInputs;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;

import org.joml.Matrix4fc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
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
 * <strong>Both halves of the geometry are served, in two windows and never in one</strong>, because
 * the game itself splits them: a submitted model goes to the solid submits or to the translucent
 * ones on {@code RenderType.hasBlending} ({@code SubmitNodeCollection.java:173-179}), and the two
 * are executed on opposite sides of the event this engine runs its deferred stage at
 * ({@code feature/FeatureRenderDispatcher.java:198-217}). The table below is keyed by pipeline and
 * so carries the same split without a column for it, a pipeline either declaring a blend function
 * or not, and {@link #inWindow} is what refuses a row offered in the other half's window.
 * <p>
 * <strong>That sort is not the whole of how geometry reaches the two windows, and two of the rows
 * below depend on the rest of it.</strong> The water mask is peeled off by identity BEFORE the blend
 * is looked at ({@code SubmitNodeCollection.java:174-175}), so it is neither half. And the mob's
 * ground shadow never goes through that sort at all: {@code submitShadow} puts it in a phase of its
 * own ({@code :88-90}), which {@code executeTranslucent} runs FIRST, ahead of the translucent models
 * ({@code feature/FeatureRenderDispatcher.java:212}). It reaches this door all the same, and that
 * had to be proved rather than assumed, its renderer being the one that could have had an
 * {@code executeGroup} of its own as the particles do:
 * {@code ShadowFeatureRenderer extends RenderTypeFeatureRenderer}
 * ({@code feature/ShadowFeatureRenderer.java:19}), inheriting it.
 * <p>
 * <strong>What that costs the two halves is not the same thing, and it is the whole of why they
 * are two.</strong> The opaque half is drawn before the deferred stage, so it takes its first draw
 * buffer by writing the coverage mask, which is what keeps the scene seed off the pixels it wrote;
 * {@link Element#covers} carries that. The blending half is drawn after the stage, onto a colour
 * target the chain has already composed, which is the position the world's own water is in: it
 * takes that buffer outright, owes no mask, and reads the far side of every target.
 * <p>
 * <strong>{@link FeatureLayer} is a second road into that same target and no draw takes both.</strong>
 * The layer catches what the game draws for itself during that window, by the game's own colour
 * override; a draw this engine records is one the game never makes, and where the pack took draw
 * buffers at all the pass built for it names the pack's target rather than the override. What is
 * left over is an ORDER, and it is a documented divergence from Iris rather than a detail:
 * {@link FeatureLayer} carries it, with what Iris does instead and what it costs the image.
 * <p>
 * <strong>What is still the game's inside this window</strong>, and therefore still goes to that
 * layer, is the eyes ({@code EYES} and {@code ENTITY_TRANSLUCENT_EMISSIVE}), the beacon beam, the
 * text of a name plate or a sign, and the two pipelines {@link #WITHHELD} names. The shadow map is a
 * family of its own and is NOT in either window, a pass of its own that neither bracket reaches.
 * <p>
 * <strong>An enchantment's glint comes in by this same door and is alone in it</strong>, in two ways
 * that are one: it is the only piece served here that is not drawn from an entity mesh, and the only
 * one whose pipeline carries more than one render type's worth of answers. {@link #GLINT_EARLY}
 * carries what that costs it, which is four pieces where every other family has one per table.
 * <p>
 * <strong>The hand comes in by this same door and is not one of those.</strong> {@link HandDraw}
 * moves it off the game's late call and submits it twice, and both submissions are
 * drawn by feature renderers with the pipelines of the table below, so a hand draw is indexed here
 * like a mob's. What tells it apart is the moment and not the draw, exactly as it is for a block
 * entity, and {@link #element} asks about it FIRST for the reason Iris does
 * ({@code pipeline/IrisPipelines.java:191-218}): a pipeline that would answer with the block or the
 * entity program answers with a hand program while a hand pass is up, whatever else is true of it.
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
	 * moment a writing row of the table below may be served.
	 * <p>
	 * <strong>The feature renderers are not the level's.</strong> One dispatcher draws three things
	 * through the same {@code executeGroup}: the level's features, the hand, and the screen, the last
	 * two out of a submit storage of their own that {@code GameRenderer} hands it after the level is
	 * finished. Every one of them reaches this class with the same pipelines and the same main
	 * target, so nothing about a draw says which of the three it belongs to; only the moment does.
	 * <p>
	 * <strong>The hand is no longer among what this window has to keep out, and the window is kept
	 * all the same.</strong> {@link HandDraw} takes it off that late call and submits it inside the
	 * level with a dispatcher of its own, so it now arrives with a mark of its own and is served
	 * rather than excluded. What is left after the level is the screen, which has none and would
	 * otherwise be drawn as a mob, so nothing about this window may be relaxed.
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

	/**
	 * Whether the game is drawing the level's own translucent features at this instant, which is the
	 * only moment a blending row of the table below may be served.
	 * <p>
	 * A window of its own and not the other one widened, for the reason the other one exists at all:
	 * the hand and the screen come through this same door with these same pipelines, and the second
	 * of the two events below is what puts them outside. It is the same bracket
	 * {@code PackChain.openFeatures} and {@code closeFeatures} take, so a draw served here is one the
	 * layer would otherwise have caught.
	 * <p>
	 * The bracket holds the game's {@code executeTranslucent} and a little more: the game copies the
	 * main depth into its translucent, item entity and particle targets between the first event and
	 * that call ({@code LevelRenderer.java:456-464}). Nothing of ours draws in there, so nothing of
	 * ours has a pass open across those copies, which the encoder would refuse.
	 */
	private static volatile boolean translucentFeatures;

	/**
	 * Whether this engine is walking the world for the light at this instant, which is the only
	 * moment a row of the shadow table may be served.
	 * <p>
	 * The third window, and the one that is entirely ours: the game submits nothing of its own into
	 * the shadow map, so what is drawn between these two calls is what this engine's own walk of the
	 * world for the light put there and nothing else. It is still a window rather than a test on the
	 * draw, for the same reason the other two are: the pipelines are the same ones the camera drew
	 * with, and nothing about a draw says which walk submitted it.
	 */
	private static volatile boolean shadowFeatures;

	/** The pass this engine opens for a run of draws, when the pack has nothing more to say. */
	private static final String LABEL = "Vitrail entity";

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
	 * @param stage         what the pack is told it is drawing. {@code NONE} for a mob, which is not a
	 *                      reading of what the pass is but Iris's answer, and {@link EntityProgram}
	 *                      has the four places it was read from; {@code BLOCK_ENTITIES} for a block
	 *                      entity, which Iris really does pose and which is the very phase its own
	 *                      table branches on to reach {@code gbuffers_block}; and one of the two hand
	 *                      phases for a hand piece, which Iris poses around each of its two passes
	 *                      ({@code pathways/HandRenderer.java:106,143})
	 * @param afterDeferred whether this piece's PASS is drawn after the deferred stage. True for
	 *                      the hand's water rows alone. A hand row answers the side with this flag
	 *                      and never with {@link #blended()}, its pipeline being the mob's and its
	 *                      moment its pass's; an entity row answers with {@link #blended()}, and
	 *                      carries false here
	 */
	record Element(RenderPipeline pipeline, String element, String program, AlphaTest alphaTest,
			RenderStage stage, boolean afterDeferred) {

		/** A piece of the mob half, which is every row of the table above. */
		Element(RenderPipeline pipeline, String element, String program, AlphaTest alphaTest) {
			this(pipeline, element, program, alphaTest, RenderStage.NONE, false);
		}

		/**
		 * Whether this piece belongs to the hand, which is a question about the phase and not about
		 * the pipeline: a hand row and the mob row it was made from share one.
		 */
		boolean hand() {
			return this.stage == RenderStage.HAND_SOLID || this.stage == RenderStage.HAND_TRANSLUCENT;
		}

		/**
		 * Whether this piece is drawn into the shadow map rather than into the picture, which is a
		 * question about the program: the shadow table asks for one name and no row outside it does.
		 */
		boolean shadow() {
			return SHADOW_ENTITIES.equals(this.program);
		}

		/**
		 * Whether this piece is an enchantment's glint, asked of the PIPELINE where {@link #shadow}
		 * asks of the program, and the difference is not a taste: what a glint answers differently is
		 * mostly what its MESH is, and the mesh belongs to the pipeline. Asked of the program instead,
		 * a row that ever kept this pipeline under another name would claim the entity format and
		 * declare six inputs against a buffer carrying two.
		 * <p>
		 * It is the answer to three other questions here, all three from that one fact: which format
		 * the pass binds, which window the row belongs in, and what the log calls it.
		 */
		@SuppressWarnings("ReferenceEquality")
		boolean glint() {
			return this.pipeline == RenderPipelines.GLINT;
		}

		/**
		 * The game's format this piece is drawn from, which is the entity mesh for every row but the
		 * glint's four.
		 * <p>
		 * Derived from {@link #glint} rather than tabulated beside it, so that the two cannot drift: a
		 * row whose format did not match the pipeline it names would declare the wrong inputs and read
		 * its texture coordinates off whatever the format really carries, without a word being said.
		 * {@link #decodable} is where the claim is checked against the pipeline in hand.
		 */
		VertexFormat format() {
			return glint() ? DefaultVertexFormat.POSITION_TEX : DefaultVertexFormat.ENTITY;
		}

		/** Where the vertex stage of this piece takes its inputs from, which is the same answer. */
		VertexInputs inputs() {
			return glint() ? VertexInputs.GLINT : VertexInputs.ENTITY;
		}

		/** One word for the log, which has to say which of the four families a line is about. */
		String family() {
			if (shadow()) {
				return "entities in the shadow map";
			}

			if (glint()) {
				return "glint";
			}

			return hand() ? "hand" : "entities";
		}

		/**
		 * Which side of the deferred stage this piece's PASS is drawn on, which is not always what
		 * its own pipeline blends.
		 * <p>
		 * <strong>One question, three answers, and asked HERE because it used to be written out
		 * twice and the two copies disagreed.</strong> An entity row is asked of its pipeline, the
		 * game sorting its own submissions that way. A hand row is asked of its row, a hand pass
		 * being drawn wholly on one side whatever its rows blend, which is what {@link
		 * #afterDeferred} carries and what the javadoc of that field says. A shadow row is neither:
		 * the map is filled before the stage has run at all, so the whole of that table is on the
		 * early side, and asking its pipeline would put its translucent rows on a side of the frame
		 * the map never reaches.
		 * <p>
		 * The copy that read the blend was {@link EntityProgram}'s, and it misbound exactly the hand
		 * rows whose blend disagrees with their pass. That is half of them and the halves are
		 * exact, both tables being twins of the one mob table and the blend being read off the
		 * shared pipeline: every mob row is misbound in one of the two tables and served correctly
		 * in the other. The arm blends and is drawn in the solid pass, so it read and wrote the far
		 * half of every target it touches; a water row built from a pipeline that does not blend was
		 * bound the other way round.
		 * <p>
		 * <strong>A glint row is asked of its row like a hand row, and for a reason of its own.</strong>
		 * Its pipeline blends and that says nothing about the moment: what carries the glint decides
		 * which half the game executes it in, and an enchanted book goes to the SOLID features, the
		 * sort looking at the item's own quads and not at the foil hung on them
		 * ({@code SubmitNodeCollection.java:325}). So one glint row is drawn before the deferred stage
		 * although it blends, which is the arm's position exactly.
		 */
		boolean afterStage() {
			if (shadow()) {
				return false;
			}

			return (hand() || glint()) ? this.afterDeferred : blended();
		}

		/**
		 * Whether this piece writes the coverage mask, which is the same question as whether it may
		 * own draw buffer nought: a piece drawn before the scene seed keeps the pack's colour only
		 * where the seed is kept off it, and the mask is what keeps it off.
		 * <p>
		 * <strong>Every piece of this door drawn before the stage, and that is what moves the
		 * entities into the pack's own targets.</strong> The mask carries the depth its fragment
		 * left, so the seed reads a mob's own depth back at every pixel of it, finds nothing drawn
		 * in front, and leaves the pixel alone. Marking it was worth nothing while the mask was a
		 * flag: the cut then compared the world's depth with one taken before a single feature was
		 * drawn, and a mob standing in front of a block moved that depth by construction, so its
		 * pixels answered "the game drew in front" whatever the flag said.
		 * <p>
		 * <strong>The glint is in and it could not be left out.</strong> It blends onto the pixels
		 * the piece under it just wrote, and those pixels are the pack's target now; left on the
		 * game's target it would blend onto a clear, and the seed would then throw it away, its own
		 * depth being the depth of the geometry it hangs on.
		 * <p>
		 * <strong>The hand is out, and not because a mask would be wrong for it.</strong> Its solid
		 * pass is a change to the order of the frame rather than to this answer, which
		 * {@link GeometryProgram} sets out where it demotes it: what that pass is waiting for is to
		 * be drawn between the seed and the deferred stage, which is Iris's own moment for it.
		 * <p>
		 * A piece drawn after the stage owes no mask, the seed having run long before, and a shadow
		 * row is in neither position: it is drawn into the map, which no seed ever paints.
		 */
		boolean covers() {
			return !shadow() && !afterStage() && !hand();
		}

		/** What the pack has to be read for to serve this piece, in terms the translation knows. */
		private PackProgram.GeometryElement asked() {
			return new PackProgram.GeometryElement(this.element, this.program, this.alphaTest,
					inputs(), covers());
		}

		/**
		 * Which half of the frame this piece belongs to, asked of the game's own pipeline rather than
		 * tabulated beside it.
		 * <p>
		 * It is the very question the game sorts its submissions by,
		 * {@code RenderType.hasBlending} at {@code rendertype/RenderType.java:44-46} reading the
		 * pipeline's colour state and {@code SubmitNodeCollection:173-179} sending the two answers to
		 * two different storages. A column of our own would be a second copy of it, and the copy that
		 * drifted would put a piece in the window where nothing is drawing it.
		 */
		boolean blended() {
			return this.pipeline.getColorTargetState().blendFunction().isPresent();
		}

		/**
		 * The model view this pass is drawn under, or null for the frame's own camera.
		 * <p>
		 * <strong>The hand is the one piece that hands one in, and the depth nudge of a render type
		 * is deliberately not in it.</strong> Where a piece LANDS comes from the matrix the game
		 * wrote for that draw, nudge included, which every program here reads through
		 * {@link LegacyGlsl#GAME_MODEL_VIEW}; what is left for this one to answer is everything
		 * DERIVED from the model view, the inverse and the normal matrix, and Iris builds those from
		 * {@code RenderSystem.getModelViewMatrix()} at program setup
		 * ({@code pipeline/programs/ExtendedShader.java:181-189}), which is the stack and carries no
		 * nudge. Putting one here would be a normal matrix a quarter of a per mille away from the
		 * reference's for the four rows that carry a transform, bought for nothing.
		 * <p>
		 * Answering null for the hand would be the one place this returns the wrong matrix rather
		 * than a spare one: null means the frame's camera, and the hand is not drawn under the
		 * frame's camera. What the stack holds while a hand pass runs is the identity, {@link
		 * HandDraw} putting the whole of the hand's transform in the projection, so what is read
		 * here is what the game itself would have read at that draw.
		 */
		private Matrix4fc modelView() {
			return hand() ? RenderSystem.getModelViewMatrixCopy() : null;
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
	 * The blending half of each of those two names, which is what Iris's own {@code getTranslucent}
	 * answers with: {@code ENTITIES_TRANSLUCENT} for a mob and {@code BE_TRANSLUCENT} for a block
	 * entity ({@code pipeline/IrisPipelines.java:212-222}), which are {@code ProgramId.EntitiesTrans}
	 * and {@code ProgramId.BlockTrans} ({@code pipeline/programs/ShaderKey.java:43,69}).
	 * <p>
	 * Both fall back onto their own opaque name and not onto the terrain, so a pack that ships one
	 * entity program and no translucent one draws its whole family with the file it wrote.
	 */
	private static final String ENTITIES_TRANSLUCENT = "gbuffers_entities_translucent";

	private static final String BLOCK_TRANSLUCENT = "gbuffers_block_translucent";

	/**
	 * What a cutout entity discards at, which is a tenth and not the half the terrain uses. Named
	 * here so that the table below reads as a table.
	 */
	private static final AlphaTest CUTOUT = AlphaTest.ONE_TENTH;

	/**
	 * The two pipelines of the blending half this engine leaves to the game, and the one reason it
	 * leaves them.
	 * <p>
	 * Both are drawn with a render type carrying a {@code TextureTransform} of the game's own, an
	 * {@code OffsetTextureTransform} built afresh per frame from the offsets the breeze and the swirl
	 * are animated by ({@code rendertype/RenderTypes.java:524,536}); every other render type of this
	 * family leaves it at {@code DEFAULT_TEXTURING}. That matrix is what a pack multiplies
	 * {@code gl_MultiTexCoord0} by, and it is one of the six sites in the whole game that set one,
	 * the four glints being the others.
	 * <p>
	 * <strong>The matrix is no longer what withholds them.</strong> A pack's
	 * {@code gl_TextureMatrix[0]} is answered from the game's own transforms, per draw, which is
	 * Iris's answer as well ({@code transform/transformer/VanillaCoreTransformer.java:86}), so a
	 * breeze served today would be animated rather than frozen and two breezes on screen would hold
	 * their own offsets. What is left is the row, and it is not one row.
	 * <p>
	 * <strong>They are not one row when they come back.</strong> The breeze is a {@code getTranslucent}
	 * row ({@code pipeline/IrisPipelines.java:56}), so it belongs with the six below; the swirl is
	 * pinned to {@code ENTITIES_CUTOUT} ({@code :60}), which is {@code ProgramId.Entities}, so it asks
	 * for the OPAQUE {@code gbuffers_entities} even though the game blends it. Whoever serves it will
	 * be adding a blending row that asks for the writing half's name, which no row here does. That is
	 * work not done, and it is the whole of what these two are still waiting on.
	 */
	private static final Map<RenderPipeline, String> WITHHELD = Map.of(
			RenderPipelines.BREEZE_WIND, "it would be an ordinary translucent row, which is what Iris "
					+ "makes of it, and that row has simply not been written",
			RenderPipelines.ENERGY_SWIRL, "it needs a row no other one here has, asking for the "
					+ "writing half's program while the game blends it, which is what Iris does with it");

	/**
	 * Every pipeline the game draws entity geometry with, and nothing else.
	 * <p>
	 * The list is drawn from the pipelines of {@code RenderPipelines} that bind
	 * {@code DefaultVertexFormat.ENTITY}, in two runs: those that declare no blend function, which
	 * the game draws among its solid features, and those that do, which it draws among its
	 * translucent ones. <strong>It is not all of them, and the ones left out are named rather than
	 * counted</strong>: {@code EYES} ({@code RenderPipelines.java:351-364}) and
	 * {@code ENTITY_TRANSLUCENT_EMISSIVE} ({@code :287-297}) bind that format and blend, and both are
	 * the EYES family, which Iris serves with {@code gbuffers_spidereyes} through
	 * {@code ENTITIES_EYES} and {@code ENTITIES_EYES_TRANS}
	 * ({@code pipeline/IrisPipelines.java:52,53}). That family is not here yet, so the game draws it
	 * and {@link FeatureLayer} carries it in. {@link #WITHHELD} names the other two left out, for a
	 * reason of their own. Each row that is here is a piece of
	 * its own even where two ask for the same program at the same threshold, which is how the sky is
	 * six pieces out of three files: they differ in what {@link Element} reads off them, and a piece
	 * is one compiled module.
	 * <p>
	 * The opaque run asks for {@code gbuffers_entities}, and that is only half the answer:
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
	 * half. All three of those halves are here, {@link #BLOCK_ELEMENTS} and the two hand tables under
	 * it. Only the end crystal beam and the offset cutout are {@code gbuffers_entities}
	 * unconditionally, and that holds of its MAIN table alone: its shadow table sends both to
	 * {@code shadow_entities}.
	 * <p>
	 * Three families are arguably somebody else's and are served here all the same: the armour pieces
	 * are the entity wearing them, the end crystal beam is drawn with the entity format and the
	 * entity snippet, and an item entity lying on the ground is an entity.
	 * <p>
	 * <strong>The blending run takes the tenth from Iris and not from the game</strong>, and that is
	 * the one place the two halves are read differently. Every row above carries whatever
	 * {@code ALPHA_CUTOUT} its pipeline declares, which happens to be Iris's answer as well; two rows
	 * below declare none at all, the banner pattern and the ground shadow, and Iris still gives them
	 * a tenth, {@code getTranslucent} reaching {@code ENTITIES_TRANSLUCENT} whatever the pipeline and
	 * that key carrying {@code ONE_TENTH_ALPHA} ({@code pipeline/programs/ShaderKey.java:42}). What
	 * it costs is the faintest ring of a mob's ground shadow, which fades out through that tenth and
	 * is clipped where it crosses it. Iris clips it too, and a pack writing its own
	 * {@code alphaTest} directive settles it for both.
	 * <p>
	 * <strong>Two rows Iris serves are missing on purpose</strong> and are named in {@link #WITHHELD}.
	 * <p>
	 * <strong>{@code ITEM_TRANSLUCENT} and {@code ENTITY_TRANSLUCENT_CULL} are here and are not
	 * corner cases</strong>, though both of the game's render types for them name
	 * {@code ITEM_ENTITY_TARGET} ({@code rendertype/RenderTypes.java:137-150,163-176}). That target
	 * is allocated only under the game's improved transparency and resolves to the main one
	 * otherwise, which is what {@link #onMainTarget} reads and why they are served at all: between
	 * them they carry every experience orb, every translucent item sheet and the translucent type of
	 * every living entity.
	 */
	private static final Map<RenderPipeline, Element> ELEMENTS = new LinkedHashMap<>();

	static {
		put(new Element(RenderPipelines.ENTITY_SOLID, "solid", ENTITIES, AlphaTest.OFF));
		put(new Element(RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD, "solid_offset", ENTITIES,
				AlphaTest.OFF));
		put(new Element(RenderPipelines.ENTITY_CUTOUT, "cutout", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ENTITY_CUTOUT_CULL, "cutout_cull", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ENTITY_CUTOUT_Z_OFFSET, "cutout_offset", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ENTITY_CUTOUT_DISSOLVE, "cutout_dissolve", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ARMOR_CUTOUT_NO_CULL, "armor", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL, "armor_decal", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.END_CRYSTAL_BEAM, "crystal_beam", ENTITIES, CUTOUT));
		put(new Element(RenderPipelines.ITEM_CUTOUT, "item", ENTITIES, CUTOUT));

		// The blending run. Every one of them reaches Iris through getTranslucent, which is one
		// function and not six rows: pipeline/IrisPipelines.java:35,36,38,39,55,83.
		put(new Element(RenderPipelines.ENTITY_TRANSLUCENT, "translucent", ENTITIES_TRANSLUCENT,
				CUTOUT));
		put(new Element(RenderPipelines.ENTITY_TRANSLUCENT_CULL, "translucent_cull",
				ENTITIES_TRANSLUCENT, CUTOUT));
		put(new Element(RenderPipelines.ARMOR_TRANSLUCENT, "armor_translucent", ENTITIES_TRANSLUCENT,
				CUTOUT));
		put(new Element(RenderPipelines.ITEM_TRANSLUCENT, "item_translucent", ENTITIES_TRANSLUCENT,
				CUTOUT));
		put(new Element(RenderPipelines.BANNER_PATTERN, "banner", ENTITIES_TRANSLUCENT, CUTOUT));
		// The dark oval a mob is given on the ground, the game's own entity_shadow render type. Named
		// for what it is rather than after that type: this class already draws into a shadow map, and
		// an element called shadow would read as a piece of it in the log and in the identifier.
		put(new Element(RenderPipelines.ENTITY_SHADOW, "ground_shadow", ENTITIES_TRANSLUCENT, CUTOUT));
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
	 * <p>
	 * <strong>The blending run has no exception at all</strong>, and that is Iris's shape rather than
	 * a simplification of it. Its three rows pinned to {@code ENTITIES_CUTOUT} whatever the phase are
	 * the energy swirl, the end crystal beam and the offset cutout
	 * ({@code pipeline/IrisPipelines.java:60,61,62}); the first of those three is withheld here and
	 * the other two are opaque, so none of them is a blending row of ours. Every row that does reach
	 * {@code getTranslucent} answers {@code BE_TRANSLUCENT} under the phase without the pipeline
	 * being consulted at all ({@code pipeline/IrisPipelines.java:212-222}). So a blending twin is
	 * always {@code gbuffers_block_translucent}, and the tenth is already what its mob row carries.
	 */
	@SuppressWarnings("ReferenceEquality")
	private static Element blockTwin(Element mob) {
		if (mob.blended()) {
			return new Element(mob.pipeline(), "block_" + mob.element(), BLOCK_TRANSLUCENT, CUTOUT,
					RenderStage.BLOCK_ENTITIES, false);
		}

		boolean ownProgram = mob.pipeline() != RenderPipelines.END_CRYSTAL_BEAM
				&& mob.pipeline() != RenderPipelines.ENTITY_CUTOUT_Z_OFFSET;

		return new Element(mob.pipeline(), "block_" + mob.element(),
				ownProgram ? BLOCK : mob.program(), ownProgram ? CUTOUT : mob.alphaTest(),
				RenderStage.BLOCK_ENTITIES, false);
	}

	/** What the hand asks for in its solid pass, and what its blending pass asks for instead. */
	private static final String HAND = "gbuffers_hand";
	private static final String HAND_WATER = "gbuffers_hand_water";

	/**
	 * The same pieces again for each of the hand's two passes, which are two programs of the pack and
	 * not two halves of the geometry.
	 * <p>
	 * <strong>Nothing about a pipeline decides which of the two answers.</strong> Iris keys the whole
	 * of it on which pass is running: every row that can carry a hand answers
	 * {@code isRenderingSolid() ? HAND_* : HAND_WATER_*}, whether it went through {@code getSolid},
	 * {@code getCutout} or {@code getTranslucent} ({@code pipeline/IrisPipelines.java:191-218}). What
	 * separates the passes is which ITEMS go into them, and that is settled a step earlier, in the
	 * submission; {@code HandDraw.skip} carries it.
	 * <p>
	 * <strong>Every row discards at a tenth, the solid ones included</strong>, and that is Iris's
	 * answer rather than an inheritance from the pipeline: its six hand keys over the entity format
	 * all carry {@code ONE_TENTH_ALPHA} ({@code pipeline/programs/ShaderKey.java:46-48,52-54}), where
	 * the entity solid key carries none. A hand row therefore does not keep the threshold of the mob
	 * row it was made from. The three it has beyond those six are glyph keys and carry
	 * {@code NON_ZERO_ALPHA} ({@code :49-51}); this engine draws no text at all, so no row here
	 * corresponds to them.
	 * <p>
	 * <strong>The blending pass reaches the arm and what it holds alike</strong>, and the second
	 * half arrived with the entities' blending rows: a translucent block held in hand draws with a
	 * blending pipeline, which is a row of the table above like the rest since that half landed, so
	 * its water twin serves it with {@code gbuffers_hand_water}; the arm around it comes on another
	 * row again. Both are the pack's in the water pass.
	 * <p>
	 * That row is {@code ENTITY_TRANSLUCENT} and not {@code ENTITY_SOLID}, which is worth saying
	 * because this file used to say the other and the demotion of draw buffer nought turns on it:
	 * {@code AvatarRenderer.renderHand} submits the arm with {@code RenderTypes.entityTranslucent}
	 * ({@code AvatarRenderer.java:288}), and that pipeline blends.
	 */
	private static final Map<RenderPipeline, Element> HAND_ELEMENTS = new LinkedHashMap<>();
	private static final Map<RenderPipeline, Element> HAND_WATER_ELEMENTS = new LinkedHashMap<>();

	static {
		// Derived from the mob table like the block one above and for the same reason: a row too many
		// is a compiled module nobody selects, a row too few is a piece of the hand silently drawn by
		// the game in the middle of one the pack drew.
		twins(HAND_ELEMENTS, "hand_", HAND, RenderStage.HAND_SOLID, false);
		twins(HAND_WATER_ELEMENTS, "hand_water_", HAND_WATER, RenderStage.HAND_TRANSLUCENT, true);
	}

	private static void twins(Map<RenderPipeline, Element> into, String prefix, String program,
			RenderStage stage, boolean afterDeferred) {
		ELEMENTS.values().stream()
				.map(mob -> new Element(mob.pipeline(), prefix + mob.element(), program, CUTOUT,
						stage, afterDeferred))
				.forEach(element -> into.put(element.pipeline(), element));
	}

	/** What everything submitted through the feature renderers asks for, seen from the light. */
	private static final String SHADOW_ENTITIES = "shadow_entities";

	/**
	 * The same pieces again as the shadow map sees them, which is ONE program for the lot and not the
	 * three the camera uses.
	 * <p>
	 * <strong>The block entities lose their own name here, and that is Iris's answer and not a
	 * simplification.</strong> Its shadow table is keyed on the pipeline like its main one, and every
	 * entity pipeline in it answers {@code SHADOW_ENTITIES_CUTOUT}, which is
	 * {@code ProgramId.ShadowEntities} ({@code pipeline/IrisPipelines.java:91-111,131} and
	 * {@code pipeline/programs/ShaderKey.java:79}). A chest and a mob are submitted with the same
	 * pipelines, so the block entity mark that buys {@code gbuffers_block} against the camera buys
	 * nothing against the light: there is no {@code shadow_block} row for anything this engine draws.
	 * The name exists in Iris and only {@code END_PORTAL} and {@code END_GATEWAY} reach it
	 * ({@code :129-130}), and neither is a row of the table above.
	 * <p>
	 * <strong>The ground shadow is left out, and it is left out because Iris leaves it out.</strong>
	 * {@code ENTITY_SHADOW} is assigned in the main table and appears nowhere in the shadow one, so a
	 * mob's dark oval keeps the game's own shader when the map is drawn. What serving it would add is
	 * not an occluder: the pipeline writes no depth at all
	 * ({@code RenderPipelines.java:375}, {@code DepthStencilState(GREATER_THAN_OR_EQUAL, false)}),
	 * and this table keeps the write exactly, so nothing of it would reach the depth a pack reads its
	 * shadows from.
	 * <p>
	 * <strong>Every row discards at a tenth and none of them blends</strong>, both read rather than
	 * chosen: the key carries {@code ONE_TENTH_ALPHA} whatever threshold its main twin had, and every
	 * shadow program of Iris is declared with {@code BlendModeOverride.OFF}
	 * ({@code shaderpack/loading/ProgramId.java:13-19}). A blending row therefore draws into the map
	 * outright, which is what a shadow map wants: the depth a surface stands at, not that depth mixed
	 * with the one behind it.
	 * <p>
	 * The stage is {@code ENTITIES} for the whole table, including the rows the camera draws under
	 * {@code NONE}. Iris poses it once for the whole stretch its feature renderers are drawn in
	 * ({@code shadows/ShadowRenderer.java:521} up to the copy), the two chunk groups of the same
	 * stage having their own ({@code :509} and {@code :599}), so unlike the main table there is no
	 * second answer for a block entity here.
	 */
	private static final Map<RenderPipeline, Element> SHADOW_ELEMENTS = new LinkedHashMap<>();

	static {
		ELEMENTS.values().stream()
				.filter(mob -> mob.pipeline() != RenderPipelines.ENTITY_SHADOW)
				.map(mob -> new Element(mob.pipeline(), "shadow_" + mob.element(), SHADOW_ENTITIES,
						CUTOUT, RenderStage.ENTITIES, false))
				.forEach(element -> SHADOW_ELEMENTS.put(element.pipeline(), element));
	}

	private static void put(Element element) {
		ELEMENTS.put(element.pipeline(), element);
	}

	/**
	 * What an enchantment's glint asks for, and the one name of this class that answers whatever else
	 * is going on.
	 * <p>
	 * Iris keys it on a CONSTANT, {@code p -> ShaderKey.GLINT}
	 * ({@code pipeline/IrisPipelines.java:50}), where almost every row of {@link #ELEMENTS} reaches a
	 * function that tests the hand and then the block entity phase. Two of them are pinned as well,
	 * the end crystal beam and the offset cutout ({@code pipeline/IrisPipelines.java:61,62}), and
	 * {@link #blockTwin} says what that costs them; what those two are pinned to is the key an entity
	 * row reaches anyway, so the pin only keeps the phase off them. The glint's names a program no
	 * other row of that table can reach. So a glint is a glint on a mob, on a chest and in the hand
	 * alike, and the four pieces below differ in the MOMENT and never in the name.
	 */
	private static final String ARMOR_GLINT = "gbuffers_armor_glint";

	/**
	 * The four moments an enchantment's glint reaches this door in, which are four compiled pieces of
	 * one program name.
	 * <p>
	 * <strong>Four and not one, because the side of the deferred stage is baked into a piece and the
	 * glint arrives on both.</strong> A piece is bound against a schedule step, which decides the half
	 * of every target it reads and whether its first output goes to the pack's target or to the
	 * game's; a single piece would be right in one window and quietly wrong in the other. It is the
	 * hand's shape, whose two passes are two programs for the same reason, and here the two picture
	 * windows need it too: what carries the glint decides which half the game executes it in, and the
	 * two halves are on opposite sides of the stage.
	 * <p>
	 * <strong>Which carrier goes where is the game's sort and not a guess.</strong> An enchanted book
	 * is submitted among the SOLID features, foil and all, {@code submitItem} looking at the item's
	 * own quads and never at the glint hung on them ({@code SubmitNodeCollection.java:325} reaching
	 * {@code ItemFeatureRenderer.Submit.hasTranslucency}); an enchanted armour piece, a trident and a
	 * shield are submitted through {@code submitModel} with a blending render type
	 * ({@code entity/layers/EquipmentLayerRenderer.java:105},
	 * {@code entity/ThrownTridentRenderer.java:38}, {@code special/ShieldSpecialRenderer.java:77}) and
	 * land among the translucent ones.
	 * <p>
	 * <strong>None of the four is a twin and none may be derived into the other tables.</strong> A
	 * block entity twin would ask for {@code gbuffers_block_translucent} inside a chest's draw and a
	 * hand twin for {@code gbuffers_hand_water} inside the hand's, and Iris asks for neither.
	 * <p>
	 * <strong>What the two picture pieces do NOT reproduce is the phase, and it is work not done
	 * rather than a choice</strong>: nothing here makes it impossible, so it is named rather than
	 * argued for. They carry {@code NONE}, so a glint submitted through {@code submitModel} from a
	 * block entity renderer is told it is drawing nothing in particular where Iris would have the
	 * block entity phase up, that phase being posed around the submission rather than read off the
	 * table. It does not touch the case this family exists for: an item's glint is filled from a
	 * buffer of {@code ItemFeatureRenderer}'s own rather than submitted, so it reaches none of the
	 * four sites Iris marks ({@code mixin/entity_render_context/MixinModelStorageTrigger.java:39,48,57}
	 * and {@code MixinGlyphRenderType.java:19}) and a held book's glint is {@code NONE} on both sides.
	 * What it costs is a pack branching on {@code MC_RENDER_STAGE_BLOCK_ENTITIES} inside its glint.
	 * <p>
	 * The threshold is Iris's and not the pipeline's, which declares none: its glint key carries
	 * {@code NON_ZERO_ALPHA} ({@code pipeline/programs/ShaderKey.java:71}).
	 */
	private static final Element GLINT_EARLY = glint("glint", RenderStage.NONE, false);

	private static final Element GLINT_LATE = glint("glint_late", RenderStage.NONE, true);

	private static final Element GLINT_HAND = glint("hand_glint", RenderStage.HAND_SOLID, false);

	private static final Element GLINT_HAND_WATER =
			glint("hand_water_glint", RenderStage.HAND_TRANSLUCENT, true);

	/**
	 * One of those four: the game's own glint pipeline, under the phase of the pass it is drawn in and
	 * on the side of the deferred stage that pass falls.
	 * <p>
	 * <strong>No row of this class holds the depth nudge its render type applies, and this pipeline is
	 * why no row could.</strong> Every other one is a render type family and so one nudge; this one is
	 * four render types and TWO, {@code ARMOR_ENTITY_GLINT} setting
	 * {@code LayeringTransform.VIEW_OFFSET_Z_LAYERING} so that it lands on the armour it covers and
	 * the other three setting none ({@code rendertype/RenderTypes.java:252} against
	 * {@code :255,263,270}). What settled it for the whole class is that the nudge does not need
	 * holding: it is already in the matrix the game wrote for that draw, which every piece the door
	 * records from the camera reads through {@link LegacyGlsl#GAME_MODEL_VIEW}.
	 */
	private static Element glint(String element, RenderStage stage, boolean afterDeferred) {
		return new Element(RenderPipelines.GLINT, element, ARMOR_GLINT, AlphaTest.NON_ZERO,
				stage, afterDeferred);
	}

	/**
	 * Which piece answers for a draw of this pipeline, which is the block entity one only where the
	 * draw came from a block entity renderer AND that pipeline has one. Null for a pipeline this
	 * engine does not serve at all, which is most of them.
	 * <p>
	 * <strong>The hand is asked about first and the order is Iris's</strong>, whose three ambiguous
	 * rows all test the hand before the block entity phase and the block entity phase before the
	 * default ({@code pipeline/IrisPipelines.java:191-218}). It matters rather than being a tidy
	 * order: the two marks can be up at once. A hand holding a chest submits through the very calls
	 * that raise the block entity mark, and asked the other way round that arm's chest would be drawn
	 * with {@code gbuffers_block} in the middle of a hand pass.
	 * <p>
	 * <strong>The glint is asked before the block entity mark and after the hand, and neither half of
	 * that is arbitrary.</strong> Before the mark, because Iris's row for it is a constant that
	 * consults nothing ({@code pipeline/IrisPipelines.java:50}): an eye on a mob standing where a
	 * chest had just been submitted must not be given the block program, and no more must a glint.
	 * After the hand, because which of its four pieces answers is a question about the MOMENT, and
	 * the hand's two passes are two of the four moments.
	 */
	@SuppressWarnings("ReferenceEquality")
	private static Element element(RenderPipeline pipeline) {
		// Asked before the other two and not after, because both of their marks can be up inside it:
		// the walk for the light submits block entities, which raises the block entity mark, and a
		// chest asked the other way round would be given gbuffers_block in the middle of the shadow
		// map. Iris has no such order to keep, its shadow table being a second table consulted
		// instead of the main one rather than a branch inside it (pipeline/IrisPipelines.java:85-134
		// against :25-83).
		if (shadowFeatures) {
			return SHADOW_ELEMENTS.get(pipeline);
		}

		if (HandDraw.drawing()) {
			if (pipeline == RenderPipelines.GLINT) {
				return HandDraw.drawingSolid() ? GLINT_HAND : GLINT_HAND_WATER;
			}

			return (HandDraw.drawingSolid() ? HAND_ELEMENTS : HAND_WATER_ELEMENTS).get(pipeline);
		}

		// The window decides which of the two picture pieces answers, and it is the only place in this
		// class where a window is read to CHOOSE a row rather than to refuse one. It has to be: the two
		// differ in the side of the deferred stage they are bound against, and nothing about a glint
		// draw says which of the game's two halves it was submitted into.
		if (pipeline == RenderPipelines.GLINT) {
			return translucentFeatures ? GLINT_LATE : GLINT_EARLY;
		}

		// One table or the other and no falling between them: the block table is built from every row
		// of the mob one, so a pipeline either has both halves or neither. The two hand tables are
		// built the same way, from the same rows.
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
	 * Opens and closes the first of the two windows this family is served in, which the caller
	 * brackets with the game's own two events: the opaque chunks are finished at the first and the
	 * opaque features are finished at the second.
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
			closeWindow();
		}
	}

	/**
	 * The same pair for the blending half, which the caller brackets with the game's next two: the
	 * opaque features are finished at the first and the translucent ones at the second.
	 * <p>
	 * It opens where the other closes and they are still two calls, because what stands between them
	 * is the whole of the deferred stage: the half opened here is drawn onto what that stage
	 * composed, and a single window would have let a blending row be drawn before it ran.
	 * <p>
	 * Closed before the layer is composed and not after, which is the one ordering this pair owes
	 * anybody: composing opens a render pass of its own, and the encoder allows one at a time.
	 */
	public static void translucentFeatures(boolean drawing) {
		translucentFeatures = drawing;
		if (!drawing) {
			closeWindow();
		}
	}

	/**
	 * Opens and closes the third window, the one the shadow map is filled in, which the caller
	 * brackets around its own walk of the world for the light.
	 * <p>
	 * A window of its own and not either of the other two widened, and the reason is what the other
	 * two are for: they say WHICH HALF of the picture is being drawn, and the map has no halves. Both
	 * of the game's submissions are drawn into it in one go, blending and writing alike, so a test
	 * that asked which half was open would refuse one of them.
	 * <p>
	 * It cannot overlap either of the others. The stage stands at the very end of the frame, after
	 * the level render has finished and both feature windows have been closed at their own events,
	 * which is also why the pass this opens can be a pass of ours: nothing of the game's is open.
	 */
	public static void shadowFeatures(boolean drawing) {
		shadowFeatures = drawing;
		if (!drawing) {
			closeWindow();
		}
	}

	/**
	 * Whether the window this piece is drawn in is the one that is open.
	 * <p>
	 * Asked of {@code Element.afterStage} and not of the blend, which is the same answer for every row
	 * whose pipeline decides its half and the right one for the glint, whose pipeline does not: a
	 * glint blends and is executed in whichever window its carrier was submitted into.
	 */
	private static boolean inWindow(Element element) {
		if (element.shadow()) {
			return shadowFeatures;
		}

		return element.afterStage() ? translucentFeatures : opaqueFeatures;
	}

	/**
	 * Whether this draw really lands on the game's main target, asked of what its output target
	 * RESOLVES to rather than of which target object it names.
	 * <p>
	 * <strong>The difference is not pedantry and it cost this family two rows.</strong> Two of the
	 * four output targets exist only while the game's improved transparency is on, and
	 * {@code OutputTarget.getRenderTarget} resolves an absent one to the main target
	 * ({@code rendertype/OutputTarget.java:24-27}); the render type keeps naming it either way. Read
	 * by identity, {@code ITEM_TRANSLUCENT} and {@code ENTITY_TRANSLUCENT_CULL} were refused on every
	 * machine that has improved transparency off, which is the default, although the game was drawing
	 * them onto the very target this engine had open. What that left to the game's own shader is not
	 * a corner: every experience orb ({@code entity/ExperienceOrbRenderer.java:21}), every translucent
	 * item sheet ({@code Sheets.java:39,41}) and the translucent type of every living entity
	 * ({@code entity/LivingEntityRenderer.java:130}).
	 * <p>
	 * Iris keys on neither, because it never meets the question: it turns improved transparency off
	 * as soon as shaders are enabled ({@code mixin/fabulous/MixinDisableFabulousGraphics.java:37-40})
	 * and then serves both pipelines through {@code getTranslucent}
	 * ({@code pipeline/IrisPipelines.java:35,36}).
	 * <p>
	 * <strong>The question is shared with the opaque half and the answer moves nothing there</strong>,
	 * which was measured rather than assumed: of the nine render types of 26.2 that name a target
	 * other than the main one ({@code rendertype/RenderTypes.java:25,141,167,260,324,345,352,359,397}),
	 * none is built on a pipeline of the opaque table, so every opaque row named the main target
	 * before and resolves to it now.
	 */
	@SuppressWarnings("ReferenceEquality")
	private static boolean onMainTarget(PreparedRenderType prepared) {
		Minecraft minecraft = Minecraft.getInstance();
		RenderTarget main = minecraft == null ? null : minecraft.gameRenderer.mainRenderTarget();

		return main != null && prepared.outputTarget().getRenderTarget() == main;
	}

	/** What closing either window costs, which is the same two things. */
	private static void closeWindow() {
		// The three marks of the block entities go with it, and this is their frame boundary. They
		// are raised and lowered around calls rather than switched, so an unbalanced one is a mob
		// lit as a chest, and it would otherwise last the rest of the session. Dropping them at the
		// first of the two closings costs the second nothing: a submission is marked while the level
		// is walked, which is over before either window opens, and the mark a draw is read by is set
		// again one line before every draw.
		BlockEntityGeometry.clear();

		// Closing the window closes the pass, and that is not the same safety net as the one at the
		// end of a group. What the game does next is not the same for the two closings and both
		// refuse an open pass: after the first it copies a depth between targets, which the encoder
		// refuses outright; after the second it composes the feature layer, draws the outlines and
		// then the translucent chunk group (LevelRenderer.java:456-464,470-475), each of which opens
		// a pass of its own where only one may be open. A pass that only the frame boundary closed
		// would already have cost the frame by then, either way.
		endGroup();
	}

	/**
	 * Records one draw of a feature group with the pack's own program, or answers no and leaves it to
	 * the game.
	 * <p>
	 * No is the ordinary answer and covers everything from text to particles: the table holds sixteen
	 * pipelines and the game has a hundred. What matters about a no is that it closes the pass this
	 * was recording into, since the caller is about to open one of its own for the same draw and the
	 * two would overlap.
	 *
	 * @return whether this engine drew it, in which case the caller must not
	 */
	public static boolean draw(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info) {
		if (served(prepared, info)) {
			return true;
		}

		// INSIDE THE LIGHT'S WALK THERE IS NO SUCH THING AS HANDING A DRAW BACK.
		// Every no above ends with the caller drawing it itself, on the target its render type names,
		// and that target at the end of a frame is the finished picture: the caster would be painted
		// across the image the player is looking at, once per frame, for as long as the reason lasts.
		// So a no becomes a drop here, whatever the reason was and not only for a pipeline the table
		// has no row for. What it costs is written in ShadowGeometry: that caster casts no shadow,
		// which is a hole in the map rather than a mark on the screen.
		if (shadowFeatures) {
			EntityDraw draw = PackChain.entities();
			if (draw != null) {
				draw.dropped(prepared.pipeline());
			}

			return true;
		}

		return false;
	}

	/**
	 * The answer before the light's walk has its say: whether this engine really recorded the draw.
	 *
	 * @return whether it was drawn, false meaning nobody drew it yet
	 */
	@SuppressWarnings("ReferenceEquality")
	private static boolean served(PreparedRenderType prepared, StagedVertexBuffer.ExecuteInfo info) {
		EntityDraw draw = PackChain.entities();
		if (draw == null) {
			return false;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		Element element = element(prepared.pipeline());
		// Two families and two conditions, because they are served at different moments. The
		// entities live inside the two windows the level's features are drawn in; the hand is
		// submitted by this engine itself, twice, and both of those moments fall outside the
		// windows. Each family is guarded by its own switch and neither borrows the other's.
		boolean inMoment = HandDraw.drawing() ? HandDraw.wanted()
				: wanted && element != null && inWindow(element);
		if (!inMoment || device == null || element == null) {
			draw.end();
			if (wanted && element == null && translucentFeatures) {
				draw.withheld(prepared.pipeline());
			}

			return false;
		}

		// Asked of the picture's rows alone. A shadow row is not drawn into the target the game named
		// at all: its pass carries the map, so which of the four the submission was addressed to
		// decides nothing here, and refusing on it would drop a caster out of the map for a reason
		// that belongs to a picture this draw never touches.
		if (!element.shadow() && !onMainTarget(prepared)) {
			draw.end();

			// The FAMILY is in the key beside the target and the piece is not, because two families now
			// name one target: the glint's translucent render type is addressed to ITEM_ENTITY_TARGET
			// like six of the entity ones, so a key on the target alone would let whichever came first
			// speak for both and the log would never say the glint went back too. Keying on the piece
			// instead would say it once per row, which is six lines for the one thing that happened.
			return draw.refuse(element, "elsewhere:" + (element.glint() ? "glint" : "entity") + ":"
					+ prepared.outputTarget(), true, "the game sends it to "
					+ prepared.outputTarget() + ", which it composes itself afterwards, and the pack's "
					+ "colour targets cannot be attached beside a picture this engine has not got. It "
					+ "is the game's improved transparency that allocates those targets, and Iris "
					+ "never meets this: it turns improved transparency OFF as soon as shaders are "
					+ "enabled (mixin/fabulous/MixinDisableFabulousGraphics.java:37-40), which this "
					+ "engine does not do. Turning improved transparency off gives it back");
		}

		try {
			return draw.record(device, element, prepared, info);
		} catch (RuntimeException e) {
			// Said before the pass is closed and not after: closing one the failure left in a bad
			// state can throw in its turn, and the second throw would carry away the only line that
			// says what went wrong first.
			//
			// All three go down together, and that is not caution: they come in by one door with
			// one set of tables, so whatever this door failed at will fail again on the next draw of
			// either. Leaving the hand on would keep the same throw coming, once a frame, with the
			// line that explains it printed only the first time.
			wanted = false;
			HandDraw.wanted(false);
			Vitrail.logger().error("Vitrail stopped drawing the entities, the hand and the glint after "
							+ "an error",
					e);
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
			return refuse(element, "missing:" + element.element(), true,
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

		// The game's own transforms, for the same reason the image above is set again per draw and
		// with a sharper one: what a pack reads as gl_TextureMatrix[0] is the matrix its render type
		// was PREPARED with, and two breezes on screen carry two of them inside one run. Bound from
		// the slice rather than rebuilt, which is what Iris does as well: it declares the same block
		// (transform/transformer/VanillaTransformer.java:52-57) and registers the game's buffer under
		// this very name (pipeline/programs/ExtendedShader.java:107).
		if (program.readsGameTransforms()) {
			this.open.setUniform(LegacyGlsl.GAME_TRANSFORMS, prepared.dynamicTransforms());
		}
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

		// NEITHER OF THESE inside the light's walk, and TerrainDraw already guards the same pair for
		// the same reason, in the same words. The walk stands after the
		// chain has closed the frame, so both per frame guards are down again: opening here advances
		// the value store a SECOND time, which turns every gbufferPrevious* of the next frame
		// into the current one, and clears the pack's colour targets over what the chain has just
		// written into them. Neither call has anything to give a caster: it is drawn into the map,
		// which the stage ensured and emptied before any of this, and it reads no colour target.
		if (!element.shadow()) {
			this.owner.beginFrame();
			if (!this.owner.openTargets(device)) {
				return refuse(element, "targets", "the colour targets could not be opened this frame");
			}
		}

		if (!shown()) {
			return refuse(element, "warming", "the chain is still compiling, so nothing written to the pack's targets "
					+ "would reach the screen yet");
		}

		// The matrix belongs to the RUN and not to the draw, which is what makes it settleable here:
		// what varies with the draw is read out of the game's own block instead, Element.modelView
		// saying what is left for this one. Null for everything but the hand, and written into the
		// block a few lines down. The volume goes in beside it and comes from the same place, the
		// pass being drawn; it is asked of HandDraw rather than carried on the row, so that the two
		// cannot be two answers: it holds nothing between its passes.
		RenderPipeline pipeline = program.prepare(device, element.modelView(), HandDraw.volume());
		if (pipeline == null) {
			// Lasting, and it took a review to see it: a program that would not compile, or that
			// declares a storage block, latches broken in GeometryProgram and never unlatches, so
			// this answers null for the rest of the load rather than for this frame.
			// Keyed by PROGRAM NAME and not by reason alone, unlike the frame's three: the two halves
			// of the family are usually two files, so one key would name whichever failed first and
			// leave the other silent. Not by piece either, or a file that will not compile would say
			// so once per piece it serves, which is ten lines for one fault.
			//
			// The glint is the exception and takes the piece, because its four are ONE name: they are
			// four compiled modules against four different sets of attachments, so the argument above
			// runs the other way and one key would hide three failures behind the first.
			return refuse(element, "prepare:" + (element.glint() ? element.element() : element.program()),
					true,
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
		if (descriptor == null && element.shadow()) {
			// The two arguments above were ignored: a shadow program builds its descriptor out of the
			// map and never out of the target the game named. Null here means the map is not there,
			// which the stage should already have refused, so this is the second door rather than the
			// first and it is worth its own sentence: with the map gone, a pass opened on the game's
			// own attachment would paint the picture with a program written for a depth buffer.
			return refuse(element, "no shadow map",
					"the shadow map has no image, so there is nothing for the caster to be drawn into");
		}

		if (descriptor == null && !program.plain()) {
			// The colour targets are not there yet, which is the first frame or two and the frames
			// after a resize. A plain pass would carry one attachment against a pipeline holding a
			// state per target the pack asked for, and setPipeline refuses that by name.
			return refuse(element, "unallocated", "one of the pack's colour targets has no image yet");
		}

		CommandEncoder encoder = device.createCommandEncoder();
		this.open = descriptor == null
				? encoder.createRenderPass(() -> LABEL, colour, Optional.empty(), depth,
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
	private boolean refuse(Element element, String reason, boolean lasting, String why) {
		if (this.refused.add(reason)) {
			// What happens next is not the same in the light's walk, and saying the wrong one is
			// worse than saying nothing: a reader told the game took over goes looking for geometry
			// lit by the wrong engine, where what is really there is geometry missing from the map.
			Vitrail.logger().warn("A draw of the {} {} because {}. {}", element.family(),
					element.shadow() ? "was dropped out of the shadow map"
							: "went back to the game's own shader",
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
	private boolean refuse(Element element, String reason, String why) {
		return refuse(element, reason, false, why);
	}

	/**
	 * Says once, per pipeline, that a caster was dropped out of the shadow map rather than handed
	 * back to the game.
	 * <p>
	 * Worth a line of its own and not folded into {@link #refuse}: every other no in this class ends
	 * with the game drawing the thing, so what the reader sees is geometry lit by the wrong engine.
	 * This one ends with the geometry not drawn at all, and a missing shadow is looked for in a
	 * different place from a wrongly lit mob.
	 */
	@SuppressWarnings("ReferenceEquality")
	private void dropped(RenderPipeline pipeline) {
		if (!this.refused.add("shadow:" + pipeline.getLocation())) {
			return;
		}

		// The deliberate one is named apart from the rest, because reporting a parity choice as a
		// fault is how a reader is sent hunting something that is working. Iris assigns
		// ENTITY_SHADOW in its main table and nowhere in its shadow one, so a mob's ground oval
		// keeps the game's shader there. The walk also submits name tags, text and other geometry no
		// shadow table anywhere has a row for, and those are the same silence the camera's table
		// gives them.
		if (pipeline == RenderPipelines.ENTITY_SHADOW) {
			Vitrail.logger().info("The ground oval under a mob is left out of the shadow map, which "
					+ "is what Iris does: it has no shadow row for that pipeline either, and the "
					+ "pipeline writes no depth, so a row for it would put nothing into the depth a "
					+ "pack reads its shadows from");

			return;
		}

		// The second deliberate one, and it is nearly a match rather than the gap it first reads as.
		// Iris carries a shadow row for the glint (pipeline/IrisPipelines.java:111) and then cancels
		// the foil while the map is filled (mixin/ItemStackMixin.java:14-17, whose comment is that a
		// glint is not visible in a shadow anyway). That cancel is on ItemStack.hasFoil, and it
		// reaches further than the name suggests: Iris's light walks the world a second time and
		// EXTRACTS and submits every render state inside the pass it brackets
		// (shadows/ShadowRenderer.java:396 and :638 around :684 and :713), so the reads that decide a
		// foil are inside it and answered no. What escapes is a foil decided somewhere other than the
		// stack, which is the thrown trident, reading its own entity data. So the row Iris carries is
		// reached by almost nothing, and what this engine leaves out is what that row would have
		// drawn.
		if (pipeline == RenderPipelines.GLINT) {
			Vitrail.logger().info("An enchantment's glint is left out of the shadow map. Iris has a "
					+ "row for it and cancels the foil while the map is filled, so almost nothing "
					+ "reaches that row there either; what this leaves out is the little it would "
					+ "have drawn, and what it costs is a foil quad missing from a map the item under "
					+ "it already fills, so a shadow keeps its shape and loses a tint");

			return;
		}

		// The reason is NOT asserted here, and that took a review to see: every no from served ends
		// up on this line, a missing device and a refused program among them, and each of those has
		// already said what it was on a line of its own. Naming the table as the cause would send a
		// reader looking for a missing row when the fault is in the load.
		Vitrail.logger().warn("What the game draws with {} casts no shadow this frame, for whichever "
				+ "reason is given above, or because this engine has no shadow row for it. It is "
				+ "dropped rather than handed back: inside the light's walk the game would open its "
				+ "own pass on the target its render type names, which at that point in the frame "
				+ "carries the finished picture", pipeline.getLocation());
	}

	/**
	 * Says once, when one of the two pipelines {@link #WITHHELD} names is really drawn, that this
	 * engine left it to the game and why.
	 * <p>
	 * Here and not at the load, because the load cannot know: a breeze and a guardian beam are things
	 * a world may or may not contain, and a line said at every load about geometry nobody will meet
	 * is a line a reader learns to skip. The rest of the table is silent on a miss for the opposite
	 * reason, the game having a hundred pipelines this family was never asked about; these two were
	 * asked about and answered no.
	 */
	private void withheld(RenderPipeline pipeline) {
		String reason = WITHHELD.get(pipeline);
		if (reason != null && this.refused.add("withheld:" + pipeline.getLocation())) {
			Vitrail.logger().warn("The game keeps its own shader for {}, which this engine leaves to "
					+ "it: {}. What it costs is that this geometry is lit by the game inside the "
					+ "window the pack is drawing, so it carries none of the pack's material and its "
					+ "colour makes one more trip through eight bits. It holds for as long as this "
					+ "pack is loaded", pipeline.getLocation(), reason);
		}
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
	 * Reads the pack for every piece at once, at the first entity or hand the game draws, and settles
	 * where the outputs of each of them go.
	 * <p>
	 * All of them and not the one being asked for, for the reason the sky reads all six: the moment
	 * a piece is first drawn is the world's to choose, and some of them wait a long time. Nothing
	 * asks for the armour decal until somebody wears armour that carries one, and read one at a time
	 * the pack would be opened, expanded and translated inside that frame, on the render thread and
	 * in the middle of the world.
	 * <p>
	 * <strong>Only what a switch asked for is read.</strong> Each of the two families here is one
	 * compiled module per row of the table, so reading a family nothing will draw is a pack expansion
	 * and ten translations spent on programs no draw will ever select. It also keeps the log honest:
	 * a line saying a pack serves nothing for the hand, printed for somebody who never turned the
	 * hand on, reads as a fault of the pack.
	 */
	private void read() {
		this.read = true;

		List<Element> served = ELEMENTS.values().stream().filter(EntityDraw::decodable).toList();

		// Asked once for the four pieces that share the glint's one pipeline, and only where a switch
		// asked for one of them: four answers would be four lines about one format, and an answer
		// nobody asked for would blame a pack for a family that is off.
		boolean glint = (wanted || HandDraw.wanted()) && decodable(GLINT_EARLY);

		// The block entity half of each served piece, and the two hand halves, added here rather than
		// filtered again: all four tables share the pipelines of the first, so a format this engine
		// cannot decode has already been reported once and saying it four times would read as four
		// defects.
		//
		// Four GROUPS and not one list, because what stands or falls together is not the same in
		// each. The entity names walk one picture and hold together WITHIN a half, and the two
		// halves settle apart, which is the particles' position; the hand's two passes are two
		// moments of the frame that share no target, so one of them can be served while the other
		// is not.
		List<List<Element>> groups = new ArrayList<>();
		if (wanted) {
			List<Element> family = Stream.concat(served.stream(),
							served.stream().map(element -> BLOCK_ELEMENTS.get(element.pipeline())))
					.toList();
			groups.add(family.stream().filter(element -> !element.afterStage()).toList());
			groups.add(family.stream().filter(Element::afterStage).toList());
		}

		if (HandDraw.wanted()) {
			groups.add(twinsOf(served, HAND_ELEMENTS));
			groups.add(twinsOf(served, HAND_WATER_ELEMENTS));
		}

		// A group of its own for each glint piece, and NOT a row added to the four above it.
		//
		// A group stands or falls together, and the reason that holds inside the entity family is that
		// those programs write into one picture: a piece whose answer could not be settled is drawn by
		// the game, and a chest lit by the game beside a mob lit by the pack is a disagreement about
		// what lights them. A glint is not beside anything. It is drawn ON TOP of a piece, so a glint
		// left to the game over an item the pack drew is what every frame looks like today, and taking
		// the mobs down with it would trade one family for four.
		if (wanted && glint) {
			groups.add(List.of(GLINT_EARLY));
			groups.add(List.of(GLINT_LATE));
		}

		if (HandDraw.wanted() && glint) {
			groups.add(List.of(GLINT_HAND));
			groups.add(List.of(GLINT_HAND_WATER));
		}

		// One group for the whole shadow table, where the picture takes two: the map has no deferred
		// stage to stand either side of and one program name for the lot, so there is no line inside
		// it for a half to fall on.
		//
		// Asked for only where the pack draws something through the feature renderers at all. A pack
		// that keeps the entities and the block entities out of its map wants no shadow_entities read,
		// and reading it anyway would be a translation and a compilation spent on a program no draw
		// can ever select. The ground shadow is not in the table, so this really is empty for such a
		// pack rather than nearly empty.
		if (wanted && TerrainDraw.shadowsAsked() && this.values.shadowCasters().anyFeature()) {
			groups.add(twinsOf(served, SHADOW_ELEMENTS));
		}

		List<Element> asked = groups.stream().flatMap(List::stream).toList();
		if (asked.isEmpty()) {
			return;
		}

		try {
			Map<String, PackProgram.Loaded> loaded = PackProgram.loadGeometry(this.packPath, this.place,
					asked.stream().map(Element::asked).toList(), this.chosen, this.profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for the entities, the hand or the "
						+ "glint, so the game keeps its own shader for them",
						this.packPath.getFileName(), this.place.isEmpty() ? "its root" : this.place);

				return;
			}

			groups.forEach(group -> keep(group, loaded));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the entity programs of "
					+ this.packPath.getFileName() + ", so the game keeps its own shader for them", e);
		}
	}

	/**
	 * Whether the pipeline this piece names really binds the format the piece claims, which is the one
	 * assumption of this door that would otherwise fail in silence.
	 * <p>
	 * The vertex head declares the elements of that format BY NAME, and an element the stage does not
	 * declare shifts the location of every one after it without a word being said: the picture stays a
	 * picture and reads its texture coordinates out of the light map. Two formats come in by this door
	 * now, the entity mesh and the glint's two elements, so the claim is the piece's and the check is
	 * against the game's own binding.
	 */
	private static boolean decodable(Element element) {
		VertexFormat format = element.pipeline().getVertexFormatBinding(0);
		if (element.format().equals(format)) {
			return true;
		}

		Vitrail.logger().warn("The game draws the {} with {} and this engine decodes {} for it, so the "
				+ "game keeps its own shader for it", element.element(), format, element.format());

		return false;
	}

	/**
	 * One table's row for each served mob row, for the tables derived from the mob one.
	 * <p>
	 * A missing row is dropped rather than carried as a null, and only one table has any: the shadow
	 * one leaves the ground shadow out, because Iris leaves it out. The two hand tables and the block
	 * one are derived row for row and cannot lose one here.
	 */
	private static List<Element> twinsOf(List<Element> served, Map<RenderPipeline, Element> table) {
		return served.stream()
				.map(element -> table.get(element.pipeline()))
				.filter(Objects::nonNull)
				.toList();
	}

	/**
	 * Builds the programs of one group, all of them or none of them, and settles where each serving
	 * file writes.
	 * <p>
	 * Asked once per serving FILE and per HALF, and not once per piece: a group is a few program
	 * names at most, so it is a few files at most, and the plan would otherwise answer for each of
	 * them ten times over. The half is half the key and not a detail, the two sides of the deferred
	 * stage reading and writing opposite sides of every target; a group is drawn wholly on one side,
	 * and the hand's two passes are two groups for exactly that reason, since they really can
	 * resolve to the same file, {@code gbuffers_hand_water} falling back on {@code gbuffers_hand}.
	 * <p>
	 * All of them or none of them, which is what the return in the middle is, and it holds across
	 * the names of ONE group rather than per name or across groups. These programs write into one
	 * picture, so a piece whose answer could not be settled would be drawn by the game into it, and
	 * a chest and the mob beside it would disagree about what lights them. Across groups it does NOT
	 * hold, for the reason the particles give: they share no target and no pass, so taking one down
	 * with another would be a choice nothing forced.
	 */
	private void keep(List<Element> group, Map<String, PackProgram.Loaded> loaded) {
		// The side is the pipeline's answer for the entity rows and the row's own flag for the
		// hand's: a hand pass is drawn wholly on one side of the deferred stage whatever its rows'
		// pipelines blend, the solid one before it and the water one after, so a hand row made from
		// a blending mob pipeline still belongs to the side its PASS is drawn on.
		Map<Half, List<ChainPlan.Attachment>> byFile = new LinkedHashMap<>();
		for (Element element : group) {
			PackProgram.Loaded one = loaded.get(element.element());
			if (one == null) {
				continue;
			}

			Half half = new Half(servedBy(one), element.afterStage(), element.shadow());
			if (byFile.containsKey(half)) {
				continue;
			}

			List<ChainPlan.Attachment> writes = writes(half);
			if (writes == null) {
				return;
			}

			byFile.put(half, writes);
		}

		group.stream()
				.filter(element -> loaded.containsKey(element.element()))
				.forEach(element -> this.programs.put(element.element(), EntityProgram.of(
						loaded.get(element.element()), element, this.values, this.load,
						byFile.get(new Half(servedBy(loaded.get(element.element())),
								element.afterStage(), element.shadow())),
						this.chainTargets, this.targets, this.chainRuns)));
	}

	/**
	 * One file serving one half of the family, which is what the plan has to be asked by: the same
	 * file may serve both halves, and the two answers are on opposite sides of every target. The
	 * hand's water pass is a half drawn after the stage like any other here, {@code gbuffers_hand_water} falling
	 * back on {@code gbuffers_hand} with the two answers apart.
	 *
	 * @param afterStage which side of the deferred stage the PASS this half belongs to is drawn on,
	 *               which is {@code Element.afterStage} and not the pipeline's blend. Named for the
	 *               question rather than for the answer one family gives it, the two parting company
	 *               on every hand row whose blend disagrees with its pass
	 * @param shadow whether this half fills the shadow map rather than the picture. Part of the key
	 *               and not a detail: a shadow half is asked nothing of the plan and owes nothing to
	 *               the scene seed, and without it here one file that serves both would answer for
	 *               the map with the picture's attachments
	 */
	private record Half(String servedBy, boolean afterStage, boolean shadow) {
	}

	/**
	 * Where the outputs of one file that serves a half belong, in draw buffer order and each on the
	 * side the schedule gives it, or null when this place cannot answer for it.
	 * <p>
	 * <strong>Empty is not a refusal, and it no longer means what it used to.</strong> It makes the
	 * piece write ONE output, to the game's target, and it covers three quite different things. A key
	 * the plan never walked, which is a name missing from its {@code NAMED_PROGRAMS}. A program the
	 * plan holds no draw buffers for at all, which since the inference means a program drawn from the
	 * light or a file the expander could not read ({@code ChainPlan.geometryOf}). And a
	 * walk the plan REFUSED, which is five cases of its own: more than eight draw buffers, the same
	 * target named twice, no schedule step, a target nothing allocates, or two sizes in one pass -
	 * the one list {@code ChainPlan.attachmentsOf} carries.
	 * <p>
	 * The last of those three is the one worth naming, because it is a fault dressed as a default: a
	 * pack whose entity program the plan refused is served with one output and loses its normals and
	 * its specular map without this file saying anything, the plan having put its reason in the notes
	 * rather than here. <strong>What is NOT in that list any more is the ordinary pack.</strong> A
	 * pack that simply declares no directive arrives with colortex0, as it does under Iris, so it
	 * never reaches this branch at all: Body Camera's {@code world1} and {@code world-1} are that
	 * case, their entities falling back on a {@code gbuffers_textured} that declares nothing, and
	 * the colortex0 inferred for it is the very target their scene seed is painted into, so the last
	 * refusal below does not fire there either.
	 * <p>
	 * An empty answer on the OPAQUE half still reaches the pack's picture, through the scene seed.
	 * On the BLENDING half it is {@link FeatureLayer} that carries it, the game's colour override
	 * still being posed over that whole window, and the layer is composed onto the first draw buffer
	 * of the translucent chunk pass.
	 * <p>
	 * Null is a refusal. Both halves refuse a place whose targets are not the size of the screen, one
	 * render pass having one render area. The opaque half refuses two more, and they are both about
	 * the seed: switched off it takes the only road that half's first output has, and a first draw
	 * buffer that is not the one it paints would have that output carried into a target the pack did
	 * not ask for, which is a pack's albedo read as its normals.
	 * <p>
	 * <strong>The half drawn after the stage needs no seed and is not a relaxation of the rule but
	 * the other side of it.</strong> It is drawn onto a colour target the chain has already
	 * composed, so {@code GeometryProgram} gives it its own draw buffer nought: the output goes to
	 * the pack outright rather than making the trip through the game's eight bit target. That is the
	 * position the world's own water is in, and the translucent particles with it. The side of the
	 * stage and not the blend is what earns it, and the hand is where the two part company: its
	 * solid pass blends and is drawn one line before the seed, so it keeps draw buffer nought on the
	 * game's target like the opaque halves.
	 */
	private List<ChainPlan.Attachment> writes(Half half) {
		// A shadow half writes the map and nothing else. GeometryProgram gives a shadow pass no slots
		// at all, so the list is never read for one, and asking the plan for it would ask about a
		// name the plan's geometry table has no row for. Answered empty rather than left to fall
		// through, because the two paragraphs below would both give it the wrong answer: the seed has
		// nothing to do with a draw buffer nought that is shadowcolor0, and refusing there would take
		// every moving caster out of the map the moment somebody wrote seed=off.
		if (half.shadow()) {
			return List.of();
		}

		// Before the plan is even asked, because it is not the plan's to answer: the scene seed is
		// the ONLY road the opaque half's first output has into the pack's picture, so a run with the
		// seed switched off would write every other draw buffer and no albedo at all. What that
		// paints is a mob shaped hole of normals and specular over the terrain's own colours, which
		// is exactly the plausible and wrong picture the switch exists to rule out.
		// Only where the chain runs, and the condition is not a refinement. With chain=off nothing
		// of the pack reaches the screen through a final, so draw buffer nought stays on the game's
		// own target and is the picture: the seed has nothing to carry and is never even drawn.
		// Refusing the family there would take away the one configuration that tells a wrong
		// gbuffer from a wrong composite, which is what these switches exist for.
		if (!half.afterStage() && this.chainRuns && !this.seeded) {
			Vitrail.logger().info("The scene seed is off, and it is the only way the first output of "
					+ "an opaque piece reaches the pack's picture, so the game keeps its own shader "
					+ "for what {} serves: served, it would write every other draw buffer and no "
					+ "colour", half.servedBy());

			return null;
		}

		Optional<ChainPlan.Pass> geometry = this.plan.geometryOf(half.servedBy(), half.afterStage());
		if (geometry.isEmpty()) {
			return List.of();
		}

		ChainPlan.Pass pass = geometry.get();
		if (!pass.size().equals(TargetSize.ofScreen())) {
			Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so they cannot share "
					+ "a pass with the game's own target and the game keeps its own shader for that "
					+ "half of what it serves", half.servedBy());

			return null;
		}

		if (half.afterStage()) {
			return pass.attachments();
		}

		ChainPlan.Attachment first = pass.attachments().get(0);
		Optional<ChainPlan.Seed> seed = this.plan.seed();
		if (seed.isEmpty() || seed.get().target() != first.target()
				|| seed.get().side() != first.side()) {
			Vitrail.logger().warn("{} writes {} first and the scene seed paints {}, so the first output "
					+ "of an opaque piece would be carried into a target the pack did not ask for: the "
					+ "game keeps its own shader for that half", half.servedBy(),
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
		translucentFeatures(false);
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
