package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.target.ChainPlan;
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
 * The door the weather renderer comes in by, and the one place a pack's {@code gbuffers_weather} is
 * read.
 * <p>
 * <strong>It is the sky's door and not the entities'.</strong> {@code WeatherEffectRenderer} opens a
 * render pass of its own, sets one of the game's two weather pipelines and draws a buffer it filled
 * a few lines earlier, so what has to be swapped is the pass and the pipeline, exactly as for the
 * sky. The entities come in through a group of draws the game opens no pass for, which is a
 * different shape and a different door.
 * <p>
 * <strong>One pass, two draws, and the only thing that changes between them is the image</strong>:
 * the rain and the snow are one vertex buffer, drawn with one pipeline, with the rain's columns
 * first and the snow's after them. So the texture is handed over per draw, as it is for the
 * entities, and everything else is settled once when the pass opens.
 * <p>
 * <strong>It is drawn after everything else the world does.</strong> The game builds a frame graph
 * pass of its own for the weather and runs it once the main pass is finished, so the deferred stage
 * has run and the world's translucents are down by then. What that decides is the half of every
 * target this program reads and writes, and it is the one thing this family answers differently from
 * the sky and the entities, which are both drawn before the deferreds.
 * <p>
 * <strong>Two elements out of one program</strong>, and it is the same reason the sky is eight
 * pieces out of two files: the game picks between {@code WEATHER_DEPTH_WRITE} and
 * {@code WEATHER_NO_DEPTH_WRITE} at every frame, the two differ in a depth state, and a depth state
 * belongs to a compiled pipeline rather than to a draw. A pack's file is still read and translated
 * once, the two elements sharing a translation.
 * <p>
 * <strong>No pack of the corpus asks for the depth writing one</strong>, and it is still made. The
 * game picks it under improved transparency or under a pack's {@code rain.depth}, and three packs of
 * the eight write that directive with all three writing false. The first of those two routes is
 * usually refused a few lines further down, but not always and not by the same question: the game
 * reads {@code useShaderTransparency()} while the refusal reads whether the weather target was
 * really allocated, and a run with no post chain behind it has one and not the other. So the element
 * is reached rarely rather than never. What it costs is one compiled module at the first rainfall of
 * a pack that asks; what leaving it out would cost is that pack silently getting the other one's
 * depth state.
 */
public final class WeatherDraw {

	/** Off unless {@code options.txt} asks otherwise, and read again at every load. */
	private static volatile boolean wanted;

	/** The bare name the pack is asked for. Its fallback tree is walked like any other. */
	private static final String PROGRAM = "gbuffers_weather";

	/**
	 * What the curtain discards at when the pack says nothing, which is the tenth Iris gives its one
	 * weather key ({@code pipeline/programs/ShaderKey.java:60}). One key and two pipelines: both of
	 * the game's weather pipelines reach that single key ({@code pipeline/IrisPipelines.java:70-71}),
	 * which is the same shape as the two elements below. The game's own weather pipeline carries no
	 * cutout define at all, so there is nothing of its to inherit here.
	 */
	private static final AlphaTest CUTOUT = AlphaTest.ONE_TENTH;

	/**
	 * One way the game draws its weather: which pipeline it picked, and what the pack is asked for.
	 *
	 * @param pipeline the game's own pipeline, which is both how the two are told apart and where the
	 *                 blend, the depth window, the culling and the topology are read from
	 * @param element  one word for the log and for the shader identifier, which has to tell the two
	 *                 apart: they are one file and two compiled modules
	 * @param stage    what the pack is told it is drawing, which is {@code RAIN_SNOW} for both. Iris
	 *                 poses it around the whole of the game's weather lambda
	 *                 ({@code mixin/MixinLevelRenderer.java:245-247}), the world border alone moving
	 *                 off it again
	 */
	record Element(RenderPipeline pipeline, String element, RenderStage stage) {

		/**
		 * What the pack has to be read for to serve this piece, in terms the translation knows.
		 * <p>
		 * No coverage mask, which {@link WeatherProgram} sets out where it decides the same thing
		 * for the pass.
		 */
		private PackProgram.GeometryElement asked() {
			return new PackProgram.GeometryElement(this.element, PROGRAM, CUTOUT,
					VertexInputs.PARTICLE, false);
		}
	}

	private static final Map<RenderPipeline, Element> ELEMENTS = new LinkedHashMap<>();

	static {
		put(new Element(RenderPipelines.WEATHER_NO_DEPTH_WRITE, "weather", RenderStage.RAIN_SNOW));
		put(new Element(RenderPipelines.WEATHER_DEPTH_WRITE, "weather_depth", RenderStage.RAIN_SNOW));
	}

	private static void put(Element element) {
		ELEMENTS.put(element.pipeline(), element);
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
	private final ColorTargets targets;

	/** One program per pipeline the game may pick. Empty until the pack has been read, and it stays
	 * empty for a pack this place can serve no weather with at all. */
	private final Map<String, WeatherProgram> programs = new LinkedHashMap<>();

	/** Whether the pack has been read for its weather. A reading that served nothing is still one. */
	private volatile boolean read;

	/** The reasons the curtain has already been handed back to the game. One line each, not one a
	 * frame. The entities' rule, and this family owed it as much as they did: a curtain drawn by the
	 * game inside a pack lit world is exactly the plausible and wrong picture nothing reports. */
	private final Set<String> refused = new LinkedHashSet<>();

	/** The program of the pass being recorded, between the moment it is prepared and its draws. */
	private WeatherProgram drawing;

	/** The pass that program wants opened, worked out beside it. Null means the renderer's own. */
	private RenderPassDescriptor descriptor;

	WeatherDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
			String profile, PackValues values, int load, ChainPlan plan, TargetPlan chainTargets,
			boolean chainRuns, ColorTargets targets) {
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
		this.targets = targets;
	}

	/** Whether a pack's own weather program takes over the game's, from the loaded options. */
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
	 * Whether the game is to draw its rain and snow at all, which is the pack's to refuse.
	 * <p>
	 * <strong>Not the same question as which shader draws it</strong>, and it is the sun and the
	 * moon's question word for word: a pack writing {@code weather=false} has drawn its own curtain
	 * inside its own programs, and handing it the game's on top puts two curtains in the air. Iris
	 * cancels the whole of {@code render} on it ({@code mixin/MixinWeatherRenderer.java:23-28}) and
	 * so does this.
	 * <p>
	 * Tied to this engine really drawing the weather, as {@code SkyDraw.draws} is and for the same
	 * reason: with {@code weather=off} in {@code options.txt} there is no curtain of the pack's for
	 * the refusal to be making room for, so honouring it would leave a rainstorm with no rain in it.
	 * That switch is this engine's own and Iris has none like it, so under the configuration Iris can
	 * be compared against the two answer alike.
	 */
	public static boolean draws() {
		WeatherDraw draw = PackChain.weather();

		return draw == null || !wanted || draw.values.weather().drawn();
	}

	/**
	 * Whether the game is to keep spawning the splashes the ground throws up under the rain, which is
	 * the second word of the same directive.
	 * <p>
	 * A different thing in a different place: they are ordinary particles, spawned on a tick by the
	 * level rather than drawn by the weather renderer, and Iris takes them away by handing that tick
	 * the most frugal of the game's particle settings rather than by skipping it
	 * ({@code mixin/MixinWeatherRenderer.java:30-37}). The method it hangs that on is gone in 26.2,
	 * the spawning having moved into {@code ClientLevel.tickWeatherEffects}, and the setting it hands
	 * over is still there and still read at the top of it.
	 */
	public static boolean splashes() {
		WeatherDraw draw = PackChain.weather();

		return draw == null || !wanted || draw.values.weather().particles();
	}

	/**
	 * Whether the pack asked for the rain and the snow to write the world's depth.
	 * <p>
	 * <strong>Not conditioned on this engine drawing the weather</strong>, unlike the two above, and
	 * for the reason {@code SkyDraw.sunPathRotation} is not either: this decides what the game's own
	 * curtain occludes rather than which shader draws it, so it is just as true with
	 * {@code weather=off}. Iris ties it to a pipeline being loaded and to nothing else.
	 */
	public static boolean depth() {
		WeatherDraw draw = PackChain.weather();

		return draw != null && draw.values.rainDepth();
	}

	/**
	 * Everything that has to happen before the weather renderer opens its pass: the program read, the
	 * pipeline compiled, the frame opened and this frame's block written.
	 *
	 * @param game   the pipeline the renderer picked earlier in the same method, which is where
	 *               every state this engine does not decide comes from
	 * @param colour the colour view the renderer was going to draw into, which stays attachment
	 *               nought wherever the pack's own targets do not take it
	 * @param depth  the depth view it was going to use, kept as it is
	 * @return the pipeline to draw it with, or null to leave the game's own alone
	 */
	public static RenderPipeline element(RenderPipeline game, GpuTextureView colour,
			GpuTextureView depth) {
		WeatherDraw draw = PackChain.weather();
		GpuDevice device = RenderSystem.tryGetDevice();
		Element element = ELEMENTS.get(game);
		if (draw == null || !wanted || device == null || element == null) {
			return null;
		}

		try {
			return draw.prepare(device, element, colour, depth);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the weather after an error", e);

			return null;
		}
	}

	/**
	 * The render pass the curtain wants opened, or null to open the plain one the renderer was going
	 * to open.
	 * <p>
	 * Worked out by {@link #element} and only handed back here, which is the sky's rule made
	 * mechanical: the two answers have to be ONE, a pipeline carrying a colour state per attachment
	 * this names and setting it against a pass built for anything else throwing by name in the middle
	 * of a rainstorm. Asked as a second question they could differ, and the case where they would is
	 * real rather than theoretical - a pass this engine cannot build is not the same thing as a pass
	 * it does not need.
	 */
	public static RenderPassDescriptor descriptor() {
		WeatherDraw draw = PackChain.weather();

		return draw == null ? null : draw.descriptor;
	}

	/**
	 * The image the game was going to draw this half of the curtain with, on its way past. Recorded
	 * rather than looked up, so that a pack reading {@code gtexture} reads the rain's own texture
	 * while the rain is drawn and the snow's while the snow is.
	 */
	public static void texture(GpuTextureView view, GpuSampler sampler) {
		WeatherDraw draw = PackChain.weather();
		if (draw != null && draw.drawing != null) {
			draw.drawing.texture(view, sampler);
		}
	}

	/**
	 * Binds the weather program's block and samplers, inside the pass the renderer opened, once for
	 * each of the two draws.
	 * <p>
	 * The pipeline that is really bound decides, as it does for the sky and the terrain: binding into
	 * a pass drawing the game's own shader would be harmless, the descriptor flush walking the layout
	 * of the bound pipeline, but it would also mean this engine had stopped knowing which of the two
	 * was drawing.
	 */
	public static void bind(RenderPass pass, RenderPipeline bound) {
		WeatherDraw draw = PackChain.weather();
		if (draw != null && draw.drawing != null && draw.drawing.owns(bound)) {
			draw.drawing.bind(pass);
		}
	}

	/**
	 * Whether what the weather writes still reaches the screen this frame, which is the question
	 * {@code TerrainDraw.shown} and {@code SkyDraw.shown} both ask and the same answer.
	 */
	private boolean shown() {
		return !this.chainRuns || this.owner.drawable();
	}

	private RenderPipeline prepare(GpuDevice device, Element element, GpuTextureView colour,
			GpuTextureView depth) {
		if (!this.read) {
			return null;
		}

		this.descriptor = null;
		WeatherProgram program = this.programs.get(element.element());
		this.drawing = program;
		if (program == null) {
			return null;
		}

		// The one refusal this family has that the sky has not, and it is a question about the run
		// rather than about the pack. The renderer draws into OutputTarget.WEATHER_TARGET, which is a
		// target of its own wherever the game's transparency chain is running and the main target
		// everywhere else; the pack's colour targets are attached beside that image, and beside a
		// target the game is going to compose itself afterwards they would be attached to a picture
		// this engine has not got and does not read.
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.levelRenderer.weatherTarget() != null) {
			this.drawing = null;

			return refuse("fabulous", "the game's improved transparency is on, so it draws its "
					+ "weather into a target of its own that it composes afterwards, and the pack's "
					+ "colour targets cannot be attached beside it. This engine turns that option off "
					+ "when the pack is loaded, as Iris does when shaders are enabled, so reaching "
					+ "this line means it was turned back on afterwards. Turning it off again gives "
					+ "the pack's rain back");
		}

		// The same two calls the sky and the terrain make, and for the same reasons. This is never the
		// call that opens the frame, the weather being drawn after the whole of the main pass, but
		// beginFrame is what makes that a fact rather than an assumption.
		this.owner.beginFrame();
		if (!this.owner.openTargets(device)) {
			this.drawing = null;

			return null;
		}

		if (!shown()) {
			this.drawing = null;

			return null;
		}

		RenderPipeline pipeline = program.prepare(device);
		if (pipeline == null) {
			this.drawing = null;

			return refuse("prepare:" + element.element(), "the " + element.element() + " program "
					+ "refused to prepare, which it says on its own line above. That is settled for as "
					+ "long as this pack is loaded, so every frame the game picks that pipeline for "
					+ "draws its own rain, steadily rather than as a flicker");
		}

		// Here and not at the door's second call, so that the two cannot answer differently. A null
		// descriptor means one of two things and only one of them is safe: a pass that gains nothing
		// over the renderer's own, which is a program writing the game's target alone, or a pass this
		// engine could not build. The second one has to take the pipeline down with it, since binding
		// a pipeline that carries a state per target the pack asked for into the renderer's single
		// attachment pass is refused by name, in the middle of a rainstorm.
		this.descriptor = program.descriptor(colour, depth);
		if (this.descriptor == null && !program.plain()) {
			this.drawing = null;

			// Keyed by the family and not by the element, unlike the refusal above and unlike the
			// particles': the two elements are built from ONE writes list and one set of colour
			// targets, so this condition is never true of one of them and false of the other, and a
			// key per element would print the same sentence twice.
			return refuse("unallocated", "one of the pack's colour targets had no image yet on some "
					+ "frame, so the pass the curtain wanted could not be built then. That comes and "
					+ "goes with the frame rather than lasting");
		}

		return pipeline;
	}

	/**
	 * Hands the curtain back to the game and says why, once per reason and per load.
	 * <p>
	 * The entities' rule and the entities' reason: a curtain handed back is drawn by the game's own
	 * shader inside a world the pack lit, and without a line there is nothing anywhere to say which
	 * reason it was. How long each one lasts is written into the sentence rather than into a flag.
	 *
	 * @return null always, so that a caller can hand this straight back
	 */
	private RenderPipeline refuse(String reason, String why) {
		if (this.refused.add(reason)) {
			Vitrail.logger().warn("The rain and the snow went back to the game's own shader because "
					+ "{}", why);
		}

		return null;
	}

	/**
	 * Reads the pack for both elements at once, at the first frame it rains, and settles where their
	 * outputs go.
	 * <p>
	 * Both and not the one being asked for, for the reason the sky reads all six: the moment the
	 * second one is first wanted is the player's, a graphics setting away, and read one at a time the
	 * pack would be opened, expanded and translated inside that frame, on the render thread and in
	 * the middle of a storm.
	 */
	private void read(OpenedPack shared) {
		try {
			List<PackProgram.GeometryElement> asked = ELEMENTS.values().stream().map(Element::asked).toList();
			Map<String, PackProgram.Loaded> loaded = shared != null
					? PackProgram.loadGeometry(shared, this.place, asked)
					: PackProgram.loadGeometry(this.packPath, this.place, asked, this.chosen, this.profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for the weather, so the game keeps its "
						+ "own shader for the rain and the snow", this.packPath.getFileName(),
						this.place.isEmpty() ? "its root" : this.place);

				return;
			}

			// Asked once for the family rather than once per element: the two are one program name and
			// so one file, and the plan would answer for it twice over.
			List<ChainPlan.Attachment> writes = writes(loaded.values().iterator().next());
			if (writes == null) {
				return;
			}

			ELEMENTS.values().stream()
					.filter(element -> loaded.containsKey(element.element()))
					.forEach(element -> this.programs.put(element.element(), WeatherProgram.of(
							loaded.get(element.element()), element, this.values, this.load, writes,
							this.chainTargets, this.targets, this.chainRuns)));
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the weather program of "
					+ this.packPath.getFileName() + ", so the game keeps its own shader for the rain "
					+ "and the snow", e);
		} finally {
			this.read = true;
		}
	}

	/**
	 * Where the outputs of the file that serves the weather belong, in draw buffer order and each on
	 * the half the schedule gives it, or null when this place cannot answer at all.
	 * <p>
	 * Empty is not a refusal, and it is not the pack's silence: a program that declares no draw
	 * buffer is answered colortex0, as Iris answers it, and the curtain then lands in the pack's own
	 * target rather than on the game's. What is left is the plan having no answer at all for the file
	 * that serves the weather, and what puts a file there is the one list
	 * {@link ChainPlan#geometry} carries; the curtain then stays on the game's own target, where the
	 * game's own weather went and where the chain's final still overwrites it.
	 * <p>
	 * Null is a refusal, and there is one: a place whose weather targets are not the size of the
	 * screen cannot share a pass with the game's own target, one render pass having one render area.
	 * No pack of the corpus scales one, and the line says so rather than the encoder throwing in the
	 * middle of a frame.
	 * <p>
	 * The half is the one AFTER the deferreds, which is this family's own answer and not a copy of
	 * the sky's: the game draws its weather once the main pass is finished, so what it reads and
	 * writes is what the deferred stage left, exactly as the world's translucents do.
	 */
	private List<ChainPlan.Attachment> writes(PackProgram.Loaded loaded) {
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		Optional<ChainPlan.Pass> geometry = this.plan.geometryOf(servedBy, true);
		if (geometry.isEmpty()) {
			return List.of();
		}

		ChainPlan.Pass pass = geometry.get();
		if (!pass.size().equals(TargetSize.ofScreen())) {
			Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so they cannot share "
					+ "a pass with the game's own target and the game keeps its own shader for the rain "
					+ "and the snow", servedBy);

			return null;
		}

		return pass.attachments();
	}

	/** The programs once the weather has been read, for the decoded dump. Empty until then. */
	Collection<WeatherProgram> programs() {
		return this.programs.values();
	}

	/** Rotates the ring buffers. Called once the frame's weather draws have been recorded. */
	void rotate() {
		this.drawing = null;
		this.descriptor = null;
		if (!this.read) {
			return;
		}

		this.programs.values().forEach(WeatherProgram::rotate);
	}

	void release() {
		this.programs.values().forEach(WeatherProgram::release);
		this.programs.clear();
		this.drawing = null;
		this.descriptor = null;
		// With the programs, or "once per load" would mean once per session: what is read again
		// after this can refuse again, and a reader watching a storm would see the first reading's
		// lines and nothing after them.
		this.refused.clear();
		this.read = false;
	}
}
