package dev.vitrail.render;

import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Draws the game's opaque scene into the target the terrain would have written, standing in for
 * every gbuffers stage that does not run: the sky, the entities, the particles and the weather.
 * <p>
 * It is cut around the pack's own terrain rather than painted over it. The opaque and cutout chunk
 * passes write that target themselves, and they mark every pixel they covered as they go, so what
 * lands here is the game's picture everywhere the pack answered for nothing. Without the cut the
 * two would fight and the game would win, because it is drawn second. The translucent pass is
 * different again: it draws after this and blends onto what the two of them left.
 * <p>
 * <strong>The mask alone cuts too much, and what it cuts is every entity standing in front of a
 * block.</strong> The game draws its opaque features after the chunk passes and into the same
 * picture, so a mob, an item frame or a block entity in front of a wall lands on a pixel the mask
 * says the pack answered for, and the cut throws the mob away with the wall. The mask is therefore
 * read against a depth taken the moment the pack's own geometry was finished with it: where the
 * depth has moved closer since, the game drew something in front, and that pixel is the game's to
 * paint after all. Where it has not, the pixel is the pack's and is cut as before. Nothing here
 * needs to know what was drawn, only that something was.
 * <p>
 * This is not a fallback and should not be read as one. The first draw buffer of the terrain pass
 * is, by the definition of the OptiFine model, where the world's colour ends up, so it is the one
 * place where putting the game's own picture back is the right answer rather than a guess. Which
 * target that is comes from the plan and is not always colortex0: Sildur's serves its terrain
 * through {@code gbuffers_textured}, whose draw buffers start at colortex4. The whole class goes
 * away the day the gbuffers run, and the mask with it.
 * <p>
 * A draw and not a copy. {@code copyTextureToTexture} ends up on {@code vkCmdCopyImage}, which
 * reinterprets bits instead of converting them, and the Java side only checks that both formats
 * carry a colour aspect. The main target is RGBA8_UNORM and colortex0 is RG11B10_FLOAT on most
 * packs; both are thirty two bits wide, so a copy passes every check and hands back nonsense.
 * <p>
 * What the seed cannot repair has to be said out loud rather than assumed: the scene it carries
 * is already tone mapped, already gamma corrected, and already has vanilla fog. A pack that
 * exposes automatically works on an image that was exposed once already. The picture is readable
 * and wrong, which is the most misleading shape a result can take, so nothing about a pass is
 * ever proved through it.
 */
final class SceneSeed {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/seed_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/seed_fragment");
	private static final Identifier KEEP_FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/seed_keep_fragment");

	private static final String SAMPLER = "InSampler";

	/** Where the pack's own opaque geometry has already written, and where this must not paint. */
	private static final String COVERAGE = "CoverageSampler";

	/** The world's depth as it stands, which by now carries the game's own features. */
	private static final String DEPTH = "DepthSampler";

	/** And as the pack's own geometry left it, before any of them was drawn. */
	private static final String KEPT = "KeptSampler";

	private static final Supplier<String> LABEL = () -> "Vitrail scene seed";

	private static final Supplier<String> KEEP_LABEL = () -> "Vitrail depth before the features";

	/** One float a texel, and no conversion: this is compared with the game's depth, not read as one. */
	private static final GpuFormat KEEP_FORMAT = GpuFormat.R32_FLOAT;

	/**
	 * Which way a depth grows towards the eye, taken from the one constant that holds the game's
	 * convention rather than written out again. The game rasterises reversed, so a fragment drawn in
	 * front of another has the GREATER value, and reading that off {@link ClipSpace#REVERSED} means
	 * the day the game stops reversing, this moves with it instead of quietly inverting the test.
	 */
	private static final String CLOSER = ClipSpace.REVERSED.z < 0.0F ? ">" : "<";

	/** Two triangles, the same quad the pass itself draws, which is why it is passed in. */
	private static final int VERTICES = 6;

	private static final String VERTEX = """
			#version 460 core

			in vec3 Position;
			in vec2 UV0;

			out vec2 ofTexCoord;

			void main() {
				ofTexCoord = UV0;
				gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	private static final String FRAGMENT = String.format(Locale.ROOT, """
			#version 460 core

			uniform sampler2D InSampler;
			uniform sampler2D CoverageSampler;
			uniform sampler2D DepthSampler;
			uniform sampler2D KeptSampler;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				bool mine = texture(CoverageSampler, ofTexCoord).r > 0.5;
				bool infront = texture(DepthSampler, ofTexCoord).r
						%s texture(KeptSampler, ofTexCoord).r;
				if (mine && !infront) {
					discard;
				}

				ofFragData0 = texture(InSampler, ofTexCoord);
			}
			""", CLOSER);

	/**
	 * Keeps the depth as it stands, unconverted and one float a texel.
	 * <p>
	 * Not turned into the pack's window on the way, unlike {@link PackDepth}: nobody reads this as a
	 * depth, it is only ever compared with the image it was copied from. Both sides then carry the
	 * value the same way, and a fragment nothing was drawn over compares exactly equal however the
	 * game encodes its depth.
	 */
	private static final String KEEP_FRAGMENT = """
			#version 460 core

			uniform sampler2D InSampler;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				ofFragData0 = vec4(texture(InSampler, ofTexCoord).r);
			}
			""";

	private final ChainPlan.Seed seed;
	private final RenderPipeline pipeline;
	private final RenderPipeline keep;
	private final ShaderSource source;

	/** The depth the pack's own geometry left, or null until a frame has taken one. */
	private TargetSurface kept;

	/**
	 * Whether the image above holds THIS frame's depth. Cleared at the frame boundary and set by a
	 * capture that really ran, which is the only pair of moments that makes it true.
	 * <p>
	 * A frame that could not capture must fall back to the mask alone rather than to the depth of the
	 * frame before. The image survives the boundary because it is a texture and nobody frees it every
	 * frame, and the previous frame's depth is the worst answer available here: the camera has moved
	 * since, so it differs at nearly every pixel of the screen, and the comparison then says that
	 * something was drawn in front of the pack's terrain everywhere at once. The seed repaints the
	 * whole target with the game's picture, which is the pack's geometry gone and nothing said.
	 */
	private boolean captured;

	private boolean reported;

	/**
	 * @param seed        which target the scene goes into and on which half, both taken from the
	 *                    geometry program it stands in for rather than assumed
	 * @param destination that target's format as the pack declared it. The caller is the one that
	 *                    checks the target exists: a place that has nowhere to put the scene draws
	 *                    no seed and says so, it does not refuse the pack
	 */
	SceneSeed(ChainPlan.Seed seed, GpuFormat destination) {
		this.seed = seed;
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				if (FRAGMENT_ID.equals(id)) {
					return FRAGMENT;
				}

				return KEEP_FRAGMENT_ID.equals(id) ? KEEP_FRAGMENT : null;
			}

			return VERTEX_ID.equals(id) ? VERTEX : null;
		};

		this.pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/scene_seed"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler(SAMPLER)
						.withSampler(COVERAGE)
						.withSampler(DEPTH)
						.withSampler(KEPT)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				// The format of the target as the pack declared it, not the one of the main
				// target: setting a pipeline whose colour state disagrees with the attachment
				// throws, and the message names both formats, which is the useful failure.
				.withColorTargetState(new ColorTargetState(Optional.empty(), destination,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();

		this.keep = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/seed_keep"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(KEEP_FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder().withSampler(SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), KEEP_FORMAT,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	/** Where the scene goes, which is the first draw buffer of the terrain program. */
	int target() {
		return this.seed.target();
	}

	/** The half that terrain program would have written, so that the chain reads what it wrote. */
	TargetSchedule.Side side() {
		return this.seed.side();
	}

	/** The geometry program this stands in for, for the log. */
	String from() {
		return this.seed.from();
	}

	/**
	 * Keeps the world's depth as the pack's own geometry left it, before the game draws a single
	 * feature over it. Must run on the render thread and outside any render pass.
	 * <p>
	 * The moment is the whole value of the image and it is not the moment anything else here is
	 * taken: {@link PackDepth} takes its opaque world once the features are drawn, because that is
	 * what a pack means by {@code depthtex1}. This one has to be older than they are, or the
	 * comparison it exists for compares a thing with itself.
	 *
	 * @param live the game's depth as it stands, which the caller has to take before the features
	 * @return false when nothing could be kept, in which case the cut falls back to the mask alone,
	 *         which is what it did before this image existed
	 */
	boolean capture(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView live,
			int width, int height) {
		this.captured = false;
		if (quad == null || live == null || width <= 0 || height <= 0
				|| !device.precompilePipeline(this.keep, this.source).isValid()) {
			return false;
		}

		if (this.kept == null) {
			this.kept = new TargetSurface("Vitrail depth before the features", KEEP_FORMAT, false,
					width, height);
		} else {
			this.kept.resize(width, height);
		}

		if (this.kept.view() == null) {
			return false;
		}

		// Loaded rather than cleared: the draw covers the image whole.
		try (RenderPass pass = encoder.createRenderPass(KEEP_LABEL, this.kept.view(), Optional.empty())) {
			pass.setPipeline(this.keep);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			// NEAREST, so one texel of the copy is one texel of the depth and the two compare as
			// the same number rather than as a neighbourhood of it.
			pass.bindTexture(SAMPLER, live,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		this.captured = true;

		return true;
	}

	/**
	 * Forgets the depth this frame kept, at the frame boundary and nowhere else, so that the next
	 * frame answers for itself. See {@link #captured} for what carrying it over would look like.
	 */
	void endFrame() {
		this.captured = false;
	}

	/** Called every frame: a resource reload empties the pipeline cache. */
	boolean prepare(GpuDevice device) {
		if (device.precompilePipeline(this.pipeline, this.source).isValid()) {
			return true;
		}

		if (!this.reported) {
			this.reported = true;
			Vitrail.logger().error("The scene seed did not compile, {} keeps its clear colour",
					TargetName.canonical(this.seed.target()));
		}

		return false;
	}

	/**
	 * Draws the scene over {@code into}, everywhere {@code covered} says the pack's own geometry has
	 * not already written. Only worth calling once {@link #prepare} has said the pipeline is usable.
	 *
	 * @param covered the mask, which is read and never judged: an image of nought everywhere is a
	 *                mask that hides nothing and the seed then covers the target whole, which is
	 *                what the frames with no terrain of the pack's in them have to look like
	 * @param live    the world's depth as it stands, features included. Given the kept image as
	 *                well when nothing kept one, so that the two compare equal everywhere and the
	 *                cut is the mask alone, which is what it was before either existed
	 * @return false when a side of the draw is missing, in which case the target keeps its clear
	 *         colour rather than holding half an image
	 */
	boolean draw(CommandEncoder encoder, GpuBuffer quad, GpuTextureView scene,
			GpuTextureView covered, GpuTextureView live, GpuTextureView into) {
		GpuTextureView before = this.captured && this.kept != null ? this.kept.view() : live;
		if (quad == null || scene == null || covered == null || live == null || before == null
				|| into == null) {
			return false;
		}

		// Loaded rather than cleared: the clears have already run, and the draw no longer covers the
		// target whole in any case.
		try (RenderPass pass = encoder.createRenderPass(LABEL, into, Optional.empty())) {
			pass.setPipeline(this.pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			pass.bindTexture(SAMPLER, scene,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			// NEAREST, and the mask is the size of the screen, so a texel is a pixel and the answer
			// is the one the geometry wrote. Filtered, the edge of every block would read as half
			// covered and the threshold would move it by half a pixel.
			pass.bindTexture(COVERAGE, covered,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			// NEAREST for the same reason again, and it matters more here than anywhere: the two
			// depths are compared for having moved, and a filtered read of either would move them
			// both by a fraction of a texel along every silhouette in the picture.
			pass.bindTexture(DEPTH, live,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(KEPT, before,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return true;
	}

	/**
	 * Frees the kept depth. A pipeline is not a resource this class owns: the compiled form lives
	 * in the device cache, which the game empties on its own at every resource reload.
	 */
	void release() {
		if (this.kept != null) {
			this.kept.close();
			this.kept = null;
		}

		this.captured = false;
	}
}
