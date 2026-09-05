package dev.vitrail.render;

import dev.vitrail.glsl.EntityVertex;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The game's entity mesh with three elements of this engine's appended to it, as a format of its
 * own, and the one instant at which it comes into force.
 * <p>
 * <strong>A format APART, which is Iris's own shape</strong>
 * ({@code mixin/vertices/MixinBufferBuilder.iris$extendFormat:107-111} hands back
 * {@code IrisVertexFormats.ENTITY}, an object of its own built at
 * {@code vertices/IrisVertexFormats.java:49-60}). <strong>What this replaces is lengthening
 * {@code DefaultVertexFormat.ENTITY} itself</strong>, which was this engine's first answer and is
 * nobody else's: it drew no mob at all. Sodium's
 * {@code api/vertex/format/common/EntityVertex} declares {@code FORMAT = DefaultVertexFormat.ENTITY},
 * the same object, beside a {@code STRIDE = 36} its compiler folds into every caller;
 * {@code mixin/features/render/entity/CubeMixin} cancels the game's own {@code compile} for every
 * cuboid of a {@code ModelPart} and writes at that stride into a stack buffer; and
 * {@code mixin/core/render/immediate/consumer/BufferBuilderMixin.push} reserves
 * {@code count * this.vertexSize} and copies the source over it RAW whenever
 * {@code srcFormat == this.format}. Lengthening the game's field leaves that identity test passing
 * and copies fifty-six bytes a vertex out of a buffer carrying thirty-six. It was measured rather
 * than deduced: under the lengthened field, on one bench and with only the jar changing, the spider,
 * the enderman, the warden, the charged creeper and the player's own arm were all gone, and only the
 * enderman's particles were left hanging where its body should have been. All three readings are off
 * the bytecode of the shipped jars, 0.9.1 and 0.9.2-alpha.4, and not off a newer checkout.
 * <p>
 * A separate object fails that identity test, so the same {@code push} takes {@code copySlow}, which
 * asks {@code VertexSerializerRegistry} for a serializer from one format to the other.
 * {@link dev.vitrail.sodium.EntityMeshSerializer} is what answers, and it has to: left to itself the
 * registry generates one that copies the elements the two formats share and leaves the twenty bytes
 * after them at whatever the arena last held.
 * <p>
 * <strong>It buys a second thing for nothing, and Iris pays a mixin for it.</strong>
 * {@code BufferBuilder} picks its eleven argument fast path for an entity vertex by the same
 * identity test ({@code BufferBuilder:65}, {@code entityFormat = format == DefaultVertexFormat.ENTITY})
 * and writes it at literal offsets. Under a separate object that test is false, so the vertex falls
 * through to the eleven setters, every one of which begins at {@code beginVertex} where
 * {@code BufferBuilderMixin} writes. Iris disarms the same path with a {@code @Redirect} on
 * {@code fastFormat}, its own branch having no such identity test to fail.
 *
 * <h2>What becomes of a draw a loaded pack does not serve</h2>
 *
 * <strong>It keeps the game's own pipeline, and that pipeline is handed this format too.</strong>
 * Iris answers it in one method: {@code mixin/MixinRenderPipeline.iris$change} returns its own entity
 * format wherever a pipeline declares the game's, under the same gate as the buffer builder swap, so
 * the mesh and the binding move together and cannot disagree. {@link dev.vitrail.mixin.RenderPipelineMixin}
 * is that method here.
 * <p>
 * What makes it safe is that the three go LAST. The game's entity vertex stage declares the six
 * names the game's format spells and knows nothing of the three after them;
 * {@code IntermediaryShaderModule.rebind} walks the format's names and only counts the ones it finds
 * in the SPIR-V, while {@code VulkanRenderPipeline} counts every element of the format, so an
 * element the stage skips shifts the location of everything AFTER it and there has to be nothing
 * after them. That is already
 * this engine's answer for the chunk mesh, and {@code sodium/TerrainMesh} says it there for Sodium's
 * own shader.
 *
 * <h2>Why the answer has to settle rather than be read live</h2>
 *
 * Iris reads its gate live, at every buffer and at every call for a format, and can afford to: it
 * runs against a backend that rebuilds the vertex array from the pipeline's bindings at the draw.
 * Here {@code VulkanRenderPipeline.compile} bakes the stride into the compiled pipeline
 * ({@code VulkanRenderPipeline:99}, {@code .stride(bindings.getVertexSize())} over the bindings it
 * reads at {@code :82}), and {@code VulkanDevice.pipelineCache} is an identity map
 * that nothing empties but {@code ShaderManager.apply}. A live answer would therefore leave a mesh
 * built under one answer bound by a pipeline compiled under the other, which is the same wrong stride
 * by another road.
 * <p>
 * So there is one answer in force and {@link #settle()} is the only thing that moves it. Its
 * instant is the one {@code TerrainMesh.settle} already uses, the head of Sodium's
 * {@code initRenderer}, reached from the extract that consumes {@code LevelExtractor.allChanged}.
 * That instant is safe for moving a format answer and for map work, and destruction is neither: the
 * game's compiled entity pipelines are dropped from the device's cache and compiled again here, but
 * what leaves the cache is destroyed only at the next safe purge, and the body of {@code settle}
 * says why that split is load-bearing.
 * <p>
 * <strong>Asking is all a switch does</strong>, which is what makes the one place that writes
 * {@code EntityDraw.wanted} directly safe: an entity program that threw stops being offered at once
 * and the mesh goes on carrying what it carried, because the format follows this settled reading and
 * not that field.
 */
public final class EntityMesh {

	/**
	 * The format this engine binds for entity geometry, built once and never rebuilt: it holds no
	 * state, and Sodium hands every format a global id at construction
	 * ({@code mixin/core/render/VertexFormatMixin}), which its serializer registry keys its cache on.
	 */
	private static final VertexFormat FORMAT = extend();

	/** The answer in force, which only {@link #settle()} moves. */
	private static boolean carrying;

	/**
	 * Whether the answer has ever been said out loud, kept apart from {@link #carrying} because the
	 * two share their initial value: without it the first settle of a game nobody picked a pack in
	 * would find nothing changed and stay silent, which is the case that most needs the line.
	 */
	private static boolean said;

	/**
	 * Whether a rebuild has already been asked for and not yet answered, so that one load asks for
	 * one.
	 * <p>
	 * The two switches are set one after the other and nothing settles between them, so
	 * {@link #ask()} would find the same disagreement twice and print the same line twice for one
	 * load. Comparing against {@link #carrying} cannot see that: the answer in force is exactly what
	 * has not moved yet.
	 * <p>
	 * <strong>Cleared at every settle and whenever the answer comes back into agreement</strong>,
	 * which is two roads and not one. Clearing only where the answer MOVED would leave it standing
	 * after a settle that found nothing to do, and a flag stuck true swallows the next rebuild that
	 * is really owed. Nothing reaches that state today, the one writer that moves a switch without
	 * asking sitting behind a gate that is shut exactly then, but the guard costs a line and the
	 * reachability argument costs a paragraph nobody will re-derive.
	 */
	private static boolean rebuildAsked;

	private EntityMesh() {
	}

	/**
	 * The extended format itself, whether or not it is in force. What a pack's own pipeline is built
	 * with, a pack's programs only existing where a pack is loaded and the mesh carrying the element
	 * exactly then.
	 */
	public static VertexFormat format() {
		return FORMAT;
	}

	/** Whether the mesh really carries the identifiers as things stand. */
	public static boolean carrying() {
		return carrying;
	}

	/**
	 * What a binding declared as {@code declared} really is, which is this format wherever the game
	 * declares its own entity one and the mesh is carrying, and {@code declared} everywhere else.
	 * <p>
	 * By identity and not by equality, because that is the question: a pipeline of this engine's own
	 * already binds {@link #FORMAT} and must be handed straight back, and it is a format equal to
	 * neither. Null passes through, a pipeline being free to leave a binding unused.
	 */
	public static @Nullable VertexFormat binding(@Nullable VertexFormat declared) {
		@SuppressWarnings("ReferenceEquality")
		boolean entity = declared == DefaultVertexFormat.ENTITY;

		return carrying && entity ? FORMAT : declared;
	}

	/**
	 * What the two switches now ask for, which is not what the mesh carries until {@link #settle()}
	 * has been round.
	 * <p>
	 * Both and not one: the hand is drawn from this same mesh and has a switch of its own, so
	 * {@code entities=off hand=on} still needs the element on the vertex. The shadow map's casters
	 * come off it too and are already behind the entity switch.
	 */
	private static boolean asked() {
		return EntityDraw.wanted() || HandDraw.wanted();
	}

	/**
	 * Says that a switch has moved, and has the world rebuilt when that leaves the mesh owing
	 * something it is not carrying.
	 * <p>
	 * <strong>Asking is all it does</strong>, {@link #settle()} being the only thing that moves the
	 * answer. The door is the one {@code TerrainDraw.wanted} uses and F3+A uses,
	 * {@code LevelExtractor.allChanged}: it raises a flag the next extract consumes rather than
	 * tearing anything down inside the frame this is called from, and that extract is what reaches
	 * Sodium's {@code initRenderer} and therefore {@link #settle()}.
	 * <p>
	 * Silent before a world is joined, where nothing has been meshed and the settle that comes with
	 * the world answers it. Silent too where the answer already agrees, which is the ordinary reload:
	 * a settings file saved with neither switch touched owes no rebuild.
	 */
	static void ask() {
		boolean asked = asked();
		if (asked == carrying) {
			// Back in agreement, so whatever was asked for is owed no longer and the next real
			// disagreement gets its own line. This is the road a load takes when it turns one switch
			// off and the other leaves the answer where it was.
			rebuildAsked = false;

			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (rebuildAsked || minecraft == null || minecraft.level == null) {
			return;
		}

		Vitrail.logger().info("The pack draws the entities or the hand {} now, and the entity mesh "
				+ "only carries these while it does, so the world is built again: {}",
				asked ? "and did not" : "and did", EntityVertex.APPENDED);
		minecraft.levelExtractor.allChanged();
		rebuildAsked = true;
	}

	/**
	 * Takes the answer the two switches now ask for, at the one instant it is safe to change it.
	 */
	public static void settle() {
		boolean asked = asked();
		boolean moved = asked != carrying;
		// Before the way out and not after it: this runs whether or not the answer moved, and a settle
		// that found nothing to do is exactly the one that would leave the flag standing.
		rebuildAsked = false;
		if (said && !moved) {
			return;
		}

		said = true;
		carrying = asked;

		// ONLY where the answer really moved, and the two conditions are not one. The first settle of
		// a session has never said anything and must print, but a session nobody picked a pack in
		// settles false onto false and has nothing to drop: dropping anyway is a full recompile of
		// every static pipeline at the first world join, for a stride that did not change.
		//
		// Where it did move, a drop is owed: the game precompiles every static pipeline at every
		// resource load and caches it by identity (ShaderManager.apply, VulkanDevice.pipelineCache),
		// this backend bakes the stride in at compile, so the entity ones standing here carry the
		// other answer's stride. Left standing they read the mesh at the wrong offsets from now on,
		// which on screen is the hand and every fallback entity as triangles stretched across the
		// picture, and it does not heal: the cache is only emptied at a resource reload, so the
		// defect holds for the rest of the session. Measured before it was believed: hand=off on a
		// stock bench, one world join, and the artefacts stood until a pack toggle forced the
		// reload.
		//
		// AND NOTHING IS DESTROYED HERE ALL THE SAME, which was this engine's half of issue 111.
		// Destruction waits the device idle, and there is no instant of a running session where
		// that is safe: this backend records continuously, so at any positional hook something
		// already recorded still names what a purge frees. Freed here, on a pack load in a running
		// world, the device was lost seconds later with nothing in the log; moved to the head of
		// the frame, it took the boot's world join down instead. So the two halves of eviction are
		// split along what each one can bear. Leaving the map is map work, safe anywhere, and
		// enough on its own: what no lookup can answer with, no NEW draw binds. The compiled
		// pipelines wait in the mixin for clearPipelineCache, the game's one safe road, which
		// quiesces first and now frees them with the rest. And the recompile happens at once
		// rather than lazily at the next bind. The null asks the backend's default source, and it
		// is never read: a source is only consulted on a miss of the device's shader module cache
		// (VulkanDevice.getOrCompileShader, keyed by id, type and defines), the drop leaves that
		// cache standing, and the moved answer changes none of the three keys. The recompile
		// therefore reuses the very modules the last resource load compiled, core shaders a
		// resource pack replaced included, and only the pipeline around them takes the new stride.
		//
		// The GL backend is left exactly as it stood, debt included, and the old line still says
		// the answer moved there. Whether GL owes the same eviction is UNPROVEN in both
		// directions: its encoder rebuilds the vertex array from the live getter at every draw
		// (GlCommandEncoder), which reads as immune, but GlProgram.link binds attribute locations
		// once from the format it is handed, which reads as the same bake by another name. Nobody
		// has drawn the artefact there, and an eviction shipped unmeasured would be this fix's own
		// plausible-and-wrong.
		if (moved) {
			GpuDevice device = RenderSystem.getDevice();
			if (((GpuDeviceAccessor) device).vitrail$backend() instanceof StalePipelines stale) {
				List<RenderPipeline> dropped = stale.vitrail$dropEntityPipelines();
				for (RenderPipeline pipeline : dropped) {
					device.precompilePipeline(pipeline, null);
				}

				Vitrail.logger().info("{} entity pipelines of the game carried the previous "
						+ "stride: compiled again at the one now in force, the old ones set "
						+ "aside for the next safe purge", dropped.size());
			} else {
				Vitrail.logger().info("The game's entity pipelines were compiled under the "
						+ "previous answer and stand until the next resource reload; this "
						+ "backend takes no eviction, and what the stand costs it is unmeasured");
			}
		}

		if (!asked) {
			Vitrail.logger().info("Neither the entities nor the hand are drawn by the pack, so the "
					+ "entity mesh keeps the game's own format and carries none of {}",
					EntityVertex.APPENDED);

			return;
		}

		Vitrail.logger().info("The entity mesh carries {} bytes a vertex instead of {}, the "
				+ "difference being the three identifiers a pack tells one entity, block entity or "
				+ "held item apart by, then the middle of the sprite a polygon is mapped to and the "
				+ "tangent of that mapping: {}",
				FORMAT.getVertexSize(), DefaultVertexFormat.ENTITY.getVertexSize(),
				EntityVertex.APPENDED);
	}

	/**
	 * The game's entity format with three elements appended after its six.
	 * <p>
	 * Rebuilt element by element from the game's own rather than written out again, so that the six
	 * keep the names, the formats and the offsets the game gave them whatever the game does to them
	 * next. Every element of that format is laid out end to end, so appending after the last leaves
	 * the six exactly where they were.
	 * <p>
	 * The order and the widths are Iris's, {@code vertices/IrisVertexFormats.java:49-60}. The
	 * identifiers are four unsigned shorts of which three are read; the middle of the sprite is the
	 * pair of floats {@code UV0} already spells a corner in; the tangent is four normalised bytes,
	 * which is what the game spells {@code Normal} as and what the fourth component being a
	 * handedness needs. Each is a whole number of words wide, which a vertex has to stay.
	 */
	private static VertexFormat extend() {
		VertexFormat.Builder builder = VertexFormat.builder(DefaultVertexFormat.ENTITY.getStepRate());
		for (VertexFormatElement element : DefaultVertexFormat.ENTITY.getElements()) {
			builder.addAttribute(element.name(), element.format());
		}

		return builder.addAttribute(EntityVertex.IDENTIFIERS, GpuFormat.RGBA16_UINT)
				.addAttribute(EntityVertex.MID_TEX_COORD, GpuFormat.RG32_FLOAT)
				.addAttribute(EntityVertex.TANGENT, GpuFormat.RGBA8_SNORM)
				.build();
	}
}
