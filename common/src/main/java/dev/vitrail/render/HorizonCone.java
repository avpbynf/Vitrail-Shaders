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
	 *
	 * @param program what is drawing it, for the one line that says it happened
	 */
	void draw(RenderPass pass, String program) {
		if (this.buffer == null) {
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
