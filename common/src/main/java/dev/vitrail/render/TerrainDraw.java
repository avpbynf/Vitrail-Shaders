package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.OptionValue;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.file.Path;
import java.util.Map;

/**
 * The door the chunk renderer comes in by, and the one place a pack's terrain program is read.
 * <p>
 * It sits apart from the chain because it runs at a different moment and against different state.
 * The chain draws once the world is finished; this is asked for while the world is being drawn, from
 * where the chunk renderer picks its shader, and that is also where the frame boundary now hangs:
 * whichever of the two comes first opens the frame, or the terrain stage would be handed the
 * previous frame's matrices.
 * <p>
 * The program is read on demand rather than with the chain, and once. It costs one plan build of its
 * own, which is the price of not making every place that never draws terrain pay for a program only
 * this step uses, and the vertex format it is compiled against is only known here.
 */
public final class TerrainDraw {

	/**
	 * Off unless {@code options.txt} asks for it, and read again at every load. It is also what an
	 * error latches: a terrain program that threw once stops being offered rather than throwing again
	 * inside the pass the chunk renderer opened, where it would read as a failure of that renderer.
	 */
	private static volatile boolean wanted;

	private final PackChain owner;
	private final Path packPath;
	private final String place;
	private final Map<String, OptionValue> chosen;
	private final String profile;
	private final PackValues values;
	private final int load;

	private TerrainProgram program;
	private boolean read;

	TerrainDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
			String profile, PackValues values, int load) {
		this.owner = owner;
		this.packPath = packPath;
		this.place = place;
		this.chosen = Map.copyOf(chosen);
		this.profile = profile;
		this.values = values;
		this.load = load;
	}

	/** Whether a pack's terrain program takes over the opaque chunk pass, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/**
	 * The same, for the side that has to decide what the chunk mesh carries.
	 * <p>
	 * That decision is taken once for the whole run and never revisited, so this is read at a moment
	 * of somebody else's choosing rather than per frame, and a pack loaded later cannot move it.
	 */
	public static boolean asked() {
		return wanted;
	}

	/**
	 * Answers the pipeline to draw the opaque terrain with, reading and translating the pack's
	 * program the first time it is asked.
	 *
	 * @param format the chunk mesh format, handed in rather than looked up, because nothing in this
	 *               module is allowed to name Sodium
	 * @param atlas  the block atlas of the pass being drawn
	 * @return the pipeline to draw with, or null to leave the game's own shader alone
	 */
	public static RenderPipeline pipeline(VertexFormat format, GpuTextureView atlas) {
		TerrainDraw draw = PackChain.terrain();
		if (draw == null || !wanted) {
			return null;
		}

		try {
			return draw.prepare(format, atlas);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the terrain after an error", e);

			return null;
		}
	}

	/** The sampler the game configured for the block atlas, taken where the chunk pass begins. */
	public static void sampler(GpuSampler sampler) {
		TerrainDraw draw = PackChain.terrain();
		if (draw != null && draw.program != null) {
			draw.program.sampler(sampler);
		}
	}

	/**
	 * Binds the terrain program's block and samplers, inside the pass the chunk renderer opened.
	 * <p>
	 * Called for every chunk pass and not only ours, so the pipeline that is really bound decides.
	 * Binding into a pass drawing the renderer's own shader would be harmless, since the descriptor
	 * flush walks the layout of the bound pipeline, but it would also mean this engine had stopped
	 * knowing which of the two was drawing.
	 */
	public static void bind(RenderPass pass, RenderPipeline bound) {
		TerrainDraw draw = PackChain.terrain();
		if (draw != null && draw.program != null && draw.program.owns(bound)) {
			draw.program.bind(pass);
		}
	}

	private RenderPipeline prepare(VertexFormat format, GpuTextureView atlas) {
		if (!this.read) {
			this.read = true;
			if (TerrainProgram.carries(format)) {
				this.program = TerrainProgram.read(this.packPath, this.place, TerrainProgram.PROGRAM,
						this.chosen, this.profile, this.values, this.load, format).orElse(null);
			}
		}

		if (this.program == null) {
			return null;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}

		this.owner.beginFrame();

		return this.program.prepare(device, atlas);
	}

	/** The program itself once it has been read, for the decoded dump. Null until then. */
	TerrainProgram program() {
		return this.program;
	}

	/** Rotates the ring buffer. Called once the frame's terrain draw has been recorded. */
	void rotate() {
		if (this.program != null) {
			this.program.rotate();
		}
	}

	void release() {
		if (this.program != null) {
			this.program.release();
		}
	}
}
