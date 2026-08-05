package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
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
 * <strong>What this slice does not do.</strong> Every element draws into the pass the game opened,
 * with the one attachment the game gave it, exactly as a terrain program that gains nothing keeps
 * Sodium's pass. Where a pack's {@code DRAWBUFFERS} really want it is {@code ChainPlan.sky}'s
 * answer, measured out of game and not yet honoured here.
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
					this.format.getElements().stream().map(VertexFormatElement::name).toList());
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

	static {
		put(new Element("Sky disc", "gbuffers_skybasic", "disc", DefaultVertexFormat.POSITION,
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
	private final TargetPlan chainTargets;
	private final ColorTargets targets;

	/** One program per element the pack serves. Empty until the pack has been read, and it stays
	 * empty for a pack that serves no sky at all. */
	private final Map<String, SkyProgram> programs = new LinkedHashMap<>();

	/** Whether the pack has been read for its sky. A reading that served nothing is still one. */
	private boolean read;

	/** The program of the pass being recorded, between the moment it is prepared and its bind. */
	private SkyProgram drawing;

	SkyDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
			String profile, PackValues values, int load, TargetPlan chainTargets,
			ColorTargets targets) {
		this.owner = owner;
		this.packPath = packPath;
		this.place = place;
		this.chosen = Map.copyOf(chosen);
		this.profile = profile;
		this.values = values;
		this.load = load;
		this.chainTargets = chainTargets;
		this.targets = targets;

		// Once at load and only when there is something to say. A piece the pack refuses is a piece
		// the player stops seeing, and nothing else in the frame would account for it.
		List<String> off = values.skyElements().off();
		if (!off.isEmpty()) {
			Vitrail.logger().info("{} draws its own {}, so the game draws neither that nor a shader "
					+ "of the pack's in its place", packPath.getFileName(), String.join(" and ", off));
		}
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
	 * handing it the game's on top puts two suns in the sky. Iris cancels the same two methods at
	 * their head for the same reason.
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
	 * Reads the pack for all six pieces at once, at the first of them the game draws.
	 * <p>
	 * All six and not the one being asked for, which is what this used to do. The game reaches four
	 * of these pieces at moments of its own choosing and three of those moments are conditions of the
	 * sky itself: the band is skipped until its alpha passes a thousandth, the stars until their
	 * brightness leaves nought, and the void plane until the eye goes under the world's horizon. Read
	 * one at a time, the pack was opened, expanded and compiled inside the frame the sun first neared
	 * the horizon, on the render thread and in the middle of the world.
	 */
	private void read() {
		this.read = true;
		try {
			Map<String, PackProgram.Loaded> loaded = PackProgram.loadSky(this.packPath, this.place,
					ELEMENTS.values().stream().map(Element::asked).toList(), this.chosen, this.profile);
			ELEMENTS.values().stream()
					.filter(element -> loaded.containsKey(element.element()))
					.forEach(element -> this.programs.put(element.label(), SkyProgram.of(
							loaded.get(element.element()), element, this.values, this.load,
							this.chainTargets, this.targets)));

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

		// The colour goes to every element and the matrix only to those the game rotated: the two
		// are not the same question. Every sky draw carries a modulator, and the disc is the one
		// element whose matrix is the camera's already.
		return program.prepare(device, element.rotated() ? modelView : null, colour);
	}

	/** The programs once they have been read, for the decoded dump. Empty until then. */
	Iterable<SkyProgram> programs() {
		return this.programs.values();
	}

	/** Rotates the ring buffers. Called once the frame's sky draws have been recorded. */
	void rotate() {
		this.drawing = null;
		this.programs.values().forEach(SkyProgram::rotate);
	}

	void release() {
		this.programs.values().forEach(SkyProgram::release);
		this.programs.clear();
		this.drawing = null;
		this.read = false;
	}
}
