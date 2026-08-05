package dev.vitrail.render;

import dev.vitrail.pack.option.OptionValue;
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

import org.joml.Matrix4fc;
import org.joml.Vector4fc;

import java.nio.file.Path;
import java.util.LinkedHashMap;
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
	 */
	private record Element(String label, String program, String element, VertexFormat format,
			PrimitiveTopology topology, Optional<BlendFunction> blend, boolean rotated) {
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
				PrimitiveTopology.TRIANGLE_FAN, Optional.empty(), false));
		put(new Element("Sky dark", "gbuffers_skybasic", "dark", DefaultVertexFormat.POSITION,
				PrimitiveTopology.TRIANGLE_FAN, Optional.empty(), true));
		put(new Element("Stars", "gbuffers_skybasic", "stars", DefaultVertexFormat.POSITION,
				PrimitiveTopology.QUADS, Optional.of(BlendFunction.OVERLAY), true));
		put(new Element("Sunrise sunset", "gbuffers_skybasic", "sunrise",
				DefaultVertexFormat.POSITION_COLOR, PrimitiveTopology.TRIANGLE_FAN,
				Optional.of(BlendFunction.TRANSLUCENT), true));
		put(new Element("Sky sun", "gbuffers_skytextured", "sun", DefaultVertexFormat.POSITION_TEX,
				PrimitiveTopology.QUADS, Optional.of(BlendFunction.OVERLAY), true));
		put(new Element("Sky moon", "gbuffers_skytextured", "moon", DefaultVertexFormat.POSITION_TEX,
				PrimitiveTopology.QUADS, Optional.of(BlendFunction.OVERLAY), true));
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

	/** One program per element, once it has been asked for. A value of null is an element that was
	 * asked for and that the pack serves nothing for; the difference from an absent key is what
	 * keeps a refusal from being retried every frame. */
	private final Map<String, SkyProgram> programs = new LinkedHashMap<>();

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
	}

	/** Whether a pack's own sky programs take over the game's, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
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

	private RenderPipeline prepare(GpuDevice device, Element element, Matrix4fc modelView,
			Vector4fc colour) {
		// Read once each, and the miss is REMEMBERED. Never computeIfAbsent here: it records
		// nothing when the answer is null, so an element the pack serves nothing for would open
		// the pack again on every frame of the game.
		if (!this.programs.containsKey(element.label())) {
			this.programs.put(element.label(),
					SkyProgram.read(this.packPath, this.place, element.program(), element.element(),
							this.chosen, this.profile, this.values, this.load, element.format(),
							element.topology(), element.blend(), this.chainTargets, this.targets));
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
		return this.programs.values().stream().filter(one -> one != null).toList();
	}

	/** Rotates the ring buffers. Called once the frame's sky draws have been recorded. */
	void rotate() {
		this.drawing = null;
		programs().forEach(SkyProgram::rotate);
	}

	void release() {
		programs().forEach(SkyProgram::release);
		this.programs.clear();
		this.drawing = null;
	}
}
