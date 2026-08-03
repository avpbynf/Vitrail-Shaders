package dev.vitrail.render;

import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.pack.target.TargetFormat;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.uniform.NoiseTexture;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The textures behind one pack's colour targets: what exists, at which format and size, what is
 * cleared and to what, and what a sampler is handed when nothing serves it.
 * <p>
 * Nothing here decides anything. Which indices exist, which format each was declared with, which
 * side of a ping-pong a program reads, and which sizes are relative to the screen are all answers
 * the plan already carries; this class allocates what the plan describes and clears it. Keeping
 * the decisions on the pack side is what lets them be measured against the eight packs without
 * starting the game.
 * <p>
 * Allocating and clearing both have to happen outside a render pass. Creating a texture records
 * a barrier into the very command buffer a pass would be recording into, and the clear commands
 * refuse outright while one is open.
 * <p>
 * No texture view is ever held. A resize destroys and recreates the texture behind a target,
 * closing its views, and nothing on the Vulkan backend checks that a bound view is still alive:
 * a view kept across a resize is a silent use after free rather than an exception. Views are
 * therefore looked up again at every use, which is what {@link #view} is for.
 * <p>
 * A target the schedule turns over carries two textures, and which half a program reads and
 * writes is the schedule's answer rather than a flag kept here. The one thing this class owes the
 * ping pong is {@link #swapBack}: a target the pack keeps between frames and that the chain left
 * on the alternate half has to come back to the main one, because the next frame starts its walk
 * from an empty flipped set and would read the half nothing wrote.
 */
final class ColorTargets {

	/** Enough for the three constants: they carry one pixel each and nothing samples their precision. */
	private static final GpuFormat CONSTANT_FORMAT = GpuFormat.RGBA8_UNORM;

	private static final Vector4fc OPAQUE_BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	private static final Vector4fc OPAQUE_WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector4fc MID_GREY = new Vector4f(0.5F, 0.5F, 0.5F, 1.0F);

	/** Past this much the log says so once. Refusing to allocate would trade a stutter for a black screen. */
	private static final long LOUD_BYTES = 512L * 1024L * 1024L;

	private final TargetPlan plan;
	private final Set<Integer> doubled;
	private final int noiseResolution;

	/**
	 * The shadow map, allocated and cleared with the rest.
	 * <p>
	 * It is held here rather than beside because everything that binds a sampler already holds this
	 * object, and a second one threaded through the same calls would only be a second chance for the
	 * two to be allocated, cleared or released at different moments. It is a family of its own all
	 * the same: its own resolution, its own depth window, its own clear colour.
	 */
	private final ShadowTargets shadowMap;

	private final Map<Integer, GpuFormat> formats = new LinkedHashMap<>();
	private final Map<Integer, Vector4fc> clearColours = new LinkedHashMap<>();
	private final Map<Integer, TextureTarget> mainSide = new LinkedHashMap<>();
	private final Map<Integer, TextureTarget> altSide = new LinkedHashMap<>();
	private final Set<Integer> fellBack = new TreeSet<>();
	private final List<String> notes = new ArrayList<>();

	private TextureTarget black;
	private TextureTarget white;
	private TextureTarget grey;
	private TextureTarget noise;

	private GpuTexture depthCopy;
	private GpuTextureView depthCopyView;

	private int screenWidth;
	private int screenHeight;
	private boolean clearOwed;
	private boolean broken;

	/**
	 * The plan describes the programs that run and no others, so what its schedule turns over is
	 * exactly what needs a second texture. Nothing is filtered here: a set of doubled targets
	 * worked out from anything but the schedule the samplers were bound against is a parity that
	 * disagrees with itself, and that shows as a plausible picture rather than as an error.
	 */
	ColorTargets(TargetPlan plan, int noiseResolution, int shadowResolution) {
		this.plan = plan;
		// The pack asks for it by directive and 256 is the default. Held rather than looked up at
		// allocation: this class knows nothing of a frame, and the resolution never moves while a
		// pack is loaded.
		this.noiseResolution = noiseResolution;
		this.shadowMap = new ShadowTargets(shadowResolution);
		this.doubled = Set.copyOf(plan.schedule().doubled());

		// The format and the starting colour are read once and kept. Neither moves while a pack
		// is loaded, and the colour especially is a decision the plan has already made: the Iris
		// defaults, the pack's own override and the corrections this engine owes are settled
		// there, and answering any of it again here would be the second answer to one question.
		TargetDirectives directives = plan.directives();
		for (int index : plan.ordered()) {
			TargetDirectives.Colour colour = directives.clearColour(index);
			this.formats.put(index, GpuFormats.of(directives.format(index).used()));
			this.clearColours.put(index, new Vector4f(colour.r(), colour.g(), colour.b(), colour.a()));
		}

		// A flip directive may turn over a target no program of this place writes or samples, and
		// nothing is allocated for one of those. Said here because the count of doubled targets is
		// read against the memory the pack costs, and those two would otherwise disagree.
		List<Integer> nowhere = this.doubled.stream()
				.filter(index -> !this.formats.containsKey(index))
				.toList();
		if (!nowhere.isEmpty()) {
			note("targets the schedule turns over and that no program of this place writes or "
					+ "samples, so no texture exists for either half: " + nowhere);
		}
	}

	/**
	 * Makes every planned target exist at its own size, allocating or resizing as needed, and
	 * owes itself a full clear whenever it had to. Must run on the render thread and outside any
	 * render pass.
	 *
	 * @return false when nothing usable could be prepared, in which case nothing may be drawn
	 */
	boolean ensure(int screenWidth, int screenHeight) {
		if (this.broken || screenWidth <= 0 || screenHeight <= 0) {
			return false;
		}

		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;

		boolean changed = false;
		try {
			changed = ensureConstants();
			// Not sized on the screen and therefore never resized with it: the pack's own resolution
			// is the whole point of the map. Its answer is not folded into the debt below because the
			// map is not cleared here at all: the shadow stage empties it itself, right before it
			// draws, because its content has to cross the frame boundary.
			this.shadowMap.ensure();
			for (int index : this.plan.ordered()) {
				// Each target has its own size, so one of them can be half the screen and be the
				// only one reallocated when the window changes.
				TargetSize size = this.plan.directives().size(index);
				int width = size.width(screenWidth);
				int height = size.height(screenHeight);

				changed |= ensureSide(this.mainSide, index, width, height, "");
				if (this.doubled.contains(index)) {
					changed |= ensureSide(this.altSide, index, width, height, " alt");
				}
			}
		} catch (RuntimeException e) {
			this.broken = true;
			note("the colour targets could not be allocated: " + e.getMessage());
			Vitrail.logger().error("Vitrail could not allocate the colour targets of {}, nothing will be drawn",
					this.plan.packName(), e);

			return false;
		}

		if (changed) {
			// One debt, paid by the next clear. Two calls that a later reader could put out of
			// step would show as an image that explodes one frame after a resize and blames
			// everything but the resize.
			this.clearOwed = true;
			announceSize();
		}

		return true;
	}

	/**
	 * Clears both halves of what the pack asked to have cleared, and pays any full clear owed.
	 * <p>
	 * The shadow map is deliberately not in this list. It is drawn at the end of a frame for the
	 * next one, so what it holds when the frame opens is exactly what the gbuffers are about to
	 * read, and the shadow stage empties it itself right before drawing.
	 */
	void clear(CommandEncoder encoder) {
		boolean full = this.clearOwed;
		this.clearOwed = false;

		if (full) {
			clear(encoder, this.black, OPAQUE_BLACK);
			clear(encoder, this.white, OPAQUE_WHITE);
			clear(encoder, this.grey, MID_GREY);
			uploadNoise(encoder);
		}

		for (int index : this.plan.ordered()) {
			// A full clear ignores colortexNClear: a target that is kept from one frame to the
			// next still has to start from something known the first time it is written.
			if (!full && !this.plan.directives().clears(index)) {
				continue;
			}

			Vector4fc colour = this.clearColours.get(index);
			clear(encoder, this.mainSide.get(index), colour);
			clear(encoder, this.altSide.get(index), colour);
		}
	}

	/**
	 * Copies the alternate half back over the main one, for the targets the plan named and no
	 * others. Must run outside any render pass, and after the last one of the frame.
	 * <p>
	 * Only a target the pack keeps between frames is ever in that list. The next frame walks the
	 * chain from an empty flipped set, so it reads the main half of everything before anything has
	 * written it; without this the pack would be handed, once per frame, the half it filled two
	 * frames ago. Both halves carry the same format, so this is a copy of bits that mean the same
	 * thing on both sides rather than a reinterpretation.
	 */
	void swapBack(CommandEncoder encoder, List<Integer> targets) {
		for (int index : targets) {
			TextureTarget alt = this.altSide.get(index);
			TextureTarget main = this.mainSide.get(index);
			if (alt == null || main == null) {
				note("nothing to copy back for " + TargetName.canonical(index) + ": the plan asks "
						+ "for it and only one half of it exists");
				continue;
			}

			GpuTexture from = alt.getColorTexture();
			GpuTexture to = main.getColorTexture();
			if (from != null && to != null) {
				encoder.copyTextureToTexture(from, to, 0, 0, 0, 0, 0, main.width, main.height);
			}
		}
	}

	/**
	 * Copies the game's depth as it stands, which the OptiFine model calls {@code depthtex1}: the
	 * depth of the opaque world, taken before anything translucent is drawn. Must run on the render
	 * thread, outside any render pass, and at the right moment of the frame, which is the caller's
	 * to know.
	 * <p>
	 * The copy carries the source's own format, taken from the texture rather than assumed: the
	 * game's depth is {@code D32_FLOAT} until a mod asks NeoForge for a stencil, and a depth copy
	 * with any other format than its source is refused outright by the encoder.
	 */
	void copyDepth(CommandEncoder encoder, GpuTexture depth) {
		if (depth == null || this.broken) {
			return;
		}

		int width = depth.getWidth(0);
		int height = depth.getHeight(0);
		if (this.depthCopy != null && (this.depthCopy.getWidth(0) != width
				|| this.depthCopy.getHeight(0) != height
				|| this.depthCopy.getFormat() != depth.getFormat())) {
			releaseDepthCopy();
		}

		if (this.depthCopy == null) {
			this.depthCopy = RenderSystem.getDevice().createTexture(
					() -> "Vitrail depthtex1",
					GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
					depth.getFormat(), width, height, 1, 1);
			this.depthCopyView = RenderSystem.getDevice().createTextureView(this.depthCopy);
		}

		encoder.copyTextureToTexture(depth, this.depthCopy, 0, 0, 0, 0, 0, width, height);
	}

	/**
	 * The copy {@link #copyDepth} last took, or null before the first one. Looked up at every use
	 * like every other view here: a resize destroys and recreates it.
	 */
	GpuTextureView depthCopy() {
		return this.depthCopyView;
	}

	/** Never held from one frame to the next. Null when this index was never allocated. */
	GpuTextureView view(int index, TargetSchedule.Side side) {
		TextureTarget target = target(index, side);

		return target == null ? null : target.getColorTextureView();
	}

	GpuTexture texture(int index, TargetSchedule.Side side) {
		TextureTarget target = target(index, side);

		return target == null ? null : target.getColorTexture();
	}

	GpuFormat format(int index) {
		return this.formats.get(index);
	}

	int screenWidth() {
		return this.screenWidth;
	}

	int screenHeight() {
		return this.screenHeight;
	}

	/** LINEAR wherever Iris allows it, which is everywhere but an integer format. */
	FilterMode filter(int index) {
		TargetFormat format = this.plan.directives().format(index).used();

		return has(index) ? GpuFormats.filterFor(format) : FilterMode.NEAREST;
	}

	/**
	 * Whether the plan carries this target at all, which is answerable before anything has been
	 * allocated. {@link #view} still comes back null until {@link #ensure} has run.
	 */
	boolean has(int index) {
		return this.formats.containsKey(index);
	}

	/** Opaque black, one pixel, for a sampler the engine has no answer for. Never the scene. */
	GpuTextureView black() {
		return this.black == null ? null : this.black.getColorTextureView();
	}

	/** White, so a shadow lookup reads the far plane rather than putting the world in shadow. */
	GpuTextureView white() {
		return this.white == null ? null : this.white.getColorTextureView();
	}

	/** Mid grey, for a lookup this engine has no answer for. */
	GpuTextureView grey() {
		return this.grey == null ? null : this.grey.getColorTextureView();
	}

	/** The pack's noise image, at the resolution it asked for. */
	GpuTextureView noise() {
		return this.noise == null ? grey() : this.noise.getColorTextureView();
	}

	/** The shadow map. Never null, and its own images are null until the first frame allocates them. */
	ShadowTargets shadow() {
		return this.shadowMap;
	}

	Set<Integer> doubled() {
		return this.doubled;
	}

	long bytes() {
		long total = 0L;
		for (Map.Entry<Integer, TextureTarget> entry : this.mainSide.entrySet()) {
			total += bytes(entry.getKey(), entry.getValue());
		}

		for (Map.Entry<Integer, TextureTarget> entry : this.altSide.entrySet()) {
			total += bytes(entry.getKey(), entry.getValue());
		}

		return total;
	}

	List<String> notes() {
		return List.copyOf(this.notes);
	}

	void release() {
		this.mainSide.values().forEach(TextureTarget::destroyBuffers);
		this.altSide.values().forEach(TextureTarget::destroyBuffers);
		this.mainSide.clear();
		this.altSide.clear();

		this.black = release(this.black);
		this.white = release(this.white);
		this.grey = release(this.grey);
		this.noise = release(this.noise);
		this.shadowMap.release();
		releaseDepthCopy();
	}

	private void releaseDepthCopy() {
		if (this.depthCopyView != null) {
			this.depthCopyView.close();
			this.depthCopyView = null;
		}

		if (this.depthCopy != null) {
			this.depthCopy.close();
			this.depthCopy = null;
		}
	}

	private boolean ensureConstants() {
		if (this.black != null) {
			return false;
		}

		// One pixel each, and clamped when they are bound, so the value is what a lookup reads
		// wherever it lands.
		this.black = new TextureTarget("Vitrail black", 1, 1, false, CONSTANT_FORMAT);
		this.white = new TextureTarget("Vitrail white", 1, 1, false, CONSTANT_FORMAT);
		this.grey = new TextureTarget("Vitrail grey", 1, 1, false, CONSTANT_FORMAT);

		int resolution = this.noiseResolution;
		this.noise = new TextureTarget("Vitrail noise", resolution, resolution, false,
				CONSTANT_FORMAT);

		return true;
	}

	/**
	 * Uploads the noise image, once, from the generator the harness has a fingerprint for.
	 * <p>
	 * A pack indexes this image with coordinates of its own, so it is not enough for the picture to
	 * look like noise: it has to be the image the pack was tuned against, which is why the generator
	 * follows Iris bit for bit and why no observation in the game could ever prove it right.
	 * <p>
	 * Until this was here every {@code noisetex} lookup read one mid grey pixel, which is a constant
	 * where the pack asked for a field. That is not a missing detail: BSL builds its cloud distance
	 * from it, and a cloud distance of nought discards every fragment of water.
	 */
	private void uploadNoise(CommandEncoder encoder) {
		int resolution = this.noise.width;
		byte[] pixels = NoiseTexture.rgba(resolution);
		ByteBuffer data = ByteBuffer.allocateDirect(pixels.length).order(ByteOrder.nativeOrder());
		data.put(pixels).flip();

		encoder.writeToTexture(this.noise.getColorTexture(), data, 0, 0, 0, 0, resolution, resolution);
	}

	private boolean ensureSide(Map<Integer, TextureTarget> side, int index, int width, int height, String suffix) {
		TextureTarget target = side.get(index);
		if (target != null && target.width == width && target.height == height) {
			return false;
		}

		String name = TargetName.canonical(index) + suffix;
		if (target == null) {
			GpuFormat format = this.formats.get(index);
			TargetDirectives directives = this.plan.directives();
			// Named before it is allocated, on purpose. RG11B10_FLOAT as a colour attachment is
			// not something the Vulkan specification guarantees and nothing in the game asks the
			// driver whether it has it, so the last line written has to name the format asked for.
			// The declaration comes with it, because a wrong image starts at a wrong declaration
			// and reading it off the picture is what has to stop being necessary.
			Vitrail.logger().info("Allocating {} as {} at {}x{}, declared {} at {}", name, format,
					width, height, directives.format(index).declared(), directives.formatSource(index));
			side.put(index, new TextureTarget("Vitrail " + name, width, height, false, format));
		} else {
			target.resize(width, height);
		}

		return true;
	}

	private void announceSize() {
		long bytes = bytes();
		long single = this.plan.bytesAt(this.screenWidth, this.screenHeight, Set.of());
		Vitrail.logger().info("Colour targets of {} sized for {}x{}: {} targets, {} MiB",
				this.plan.packName(), this.screenWidth, this.screenHeight, this.mainSide.size(),
				megabytes(bytes));

		// Named and not counted. Which targets take part in the ping pong is the one thing a wrong
		// picture is read back against, and the cost of the second half is the price of the chain.
		if (!this.doubled.isEmpty()) {
			Vitrail.logger().info("{} targets doubled: {}, {} MiB instead of {}",
					this.doubled.size(), this.doubled, megabytes(bytes), megabytes(single));
		}

		if (bytes > LOUD_BYTES) {
			Vitrail.logger().warn("{} takes {} MiB of colour targets at {}x{}, {} of them for the "
					+ "second halves of {}", this.plan.packName(), megabytes(bytes), this.screenWidth,
					this.screenHeight, megabytes(bytes - single), this.doubled);
		}
	}

	private static long megabytes(long bytes) {
		return bytes / (1024L * 1024L);
	}

	private TextureTarget target(int index, TargetSchedule.Side side) {
		// A target nothing flips has one texture, and a schedule asking for its other side is
		// asking for the only one there is.
		if (side == TargetSchedule.Side.ALT) {
			TextureTarget alt = this.altSide.get(index);
			if (alt != null) {
				return alt;
			}

			// Said once per target, because past this point it must never happen: the schedule
			// doubles everything a running program turns over, so a fall back here means the halves
			// the samplers were bound against and the halves that exist are two different answers.
			// Left silent it is the one place such a disagreement can hide.
			if (this.formats.containsKey(index) && this.fellBack.add(index)) {
				Vitrail.logger().warn("{} was read from its alternate half and only one half of it "
						+ "exists, so both sides of the ping pong are the same texture",
						TargetName.canonical(index));
			}
		}

		return this.mainSide.get(index);
	}

	private long bytes(int index, TextureTarget target) {
		GpuFormat format = this.formats.get(index);

		return format == null ? 0L : (long) target.width * target.height * format.blockSize();
	}

	private void note(String text) {
		if (!this.notes.contains(text)) {
			this.notes.add(text);
		}
	}

	private static void clear(CommandEncoder encoder, TextureTarget target, Vector4fc colour) {
		GpuTexture texture = target == null ? null : target.getColorTexture();
		if (texture != null) {
			encoder.clearColorTexture(texture, colour);
		}
	}

	private static TextureTarget release(TextureTarget target) {
		if (target != null) {
			target.destroyBuffers();
		}

		return null;
	}
}
