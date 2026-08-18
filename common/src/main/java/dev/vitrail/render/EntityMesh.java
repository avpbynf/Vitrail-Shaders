package dev.vitrail.render;

import dev.vitrail.glsl.EntityVertex;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;

import org.jspecify.annotations.Nullable;

/**
 * The game's entity mesh with the three identifiers appended to it, as a format of this engine's
 * own, and the one instant at which it comes into force.
 * <p>
 * <strong>A format APART, and lengthening {@code DefaultVertexFormat.ENTITY} itself is what this
 * replaces.</strong> That is Iris's shape ({@code mixin/vertices/MixinBufferBuilder.iris$extendFormat}),
 * and here it is an obstacle rather than a preference. Sodium's
 * {@code api/vertex/format/common/EntityVertex} declares {@code FORMAT = DefaultVertexFormat.ENTITY},
 * the same object, beside a {@code STRIDE = 36} its compiler folds into every caller;
 * {@code mixin/features/render/entity/CubeMixin} cancels the game's own {@code compile} for every
 * cuboid of a {@code ModelPart} and writes at that stride into a stack buffer; and
 * {@code mixin/core/render/immediate/consumer/BufferBuilderMixin.push} reserves
 * {@code count * this.vertexSize} and copies the source over it RAW whenever
 * {@code srcFormat == this.format}. Lengthening the game's field leaves that identity test passing
 * and copies forty-four bytes a vertex out of a buffer carrying thirty-six. It was measured rather
 * than deduced: under the lengthened field, on one bench and with only the jar changing, the spider,
 * the enderman, the warden, the charged creeper and the player's own arm were all gone, and only the
 * enderman's particles were left hanging where its body should have been. All three readings are off
 * the bytecode of the 0.9.1 this compiles against and not off a newer checkout.
 * <p>
 * A separate object fails that identity test, so the same {@code push} takes {@code copySlow}, which
 * asks {@code VertexSerializerRegistry} for a serializer from one format to the other.
 * {@link dev.vitrail.sodium.EntityMeshSerializer} is what answers, and it has to: left to itself the
 * registry generates one that copies the elements the two formats share and leaves the eight bytes
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
 * What makes it safe is that the element goes LAST. The game's entity vertex stage declares the six
 * names the game's format spells and knows nothing of a seventh; {@code IntermediaryShaderModule.rebind}
 * walks the format's names and only counts the ones it finds in the SPIR-V, while
 * {@code VulkanRenderPipeline} counts every element of the format, so an element the stage skips
 * shifts the location of everything AFTER it and there has to be nothing after it. That is already
 * this engine's answer for the chunk mesh, and {@code sodium/TerrainMesh} says it there for Sodium's
 * own shader.
 *
 * <h2>Why the answer has to settle rather than be read live</h2>
 *
 * Iris reads its gate live, at every buffer and at every call for a format, and can afford to: it
 * runs against a backend that rebuilds the vertex array from the pipeline's bindings at the draw.
 * Here {@code VulkanRenderPipeline.compile} bakes the stride into the compiled pipeline
 * ({@code VulkanRenderPipeline:82-96}), and {@code VulkanDevice.pipelineCache} is an identity map
 * that nothing empties but {@code ShaderManager.apply}. A live answer would therefore leave a mesh
 * built under one answer bound by a pipeline compiled under the other, which is the same wrong stride
 * by another road.
 * <p>
 * So there is one answer in force, {@link #settle()} is the only thing that moves it, and it discards
 * the game's compiled pipelines on the way. Its instant is the one {@code TerrainMesh.settle} already
 * uses, the head of Sodium's {@code initRenderer}: it is reached from the extract that consumes
 * {@code LevelExtractor.allChanged}, which is after the previous frame was submitted and presented
 * and before this one has bound a single pipeline, so {@code clearPipelineCache} and the
 * {@code waitIdle} inside it have nothing in flight to tear down.
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
		Minecraft minecraft = Minecraft.getInstance();
		if (asked() == carrying || minecraft == null || minecraft.level == null) {
			return;
		}

		Vitrail.logger().info("The pack draws the entities or the hand {} now, and the entity mesh "
				+ "carries {} only while it does, so the world is built again",
				asked() ? "and did not" : "and did", EntityVertex.IDENTIFIERS);
		minecraft.levelExtractor.allChanged();
	}

	/**
	 * Takes the answer the two switches now ask for, at the one instant it is safe to change it.
	 */
	public static void settle() {
		boolean asked = asked();
		if (said && asked == carrying) {
			return;
		}

		said = true;
		carrying = asked;

		// The game precompiles every static pipeline at every resource load and caches it by
		// identity (ShaderManager.apply, VulkanDevice.pipelineCache), so the entity ones standing at
		// this instant were compiled under the answer that has just moved. Emptying the cache is what
		// ShaderManager itself does before it recompiles, and what is dropped here comes back on
		// demand through the same shader source the device was built with.
		GpuDevice device = RenderSystem.tryGetDevice();
		if (device != null) {
			device.clearPipelineCache();
		}

		if (!asked) {
			Vitrail.logger().info("Neither the entities nor the hand are drawn by the pack, so the "
					+ "entity mesh keeps the game's own format and carries no {}",
					EntityVertex.IDENTIFIERS);

			return;
		}

		Vitrail.logger().info("The entity mesh carries {} bytes a vertex instead of {}, the "
				+ "difference being {}, which is the three identifiers a pack tells one entity, block "
				+ "entity or held item apart by",
				FORMAT.getVertexSize(), DefaultVertexFormat.ENTITY.getVertexSize(),
				EntityVertex.IDENTIFIERS);
	}

	/**
	 * The game's entity format with one element appended after its six.
	 * <p>
	 * Rebuilt element by element from the game's own rather than written out again, so that the six
	 * keep the names, the formats and the offsets the game gave them whatever the game does to them
	 * next. Every element of that format is laid out end to end, so appending one after the last
	 * leaves the six exactly where they were.
	 * <p>
	 * Four lanes of which three are read, and unsigned: {@code EntityVertex.IDENTIFIERS} says why
	 * both, and four is in any case the only width that fits, a vertex having to be a whole number of
	 * words wide.
	 */
	private static VertexFormat extend() {
		VertexFormat.Builder builder = VertexFormat.builder(DefaultVertexFormat.ENTITY.getStepRate());
		for (VertexFormatElement element : DefaultVertexFormat.ENTITY.getElements()) {
			builder.addAttribute(element.name(), element.format());
		}

		return builder.addAttribute(EntityVertex.IDENTIFIERS, GpuFormat.RGBA16_UINT).build();
	}
}
