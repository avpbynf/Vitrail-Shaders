package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import org.joml.Matrix4fc;
import org.joml.Vector4fc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The door the sky renderer comes in by, and the one place a pack's sky programs are read.
 * <p>
 * It is {@link TerrainDraw} for another piece of geometry, and it runs earlier: the game draws the
 * sky before the world, so this is now what usually opens the frame. That is the design and not a
 * side effect, and {@link PackChain#beginFrame} says why the boundary hangs off whichever comes
 * first.
 * <p>
 * <strong>An element is named by the label the game gives its own pass.</strong> The renderer opens
 * one pass per piece of sky and labels each of them, so the label is an answer the game hands out
 * at exactly the moment the answer is needed, and it costs no second table that could drift from
 * the first.
 * <p>
 * <strong>Every piece draws where the pack's own draw buffers send it</strong>, on the halves the
 * schedule gives them, which for most of the corpus is colortex0 and for Sildur's is colortex4. The
 * two pieces that write outright rather than blend also mark the pixels they covered, so the scene
 * seed stops painting the game's own sky over them, exactly as it stops over the terrain.
 * <p>
 * <strong>All the pieces or none of them</strong>, settled once per place in {@link #read}. The
 * mark is what makes it all or nothing: it cuts the seed, and the seed is the one road into the
 * pack's colour target left to a piece that stayed on the game's, so a place where the disc marks
 * the sky and the sun is still on the game's target is a place with no sun in it.
 */
public final class SkyDraw {

	/** Off unless {@code options.txt} asks otherwise, and read again at every load. */
	private static volatile boolean wanted;

	/**
	 * One piece of the game's sky: what the game calls its pass, which program of the pack answers
	 * for it, and what that pass binds.
	 *
	 * @param label    the game's own label, which is how a pass is recognised
	 * @param program  the bare name the game would have drawn with
	 * @param element  one word for the log and for the shader identifier, which has to tell two
	 *                 elements served by one file apart: the sun and the moon are one program
	 * @param blend    what the game's own pipeline blends this element with
	 * @param rotated  whether the game pushes a model view of its own for this element, which is
	 *                 where the sun and the moon are. The disc pushes nothing and is drawn under the
	 *                 camera's, so it is handed none and reads the frame's, which is the same matrix
	 *                 by another road and the one it has already been seen to draw right under
	 * @param stage    what a pack is told it is drawing. Iris sets one per method of the renderer and
	 *                 these are its six, one for one: {@code renderSkyDisc} is {@code SKY},
	 *                 {@code renderDarkDisc} is {@code VOID}, and the other four carry their own name
	 * @param directive the line of {@code shaders.properties} a pack switches this piece off with,
	 *                  spelled as the file spells it, or "" for the two pieces the format names
	 *                  nothing for. One directive for one piece and no wider reading of any of them:
	 *                  {@code sky} is the disc the game draws overhead, and the void plane under the
	 *                  world is a piece of its own that no pack can ask for or refuse
	 */
	record Element(String label, String program, String element, VertexFormat format,
			PrimitiveTopology topology, Optional<BlendFunction> blend, boolean rotated,
			RenderStage stage, String directive) {

		/** What the pack has to be read for to serve this piece, in terms the translation knows. */
		private PackProgram.SkyElement asked() {
			return new PackProgram.SkyElement(this.element, this.program,
					this.format.getElements().stream().map(VertexFormatElement::name).toList(),
					this.blend.isEmpty());
		}
	}

	/**
	 * The six elements of the overworld sky, which is all of them but the two the End draws.
	 * <p>
	 * The formats, the topologies and the blends are the game's own, read off
	 * {@code RenderPipelines} one by one rather than guessed at from a family: {@code SKY} is a
	 * triangle fan over {@code POSITION} that blends nothing, {@code STARS} is quads over the same
	 * format blended as an overlay, {@code SUNRISE_SUNSET} is a translucent fan over
	 * {@code POSITION_COLOR}, and {@code CELESTIAL} is quads over {@code POSITION_TEX}, overlaid.
	 * Any of them getting one of these wrong is a pipeline the pass refuses to bind, by name and in
	 * the middle of the world, and getting the FORMAT wrong is worse: it shifts the location of
	 * every element after the one that differs, without a word.
	 */
	private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();

	/**
	 * The label of the one pass that draws more than the game put in it. The horizon cone has no
	 * element of its own, so this is how the piece it rides on is recognised.
	 */
	private static final String DISC = "Sky disc";

	static {
		put(new Element(DISC, "gbuffers_skybasic", "disc", DefaultVertexFormat.POSITION,
				PrimitiveTopology.TRIANGLE_FAN, Optional.empty(), false, RenderStage.SKY, "sky"));
		put(new Element("Sky dark", "gbuffers_skybasic", "dark", DefaultVertexFormat.POSITION,
				PrimitiveTopology.TRIANGLE_FAN, Optional.empty(), true, RenderStage.VOID, ""));
		put(new Element("Stars", "gbuffers_skybasic", "stars", DefaultVertexFormat.POSITION,
				PrimitiveTopology.QUADS, Optional.of(BlendFunction.OVERLAY), true,
				RenderStage.STARS, "stars"));
		put(new Element("Sunrise sunset", "gbuffers_skybasic", "sunrise",
				DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.TRIANGLE_FAN,
				Optional.of(BlendFunction.TRANSLUCENT), true, RenderStage.SUNSET, ""));
		put(new Element("Sky sun", "gbuffers_skytextured", "sun", DefaultVertexFormat.POSITION_TEX,
				PrimitiveTopology.QUADS, Optional.of(BlendFunction.OVERLAY), true, RenderStage.SUN,
				"sun"));
		put(new Element("Sky moon", "gbuffers_skytextured", "moon", DefaultVertexFormat.POSITION_TEX,
				PrimitiveTopology.QUADS, Optional.of(BlendFunction.OVERLAY), true,
				RenderStage.MOON, "moon"));
	}

	private static void put(Element element) {
		ELEMENTS.put(element.label(), element);
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

	/** One program per element the pack serves. Empty until the pack has been read, and it stays
	 * empty for a pack that serves no sky at all. */
	private final Map<String, SkyProgram> programs = new LinkedHashMap<>();

	/**
	 * The geometry the game has none of, drawn with the disc and in the disc's own pass. Held here
	 * rather than beside the elements because it is not one: no pass of the game's is named after
	 * it, and no directive of the format can ask for it or refuse it.
	 */
	private final HorizonCone horizon = new HorizonCone();

	/** Whether the pack has been read for its sky. A reading that served nothing is still one. */
	private boolean read;

	/** The program of the pass being recorded, between the moment it is prepared and its bind. */
	private SkyProgram drawing;

	SkyDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
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

	/** Whether a pack's own sky programs take over the game's, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/**
	 * How far the loaded pack tilts the path of the sun and the moon, in degrees, and nought when no
	 * pack is loaded.
	 * <p>
	 * <strong>Not conditioned on this engine drawing the sky</strong>, and that is deliberate. The
	 * angle is a property of the pack's lighting: its shadow matrices already turn by it, so the
	 * light comes from a place the game's own sun does not stand in. Leaving the bodies where the
	 * game put them means a world lit from one direction with a sun visibly in another, which is
	 * the whole of the defect this closes, and it is just as wrong with {@code sky=off}. Iris ties
	 * it to a pipeline being loaded for the same reason.
	 */
	public static float sunPathRotation() {
		SkyDraw draw = PackChain.sky();

		return draw == null ? 0.0F : draw.values.sunPathRotation();
	}

	/**
	 * Everything that has to happen before the sky renderer opens one of its passes: the program
	 * read, the pipeline compiled, the frame opened and this frame's block written.
	 * <p>
	 * Called with the model view the game has already pushed for this element, which is where the
	 * sun and the moon are: see {@code ViewSource.passModelView}.
	 *
	 * @param label the label the game gave the pass it is about to open
	 * @return the pipeline to draw it with, or null to leave the game's own alone
	 */
	public static RenderPipeline element(String label, Matrix4fc modelView, Vector4fc colour) {
		SkyDraw draw = PackChain.sky();
		GpuDevice device = RenderSystem.tryGetDevice();
		Element element = ELEMENTS.get(label);
		if (draw == null || !wanted || device == null || element == null) {
			return null;
		}

		try {
			return draw.prepare(device, element, modelView, colour);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the sky after an error", e);

			return null;
		}
	}

	/**
	 * Whether the game is to draw one piece of its sky at all, which is the pack's to refuse.
	 * <p>
	 * <strong>Not the same question as which shader draws it.</strong> Everywhere else this class
	 * answers "the pack's program or the game's own"; here the answer is "nothing at all", because a
	 * pack writing {@code sun=false} has drawn its own sun inside {@code gbuffers_skybasic} and
	 * handing it the game's on top puts two suns in the sky.
	 * <p>
	 * <strong>Four pieces where the references take out two, and that is a deviation.</strong> Iris
	 * cancels {@code renderSun} and {@code renderMoon} at their head for the reason above and takes
	 * no notice of the {@code stars} and {@code sky} it reads, and OptiFine's own documented format
	 * has a word for neither. Carrying the reason to all four is this engine's, it costs Bliss and
	 * Reverie the stars both references leave them, and the NOTICE says so.
	 * <p>
	 * Tied to this engine drawing the sky, unlike {@link #sunPathRotation()}, and the two are not
	 * inconsistent. The rotation is a property of the pack's light, which reaches every surface of
	 * the world whether or not a sky program runs; this is a property of the pack's own sky, and
	 * with {@code sky=off} in {@code options.txt} there is no sky of the pack's for the refusal to
	 * be making room for.
	 *
	 * @param label the label the game gave the pass it is about to open
	 */
	public static boolean draws(String label) {
		SkyDraw draw = PackChain.sky();
		Element element = ELEMENTS.get(label);
		if (draw == null || !wanted || element == null) {
			return true;
		}

		return draw.values.skyElements().allows(element.directive());
	}

	/**
	 * The render pass one piece of the sky wants opened, or null to leave the game's own alone.
	 * <p>
	 * Asked right after {@link #element} and about the piece that call prepared, which is why it takes
	 * no label of its own: the two answers are one answer, and a piece named twice is a chance for
	 * them to differ. They have to be one, because the pipeline carries a colour state per attachment
	 * this names and setting it against a pass built for anything else throws by name in the middle
	 * of the sky. Nothing of the game's pass is lost by replacing it, since every one of the six opens
	 * with the main target's colour and depth and clears neither.
	 *
	 * @param colour the colour view the renderer was going to draw into, which stays attachment
	 *               nought wherever the pack's own targets do not take it
	 * @param depth  the depth view it was going to use, kept as it is. The sky neither tests nor
	 *               writes it, and the world drawn afterwards has to find it as the clear left it
	 */
	public static RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		SkyDraw draw = PackChain.sky();
		if (draw == null || draw.drawing == null) {
			return null;
		}

		return draw.drawing.descriptor(colour, depth);
	}

	/**
	 * Whether what the sky writes still reaches the screen this frame.
	 * <p>
	 * The same question {@code TerrainDraw.shown} asks and the same answer, because the sky now has
	 * the same way of leaving the screen: with draw buffer nought going to a target of the pack's,
	 * the chain's final is the only road back, and the chain draws nothing at all while it is still
	 * compiling. Those frames would be a sky drawn into a target nothing reads, which is a frame with
	 * no sky in it.
	 */
	private boolean shown() {
		return !this.chainRuns || this.owner.drawable();
	}

	/**
	 * The texture the game was going to draw this element with, on its way past. Recorded rather
	 * than looked up: the celestial atlas is the renderer's own field, and what a pack reads under
	 * {@code gtexture} has to be the image the game would have drawn.
	 */
	public static void texture(GpuTextureView view, GpuSampler sampler) {
		SkyDraw draw = PackChain.sky();
		if (draw != null && draw.drawing != null) {
			draw.drawing.texture(view, sampler);
		}
	}

	/**
	 * Draws the horizon cone, inside the pass the game opened for the sky disc and with the pipeline
	 * that pass has bound.
	 * <p>
	 * <strong>In the disc's pass and not one of its own</strong>, which is the whole of the choice
	 * made here: the cone is the same program, the same vertex format, the same topology and the same
	 * attachments as the disc, so a pass of our own would be a second answer to every question this
	 * class already answers once, and a second chance for the two to differ.
	 * <p>
	 * It follows from that that the cone is drawn where the disc is and nowhere else. A pack that
	 * refuses the disc with {@code sky=false} has drawn its own, and the method the refusal cancels
	 * is the one this rides in; a place where the game draws no disc at all, the End and the Nether,
	 * never reaches this. Iris gates its own cone on the same directive.
	 */
	public static void horizon(RenderPass pass, RenderPipeline bound) {
		SkyDraw draw = PackChain.sky();
		if (draw != null && draw.drawing != null && draw.drawing.owns(bound)) {
			draw.horizon.draw(pass, draw.drawing.path());
		}
	}

	/**
	 * Binds the sky program's block and samplers, inside the pass the sky renderer opened.
	 * <p>
	 * The pipeline that is really bound decides, as it does for the terrain: binding into a pass
	 * drawing the game's own shader would be harmless, since the descriptor flush walks the layout
	 * of the bound pipeline, but it would also mean this engine had stopped knowing which of the two
	 * was drawing.
	 */
	public static void bind(RenderPass pass, RenderPipeline bound) {
		SkyDraw draw = PackChain.sky();
		if (draw != null && draw.drawing != null && draw.drawing.owns(bound)) {
			draw.drawing.bind(pass);
		}
	}

	/**
	 * Reads the pack for all six pieces at once, at the first of them the game draws, and settles
	 * where every one of them is drawn.
	 * <p>
	 * All six and not the one being asked for, which is what this used to do. The game reaches four
	 * of these pieces at moments of its own choosing and three of those moments are conditions of the
	 * sky itself: the band is skipped until its alpha passes a thousandth, the stars until their
	 * brightness leaves nought, and the void plane until the eye goes under the world's horizon. Read
	 * one at a time, the pack was opened, expanded and translated inside the frame the sun first
	 * neared the horizon, on the render thread and in the middle of the world. The compiling is not
	 * moved with it and still falls where the piece is first drawn, one module a piece.
	 */
	private void read() {
		this.read = true;

		// Here and not at the load, because here is the first moment it is true. This runs only
		// where the sky is really this engine's to draw, which is a place the game opens a sky pass
		// in and a run with the option on: said at the load it would be said once per place and
		// would say the opposite of the code with sky=off, the game drawing the piece after all.
		List<String> off = this.values.skyElements().off();
		if (!off.isEmpty()) {
			Vitrail.logger().info("{} draws its own {}, so the game draws neither that nor a shader "
					+ "of the pack's in its place", this.packPath.getFileName(),
					String.join(" and ", off));
		}

		try {
			Map<String, PackProgram.Loaded> loaded = PackProgram.loadSky(this.packPath, this.place,
					ELEMENTS.values().stream().map(Element::asked).toList(), this.chosen, this.profile);

			// Asked once per PROGRAM and not once per piece: four of the six are drawn with
			// gbuffers_skybasic, and the plan would answer for that one four times over.
			Map<String, List<ChainPlan.Attachment>> byProgram = new LinkedHashMap<>();
			ELEMENTS.values()
					.forEach(element -> byProgram.computeIfAbsent(element.program(), this::writes));

			// All of them or none of them, for the reason the class comment gives. Body Camera is
			// the pack this is decided for: it declares draw buffers on gbuffers_skybasic and none
			// on gbuffers_skytextured, which the format allows, so the disc would mark the whole sky
			// and its sun and its moon would be drawn on the game's target and cut out of the seed.
			List<String> behind = behind(loaded, byProgram);
			if (!behind.isEmpty()) {
				Vitrail.logger().info("{} has nowhere of its own for the {} of its sky, so the whole "
						+ "of the sky keeps the game's target and the scene seed brings it across",
						this.packPath.getFileName(), String.join(", ", behind));
				byProgram.clear();
			}

			ELEMENTS.values().stream()
					.filter(element -> loaded.containsKey(element.element()))
					.forEach(element -> this.programs.put(element.label(), SkyProgram.of(
							loaded.get(element.element()), element, this.values, this.load,
							byProgram.getOrDefault(element.program(), List.of()), this.chainTargets,
							this.targets, this.chainRuns)));

			List<String> missing = ELEMENTS.values().stream()
					.filter(element -> !loaded.containsKey(element.element()))
					.map(Element::element)
					.toList();
			if (!missing.isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for the {} of its sky, so the game "
						+ "keeps its own", this.packPath.getFileName(),
						this.place.isEmpty() ? "its root" : this.place, String.join(", ", missing));
			}
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the sky programs of "
					+ this.packPath.getFileName() + ", so the game keeps its own sky", e);
		}
	}

	/**
	 * Where the outputs of one of the programs the sky is drawn with belong, which is the plan's
	 * answer for it, and empty when this place has none.
	 * <p>
	 * A place whose sky targets are not the size of the screen is refused here rather than at the
	 * first draw: the depth these passes test nothing against is still attached to them and is the
	 * screen's, and one render pass has one render area. No pack of the corpus scales one, and the
	 * line says so rather than the encoder throwing in the middle of a frame.
	 */
	private List<ChainPlan.Attachment> writes(String program) {
		return this.plan.sky(program)
				.filter(sky -> {
					if (sky.size().equals(TargetSize.ofScreen())) {
						return true;
					}

					Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so every "
							+ "piece of the sky it draws keeps the game's own target", sky.program());

					return false;
				})
				.map(ChainPlan.Pass::attachments)
				.orElse(List.of());
	}

	/**
	 * The pieces of the sky that would keep the game's own target, out of those the game still
	 * draws. Empty is the answer that lets the sky move into the pack's targets at all.
	 * <p>
	 * A piece is one of these for either of two reasons, and they weigh the same: the pack serves no
	 * program for it, so the game's own shader draws it, or the pack does serve one and declared no
	 * draw buffer on it, so the plan has nowhere to send what it writes. Both leave that piece
	 * reaching the pack's colour target through the scene seed alone.
	 * <p>
	 * A piece the pack switched off is not counted at all. Nothing draws it, so there is nothing
	 * about it for the seed to carry and nothing for it to hold the rest back over.
	 */
	private List<String> behind(Map<String, PackProgram.Loaded> loaded,
			Map<String, List<ChainPlan.Attachment>> byProgram) {
		return ELEMENTS.values().stream()
				.filter(element -> this.values.skyElements().allows(element.directive()))
				.filter(element -> !loaded.containsKey(element.element())
						|| byProgram.get(element.program()).isEmpty())
				.map(Element::element)
				.toList();
	}

	private RenderPipeline prepare(GpuDevice device, Element element, Matrix4fc modelView,
			Vector4fc colour) {
		if (!this.read) {
			read();
		}

		SkyProgram program = this.programs.get(element.label());
		this.drawing = program;
		if (program == null) {
			return null;
		}

		// The same two calls the terrain makes, and for the same reasons. The sky is drawn first in
		// the frame, so more often than not it is this that opens it; the second call is what makes
		// sure the colour targets a sky program samples exist before it reads them.
		this.owner.beginFrame();
		if (!this.owner.openTargets(device)) {
			return null;
		}

		// After the two calls above and never at the door, for the reason TerrainDraw gives at the
		// same point: the frame boundary hangs off whichever of the sky, the terrain and the chain
		// comes first, and the sky is usually first. Refused before opening the frame, the whole warm
		// up would leave the value store standing.
		if (!shown()) {
			return null;
		}

		// The colour goes to every element and the matrix only to those the game rotated: the two
		// are not the same question. Every sky draw carries a modulator, and the disc is the one
		// element whose matrix is the camera's already.
		RenderPipeline pipeline = program.prepare(device, element.rotated() ? modelView : null, colour);

		// Here and not at the draw, because filling a vertex buffer is a copy the encoder refuses
		// while a pass is open, and this is the last moment before the disc's pass exists. Only once
		// the disc really has a pipeline: a place this engine does not draw the sky in pays nothing.
		if (pipeline != null && DISC.equals(element.label())) {
			this.horizon.update(device);
		}

		return pipeline;
	}

	/** Rotates the ring buffers. Called once the frame's sky draws have been recorded. */
	void rotate() {
		this.drawing = null;
		this.programs.values().forEach(SkyProgram::rotate);
	}

	void release() {
		this.programs.values().forEach(SkyProgram::release);
		this.programs.clear();
		this.horizon.release();
		this.drawing = null;
		this.read = false;
	}
}
