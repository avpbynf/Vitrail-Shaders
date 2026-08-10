package dev.vitrail.render;

import dev.vitrail.glsl.PackProgram;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The door the cloud renderer comes in by, and the one place a pack's {@code gbuffers_clouds} is
 * read.
 * <p>
 * It is {@link SkyDraw} for the one piece of the sky the sky renderer has nothing to do with, and
 * the differences from it are all consequences of one fact: <strong>the clouds have no mesh.</strong>
 * {@code CloudRenderer} fills a texel buffer with three bytes a face and draws six indices a face
 * out of it, working every corner out in the vertex stage. So there is no vertex format to answer
 * per pass, no piece to tell from another, and no atlas going past on the way in.
 * <p>
 * What is the same is everything that matters: one pass of the game's, its pipeline swapped for the
 * pack's and its descriptor for one naming the pack's own colour targets, the block and the samplers
 * bound inside it, and the game's own shader left alone wherever the pack serves nothing.
 * <p>
 * <strong>Two programs and one text.</strong> The game draws fancy clouds and flat clouds with two
 * pipelines that differ by a culling, so this holds one program for each and the one that is never
 * drawn never compiles a module. Which of the two is coming is the renderer's own answer, taken off
 * the argument it was called with rather than guessed at from the user's settings, because a pack is
 * allowed to overrule those and {@link #setting()} is how.
 * <p>
 * <strong>Where the clouds sit in the frame decides the rest.</strong> They are drawn after the main
 * pass, so the scene seed has already run and there is nothing for a coverage mask to keep off them;
 * and the pack's own colour target already holds the world, which is what a {@code gbuffers_clouds}
 * expects to blend onto. A pack that declares no draw buffer on the program leaves them on the
 * game's target, where the full screen layer brings them across flat, exactly as it did before this
 * class existed.
 */
public final class CloudDraw {

	/** Off unless {@code options.txt} asks otherwise, and read again at every load. */
	private static volatile boolean wanted;

	/** The one name of the format the game's clouds are drawn under. */
	private static final String PROGRAM = "gbuffers_clouds";

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

	/**
	 * One program per cloud setting the game has a pipeline for, keyed by whether it is the fancy
	 * one. Empty until the pack has been read, and it stays empty for a pack that serves no cloud
	 * program at all.
	 */
	private final Map<Boolean, CloudProgram> programs = new LinkedHashMap<>();

	/** Whether the pack has been read for its clouds. A reading that served nothing is still one. */
	private boolean read;

	/** The program of the pass being recorded, between the moment it is prepared and its bind. */
	private CloudProgram drawing;

	CloudDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
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

	/** Whether a pack's own cloud program takes over the game's, from the loaded options. */
	static void wanted(boolean asked) {
		wanted = asked;
	}

	/**
	 * The same answer, for the line at the load that says what the scene seed is still the only road
	 * in for. Asked there rather than settled once, because the clouds go through the pack or they
	 * come across flat, and the sentence differs.
	 */
	static boolean wanted() {
		return wanted;
	}

	/**
	 * What the loaded pack asked the game's cloud setting to be, or empty to leave the user's own
	 * alone.
	 * <p>
	 * <strong>Tied to this engine really drawing the clouds</strong>, unlike
	 * {@link SkyDraw#sunPathRotation()} and like {@link SkyDraw#draws}. The word is a pack saying
	 * "draw them this way, I have written for it", and with nothing of ours behind it
	 * {@code clouds=off} would take the game's clouds away and put nothing in their place. That is
	 * exactly what this engine refused to honour while no program here drew a cloud.
	 * <p>
	 * It is read at the head of the game's own accessor, so it reaches the frame graph as well as the
	 * renderer: a pack that switched them off has no cloud pass opened for it at all.
	 */
	public static Optional<ShaderProperties.CloudSetting> setting() {
		CloudDraw draw = PackChain.clouds();
		if (draw == null || !wanted) {
			return Optional.empty();
		}

		ShaderProperties.CloudSetting asked = draw.values.skyElements().clouds();

		return asked == ShaderProperties.CloudSetting.DEFAULT ? Optional.empty() : Optional.of(asked);
	}

	/**
	 * Everything that has to happen before the cloud renderer opens its pass: the program read, the
	 * pipeline compiled, the frame opened and this frame's block written.
	 *
	 * @param fancy whether the game is about to draw its boxed clouds rather than its flat ones,
	 *              which is the one thing that tells its two pipelines apart
	 * @return the pipeline to draw them with, or null to leave the game's own alone
	 */
	public static RenderPipeline pipeline(boolean fancy) {
		CloudDraw draw = PackChain.clouds();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (draw == null || !wanted || device == null) {
			return null;
		}

		try {
			return draw.prepare(device, fancy);
		} catch (RuntimeException e) {
			wanted = false;
			Vitrail.logger().error("Vitrail stopped drawing the clouds after an error", e);

			return null;
		}
	}

	/**
	 * The render pass the clouds want opened, or null to leave the game's own alone.
	 * <p>
	 * Asked right after {@link #pipeline} and about the draw that call prepared, which is why it
	 * takes no setting of its own: the two answers are one answer, and a pipeline carries a colour
	 * state per attachment this names.
	 *
	 * @param colour the colour view the renderer was going to draw into, which stays attachment
	 *               nought wherever the pack's own targets do not take it
	 * @param depth  the depth view it was going to use, kept as it is. A cloud tests against the
	 *               world in front of it and writes its own depth, which is the game's own state
	 */
	public static RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		CloudDraw draw = PackChain.clouds();
		if (draw == null || draw.drawing == null) {
			return null;
		}

		return draw.drawing.descriptor(colour, depth);
	}

	/**
	 * Binds the cloud program's block and samplers, inside the pass the cloud renderer opened.
	 * <p>
	 * The pipeline that is really bound decides, as it does for the terrain and the sky.
	 */
	public static void bind(RenderPass pass, RenderPipeline bound) {
		CloudDraw draw = PackChain.clouds();
		if (draw != null && draw.drawing != null && draw.drawing.owns(bound)) {
			draw.drawing.bind(pass);
		}
	}

	/**
	 * Whether what the clouds write still reaches the screen this frame.
	 * <p>
	 * The same question {@code TerrainDraw.shown} and {@code SkyDraw.shown} ask, and the same answer:
	 * with draw buffer nought going to a target of the pack's, the chain's final is the only road
	 * back, and the chain draws nothing at all while it is still compiling.
	 */
	private boolean shown() {
		return !this.chainRuns || this.owner.drawable();
	}

	/**
	 * Reads the pack for its cloud program and settles where the clouds are drawn.
	 * <p>
	 * Once per place and on demand, like the sky and the entities: the game builds no cloud geometry
	 * until it is about to draw one, and a place with the clouds switched off should not pay for a
	 * program it never draws.
	 */
	private void read() {
		this.read = true;

		try {
			Optional<PackProgram.Loaded> loaded =
					PackProgram.loadClouds(this.packPath, this.place, this.chosen, this.profile);
			if (loaded.isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for its clouds, so the game keeps its "
						+ "own", this.packPath.getFileName(),
						this.place.isEmpty() ? "its root" : this.place);

				return;
			}

			List<ChainPlan.Attachment> writes = writes();
			// Both of them, because a cloud setting is changed in the middle of a session and neither
			// answer is a compile: a module is only built when a pipeline is first prepared, so the
			// setting nobody plays on costs one Java object and nothing on the device.
			this.programs.put(Boolean.TRUE, CloudProgram.of(loaded.get(), true, this.values, this.load,
					writes, this.chainTargets, this.targets, this.chainRuns));
			this.programs.put(Boolean.FALSE, CloudProgram.of(loaded.get(), false, this.values,
					this.load, writes, this.chainTargets, this.targets, this.chainRuns));

			if (writes.isEmpty()) {
				Vitrail.logger().info("{} has nowhere of its own for its clouds, so they keep the "
						+ "game's target and the full screen layer brings them across",
						this.packPath.getFileName());
			}
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the cloud program of "
					+ this.packPath.getFileName() + ", so the game keeps its own clouds", e);
		}
	}

	/**
	 * Where the clouds' outputs belong, which is the plan's answer for the program that draws them,
	 * and empty when this place has none.
	 * <p>
	 * A place whose cloud targets are not the size of the screen is refused here rather than at the
	 * first draw, for the reason {@code SkyDraw.writes} gives: the depth these passes test against is
	 * the screen's and one render pass has one render area.
	 */
	private List<ChainPlan.Attachment> writes() {
		return this.plan.sky(PROGRAM)
				.filter(clouds -> {
					if (clouds.size().equals(TargetSize.ofScreen())) {
						return true;
					}

					Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so its "
							+ "clouds keep the game's own target", clouds.program());

					return false;
				})
				.map(ChainPlan.Pass::attachments)
				.orElse(List.of());
	}

	private RenderPipeline prepare(GpuDevice device, boolean fancy) {
		if (!this.read) {
			read();
		}

		CloudProgram program = this.programs.get(fancy);
		this.drawing = program;
		if (program == null) {
			return null;
		}

		// The same two calls the terrain and the sky make, and for the same reasons. The clouds are
		// drawn late in the frame and will not usually be what opens it, but they can be: the sky is
		// refused in the Nether and the terrain can be turned off in one line.
		this.owner.beginFrame();
		if (!this.owner.openTargets(device)) {
			return null;
		}

		if (!shown()) {
			return null;
		}

		return program.prepare(device);
	}

	/** The programs once the clouds have been read, for the decoded dump. Empty until then. */
	Collection<CloudProgram> programs() {
		return this.programs.values();
	}

	/** Rotates the ring buffers. Called once the frame's cloud draw has been recorded. */
	void rotate() {
		this.drawing = null;
		this.programs.values().forEach(CloudProgram::rotate);
	}

	void release() {
		this.programs.values().forEach(CloudProgram::release);
		this.programs.clear();
		this.drawing = null;
		this.read = false;
	}
}
