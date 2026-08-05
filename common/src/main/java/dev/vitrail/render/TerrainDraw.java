package dev.vitrail.render;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

import org.joml.Matrix4f;

import java.nio.file.Path;
import java.util.Collection;
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
 * The programs are read on demand rather than with the chain, and all three at once the first time
 * any pass asks. That costs one plan build for the three, which is the price of not making every
 * place that never draws terrain pay for programs only this step uses, and the vertex format they
 * are compiled against is only known here.
 */
public final class TerrainDraw {

	/**
	 * Off unless {@code options.txt} asks for it, and read again at every load. It is also what an
	 * error latches: a terrain program that threw once stops being offered rather than throwing again
	 * inside the pass the chunk renderer opened, where it would read as a failure of that renderer.
	 */
	private static volatile boolean wanted;

	/**
	 * Off unless the options ask for it, and worth nothing without {@link #wanted}. It is also what
	 * a shadow program that threw latches, for the reason {@link #wanted} gives: the stage and not
	 * the pack, because a shadow program failing says nothing about the three the camera draws with.
	 */
	private static volatile boolean shadowWanted;

	/**
	 * Whether the renderer is drawing the shadow map rather than the world at this instant.
	 * <p>
	 * A flag and not an argument because the three doors below are the renderer's own calls and it
	 * knows nothing of a shadow: it is asked for the solid pass twice in one frame and only the
	 * caller can say which of the two it is. Set for the length of one draw and cleared in a finally,
	 * because a flag left standing would draw the world into the shadow map.
	 */
	private static boolean shadowing;

	private final PackChain owner;
	private final Path packPath;
	private final String place;
	private final Map<String, OptionValue> chosen;
	private final String profile;
	private final PackValues values;
	private final int load;
	private final ChainPlan plan;
	private final TargetPlan chainTargets;
	private final boolean chainRuns;
	private final ColorTargets targets;

	private Map<TerrainPass, TerrainProgram> programs = Map.of();
	private boolean read;

	TerrainDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
			String profile, PackValues values, int load, ChainPlan plan, TargetPlan chainTargets,
			boolean chainRuns, ColorTargets targets) {
		this.owner = owner;
		this.packPath = packPath;
		this.place = place;
		this.chosen = Map.copyOf(chosen);
		this.profile = profile;
		this.values = values;
		this.load = load;
		this.plan = plan;
		this.chainTargets = chainTargets;
		this.chainRuns = chainRuns;
		this.targets = targets;
	}

	/** Whether a pack's terrain program takes over the opaque chunk pass, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/** Whether the shadow map is drawn, from the loaded options. */
	static void shadowWanted(boolean asked) {
		shadowWanted = asked;
	}

	/**
	 * Whether a shadow pass may run at all: the pack's own geometry has to be drawing, or the map
	 * would be filled by the game's chunk shader, which writes the world's colours into it.
	 */
	public static boolean shadows() {
		return wanted && shadowWanted && PackChain.terrain() != null;
	}

	/**
	 * Takes the copy the pack reads as {@code shadowtex1}. The caller invokes it between the opaque
	 * halves of the shadow map and the translucent one, which is the only moment the two names mean
	 * different things, and outside any render pass: the renderer closes its own before returning.
	 */
	public static void copyShadowDepth() {
		TerrainDraw self = PackChain.terrain();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (self != null && device != null) {
			self.targets.shadow().copyWithoutTranslucents(device.createCommandEncoder());
		}
	}

	/**
	 * Opens the end-of-frame shadow stage: makes the map exist, empties it, and settles that its
	 * three programs will really be served, once and before any group is drawn into it. Must run
	 * outside any render pass, which the end of the frame is.
	 * <p>
	 * The map is cleared here and not with the colour targets, and that is the point of the stage
	 * running where it does. The stage draws at the very end of a frame, for the next one, so the
	 * map has to survive the frame boundary: the gbuffers read all frame long what the previous
	 * stage drew, and a clear where the frame opens would hand them an empty map every time.
	 * <p>
	 * Nothing here opens the frame itself. By this point the terrain or the chain has opened it,
	 * and calling {@code beginFrame} again would advance the value store a second time, which turns
	 * every {@code gbufferPrevious*} of the next frame into the current one.
	 */
	public static boolean openShadowStage() {
		TerrainDraw self = PackChain.terrain();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (self == null || device == null) {
			return false;
		}

		ShadowTargets shadow = self.targets.shadow();
		if (!shadows() || !self.shadowsServed()) {
			// A pack that serves no shadow program gets no shadow pass, which is Iris's rule, and
			// at the end of a frame it is also the only safe answer: with nothing of ours to hand
			// the renderer, the pass it opens for itself is the game's own target, and the stage
			// would paint the world over the finished image. The map is emptied rather than left
			// standing, so a program broken mid-session reads as no shadow and not as the last
			// map it ever drew, frozen. The stage switched off by the engine option takes the same
			// branch and for the same reason: the map is allocated whether or not the stage runs,
			// and an allocated map nothing ever empties hands the packs undefined memory as a
			// shadow, where this clear hands them the far plane.
			shadow.clear(device.createCommandEncoder());

			return false;
		}

		if (!shadow.ensure()) {
			return false;
		}

		shadow.clear(device.createCommandEncoder());

		// After the clear, so that a refusal below leaves the map emptied without a second clear of
		// its own, and before the stage is declared open, which is the whole point of the step.
		if (!self.shadowsPrepared(device)) {
			return false;
		}

		return shadow.depth() != null;
	}

	/**
	 * Compiles the three shadow programs and equips them, outside any render pass and before the
	 * stage is declared open.
	 * <p>
	 * {@link #shadowsServed} is a promise the first compilation can still break, because
	 * {@code broken} is raised by that compilation and it used to happen inside the pass the renderer
	 * had already opened. A refusal there is safe for a camera pass and is the opposite here: with
	 * nothing of ours handed back, Sodium opens its own pass on the target {@code vitrail$target}
	 * gave it, the game's own, and the shadow half repaints the whole opaque world over the finished
	 * image, coplanar and under the same reversed Z so nothing stops it. Asked here, the same
	 * refusal only closes the stage.
	 * <p>
	 * Everything it does is done again by the real prepare a few lines of the renderer later, and
	 * every bit of it is idempotent: {@code precompilePipeline} is a computeIfAbsent, the three
	 * constant textures and the ring buffer are made once, {@code announce} is latched, and the
	 * block is written again with the same values into the same buffer of the ring, which turns at
	 * the frame boundary and not here. The atlas is null because there is none to hand yet, and the
	 * real prepare puts it back well before anything is bound.
	 * <p>
	 * Called once {@link #shadowsServed} has said yes, so every shadow pass has a program; a broken
	 * precondition would throw and be caught below, which is the answer that branch wants anyway.
	 */
	private boolean shadowsPrepared(GpuDevice device) {
		for (TerrainPass pass : TerrainPass.values()) {
			if (!pass.shadow()) {
				continue;
			}

			TerrainProgram program = this.programs.get(pass);
			try {
				if (program.prepare(device, null) == null) {
					// The line this step exists for. Nothing on screen can say it: the artefact lasts
					// one frame, and a stage that never opens leaves no trace at all.
					Vitrail.logger().warn("{} refused to prepare, so the shadow stage does not open: "
							+ "with nothing of ours to hand the renderer, the pass it opens for itself "
							+ "is the game's own target and the stage would paint the world over the "
							+ "finished image", program.path());

					return false;
				}
			} catch (RuntimeException e) {
				shadowWanted = false;
				Vitrail.logger().error("Vitrail stopped drawing the shadow map after an error in "
						+ program.path(), e);

				return false;
			}
		}

		return true;
	}

	/**
	 * Whether every shadow pass has a program that can still be served. All or nothing: the three
	 * passes draw into one map, and a stage that drew the opaque half and refused the translucent
	 * one would read as a pack behaviour rather than as the refusal it is.
	 * <p>
	 * True is a promise about the programs as they stand and not about the ones that have yet to be
	 * compiled, which is what {@link #shadowsPrepared} settles before the stage opens.
	 */
	private boolean shadowsServed() {
		for (TerrainPass pass : TerrainPass.values()) {
			if (pass.shadow()) {
				TerrainProgram program = this.programs.get(pass);
				if (program == null || !program.servable()) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Draws one group of the shadow map, by running the caller back over the chunk renderer with the
	 * flag set. The caller is the only side that can name a Sodium pass, which is why the draw
	 * arrives as a runnable rather than this module reaching for one.
	 * <p>
	 * The draw is refused outright when there is no map. That refusal is the whole safety of this
	 * step: with the flag set and no map to draw into, the descriptor would come back null, the
	 * renderer would open its own pass on the game's target, and the pack's shadow program would
	 * paint the screen with whatever it writes.
	 */
	public static void shadowPass(Runnable draw) {
		TerrainDraw self = PackChain.terrain();
		if (self == null || !shadows() || self.targets.shadow().depth() == null) {
			return;
		}

		shadowing = true;
		try {
			draw.run();
		} finally {
			shadowing = false;
		}
	}

	/**
	 * The matrix that culls the world for the light, the shadow pair multiplied through, or null
	 * when no pack is drawing. This frame's pair, which is also the pair the map is drawn with.
	 */
	public static Matrix4f shadowFrustum(Matrix4f dest) {
		TerrainDraw self = PackChain.terrain();

		return self == null ? null : self.values.shadowFrustum(dest);
	}

	/** Whether the renderer is drawing the shadow map at this instant, for the loader side. */
	public static boolean drawingShadow() {
		return shadowing;
	}

	/**
	 * Which pass a call from the renderer really means, given where in the frame it arrives. Null
	 * when the shadow stage has no counterpart for it, and then the renderer keeps its own shader.
	 */
	private static TerrainPass drawn(TerrainPass pass) {
		return shadowing ? pass.inShadow() : pass;
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
	 * Answers the pipeline to draw one chunk pass with, reading and translating the pack's programs
	 * the first time it is asked.
	 *
	 * @param pass   which of the three passes is being drawn, named in this engine's own terms
	 *               because nothing in this module is allowed to name Sodium
	 * @param format the chunk mesh format, handed in rather than looked up, for the same reason
	 * @param atlas  the block atlas of the pass being drawn
	 * @return the pipeline to draw with, or null to leave the game's own shader alone
	 */
	public static RenderPipeline pipeline(TerrainPass pass, VertexFormat format,
			GpuTextureView atlas) {
		TerrainDraw draw = PackChain.terrain();
		TerrainPass drawn = drawn(pass);
		if (draw == null || !wanted || drawn == null) {
			return null;
		}

		try {
			return draw.prepare(drawn, format, atlas);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the terrain after an error", e);

			return null;
		}
	}

	/** The sampler the game configured for the block atlas, taken where a chunk pass begins. */
	public static void sampler(GpuSampler sampler) {
		TerrainDraw draw = PackChain.terrain();
		if (draw != null) {
			draw.programs.values().forEach(program -> program.sampler(sampler));
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
		if (draw == null) {
			return;
		}

		for (TerrainProgram program : draw.programs.values()) {
			if (program.owns(bound)) {
				program.bind(pass);
				return;
			}
		}
	}

	/**
	 * The render pass one chunk pass wants opened, or null to leave the chunk renderer's own alone.
	 * <p>
	 * Asked for every chunk pass and not only ours, so the pass being drawn decides. A program that
	 * writes one draw buffer never answers: it wants exactly the pass Sodium was going to open, and
	 * building an identical one of our own would only be a way of getting it wrong later.
	 */
	public static RenderPassDescriptor descriptor(TerrainPass pass, GpuTextureView colour,
			GpuTextureView depth) {
		TerrainDraw draw = PackChain.terrain();
		TerrainPass drawn = drawn(pass);
		if (draw == null || !wanted || drawn == null) {
			return null;
		}

		TerrainProgram program = draw.programs.get(drawn);

		return program == null ? null : program.descriptor(colour, depth);
	}

	private RenderPipeline prepare(TerrainPass pass, VertexFormat format, GpuTextureView atlas) {
		if (!this.read) {
			this.read = true;
			if (TerrainProgram.carries(format)) {
				this.programs = TerrainProgram.read(this.packPath, this.place, this.chosen,
						this.profile, this.values, this.load, format, this.plan, this.chainTargets,
						this.chainRuns, this.targets);
			}
		}

		TerrainProgram program = this.programs.get(pass);
		if (program == null) {
			return null;
		}

		GpuDevice device = RenderSystem.tryGetDevice();
		if (device == null) {
			return null;
		}

		// Not during the shadow stage, and that is not an optimisation. The stage runs once the
		// chain has closed the frame, so the per frame guards are down again: opening here would
		// advance the value store a second time, which turns every gbufferPrevious* of the next
		// frame into the current one, and clear the colour targets over what the chain just wrote.
		// Everything these two calls provide, the stage already has: the values were advanced when
		// this frame opened, and the map is ensured and emptied by openShadowStage.
		if (!shadowing) {
			this.owner.beginFrame();

			// Before the pipeline and before the pass, which is the whole point: the clears belong
			// ahead of the world now that something writes the pack's targets during it. And a frame
			// where the targets cannot be opened keeps the game's own shader outright: the pipeline
			// carries one colour state per attachment the descriptor would have named, and Sodium's
			// own pass, the only one left to bind it into, carries exactly one.
			if (!this.owner.openTargets(device)) {
				return null;
			}
		}

		return program.prepare(device, atlas);
	}

	/** The programs once they have been read, for the decoded dump. Empty until then. */
	Collection<TerrainProgram> programs() {
		return this.programs.values();
	}

	/** Rotates the ring buffers. Called once the frame's terrain draws have been recorded. */
	void rotate() {
		this.programs.values().forEach(TerrainProgram::rotate);
	}

	void release() {
		this.programs.values().forEach(TerrainProgram::release);
	}
}
