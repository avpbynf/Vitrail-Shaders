package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Folds several upscaled frames into one, reprojected by {@link MotionVectors}: a checkbox on the
 * engine page beside the render scale, off until asked for, and worth nothing at a scale of a
 * hundred where there is nothing to rebuild.
 * <p>
 * <strong>What it recovers is the detail the scale stopped drawing.</strong> A world rendered small
 * loses the thin things first, and they are exactly what shimmers as the camera moves: distant
 * leaves, fences, the edges of far terrain. Several frames of a small world do not carry the same
 * detail as each other, so folding them puts some of it back, and the picture at a low scale settles
 * instead of crawling.
 * <p>
 * <strong>Its failure mode is also the only proof its inputs are right.</strong> None of what feeds
 * it can be checked on a still frame, and all of it shows here within a second of walking: vectors
 * pointing the wrong way smear the world along the direction of travel instead of holding it still,
 * and a camera translation left out smears when the player moves and not when they turn. That is
 * worth knowing when a report arrives about this looking wrong, because what is wrong is more likely
 * upstream of this class than in it.
 * <p>
 * <strong>It deliberately does not jitter.</strong> Seven of the eight packs of the corpus already
 * apply their own sub-pixel offset for their own temporal pass, so the low resolution frames already
 * differ from one another and there is already something for an accumulator to recover. Arming a
 * second jitter on top would be two of them, and would confound the one question being asked. Body
 * Camera, which jitters nothing, is the control: if accumulation buys visibly less there than
 * elsewhere, that is the measurement that says our own jitter is needed, and it costs nothing to
 * take.
 * <p>
 * <strong>Where it sits is wrong by both vendors' contracts, and that is deliberate.</strong> NVIDIA
 * asks for the upscale "before tone mapping", AMD splits post processing into a column that runs
 * before and a column that runs after, with tone mapping, bloom and depth of field in the second.
 * Here all of that belongs to the pack and this pass runs after the whole of it, on the finished
 * picture, because that is where {@link RenderScale} already stands. So this is not FSR and not a
 * step towards it: it is the cheapest place to learn whether the inputs are right, and what a real
 * integration would have to buy is the cut through the pack's own chain that nothing in a pack
 * declares.
 * <p>
 * The history is folded with a fixed weight rather than a variance clip, and it is held to the
 * neighbourhood of the current frame, which is what stops a reprojection that lands on the wrong
 * surface from dragging last frame's colour across an edge. That clamp is also what makes a defect
 * honest: without it, wrong vectors produce a smear that a viewer reads as motion blur, and with it
 * they produce a shimmer nobody mistakes for a feature.
 */
public final class TemporalAccumulation {

	/** Where the answer is kept between sessions, beside the pack like the other engine settings. */
	private static final String SETTING_FILE = "temporal-fold";

	/** What a player gets without touching anything: the engine as it was, exactly. */
	public static final boolean DEFAULT_WANTED = false;

	/**
	 * Whether the fold is asked for, or null while the file has not been read.
	 * <p>
	 * A checkbox on the engine page and not a command line switch: a road a session takes to measure
	 * both sides in one jar and a road a player is handed are the same road here, and two of them
	 * drift. The value applies at the next frame, so the two sides of a comparison are a click apart
	 * rather than a relaunch apart.
	 */
	private static Boolean wanted;

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/temporal_vertex");

	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/temporal_fragment");

	private static final String CURRENT = "InSampler";
	private static final String HISTORY = "PrevSampler";
	private static final String VECTORS = "MotionSampler";

	private static final String UNIFORM_BLOCK = "OfTemporal";

	/** Two pairs and a float, which std140 lays out at 8, 8 and 4, rounded up to the alignment. */
	private static final int BLOCK_BYTES = 32;

	private static final int VERTICES = 6;
	private static final int SURFACES = 2;

	/**
	 * How much of the new frame goes in. A tenth is the usual weight for this kind of fold, and it
	 * is the one worth starting at because it is aggressive enough that a defect in the vectors is
	 * unmistakable rather than subtle.
	 */
	private static final float NEW_WEIGHT = 0.1F;

	/** Half floats: an eight bit accumulator folded at a tenth quantises before it converges. */
	private static final GpuFormat FORMAT = GpuFormat.RGBA16_FLOAT;

	private static final String LABEL = "Vitrail temporal accumulation";

	private static final String VERTEX = """
			#version 460 core

			in vec3 Position;

			void main() {
				gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	/**
	 * The motion vector is read at the output pixel and is in pixels of the RENDER resolution, so it
	 * is divided by that size and not by this pass's own: the two differ by exactly the render scale,
	 * and dividing by the wrong one produces a reprojection that is right at a hundred percent and
	 * wrong everywhere this is actually used.
	 */
	private static final String FRAGMENT = """
			#version 460 core

			uniform sampler2D InSampler;
			uniform sampler2D PrevSampler;
			uniform sampler2D MotionSampler;

			layout(std140) uniform OfTemporal {
				vec2 of_InverseSize;
				vec2 of_InverseRenderSize;
				float of_NewWeight;
			};

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				vec2 uv = gl_FragCoord.xy * of_InverseSize;
				vec4 here = texture(InSampler, uv);

				vec2 motion = texture(MotionSampler, uv).rg * of_InverseRenderSize;
				vec2 was = uv + motion;

				// Off screen a frame ago, so there is no history for this pixel and the fold would
				// be against whatever the clamp to edge hands back, which is a stripe of the border
				// smeared inwards along every edge the camera turns towards.
				//
				// Written as the negation of "inside" rather than as four outside tests, because a
				// NaN compares false against BOTH, so the four-test form lets a NaN coordinate
				// through into the history fetch while this one rejects it.
				if (!(was.x >= 0.0 && was.x <= 1.0 && was.y >= 0.0 && was.y <= 1.0)) {
					ofFragData0 = here;

					return;
				}

				vec3 low = here.rgb;
				vec3 high = here.rgb;
				for (int x = -1; x <= 1; x++) {
					for (int y = -1; y <= 1; y++) {
						vec3 neighbour =
								texture(InSampler, uv + vec2(x, y) * of_InverseSize).rgb;
						low = min(low, neighbour);
						high = max(high, neighbour);
					}
				}

				vec3 before = clamp(texture(PrevSampler, was).rgb, low, high);
				ofFragData0 = vec4(mix(before, here.rgb, of_NewWeight), here.a);
			}
			""";

	private static final ShaderSource SOURCE = (id, type) -> {
		if (type == ShaderType.FRAGMENT) {
			return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
		}

		return VERTEX_ID.equals(id) ? VERTEX : null;
	};

	private RenderPipeline pipeline;
	private TargetSurface[] history;
	private MappableRingBuffer block;

	private boolean refused;
	private int current;

	/** Whether the pair holds anything at all, which the first frame at a size does not. */
	private boolean primed;

	/**
	 * Whether the road taken has been said out loud yet.
	 * <p>
	 * A switch that prints nothing on one of its two roads leaves every reading taken there
	 * unattributable: a run that looks unchanged is either the fold running and changing little, or
	 * the fold never having run, and nothing on screen tells those apart. Said once rather than per
	 * frame, and said from the fold rather than from the flag, so what it reports is that the pass
	 * really drew and not merely that somebody asked for it.
	 */
	private boolean announced;

	/**
	 * The same, for the refusal above, and a second flag rather than a shared one on purpose: one
	 * flag would let the refusal spend it, and the fold that started working afterwards would then
	 * go unannounced, which is the exact silence this pair exists to close.
	 */
	private boolean announcedMissing;

	/** Whether the fold is asked for at all. Read before anything is allocated. */
	public static boolean wanted() {
		if (wanted == null) {
			wanted = read();
		}

		return wanted;
	}

	/**
	 * Takes what the settings screen chose, writes it beside the pack and keeps the live answer. It
	 * applies at the next frame: no pack reload and no restart.
	 */
	public static void setWanted(boolean asked) {
		wanted = asked;
		try {
			Path file = file();
			Files.createDirectories(file.getParent());
			Files.writeString(file, asked + "\n");
		} catch (IOException | RuntimeException e) {
			// Kept live anyway: a value that cannot be stored is still the one the player asked for
			// this session, and a checkbox that springs back says nothing at all.
			Vitrail.logger().warn("Could not store the temporal fold, it holds for this session "
					+ "only", e);
		}
	}

	private static boolean read() {
		try {
			Path file = file();
			if (!Files.isRegularFile(file)) {
				return DEFAULT_WANTED;
			}

			// Anything that is not the word reads as off rather than on, which is the opposite of
			// how the shadow interval treats a typo, and deliberately: that one answers a typo with
			// its default because its default is a gain that would disappear silently. This one is
			// off by default, so a typo answered with on would turn the picture over instead.
			return Boolean.parseBoolean(Files.readString(file).trim());
		} catch (IOException | RuntimeException ignored) {
			return DEFAULT_WANTED;
		}
	}

	private static Path file() {
		return Vitrail.platform().gameDirectory().resolve("vitrail").resolve(SETTING_FILE);
	}

	/**
	 * Folds this frame into the history and hands back what to read on, or null when it did not run,
	 * in which case the caller carries on with the image it already had.
	 *
	 * @param scene   the upscaled frame, at the window's size
	 * @param vectors what {@link MotionVectors} wrote, at the render size, or null on a frame it did
	 *                not draw
	 */
	GpuTextureView fold(CommandEncoder encoder, GpuDevice device, GpuBuffer quad,
			GpuTextureView scene, GpuTextureView vectors, int width, int height, int renderWidth,
			int renderHeight) {
		if (!wanted()) {
			return null;
		}

		if (vectors == null) {
			// The one refusal worth a line of its own, because it is the one that looks like the
			// fold working and changing nothing: asked for, running, and handed no vectors because
			// the chain drew none. Said once, since it is true for every frame until a pack loads.
			if (!this.announcedMissing) {
				this.announcedMissing = true;
				// What it names are the reasons that can actually be true HERE. A pack and a scale
				// under a hundred are not among them: this is only reached from inside the scaled
				// branch, which already required both, so naming them would send a reader to check
				// two things that cannot be the cause.
				Vitrail.logger().warn("Temporal fold asked for but the engine wrote no motion "
						+ "vectors this frame, so the picture is the plain upscale: either the "
						+ "window has just changed size, or the vector pass could not allocate or "
						+ "did not compile, both of which say so on their own line");
			}

			return null;
		}

		if (quad == null || scene == null || renderWidth <= 0 || renderHeight <= 0
				|| !ensure(width, height)) {
			return null;
		}

		RenderPipeline compiled = pipeline(device);
		if (compiled == null) {
			return null;
		}

		int into = 1 - this.current;
		// The first frame at a size has no history, and folding against an unwritten image would
		// hand the clamp a neighbourhood of whatever the driver left. One frame of the scene alone.
		float weight = this.primed ? NEW_WEIGHT : 1.0F;

		this.block.rotate();
		try (GpuBufferSlice.MappedView mapped = this.block.currentBuffer().map(false, true)) {
			Std140Builder.intoBuffer(mapped.data())
					.putVec2(1.0F / width, 1.0F / height)
					.putVec2(1.0F / renderWidth, 1.0F / renderHeight)
					.putFloat(weight);
		}

		try (RenderPass pass = encoder.createRenderPass(() -> LABEL, this.history[into].view(),
				Optional.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform(UNIFORM_BLOCK, this.block.currentBuffer());
			pass.setVertexBuffer(0, quad.slice());
			pass.bindTexture(CURRENT, scene,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			// LINEAR on the history, because the reprojected coordinate lands between texels and a
			// nearest fetch there is a quarter pixel of jitter added to every frame of the fold.
			pass.bindTexture(HISTORY, this.history[this.current].view(),
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			// NEAREST on the vectors: a filtered vector across a silhouette is the average of two
			// surfaces moving differently, which points at neither of them.
			pass.bindTexture(VECTORS, vectors,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		this.current = into;
		this.primed = true;
		if (!this.announced) {
			this.announced = true;
			Vitrail.logger().info("Temporal fold armed: {}x{} folded from a {}x{} world, {} of each "
					+ "new frame, reprojected by the engine's motion vectors", width, height,
					renderWidth, renderHeight, NEW_WEIGHT);
		}

		return this.history[into].view();
	}

	void release() {
		if (this.history != null) {
			for (TargetSurface one : this.history) {
				if (one != null) {
					one.close();
				}
			}

			this.history = null;
		}

		if (this.block != null) {
			this.block.close();
			this.block = null;
		}

		this.current = 0;
		this.primed = false;
	}

	private boolean ensure(int width, int height) {
		if (width <= 0 || height <= 0 || this.refused) {
			return false;
		}

		if (this.history != null && this.history[0].width() == width
				&& this.history[0].height() == height) {
			return true;
		}

		try {
			release();
			this.history = new TargetSurface[SURFACES];
			this.history[0] = new TargetSurface(LABEL, FORMAT, false, width, height);
			this.history[1] = new TargetSurface(LABEL + ", the frame before", FORMAT, false, width,
					height);
			this.block = new MappableRingBuffer(() -> LABEL,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, BLOCK_BYTES);
		} catch (RuntimeException e) {
			release();
			this.refused = true;
			Vitrail.logger().error("Vitrail could not allocate the temporal fold's history at "
					+ "{}x{}, so it stays off for this session", width, height, e);

			return false;
		}

		return true;
	}

	private RenderPipeline pipeline(GpuDevice device) {
		if (this.refused) {
			return null;
		}

		if (this.pipeline == null) {
			this.pipeline = build();
		}

		RuntimeException thrown = null;
		try {
			if (device.precompilePipeline(this.pipeline, SOURCE).isValid()) {
				return this.pipeline;
			}
		} catch (GpuDeviceLossException e) {
			throw e;
		} catch (RuntimeException e) {
			// A stage the driver refuses throws out of precompilePipeline rather than coming back
			// invalid, and RenderScale.endWorld reaches this outside every catch of the frame.
			thrown = e;
		}

		release();
		this.refused = true;
		this.pipeline = null;
		Vitrail.logger().error("The temporal fold did not compile, so it stays off for this "
				+ "session", thrown);

		return null;
	}

	private static RenderPipeline build() {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/temporal"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER)
						.withSampler(CURRENT)
						.withSampler(HISTORY)
						.withSampler(VECTORS)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}
}
