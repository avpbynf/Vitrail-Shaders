package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.TargetDirectives;
import dev.vitrail.pack.TargetFormat;
import dev.vitrail.pack.TargetName;
import dev.vitrail.pack.TargetPlan;
import dev.vitrail.pack.TargetSchedule;
import dev.vitrail.pack.TargetSize;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Only the {@code final} runs today and it writes the game's own target, so nothing flips and no
 * second texture is allocated. When the composite chain arrives, the set of doubled targets grows
 * by itself through {@code doubledFor}, and the end of frame copy from the alternate side back to
 * the main one belongs next to {@link #clear}. Neither is written here: it would be code no frame
 * executes and nothing measures.
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

	private final Map<Integer, GpuFormat> formats = new LinkedHashMap<>();
	private final Map<Integer, Vector4fc> clearColours = new LinkedHashMap<>();
	private final Map<Integer, TextureTarget> mainSide = new LinkedHashMap<>();
	private final Map<Integer, TextureTarget> altSide = new LinkedHashMap<>();
	private final List<String> notes = new ArrayList<>();

	private TextureTarget black;
	private TextureTarget white;
	private TextureTarget grey;

	private int screenWidth;
	private int screenHeight;
	private boolean clearOwed;
	private boolean broken;

	/**
	 * @param executing the programs that really run this frame, which is what decides whether a
	 *                  target needs its second half. Today that is the {@code final} alone, and
	 *                  it writes the game's target, so nothing is doubled.
	 */
	ColorTargets(TargetPlan plan, Set<String> executing) {
		this.plan = plan;
		this.doubled = Set.copyOf(plan.schedule().doubledFor(executing));

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

	/** Clears both halves of what the pack asked to have cleared, and pays any full clear owed. */
	void clear(CommandEncoder encoder) {
		boolean full = this.clearOwed;
		this.clearOwed = false;

		if (full) {
			clear(encoder, this.black, OPAQUE_BLACK);
			clear(encoder, this.white, OPAQUE_WHITE);
			clear(encoder, this.grey, MID_GREY);
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

	/** Mid grey, for a noise lookup: a constant kills dithering, black kills the image. */
	GpuTextureView grey() {
		return this.grey == null ? null : this.grey.getColorTextureView();
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

		return true;
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
		Vitrail.logger().info("Colour targets of {} sized for {}x{}: {} targets, {} MiB, {} doubled",
				this.plan.packName(), this.screenWidth, this.screenHeight, this.mainSide.size(),
				bytes / (1024L * 1024L), this.doubled.size());

		if (bytes > LOUD_BYTES) {
			Vitrail.logger().warn("{} takes {} MiB of colour targets at {}x{}", this.plan.packName(),
					bytes / (1024L * 1024L), this.screenWidth, this.screenHeight);
		}
	}

	private TextureTarget target(int index, TargetSchedule.Side side) {
		// A target nothing flips has one texture, and a schedule asking for its other side is
		// asking for the only one there is.
		if (side == TargetSchedule.Side.ALT) {
			TextureTarget alt = this.altSide.get(index);
			if (alt != null) {
				return alt;
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
