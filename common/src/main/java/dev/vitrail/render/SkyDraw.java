package dev.vitrail.render;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.file.Path;
import java.util.Map;

/**
 * The door the sky renderer comes in by, and the one place a pack's sky program is read.
 * <p>
 * It is {@link TerrainDraw} for another piece of geometry, and it runs earlier: the game draws the
 * sky before the world, so this is now what usually opens the frame. That is the design and not a
 * side effect, and {@link PackChain#beginFrame} says why the boundary hangs off whichever comes
 * first.
 * <p>
 * <strong>What this slice does not do.</strong> The disc is drawn into the pass the game opened,
 * with the one attachment the game gave it, exactly as a terrain program that gains nothing keeps
 * Sodium's pass. Where a pack's {@code DRAWBUFFERS} really want it is {@code ChainPlan.sky}'s
 * answer, measured out of game and not yet honoured here; until it is, the pack's sky colour reaches
 * the chain the way the game's own did, through the scene seed.
 */
public final class SkyDraw {

	/** Off unless {@code options.txt} asks otherwise, and read again at every load. */
	private static volatile boolean wanted;

	/** The program of the disc, which is the one element of the sky this slice draws. */
	private static final String DISC = "gbuffers_skybasic";

	private final PackChain owner;
	private final Path packPath;
	private final String place;
	private final Map<String, OptionValue> chosen;
	private final String profile;
	private final PackValues values;
	private final int load;
	private final TargetPlan chainTargets;
	private final ColorTargets targets;

	private SkyProgram disc;
	private boolean read;

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

	/** Whether a pack's own sky program takes over the game's, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/**
	 * Everything that has to happen before the sky renderer opens its pass: the program read, the
	 * pipeline compiled, the frame opened and this frame's block written.
	 *
	 * @return the pipeline to draw the disc with, or null to leave the game's own sky alone
	 */
	public static RenderPipeline disc() {
		SkyDraw draw = PackChain.sky();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (draw == null || !wanted || device == null) {
			return null;
		}

		try {
			return draw.prepare(device);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the sky after an error", e);

			return null;
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
		if (draw != null && draw.disc != null && draw.disc.owns(bound)) {
			draw.disc.bind(pass);
		}
	}

	private RenderPipeline prepare(GpuDevice device) {
		if (!this.read) {
			this.read = true;
			// POSITION and nothing else, which is what the disc's own buffer carries. The format is
			// named here rather than taken from the pass: the game builds the mesh once at startup
			// and the pass it is drawn in binds this one.
			VertexFormat format = DefaultVertexFormat.POSITION;
			this.disc = SkyProgram.read(this.packPath, this.place, DISC, "disc", this.chosen,
					this.profile, this.values, this.load, format, this.chainTargets, this.targets);
		}

		if (this.disc == null) {
			return null;
		}

		// The same two calls the terrain makes, and for the same reasons. The sky is drawn first in
		// the frame, so more often than not it is this that opens it; the second call is what makes
		// sure the colour targets a sky program samples exist before it reads them.
		this.owner.beginFrame();
		if (!this.owner.openTargets(device)) {
			return null;
		}

		return this.disc.prepare(device);
	}

	/** The programs once they have been read, for the decoded dump. Empty until then. */
	SkyProgram program() {
		return this.disc;
	}

	/** Rotates the ring buffers. Called once the frame's sky draws have been recorded. */
	void rotate() {
		if (this.disc != null) {
			this.disc.rotate();
		}
	}

	void release() {
		if (this.disc != null) {
			this.disc.release();
		}
	}
}
