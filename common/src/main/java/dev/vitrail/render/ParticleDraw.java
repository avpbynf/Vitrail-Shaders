package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The door the game's quad particles come in by, and the one place a pack's particle programs are
 * read.
 * <p>
 * <strong>It is a door of its own and the entities' mixin cannot reach it.</strong> Twelve feature
 * renderers of the game draw through {@code RenderTypeFeatureRenderer.executeGroup} and inherit it
 * without redefining it; the particles implement the interface directly and have an
 * {@code executeGroup} of their own, which opens a render pass, walks the layers of the group and
 * sets a pipeline and an atlas for each. So the shape here is the sky's and the weather's, a pass
 * replaced where it is opened and a pipeline swapped as it is set on that pass, and the draw's atlas
 * is handed over per draw as the entities' skin is.
 * <p>
 * <strong>The two halves are two programs on opposite sides of the frame, and this is the one family
 * that straddles the deferred stage.</strong> The game submits every group twice, once solid and once
 * after the terrain ({@code SubmitNodeCollection.submitQuadParticleGroup}), and those two land far
 * apart in {@code LevelRenderer.addMainPass}: the opaque half among the solid features, before the
 * event this engine runs its deferred stage at, and the translucent half after the translucent chunk
 * group, which is after the world's own water. Everything that follows from that - which half of each
 * target either one reads, and whether its first output goes to the pack or to the game - follows
 * from that one fact.
 * <p>
 * <strong>A half the pack serves nothing for is left to the game on its own</strong>, rather than
 * taking the other half down with it: the halves share no target and no pass, so nothing is made
 * inconsistent by serving one.
 * <p>
 * <strong>Only one of the two can ever be the missing one, and it is the opaque half.</strong> The
 * chains are nested rather than separate, {@code gbuffers_particles_translucent} falling back through
 * {@code gbuffers_particles} and that through {@code gbuffers_textured_lit}, so whatever answers for
 * the opaque half answers for the translucent one as well. The reverse is reachable: a pack that
 * ships the translucent file and nothing at all under it resolves that name and not the other.
 * <p>
 * <strong>{@code particles.ordering} is read and says nothing on the corpus.</strong> The five packs
 * of the eight that write it all write {@code mixed}, which names the placement above exactly. A pack
 * asking for either of the other two would be asking for its particles to be moved across the
 * deferred stage, which no engine does today, Iris parsing the word and reaching for it nowhere; the
 * difference is that here it is said in the log. That line comes out of the reading, so it needs a
 * pack this place serves at least one half for, and {@code particles=off} not written, which is the
 * same condition every other line of this family answers under.
 */
public final class ParticleDraw {

	/** Off unless {@code options.txt} asks otherwise, and read again at every load. */
	private static volatile boolean wanted;

	/**
	 * What each half discards at when the pack says nothing, which is the tenth Iris gives both of its
	 * particle keys ({@code pipeline/programs/ShaderKey.java:58-59}). The game's own particle pipeline
	 * carries no cutout define, so there is nothing of its to inherit.
	 */
	private static final AlphaTest CUTOUT = AlphaTest.ONE_TENTH;

	/**
	 * One half of the game's quad particles: which pipeline draws it, which program of the pack
	 * answers for it, and where in the frame it falls.
	 *
	 * @param pipeline      the game's own pipeline, which is where the blend, the depth window, the
	 *                      culling and the topology are read from, and which the draw checks itself
	 *                      against before a pipeline of ours is bound
	 * @param element       one word for the log and for the shader identifier
	 * @param program       the bare name the pack is asked for
	 * @param afterDeferred whether this half is drawn after the deferred stage, which decides the
	 *                      half of every target it reads and writes
	 */
	record Element(RenderPipeline pipeline, String element, String program, boolean afterDeferred) {

		/**
		 * What the pack has to be read for to serve this half, in terms the translation knows.
		 * <p>
		 * No coverage mask on either half, which {@link ParticleProgram} sets out where it decides
		 * the same thing for the pass.
		 */
		private PackProgram.GeometryElement asked() {
			return new PackProgram.GeometryElement(this.element, this.program, CUTOUT,
					VertexInputs.PARTICLE, false);
		}

		/**
		 * What a pack is told it is drawing, which is {@code PARTICLES} for both halves. Iris poses it
		 * around the whole of the particle renderer's own {@code render}
		 * ({@code mixin/MixinParticleEngine.java:24-30}) and takes no notice of which half is running.
		 */
		RenderStage stage() {
			return RenderStage.PARTICLES;
		}
	}

	/**
	 * Keyed by the boolean the game itself keys them by, which is the submit's own translucency.
	 * Ordered, so that the two lines the load may write about them come out in the order they are
	 * drawn rather than in one the runtime picks afresh on every start.
	 */
	private static final Map<Boolean, Element> ELEMENTS = new LinkedHashMap<>();

	static {
		ELEMENTS.put(false, new Element(RenderPipelines.OPAQUE_PARTICLE, "particles",
				"gbuffers_particles", false));
		ELEMENTS.put(true, new Element(RenderPipelines.TRANSLUCENT_PARTICLE, "particles_translucent",
				"gbuffers_particles_translucent", true));
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

	/** Whether the game's finished frame is painted in under the chain, which the opaque half rides
	 * on and the translucent one does not. */
	private final boolean seeded;
	private final ColorTargets targets;

	/** One program per half the pack serves. Empty until the pack has been read. */
	private final Map<String, ParticleProgram> programs = new LinkedHashMap<>();

	/** Whether the pack has been read for its particles. A reading that served nothing is still one. */
	private volatile boolean read;

	/**
	 * The reasons this engine has already handed a group back, one line each and not one a frame.
	 */
	private final Set<String> refused = new LinkedHashSet<>();

	/** The foreign pipelines already written about, so that line comes out once per caller and
	 * costs nothing on the draws after it. */
	private final Set<RenderPipeline> foreign = new LinkedHashSet<>();

	/** The name the game binds the primary texture under, and the one every atlas arrives as. */
	private static final String ATLAS_SAMPLER = "Sampler0";

	/** The program of the group being recorded, and the game pipeline it stands in for. */
	private ParticleProgram drawing;
	private RenderPipeline standsIn;
	private RenderPipeline bound;

	/** The pass the group's draws are recorded into, ours or the renderer's own. */
	private RenderPass pass;

	/**
	 * True while {@link #texture} is binding the pack's own samplers, which go through the very
	 * call it watches the atlas at. The names it binds are the pack's and the atlas name is the
	 * game's, but nothing enforces that split, and a pack naming a sampler the way the game does
	 * would re-enter it without end.
	 */
	private boolean binding;

	/**
	 * Whether the pipeline this engine last handed the renderer was its own. False for a layer that
	 * kept the game's, which is the one case where the pass draws with a shader the pack never
	 * wrote, and what {@link #texture} reads before it binds anything over it.
	 */
	private boolean handedOurs;

	/** The pass that program wants opened, worked out beside it. Null means the renderer's own. */
	private RenderPassDescriptor descriptor;

	ParticleDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
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

	/** Whether a pack's own particle programs take over the game's, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/**
	 * Whether this family is drawing, for the line of the log that names what the scene seed still
	 * carries across. {@code SkyDraw.wanted} says why that is not quite the option.
	 */
	static boolean wanted() {
		return wanted;
	}

	/**
	 * Reads the pack for this family, without compiling. One call is enough; a reading that served
	 * nothing is still one. The chain asks during its warm-up so shaderc does not land on the first
	 * draw.
	 */
	void prefetch() {
		prefetch(null);
	}

	/**
	 * The same through an opening the caller holds, which is how the load worker reads the six
	 * families: one opening, one plan of the place and one program tree shared between them,
	 * where each used to open the archive and rebuild all three for itself.
	 */
	void prefetch(OpenedPack shared) {
		if (!this.read && wanted()) {
			read(shared);
		}
	}

	/**
	 * Everything that has to happen before the particle renderer opens its pass, for one half of the
	 * frame's particles.
	 *
	 * @param translucent which half the group about to be drawn is, taken from the submits the
	 *                    renderer was handed rather than worked out here: that is the field the game
	 *                    itself reads to decide which layers go in and which target they go to
	 * @param colour      the colour view the renderer was going to draw into
	 * @param depth       the depth view it was going to use, kept as it is
	 * @return the pipeline to draw the group with, or null to leave the game's own alone
	 */
	public static RenderPipeline group(boolean translucent, GpuTextureView colour,
			GpuTextureView depth) {
		ParticleDraw draw = PackChain.particles();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (draw == null || !wanted || device == null) {
			return null;
		}

		try {
			return draw.prepare(device, ELEMENTS.get(translucent), colour, depth);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the particles after an error", e);

			return null;
		}
	}

	/**
	 * The render pass the group wants opened, or null to open the plain one the renderer was going to
	 * open.
	 * <p>
	 * Worked out by {@link #group} and only handed back here, so that the pass and the pipeline cannot
	 * be two answers. {@code WeatherDraw.descriptor} says what the case they could differ in is.
	 */
	public static RenderPassDescriptor descriptor() {
		ParticleDraw draw = PackChain.particles();

		return draw == null ? null : draw.descriptor;
	}

	/**
	 * Remembers the pass the group's draws are about to be recorded into, ours or the renderer's
	 * own.
	 * <p>
	 * It is the pass and not the call site that scopes the two hooks on {@link RenderPass}: the
	 * game's own draws go through {@code drawLayers}, but a mod may record draws of its own into
	 * the same pass from a handler of its own, which AsyncParticles' GPU particles do after
	 * {@code drawLayers} returns. A hook on the renderer's method sees none of those; a hook on
	 * the pass sees them all, and this field is what tells it which pass is the group's.
	 */
	public static void opened(RenderPass pass) {
		ParticleDraw draw = PackChain.particles();
		if (draw != null && draw.drawing != null) {
			draw.pass = pass;
		}
	}

	/**
	 * The pipeline one draw of the group is really recorded with, whoever asked for it.
	 * <p>
	 * <strong>A draw whose pipeline is neither of the two the table names keeps its own wherever it
	 * can</strong>, which is what Iris does with it: an unassigned pipeline answers a null key
	 * ({@code pipeline/IrisPipelines.java:224-231}) and its override then hands back nothing
	 * ({@code mixin/MixinShaderManager_Overrides.java:97-101}), leaving the game's shader in place.
	 * A draw like that is not a particle this engine was asked about, and drawing it with the pack's
	 * program would take its blend, its depth, its culling and its topology as well as its shader,
	 * every one of them read off the pipeline the table names rather than off the draw's own.
	 * <p>
	 * <strong>It cannot keep it once the pass is ours, and there it takes the pack's program over
	 * the caller's own vertex layout.</strong> The pass was opened before any draw was seen, and
	 * where the pack took draw buffers it carries a colour attachment for each; a pipeline
	 * declaring ONE colour state is refused by name in the middle of it. The program is recompiled
	 * over the layout the caller's mesh really has ({@code GeometryProgram.reshape}) rather than
	 * bound as built, which would read every vertex past the first at the wrong offset. That is
	 * where Iris lands for the reachable caller: AsyncParticles' GPU particles record draws into
	 * the group's pass after {@code drawLayers} returns, on a wider layout of their own, and it
	 * registers those pipelines against Iris' two particle programs
	 * ({@code GpuParticlePipelines.of} ends on {@code IrisApi.assignPipeline}), so Iris draws them
	 * with the pack's program too. What stays divergent is a pipeline Iris was never told about,
	 * which keeps the game's shader there and takes the pack's program here, the log saying so.
	 * Where the pack took none, the pass is the renderer's own single attachment one and the draw
	 * keeps everything.
	 * <p>
	 * <strong>The layer case is reachable and is not vanilla's.</strong>
	 * {@code SingleQuadParticle.Layer} is a public record and {@code getLayer} is overridable, so a
	 * layer may carry a pipeline of its own into a group whose translucency matches. Every layer of
	 * the game pairs its translucency with one of the two the table names, so nothing of the game
	 * reaches this.
	 *
	 * @param pass the pass the pipeline is being set on, which is what scopes this to the group
	 * @param game the pipeline the draw asked for
	 * @return the pipeline to bind instead, or the one asked for
	 */
	@SuppressWarnings("ReferenceEquality")
	public static RenderPipeline pipeline(RenderPass pass, RenderPipeline game) {
		ParticleDraw draw = PackChain.particles();
		if (draw == null || draw.drawing == null || draw.bound == null || pass != draw.pass) {
			return game;
		}

		draw.handedOurs = true;
		// Ours coming back around, re-set by a caller that kept a reference: nothing to swap.
		if (game == draw.bound) {
			return game;
		}

		if (draw.standsIn == game) {
			return draw.bound;
		}

		// The pack took no draw buffer here, so the pass is the renderer's own and the draw's
		// pipeline binds into it as it always did. Nothing is lost and nothing is said.
		if (draw.descriptor == null) {
			draw.handedOurs = false;

			return game;
		}

		// The binding read directly rather than through the array's length: the game hands the
		// builder's raw sixteen slots through, so the array is never short and its entries are
		// where the nulls live.
		GpuDevice device = RenderSystem.tryGetDevice();
		VertexFormat layout = game.getVertexFormatBinding(0);
		RenderPipeline reshaped = device == null || layout == null
				? null
				: draw.drawing.reshape(device, layout);
		if (draw.foreign.add(game)) {
			if (reshaped != null) {
				Vitrail.logger().warn("A particle draw asked for {}, which is neither of the two "
						+ "pipelines the game draws its own quad particles with, inside a group "
						+ "whose pass this engine had already opened over the pack's own colour "
						+ "targets, where a pipeline carrying one colour state is refused by name. "
						+ "It takes the pack's particle program over the caller's own vertex "
						+ "layout, which is where Iris lands for the callers that register such "
						+ "pipelines against its particle programs", game.getLocation());
			} else {
				Vitrail.logger().warn("A particle draw asked for {}, which is neither of the two "
						+ "pipelines the game draws its own quad particles with, inside a group "
						+ "whose pass this engine had already opened over the pack's own colour "
						+ "targets, where a pipeline carrying one colour state is refused by name. "
						+ "The pack's particle program could not be rebuilt over the caller's own "
						+ "vertex layout, so it is bound as built and reads that mesh through its "
						+ "own", game.getLocation());
			}
		}

		return reshaped == null ? draw.bound : reshaped;
	}

	/**
	 * The atlas a draw of the group was going to read, on its way past, and the pack's block and
	 * samplers bound over it.
	 * <p>
	 * One line before the draw that reads them, which is where each caller binds its own: the draws
	 * of one group come off three different atlases, so this is the draw's answer and not the pass's.
	 * Only the name the game binds every atlas under is answered, the group's pass also carrying a
	 * lightmap under another, and only outside this method's own binds, which go through the very
	 * call it is watching.
	 */
	public static void texture(RenderPass pass, String name, GpuTextureView view,
			GpuSampler sampler) {
		ParticleDraw draw = PackChain.particles();
		// What the pass really got decides, as it does for the weather and for the sky. A foreign
		// draw keeps its own pipeline wherever the pass allows it, which pipeline() answers just
		// above and this would otherwise ignore: binding the pack's block over the game's shader is
		// harmless in itself, the descriptor flush walking the layout of the pipeline really bound,
		// but it makes this engine announce a first draw for a program that drew nothing.
		if (draw == null || draw.drawing == null || draw.bound == null || !draw.handedOurs
				|| pass != draw.pass || draw.binding || !ATLAS_SAMPLER.equals(name)) {
			return;
		}

		draw.binding = true;
		try {
			draw.drawing.texture(view, sampler);
			draw.drawing.bind(pass);
		} finally {
			draw.binding = false;
		}
	}

	/**
	 * Forgets the group, at the way out of the method that drew it, thrown ways out included.
	 * <p>
	 * Owed rather than tidy: the two halves of a frame are two calls, and a group that left its
	 * program standing would hand the translucent half's layers the opaque half's block. Nothing
	 * else closes it, this family opening no pass of its own that a later draw would have to find
	 * shut.
	 */
	public static void endGroup() {
		ParticleDraw draw = PackChain.particles();
		if (draw != null) {
			draw.forget();
		}
	}

	/** The same, on the instance, for the three callers that already hold one. */
	private void forget() {
		this.drawing = null;
		this.standsIn = null;
		this.bound = null;
		this.pass = null;
		this.descriptor = null;
		this.handedOurs = false;
	}

	/**
	 * Whether what the particles write still reaches the screen this frame, which is the question
	 * every other family asks and the same answer.
	 */
	private boolean shown() {
		return !this.chainRuns || this.owner.drawable();
	}

	private RenderPipeline prepare(GpuDevice device, Element element, GpuTextureView colour,
			GpuTextureView depth) {
		if (!this.read) {
			return null;
		}

		forget();
		ParticleProgram program = this.programs.get(element.element());
		if (program == null) {
			return null;
		}

		// The refusal this family shares with the weather, and here it applies to the translucent half
		// alone: that is the only one the game sends to a target of its own, and only where the game's
		// transparency chain is running. Beside a target the game composes itself afterwards, the
		// pack's colour targets would be attached to a picture this engine has not got.
		Minecraft minecraft = Minecraft.getInstance();
		if (element.afterDeferred() && minecraft.levelRenderer.particlesTarget() != null) {
			return refuse("fabulous", "the game's improved transparency is on, so it draws its "
					+ "translucent particles into a target of its own that it composes afterwards, "
					+ "and the pack's colour targets cannot be attached beside it. This engine turns "
					+ "that option off when the pack is loaded, as Iris does when shaders are enabled, "
					+ "so reaching this line means it was turned back on afterwards. Turning it off "
					+ "again gives that half back to the pack");
		}

		this.owner.beginFrame();
		if (!this.owner.openTargets(device) || !shown()) {
			return null;
		}

		RenderPipeline pipeline = program.prepare(device);
		if (pipeline == null) {
			// Lasting, and it is GeometryProgram that makes it so: a program that would not compile
			// latches broken and answers null for the rest of the load. Keyed by the half, the two
			// being two files on most packs.
			return refuse("prepare:" + element.element(), "the " + element.element() + " program "
					+ "refused to prepare, which it says on its own line above. That is settled for "
					+ "as long as this pack is loaded, so it paints steadily rather than as a "
					+ "flicker");
		}

		// Beside the pipeline and never as a second question, for the reason
		// WeatherDraw.descriptor gives: a null descriptor is a pass this engine did not need or a
		// pass it could not build, and only the first is safe to bind a pipeline of ours into.
		RenderPassDescriptor pass = program.descriptor(colour, depth);
		if (pass == null && !program.plain()) {
			return refuse("unallocated:" + element.element(), "one of the pack's colour targets had "
					+ "no image yet on some frame, so the pass this half wanted could not be built "
					+ "then. That comes and goes with the frame rather than lasting");
		}

		this.drawing = program;
		this.standsIn = element.pipeline();
		this.bound = pipeline;
		this.descriptor = pass;

		return pipeline;
	}

	/**
	 * Hands one group back to the game and says why, once per reason and per load.
	 * <p>
	 * The entities' rule and the entities' reason: a group handed back is drawn by the game's own
	 * shader, so particles are lit by the game where everything around them is lit by the pack, with
	 * nothing anywhere to say which reason it was.
	 * <p>
	 * How long each one lasts is written into the sentence rather than into a flag, and they do not
	 * last alike: a program that would not compile latches broken and holds until the pack is read
	 * again, which paints steadily; a target that has no image yet is the first frame or two and the
	 * frames after a resize, which reads as a flicker; and the game's improved transparency holds
	 * until the player changes the setting.
	 *
	 * @return null always, so that a caller can hand this straight back
	 */
	private RenderPipeline refuse(String reason, String why) {
		if (this.refused.add(reason)) {
			Vitrail.logger().warn("A group of particles went back to the game's own shader because {}",
					why);
		}

		return null;
	}

	/**
	 * Reads the pack for both halves at once, at the first particle the game draws, and settles where
	 * each of them writes.
	 * <p>
	 * Both and not the one being asked for, for the reason the sky reads all six: the halves are one
	 * frame apart at most, so nothing is saved by waiting, and a reading is an opening and an
	 * expansion of the whole pack.
	 */
	private void read(OpenedPack shared) {
		try {
			List<PackProgram.GeometryElement> asked = ELEMENTS.values().stream().map(Element::asked).toList();
			Map<String, PackProgram.Loaded> loaded = shared != null
					? PackProgram.loadGeometry(shared, this.place, asked)
					: PackProgram.loadGeometry(this.packPath, this.place, asked, this.chosen, this.profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for the particles, so the game keeps its "
						+ "own shader for them", this.packPath.getFileName(),
						this.place.isEmpty() ? "its root" : this.place);

				return;
			}

			announceOrdering();

			// One half at a time, and it really is one at a time: they land on different sides of the
			// deferred stage and share no target, so a place that cannot answer for one can answer
			// for the other, and taking both down would be a choice nothing forced. Which one may go
			// missing is settled by the fallback tree and is written at the head of this class.
			for (Element element : ELEMENTS.values()) {
				PackProgram.Loaded one = loaded.get(element.element());
				if (one == null) {
					Vitrail.logger().info("{} serves nothing in {} for the {} particles, so the game "
							+ "keeps its own shader for that half", this.packPath.getFileName(),
							this.place.isEmpty() ? "its root" : this.place,
							element.afterDeferred() ? "translucent" : "opaque");

					continue;
				}

				List<ChainPlan.Attachment> writes = writes(element, one);
				if (writes != null) {
					this.programs.put(element.element(), ParticleProgram.of(one, element, this.values,
							this.load, writes, this.chainTargets, this.targets, this.chainRuns));
				}
			}
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the particle programs of "
					+ this.packPath.getFileName() + ", so the game keeps its own shader for them", e);
		} finally {
			this.read = true;
		}
	}

	/**
	 * Says once, at the load, when the pack asked for its particles to be placed somewhere this engine
	 * does not place them.
	 * <p>
	 * Only where the pack really wrote the line, and never off a default worked out here. Iris
	 * computes one when the line is missing, {@code AFTER} for a pack that ships deferred programs;
	 * it then reaches for the answer nowhere at all, so a line said off that default would be a line
	 * about a placement neither engine performs.
	 */
	private void announceOrdering() {
		this.values.particleOrdering()
				.filter(ordering -> !ordering.equalsIgnoreCase("mixed"))
				.ifPresent(ordering -> Vitrail.logger().info("{} asks for particles.ordering={}, and "
						+ "this engine draws them where the game does: the opaque ones before the "
						+ "deferred stage and the translucent ones after the world's water, which is "
						+ "what mixed names. Iris reads the word and moves nothing either",
						this.packPath.getFileName(), ordering));
	}

	/**
	 * Where the outputs of one half belong, in draw buffer order and each on the half of the schedule
	 * its side of the deferred stage gives it, or null when this place cannot answer for it.
	 * <p>
	 * Empty is not a refusal, and it is not the pack's silence: a program that declares no draw
	 * buffer is answered colortex0, as Iris answers it. What is left is the plan having no answer at
	 * all for the file that serves this half, and what puts a file there is the one list
	 * {@link ChainPlan#geometry} carries. No place of the corpus is in that case.
	 * <p>
	 * Null is a refusal, and the reasons are not the same for the two halves. Both refuse a place
	 * whose targets are not the size of the screen, one render pass having one render area. The
	 * OPAQUE half refuses two more, and they are the entities' two, word for word and for the same
	 * reason: it is drawn before the deferred stage over pixels the scene seed is cut out of, so its
	 * first output has no road into the pack's picture but the seed, and that road only lands where
	 * the pack asked if the seed paints the target the program writes first.
	 */
	private List<ChainPlan.Attachment> writes(Element element, PackProgram.Loaded loaded) {
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		if (this.chainRuns && !element.afterDeferred() && !this.seeded) {
			Vitrail.logger().info("The scene seed is off, and it is the only way the first output of an "
					+ "opaque particle reaches the pack's picture, so the game keeps its own shader for "
					+ "that half: served, it would write every other draw buffer and no colour");

			return null;
		}

		Optional<ChainPlan.Pass> geometry = this.plan.geometryOf(servedBy, element.afterDeferred());
		if (geometry.isEmpty()) {
			return List.of();
		}

		ChainPlan.Pass pass = geometry.get();
		if (!pass.size().equals(TargetSize.ofScreen())) {
			Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so they cannot share "
					+ "a pass with the game's own target and the game keeps its own shader for the {} "
					+ "particles", servedBy, element.afterDeferred() ? "translucent" : "opaque");

			return null;
		}

		if (element.afterDeferred()) {
			return pass.attachments();
		}

		// The plan answers it rather than this file working it out again, and the reason is not
		// tidiness: the verdicts have to know the same thing, so that a half handed back here is not
		// counted as filled there. Two readings of one condition would drift, and the drift shows up
		// as a diagnostic saying a target holds the pack's particles when the game drew them.
		ChainPlan.Attachment first = pass.attachments().get(0);
		Optional<ChainPlan.Seed> seed = this.plan.seed();
		if (!ChainPlan.leadsWithSeed(seed.orElse(null), pass)) {
			Vitrail.logger().warn("{} writes {} first and the scene seed paints {}, so the first output "
					+ "of an opaque particle would be carried into a target the pack did not ask for: "
					+ "the game keeps its own shader for that half", servedBy,
					TargetName.canonical(first.target()),
					seed.map(where -> TargetName.canonical(where.target())).orElse("nothing"));

			return null;
		}

		return pass.attachments();
	}

	/** The programs once the particles have been read, for the decoded dump. Empty until then. */
	Collection<ParticleProgram> programs() {
		return this.programs.values();
	}

	/** Rotates the ring buffers. Called once the frame's particle draws have been recorded. */
	void rotate() {
		forget();
		if (!this.read) {
			return;
		}

		this.programs.values().forEach(ParticleProgram::rotate);
	}

	void release() {
		forget();
		this.programs.values().forEach(ParticleProgram::release);
		this.programs.clear();
		this.refused.clear();
		this.foreign.clear();
		this.read = false;
	}
}
