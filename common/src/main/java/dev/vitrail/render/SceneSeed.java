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
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Draws the game's opaque scene into the target the terrain would have written, standing in for
 * every gbuffers stage that does not run: the entities, the particles and the weather.
 * <p>
 * It is cut around the pack's own geometry rather than painted over it. The opaque and cutout chunk
 * passes write that target themselves, and so do the two pieces of the sky that write outright, and
 * every one of them marks the pixels it covered as it goes, so what lands here is the game's picture
 * everywhere the pack answered for nothing. Without the cut the two would fight and the game would
 * win, because it is drawn second. The pieces that blend are different again, the translucent chunk
 * pass with them: they draw over what the others left and claim no pixel of their own.
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
 * <strong>The other draw buffers of that program are emptied wherever the scene lands on top of
 * it, or the pixel carries half a gbuffer.</strong> A gbuffers program fills all of its targets in
 * one draw, so a pixel it touched carries a set of targets that agree with one another. The seed
 * used to write the first of them alone, and where the mask is set and the game drew in front - a
 * mob standing against a wall - the colour became the mob's while the normal, the specular and the
 * lightmap stayed the wall's, and the deferred stage lit the one with the other. Those are the only
 * pixels emptied, and the reason for the narrowness is the same one: everywhere else the pack's own
 * geometry wrote nothing at all into those targets, and whatever is there is a prepare's or the
 * clear's and none of the seed's business.
 * <p>
 * It is a second draw and not a second output of the first, because those two sets of pixels are
 * not the same set: the scene goes in wherever the pack did not answer, and the emptying only where
 * it did and was covered. One pass can discard a fragment but it cannot write some of its
 * attachments and not others.
 * <p>
 * <strong>What goes into them is the target's own clear colour, and only where that value is really
 * what the pack means by an empty pixel.</strong> Three cases where it is not are left alone
 * instead: colortex0 with no colour of its own, which the renderer starts at the fog of the frame;
 * a target the pack keeps between frames, whose empty is last frame's picture; and an integer
 * format, which a {@code vec4} output does not write at all.
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
	private static final Identifier EMPTY_FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/seed_empty_fragment");

	private static final String SAMPLER = "InSampler";

	/** Where the pack's own opaque geometry has already written, and where this must not paint. */
	private static final String COVERAGE = "CoverageSampler";

	/** The world's depth as it stands, which by now carries the game's own features. */
	private static final String DEPTH = "DepthSampler";

	/** And as the pack's own geometry left it, before any of them was drawn. */
	private static final String KEPT = "KeptSampler";

	private static final Supplier<String> LABEL = () -> "Vitrail scene seed";

	private static final Supplier<String> EMPTY_LABEL = () -> "Vitrail scene seed gbuffer";

	private static final Supplier<String> KEEP_LABEL = () -> "Vitrail depth before the features";

	/**
	 * The reason a frame kept no depth when nothing else took the blame, which is the reason worth
	 * telling apart from the rest: the moment came and went without this class being asked at all,
	 * so what to look at is the hook at AfterOpaqueBlocks rather than anything here.
	 */
	private static final String NEVER_ASKED = "nothing asked for it before the game's features";

	/** How many times the cut may change answer before the log stops following it. */
	private static final int CHANGES = 8;

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

	/**
	 * One draw buffer of the geometry program the seed stands in for, past the first, that the seed
	 * can empty: where it goes, the format its target was really allocated as, and what the pack
	 * means by nothing having been drawn there.
	 *
	 * @param empty the target's own clear colour, read off the plan. The draw buffers whose empty is
	 *              not that value are not made into one of these at all; the class comment says
	 *              which those are and why
	 */
	record Extra(int target, TargetSchedule.Side side, GpuFormat format, Vector4fc empty) {
	}

	private final ChainPlan.Seed seed;

	/**
	 * The draw buffers past the first that this can empty, in the order the program declared them
	 * and with the ones it cannot answer for already left out. Empty is the ordinary answer for a
	 * pack whose terrain writes one target, and then no second pass is built at all.
	 */
	private final List<Extra> extras;

	private final RenderPipeline pipeline;

	/** The pass that empties the rest of the gbuffer, or null when there is none to empty. */
	private final RenderPipeline empties;

	private final RenderPipeline keep;
	private final ShaderSource source;

	/** Built once with one output per target emptied, and handed back at every compile. */
	private final String emptyFragment;

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

	/** Which of the two cuts was last said, whether anything has been said, and how often. */
	private boolean cutWithKept;
	private boolean said;
	private int changes;

	/** Why this frame kept no depth, in the log's own words, or null when it kept one. */
	private String refusal = NEVER_ASKED;

	/** Whether {@link #empties} compiled, asked again every frame like the seed's own pipeline. */
	private boolean emptying;

	private boolean reported;
	private boolean saidNoEmptying;

	/**
	 * @param seed        which target the scene goes into and on which half, both taken from the
	 *                    geometry program it stands in for rather than assumed
	 * @param destination that target's format as the pack declared it. The caller is the one that
	 *                    checks the target exists: a place that has nowhere to put the scene draws
	 *                    no seed and says so, it does not refuse the pack
	 * @param extras      the draw buffers of that program past the first that this may empty, in the
	 *                    program's own order and with the ones it may not already left out. Empty
	 *                    where there is nothing to empty, and then the seed is the one pass it was
	 */
	SceneSeed(ChainPlan.Seed seed, GpuFormat destination, List<Extra> extras) {
		this.seed = seed;
		this.extras = List.copyOf(extras);
		this.emptyFragment = this.extras.isEmpty() ? null : emptyFragmentOf(this.extras);
		this.source = (id, type) -> {
			if (type == ShaderType.FRAGMENT) {
				if (FRAGMENT_ID.equals(id)) {
					return FRAGMENT;
				}

				if (EMPTY_FRAGMENT_ID.equals(id)) {
					return this.emptyFragment;
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

		this.empties = this.extras.isEmpty() ? null : emptiesPipeline(this.extras);

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

	/**
	 * The pass that empties the rest of the gbuffer: one attachment per target, in the order the
	 * program declared them, and none of the pack's blending. A target either kept what the pack's
	 * geometry left or it did not.
	 */
	private static RenderPipeline emptiesPipeline(List<Extra> extras) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/seed_empty"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(EMPTY_FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler(COVERAGE)
						.withSampler(DEPTH)
						.withSampler(KEPT)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false);

		// By slot and never by append: the builder holds the states in an array and the argumentless
		// form writes slot nought every time, so three targets would leave one state and a pipeline
		// the pass refuses to bind, by name and in the middle of the world.
		for (int slot = 0; slot < extras.size(); slot++) {
			builder.withColorTargetState(slot, new ColorTargetState(Optional.empty(),
					extras.get(slot).format(), ColorTargetState.WRITE_ALL));
		}

		return builder.build();
	}

	/**
	 * Its fragment stage: the cut of the seed turned the other way round, so that what it writes is
	 * exactly what the seed painted over, and one output an emptied target.
	 */
	private static String emptyFragmentOf(List<Extra> extras) {
		StringBuilder outputs = new StringBuilder();
		StringBuilder empties = new StringBuilder();
		for (int slot = 0; slot < extras.size(); slot++) {
			outputs.append("layout(location = ").append(slot).append(") out vec4 ofFragData")
					.append(slot).append(";\n");
			empties.append("\n\tofFragData").append(slot).append(" = ")
					.append(literal(extras.get(slot).empty())).append(';');
		}

		return String.format(Locale.ROOT, """
				#version 460 core

				uniform sampler2D CoverageSampler;
				uniform sampler2D DepthSampler;
				uniform sampler2D KeptSampler;

				in vec2 ofTexCoord;

				%s
				void main() {
					bool mine = texture(CoverageSampler, ofTexCoord).r > 0.5;
					bool infront = texture(DepthSampler, ofTexCoord).r
							%s texture(KeptSampler, ofTexCoord).r;
					if (!mine || !infront) {
						discard;
					}
				%s
				}
				""", outputs, CLOSER, empties);
	}

	/**
	 * One clear colour as a GLSL literal, {@code vec4(0.0, 0.0, 0.0, 1.0)}, printed exactly rather
	 * than rounded: it is the pack's own value and the target it goes into is often a float one.
	 */
	private static String literal(Vector4fc colour) {
		return String.format(Locale.ROOT, "vec4(%s, %s, %s, %s)", colour.x(), colour.y(), colour.z(),
				colour.w());
	}

	/** The draw buffers past the first that the seed really empties, for the log. In pack order. */
	List<Integer> emptied() {
		return this.extras.stream().map(Extra::target).toList();
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
		if (live == null) {
			this.refusal = "the game's main target carries no depth";

			return false;
		}

		if (quad == null || width <= 0 || height <= 0) {
			this.refusal = "there was nothing to draw the copy with, at " + width + "x" + height;

			return false;
		}

		if (!device.precompilePipeline(this.keep, this.source).isValid()) {
			this.refusal = "the copy did not compile";

			return false;
		}

		if (this.kept == null) {
			this.kept = new TargetSurface("Vitrail depth before the features", KEEP_FORMAT, false,
					width, height);
		} else {
			this.kept.resize(width, height);
		}

		if (this.kept.view() == null) {
			this.refusal = "the image to keep it in could not be allocated";

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
		this.refusal = null;

		return true;
	}

	/**
	 * Forgets the depth this frame kept, at the frame boundary and nowhere else, so that the next
	 * frame answers for itself. See {@link #captured} for what carrying it over would look like.
	 * <p>
	 * The reason goes back to the one that costs nothing to name: a frame in which the capture was
	 * never even reached. Every other reason is written where it happens, so the one left standing
	 * here is the one nothing wrote.
	 */
	void endFrame() {
		this.captured = false;
		this.refusal = NEVER_ASKED;
	}

	/** Called every frame: a resource reload empties the pipeline cache. */
	boolean prepare(GpuDevice device) {
		if (!device.precompilePipeline(this.pipeline, this.source).isValid()) {
			if (!this.reported) {
				this.reported = true;
				Vitrail.logger().error("The scene seed did not compile, {} keeps its clear colour",
						TargetName.canonical(this.seed.target()));
			}

			return false;
		}

		this.emptying = this.empties != null
				&& device.precompilePipeline(this.empties, this.source).isValid();

		// The scene still goes in when the second pass will not compile: half a repair is what the
		// seed did before that pass existed and it is worth more than no picture at all. Said all
		// the same, because the gbuffer it leaves behind is what the pass is there for and nothing
		// on screen says so.
		if (this.empties != null && !this.emptying && !this.saidNoEmptying) {
			this.saidNoEmptying = true;
			Vitrail.logger().error("The scene seed's gbuffer pass did not compile, so {} keep what "
					+ "the pack's own terrain wrote where the game drew in front of it, and the "
					+ "deferred stage lights the game's colour with them",
					emptied().stream().map(TargetName::canonical).toList());
		}

		return true;
	}

	/**
	 * Draws the scene over the first of the geometry program's draw buffers, everywhere
	 * {@code covered} says the pack's own geometry has not already written, and empties the rest of
	 * them where it landed on top of that geometry. Only worth calling once {@link #prepare} has
	 * said the pipeline is usable.
	 *
	 * @param covered the mask, which is read and never judged: an image of nought everywhere is a
	 *                mask that hides nothing and the seed then covers the target whole, which is
	 *                what the frames with no terrain of the pack's in them have to look like
	 * @param live    the world's depth as it stands, features included. Given the kept image as
	 *                well when nothing kept one, so that the two compare equal everywhere and the
	 *                cut is the mask alone, which is what it was before either existed
	 * @param targets where the pack's colour targets are looked up, every frame and never held: a
	 *                resize replaces the images and keeping one across it draws into a texture that
	 *                has been closed
	 * @return false when a side of either draw is missing. The two are not the same refusal: the
	 *         scene draw is settled before anything is recorded, so a false from it leaves the
	 *         target on its clear colour, while the emptying runs after the scene has been recorded
	 *         and a false from it means the scene is in and the rest of the gbuffer still holds what
	 *         the block behind wrote. No caller reads this today
	 */
	boolean draw(CommandEncoder encoder, GpuBuffer quad, GpuTextureView scene,
			GpuTextureView covered, GpuTextureView live, ColorTargets targets) {
		boolean held = this.captured && this.kept != null;
		GpuTextureView before = held ? this.kept.view() : live;
		GpuTextureView into = targets.view(this.seed.target(), this.seed.side());
		if (quad == null || scene == null || covered == null || live == null || before == null
				|| into == null) {
			return false;
		}

		say(held);

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

		return !this.emptying || empty(encoder, quad, covered, live, before, targets);
	}

	/**
	 * Empties the rest of the gbuffer over the pixels the draw above painted on top of the pack's
	 * own geometry, which are the ones its cut let through with the mask set.
	 * <p>
	 * Nothing at all is written when the seed was cut against the mask alone: {@code infront} is
	 * then false at every pixel, so this discards the whole screen. That is right rather than a gap.
	 * The seed threw away every one of those pixels in that mode, the pack's terrain still shows
	 * there, and its gbuffer is the one that belongs with it.
	 *
	 * @return false when a target is missing or is no longer the size of the first one, which is a
	 *         frame in the middle of a resize. The gbuffer then keeps what it held for that frame,
	 *         where dropping one attachment is not open to this: the pipeline carries a state per
	 *         target and the pass refuses to bind against any other count
	 */
	private boolean empty(CommandEncoder encoder, GpuBuffer quad, GpuTextureView covered,
			GpuTextureView live, GpuTextureView before, ColorTargets targets) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(EMPTY_LABEL);
		int width = 0;
		int height = 0;
		for (Extra extra : this.extras) {
			GpuTextureView view = targets.view(extra.target(), extra.side());
			if (view == null) {
				return false;
			}

			if (width == 0) {
				width = view.getWidth(0);
				height = view.getHeight(0);
			} else if (view.getWidth(0) != width || view.getHeight(0) != height) {
				return false;
			}

			descriptor.withColorAttachment(view);
		}

		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));

		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setPipeline(this.empties);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			// NEAREST on all three, and for the reasons the draw above gives: a texel of the mask is
			// a pixel, and the two depths are compared for having moved.
			pass.bindTexture(COVERAGE, covered,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(DEPTH, live,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(KEPT, before,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return true;
	}

	/**
	 * Says which of the two cuts a frame drew with, the first time and at every change of answer
	 * afterwards. A steady session therefore says it once.
	 * <p>
	 * <strong>Neither answer shows on screen as itself, and they are a whole defect apart.</strong>
	 * Cut against the kept depth, everything the game drew in front of the pack's terrain comes back
	 * around it; cut against the mask alone, every villager, every item frame and every block entity
	 * standing in front of a block is thrown away with the block, and what the player sees is not a
	 * cut but an empty village. <strong>Worse, and differently, once an entity is drawn with the
	 * pack's own program</strong>: that entity has written the pack's other targets already, so what
	 * the fallback throws away is its colour alone, and the normals and the material it left behind
	 * are lit over the wall it stood in front of.
	 * The fallback was silent, so a frame that took it read exactly like a
	 * frame that never had the depth in the first place, and there was no way to tell the two apart
	 * from the picture. This is the line that tells them apart.
	 * <p>
	 * Bounded, because the answer changing every frame is a real possibility and a line a frame at
	 * sixty frames a second is a log nobody can read and a game nobody can play. The bound is said
	 * when it is reached, so that a reader is never left thinking the answer settled.
	 */
	private void say(boolean held) {
		if (this.said && held == this.cutWithKept) {
			return;
		}

		this.said = true;
		this.cutWithKept = held;
		if (this.changes++ >= CHANGES) {
			if (this.changes == CHANGES + 1) {
				Vitrail.logger().warn("The scene seed has changed which cut it draws with {} times, so "
						+ "it stops saying: the answer is not settling and the first lines above say "
						+ "what the two are", CHANGES);
			}

			return;
		}

		if (held) {
			Vitrail.logger().info("The scene seed is cut against the depth the pack's own geometry left,"
					+ " so what the game drew in front of it is kept");
		} else {
			Vitrail.logger().warn("The scene seed is cut against the coverage mask alone, because {}: "
					+ "every entity standing in front of the pack's terrain is thrown away with the "
					+ "terrain", this.refusal);
		}
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
