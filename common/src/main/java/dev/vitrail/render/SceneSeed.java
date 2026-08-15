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

/**
 * Draws the game's opaque scene into the target the terrain would have written, standing in for
 * every gbuffers stage that does not run: the entities, the particles and the weather.
 * <p>
 * It is cut around the pack's own geometry rather than painted over it. The opaque and cutout chunk
 * passes write that target themselves, and so do the two pieces of the sky that write outright and
 * the opaque half of the entities, and every one of them records the depth it left as it goes, so
 * what lands here is the game's picture everywhere the pack answered for nothing. Without the cut
 * the two would fight and the game would win, because it is drawn second. The pieces that blend are
 * different again, the translucent chunk pass with them: they draw over what the others left and
 * claim no pixel of their own.
 * <p>
 * <strong>The cut is one comparison, and it is the mask against the world's depth as it stands.</strong>
 * The mask carries a depth and not a flag: what a program of the pack wrote into the depth
 * attachment, at the moment it wrote it. So a pixel nothing has been drawn over since compares
 * equal and is the pack's, and a pixel the game drew a feature onto compares closer and is the
 * game's to paint after all. That second case is the one a flag could not answer, and what it is
 * made of is every piece the game still draws for itself in front of a block: the eyes of a mob,
 * a beacon beam, a name plate, the hand.
 * <p>
 * <strong>Where the pack wrote nothing the mask carries a value outside zero to one</strong>, which
 * every real depth is in front of, so those pixels take the game's picture through the same
 * comparison and owe no test of their own. That is the whole of why the mask is a depth: the two
 * questions a flag needed - did the pack write here, and has anything moved closer since - are one
 * number and one test, and the depth the pack's geometry left no longer has to be copied into an
 * image of its own before the game's features overwrite it.
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
	private static final Identifier EMPTY_FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/seed_empty_fragment");

	private static final String SAMPLER = "InSampler";

	/** The depth the pack's own geometry left, and the value this whole class is cut against. */
	private static final String COVERAGE = "CoverageSampler";

	/** The world's depth as it stands, which by now carries the game's own features. */
	private static final String DEPTH = "DepthSampler";

	private static final String LABEL = "Vitrail scene seed";

	private static final String EMPTY_LABEL = "Vitrail scene seed gbuffer";

	/**
	 * Which way a depth grows towards the eye, taken from the one constant that holds the game's
	 * convention rather than written out again. The game rasterises reversed, so a fragment drawn in
	 * front of another has the GREATER value, and reading that off {@link ClipSpace#REVERSED} means
	 * the day the game stops reversing, this moves with it instead of quietly inverting the test.
	 */
	private static final String CLOSER = ClipSpace.REVERSED.z < 0.0F ? ">" : "<";

	/**
	 * How the mask says the pack wrote here at all, which is the sentinel of
	 * {@link ColorTargets#COVERAGE_EMPTY} told from a real depth. Written from the same constant as
	 * the sentinel itself, so the two cannot be moved apart: an empty pixel is on the far side of
	 * the depth range and every depth in it satisfies this.
	 */
	private static final String WRITTEN = ClipSpace.REVERSED.z < 0.0F ? ">= 0.0" : "<= 1.0";

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

	/**
	 * The cut, and the whole of it: the game's picture goes in wherever the world's depth stands in
	 * front of the depth the pack's geometry left.
	 * <p>
	 * Neither side is converted into the pack's window. The mask is filled by the fragment stage
	 * from the value it hands the depth attachment, and this reads that attachment back, so the two
	 * are the same number written by the same draw and a pixel nothing was drawn over compares
	 * exactly equal however the game encodes its depth.
	 */
	private static final String FRAGMENT = String.format(Locale.ROOT, """
			#version 460 core

			uniform sampler2D InSampler;
			uniform sampler2D CoverageSampler;
			uniform sampler2D DepthSampler;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				bool infront = texture(DepthSampler, ofTexCoord).r
						%s texture(CoverageSampler, ofTexCoord).r;
				if (!infront) {
					discard;
				}

				ofFragData0 = texture(InSampler, ofTexCoord);
			}
			""", CLOSER);

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

	private final ShaderSource source;

	/** Built once with one output per target emptied, and handed back at every compile. */
	private final String emptyFragment;

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

				return EMPTY_FRAGMENT_ID.equals(id) ? this.emptyFragment : null;
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
	 * <p>
	 * The mask is asked one thing more than the seed asks it, and it is the question the sentinel
	 * answers: whether the pack wrote this pixel at all. The seed does not need it, an empty pixel
	 * being one the game's picture goes into either way; here it is the difference between a pixel
	 * the seed painted OVER the pack's geometry, whose gbuffer no longer belongs with the colour,
	 * and one the pack never touched, whose targets hold a prepare's or a clear's and are none of
	 * this pass's business.
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

				in vec2 ofTexCoord;

				%s
				void main() {
					float mask = texture(CoverageSampler, ofTexCoord).r;
					bool mine = mask %s;
					bool infront = texture(DepthSampler, ofTexCoord).r %s mask;
					if (!mine || !infront) {
						discard;
					}
				%s
				}
				""", outputs, WRITTEN, CLOSER, empties);
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
	 * @param covered the mask, which is read and never judged: an image of the sentinel everywhere
	 *                is a mask that hides nothing and the seed then covers the target whole, which
	 *                is what the frames with no geometry of the pack's in them have to look like
	 * @param live    the world's depth as it stands, features included
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
		GpuTextureView into = targets.view(this.seed.target(), this.seed.side());
		if (quad == null || scene == null || covered == null || live == null || into == null) {
			return false;
		}

		// Loaded rather than cleared: the clears have already run, and the draw no longer covers the
		// target whole in any case.
		try (RenderPass pass = encoder.createRenderPass(() -> LABEL, into, Optional.empty())) {
			pass.setPipeline(this.pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			pass.bindTexture(SAMPLER, scene,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			// NEAREST on both, and it matters more here than anywhere: the mask is the size of the
			// screen, so a texel is a pixel and the answer is the one the geometry wrote, and the
			// two depths are compared for having moved. Filtered, either read would move by a
			// fraction of a texel along every silhouette in the picture, and the pack's own geometry
			// would be repainted along all of them.
			pass.bindTexture(COVERAGE, covered,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(DEPTH, live,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return !this.emptying || empty(encoder, quad, covered, live, targets);
	}

	/**
	 * Empties the rest of the gbuffer over the pixels the draw above painted on top of the pack's
	 * own geometry, which are the ones its cut let through where the mask holds a depth of its own.
	 *
	 * @return false when a target is missing or is no longer the size of the first one, which is a
	 *         frame in the middle of a resize. The gbuffer then keeps what it held for that frame,
	 *         where dropping one attachment is not open to this: the pipeline carries a state per
	 *         target and the pass refuses to bind against any other count
	 */
	private boolean empty(CommandEncoder encoder, GpuBuffer quad, GpuTextureView covered,
			GpuTextureView live, ColorTargets targets) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> EMPTY_LABEL);
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
			// NEAREST on both, and for the reason the draw above gives: a texel of the mask is a
			// pixel, and the two depths are compared for having moved.
			pass.bindTexture(COVERAGE, covered,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(DEPTH, live,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return true;
	}

}
