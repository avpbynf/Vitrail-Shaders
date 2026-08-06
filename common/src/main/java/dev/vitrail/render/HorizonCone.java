package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;

import java.util.function.Supplier;

/**
 * The wall of sky the game has no geometry for, so that the pack's own sky program has a surface to
 * run on all the way down to the terrain.
 * <p>
 * <strong>What the game leaves open, and why nothing else could close it.</strong>
 * {@code SkyRenderer} builds two discs, one at y plus sixteen and one at y minus sixteen, each of
 * radius 512, and it draws the lower one only while the eye is under the world's horizon height. So
 * above sea level nothing of the game's covers the directions below atan(16/512), which is 1.79
 * degrees over the horizontal, and that boundary is a perfectly straight line across the picture
 * with an empty band under it. Two earlier corrections aimed at what filled that band, the colour
 * colortex0 is emptied to and the scene seed painting the game's own sky into it; both were right
 * and neither could close it, because what is missing there is not a colour but a surface. A shader
 * runs on fragments, and there were none.
 * <p>
 * <strong>The shape is Iris's, and the NOTICE says which parts.</strong> An inverted octagonal cone
 * from an apex under the camera out to a ring above it, on exactly the two planes the game leaves
 * the gap between.
 * <p>
 * <strong>It is not a piece of the sky in the sense {@link SkyDraw} means.</strong> The game names
 * no pass for it and no pack can ask for it, so it carries no element, no directive and no program
 * of its own: it is extra geometry drawn with the disc's, inside the pass the game opened for the
 * disc, and it lives or dies with it.
 * <p>
 * <strong>It claims the whole lower hemisphere against the scene seed, and that is the price.</strong>
 * Seen from the eye the surface runs from the apex, straight down, up to atan(16/radius) over the
 * horizontal, so every direction below the horizon is on it; and it shares the disc's pipeline, so
 * it writes the disc's mask over all of them. {@link SceneSeed} throws away the game's own picture
 * wherever that mask is set and the depth has not moved since the pack's geometry finished with it.
 * <p>
 * <strong>Nothing the game still draws there is lost by that on any place of the corpus, and the
 * reason is worth writing out because it is conditional rather than structural.</strong>
 * <p>
 * It holds because the seed is painted in the half of the frame this engine records at
 * {@code AfterOpaqueFeatures}. There the clouds and the weather cannot have been taken away, because
 * {@code LevelRenderer} adds {@code addCloudsPass} and {@code addWeatherPass} after the main pass
 * that posts it. What the seed does read is the solid feature phase, and everything submitted to it
 * moves the depth of the main target: most render types reach it through the
 * {@code SubmitNodeCollection} branches that ask {@code hasBlending()} first, and the three that
 * submit unconditionally are covered one by one - the flame and the leash inherit
 * {@code DepthStencilState.DEFAULT}, and the opaque particle group keeps the main target, since
 * {@code QuadParticleFeatureRenderer} only takes the particle target for a translucent group. The
 * armour decal is the single pipeline that does not blend and does not write depth, and it tests
 * {@code EQUAL}, so it lands only where something in its own phase already wrote one.
 * <p>
 * And the seed never falls outside that half. {@code PackChain.drawRange} paints a rank that lands
 * on the boundary between the two at the tail of the first one, and the plan never puts the world
 * past that boundary: the rank counts the begins and the prepares, and {@code deferredEnd()} counts
 * those and the deferred stage after them. A place shipping no deferred at all has the two equal,
 * and it is that equality the walk used to lose: on a half open interval alone the seed missed the
 * first half and led the second, at {@code AfterLevel}, by which time the clouds, the weather and
 * the particles are in the game's target and the mask would have taken them. Measured on the corpus
 * before it was fixed: one place in twenty five, Body Camera's overworld. The same state is
 * reachable on any pack through {@code passes=}, which removes passes from the running list exactly
 * as {@code terrain=off} removes the terrain, and it is no longer a state that costs anything.
 * <p>
 * <strong>The one thing that would be lost is the world itself</strong>, and only where the world
 * reaches the pack's colour target through that same seed rather than writing it. There the cone
 * would cut the ground out from under the picture, which is why it is drawn only for
 * {@link TerrainDraw.Mask#WRITTEN}.
 */
final class HorizonCone {

	/** The plane the game draws overhead, which is where the cone's ring closes. */
	private static final float TOP = 16.0F;

	/** The plane the game draws underfoot, which is where the cone's apex sits. */
	private static final float BOTTOM = -16.0F;

	/** Eight walls, so nine ring vertices, the last one repeating the first to close the fan. */
	private static final int SIDES = 8;

	/** The apex and the ring, which is what one draw of the fan spends. */
	private static final int VERTICES = SIDES + 2;

	/**
	 * The furthest out the ring is ever put, in blocks. Iris caps it at the same value and states
	 * the reason: past it, packs that rework the vanilla sky break on the cone.
	 */
	private static final int MAX_RADIUS = 256;

	private static final Supplier<String> LABEL = () -> "Vitrail horizon cone";

	private GpuBuffer buffer;

	/** The radius the buffer really holds, and nought while there is no buffer. */
	private int radius;

	/** Whether the first draw has been reported. */
	private boolean drew;

	/** And whether the refusal has been, which is the other thing worth saying exactly once. */
	private boolean refused;

	/**
	 * Builds the cone, and rebuilds it when the render distance has moved the ring.
	 * <p>
	 * Outside a render pass and never inside one. Filling a vertex buffer is a copy recorded into
	 * the very command buffer a pass would be recording into, and {@code CommandEncoder} refuses it
	 * outright while one is open.
	 */
	void update(GpuDevice device) {
		int wanted = radius();
		if (this.buffer != null && this.radius == wanted) {
			return;
		}

		release();
		this.radius = wanted;

		int bytes = VERTICES * DefaultVertexFormat.POSITION.getVertexSize();
		try (ByteBufferBuilder sink = ByteBufferBuilder.exactlySized(bytes)) {
			BufferBuilder builder =
					new BufferBuilder(sink, PrimitiveTopology.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
			build(builder, wanted);

			try (MeshData mesh = builder.buildOrThrow()) {
				this.buffer = device.createBuffer(LABEL, GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
			}
		}
	}

	/**
	 * Draws the cone in the pass the sky disc opened, with everything that pass already has bound:
	 * the pack's program, its attachments, its block and its samplers.
	 * <p>
	 * Only where the world's own opaque geometry marks the pixels it wrote, for the reason the class
	 * comment gives in full. An answer nobody has given yet counts as no, and costs the first frame
	 * of a world its cone: the geometry is read where the chunk renderer picks its shader, which is
	 * after the sky in the frame, so the first sky of a world is drawn before the world has said
	 * anything. That frame then looks exactly as it looked before this class existed, which is the
	 * one wrong answer that cannot make a picture worse.
	 *
	 * @param program what is drawing it, for the one line that says it happened
	 * @param world   what the world's opaque geometry does about the mask
	 */
	void draw(RenderPass pass, String program, TerrainDraw.Mask world) {
		if (this.buffer == null || world != TerrainDraw.Mask.WRITTEN) {
			// Said once, and only for the answer that is really an answer: a refusal while nothing
			// has been read yet is the first frame and settles itself. The band staying bare is not
			// a thing a reader can tell from a picture, so without this line the cone would be
			// missing for a reason nothing anywhere says.
			if (world == TerrainDraw.Mask.ABSENT && !this.refused) {
				this.refused = true;
				Vitrail.logger().info("The horizon cone is not drawn: the world's opaque geometry "
						+ "does not mark the pixels it wrote, so its picture reaches the pack's "
						+ "target through the scene seed, and a cone would cut it out of the seed");
			}

			return;
		}

		pass.setVertexBuffer(0, this.buffer.slice());
		pass.draw(VERTICES, 1, 0, 0);

		// Once, and it is the only proof this lot can leave outside the picture: the band it closes
		// is a thing nobody can measure from a log, but whether the geometry was recorded at all is
		// exactly what this says, and with which program.
		if (!this.drew) {
			this.drew = true;
			Vitrail.logger().info("The horizon cone is drawn with {}, {} walls out to {} blocks "
					+ "between y {} and y {}, which is the band the game's own sky leaves bare",
					program, SIDES, this.radius, BOTTOM, TOP);
		}
	}

	void release() {
		if (this.buffer != null) {
			this.buffer.close();
			this.buffer = null;
		}

		this.radius = 0;
		this.drew = false;
		this.refused = false;
	}

	/**
	 * How far out the ring stands: the render distance in blocks, capped. Read from the options
	 * every time rather than kept, since it is what tells a rebuild from a reuse.
	 */
	private static int radius() {
		Minecraft minecraft = Minecraft.getInstance();
		int blocks = minecraft == null ? MAX_RADIUS : minecraft.options.getEffectiveRenderDistance() * 16;

		return Math.min(blocks, MAX_RADIUS);
	}

	/**
	 * The fan: the apex under the camera, then the ring above it.
	 * <p>
	 * <strong>The ring is walked backwards, and that is not cosmetic.</strong> The pipeline this is
	 * drawn with culls back faces, as every geometry program here does and {@link GeometryProgram}
	 * builds them, so a wall counts only while its face is turned towards the camera. The camera
	 * stands inside this cone, so all eight walls have to face inwards; taken the other way round
	 * they would all face out, every one of them would be culled, and the band would stay exactly as
	 * bare as it is now with nothing on screen to say why. The direction is Iris's own and is
	 * reproduced rather than worked out.
	 */
	private static void build(VertexConsumer consumer, int radius) {
		consumer.addVertex(0.0F, BOTTOM, 0.0F);

		for (int i = 0; i <= SIDES; i++) {
			double angle = -i * Math.PI * 2.0 / SIDES;
			consumer.addVertex((float) (radius * Math.cos(angle)), TOP,
					(float) (radius * Math.sin(angle)));
		}
	}
}
