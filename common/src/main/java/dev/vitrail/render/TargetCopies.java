package dev.vitrail.render;

import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * A copy of each colour target a translucent geometry program samples on the half it also writes,
 * taken at the end of the deferred stage and again once the game's translucent features are
 * composed, and served to that program where it asks for the target.
 * <p>
 * <strong>What the pack was written against.</strong> Under OpenGL a gbuffers program's sampler
 * and its draw buffer are one texture: Iris binds {@code colortex4} and up to the main half unless
 * the target is flipped ({@code samplers/IrisSamplers.java:51-69}) and attaches the draw buffer to
 * the same half on the same rule ({@code targets/RenderTargets.java:309-320} and
 * {@code :345-368}, both sets taken from one snapshot at
 * {@code pipeline/IrisRenderingPipeline.java:359-360} and {@code :677-678}). So a
 * {@code gbuffers_water} that samples the target it draws into reads what the opaque world left
 * there, its own fog colour behind the water and the scene it reflects, and it is a read the
 * packs make on purpose: Sildur's {@code gbuffers_water.fsh} samples {@code colortex4} for both
 * while drawing to it. OpenGL leaves such a read undefined and the drivers hand back what stood
 * at the texel for a fragment reading its own, which is the read these packs rely on.
 * <p>
 * <strong>Why a copy here, which is a divergence and is written up as one.</strong> Vulkan does
 * not let one image be a colour attachment and a sampled texture of the same pass, whatever the
 * layout; the game's backend has no feedback loop layout and no input attachment to offer, so the
 * read was answered with one black pixel, and Sildur's water lost its fog and its reflections.
 * What Iris's framebuffer holds when the world's translucents are drawn is the opaque world as
 * the deferred stage, the hand, the translucent entities and the game's features left it, and
 * what it holds when the translucent entities themselves are drawn is that world without the
 * last two; both stand in the target at their moment here, so an image copy per such target,
 * taken outside any pass at each of the two boundaries, is that picture
 * ({@code PackChain.takeReadCopies} says where). What it costs is the copies, two of one target
 * a frame per target read this way, and one more image of that target's size. What it leaves: a
 * translucent draw reading a texel another translucent draw of the same frame has already
 * written sees the opaque world there, where the reference's read is one the specification
 * leaves undefined; and the copy carries level nought alone, so a program reading such a target
 * at a lod is served the base level whatever the lod, where a mipmapped target would have had a
 * chain.
 * <p>
 * Only the passes drawn after the deferred stage are served, and only for the targets the
 * reference binds to a geometry program at all ({@link #FIRST_SAMPLED}). A pass drawn before that
 * stage reads a target the frame's clear has just emptied, or that its own siblings are filling,
 * and neither is a picture a copy taken at one moment can stand in for; those keep the one pixel
 * and the log line that names it. The hand's translucent pass is served the second copy, so it
 * sees the world without the water where the reference shows it the water too. And what a
 * geometry program reads of the first four targets where it does NOT write them is a question
 * this class leaves where it found it: the live target, where the reference binds nothing.
 */
final class TargetCopies {

	/**
	 * The first target a geometry program may read at all. Iris binds {@code colortex0} to
	 * {@code colortex3} to its full screen passes alone ({@code samplers/IrisSamplers.java:53-55}),
	 * so a gbuffers program naming one of them reads whatever stands on its first texture unit
	 * there, and no copy stands in for that.
	 */
	static final int FIRST_SAMPLED = 4;

	private record Key(int target, TargetSchedule.Side side) {
	}

	/**
	 * What a program asked for, in the order asked, whether or not a texture exists for it yet.
	 * Written from the worker threads that build the entity programs and read on the render
	 * thread, so every touch of it is under this object's lock; nothing else here leaves the
	 * render thread.
	 */
	private final Set<Key> asked = new LinkedHashSet<>();

	/** The program paths whose reads have been said in the log, one line per path and target. */
	private final Set<String> said = new HashSet<>();

	/** One surface per key, at the size and format of the target it copies. */
	private final Map<Key, TargetSurface> copies = new LinkedHashMap<>();

	/** The target each copy is taken from, settled by the last {@link #ensure}. */
	private final Map<Key, TargetSurface> sources = new LinkedHashMap<>();

	/** Keys whose allocation failed, refused for the session rather than retried every frame. */
	private final Set<Key> refused = new LinkedHashSet<>();

	/**
	 * Whether {@link #take} has filled the copies this frame. A fresh image holds whatever the
	 * driver left there, and an image a frame old holds a camera that has moved on; a reader
	 * served either would read it as the scene, so it is lowered at the frame's close and at every
	 * allocation.
	 */
	private boolean written;

	/**
	 * Says that a geometry program drawn after the deferred stage samples this target on the half
	 * it writes. A name the program only declares, as a shared header declares every target, does
	 * not ask. Asked at the program's construction, which is before any frame; a key asked once a
	 * frame is running is served from the next {@link #ensure} on.
	 */
	void ask(int target, TargetSchedule.Side side) {
		synchronized (this) {
			this.asked.add(new Key(target, side));
		}
	}

	/**
	 * Whether this program's read of this target is being named for the first time, so that the
	 * load says it once per program rather than once per piece the program is built for: the
	 * entity family builds one program per element it serves, off one path.
	 */
	boolean firstMention(String path, int target, TargetSchedule.Side side) {
		synchronized (this) {
			return this.said.add(path + "|" + target + "|" + side);
		}
	}

	/**
	 * Makes a copy exist for every key asked whose target exists, at that target's size and
	 * format, reallocating when the target moved. Must run on the render thread and outside any
	 * render pass, after the targets themselves have been sized for the frame.
	 *
	 * @param source the target's own surface for a target and a half, or null when none exists
	 */
	void ensure(BiFunction<Integer, TargetSchedule.Side, TargetSurface> source) {
		List<Key> keys;
		synchronized (this) {
			keys = List.copyOf(this.asked);
		}

		for (Key key : keys) {
			if (this.refused.contains(key)) {
				continue;
			}

			TargetSurface target = source.apply(key.target(), key.side());
			if (target == null) {
				continue;
			}

			TargetSurface copy = this.copies.get(key);
			if (copy != null && copy.width() == target.width() && copy.height() == target.height()) {
				this.sources.put(key, target);
				continue;
			}

			String name = TargetName.canonical(key.target()) + (key.side() == TargetSchedule.Side.ALT
					? " alt" : "");
			try {
				if (copy == null) {
					// Named before it is allocated, like the target it copies, so that the last line
					// written names what was asked for when the allocation is what fails.
					Vitrail.logger().info("Allocating a copy of {} as {} at {}x{}, read by a "
							+ "translucent geometry program on the half it writes", name,
							target.texture().getFormat(), target.width(), target.height());
					this.copies.put(key, new TargetSurface("Vitrail " + name + " copy",
							target.texture().getFormat(), false, target.width(), target.height()));
				} else {
					copy.resize(target.width(), target.height());
				}
			} catch (GpuDeviceLossException e) {
				throw e;
			} catch (RuntimeException e) {
				this.refused.add(key);
				TargetSurface failed = this.copies.remove(key);
				if (failed != null) {
					failed.close();
				}

				this.sources.remove(key);
				Vitrail.logger().error("Vitrail could not allocate the copy of {} at {}x{}, so the "
						+ "programs reading it on the half they write read one pixel there instead",
						name, target.width(), target.height(), e);
				continue;
			}

			// A new image, or an image of another size: nothing has filled it yet.
			this.written = false;
			this.sources.put(key, target);
		}
	}

	/** Whether any copy exists to take, which is what decides whether the frame pays for one. */
	boolean any() {
		return !this.copies.isEmpty();
	}

	/**
	 * Copies every target into its copy, level nought whole. Must run on the render thread and
	 * outside any render pass, at each of the two boundaries {@code PackChain.takeReadCopies}
	 * names, with the clears still owed paid ahead of it.
	 */
	void take(CommandEncoder encoder) {
		for (Map.Entry<Key, TargetSurface> entry : this.copies.entrySet()) {
			TargetSurface source = this.sources.get(entry.getKey());
			TargetSurface copy = entry.getValue();
			if (source == null || source.texture() == null || copy.texture() == null) {
				continue;
			}

			encoder.copyTextureToTexture(source.texture(), copy.texture(), 0, 0, 0, 0, 0,
					copy.width(), copy.height());
		}

		this.written = true;
	}

	/**
	 * Forgets that this frame's copies were taken, at the frame's close: the next frame reads the
	 * pixel until it takes its own, never the frame before's world.
	 */
	void forget() {
		this.written = false;
	}

	/**
	 * The copy of this target on this half, or null when there is none to serve: nothing asked for
	 * it, the target does not exist, the allocation was refused, or this frame has not filled it.
	 */
	GpuTextureView view(int target, TargetSchedule.Side side) {
		if (!this.written) {
			return null;
		}

		TargetSurface copy = this.copies.get(new Key(target, side));

		return copy == null ? null : copy.view();
	}

	/** Frees every copy. What was asked stays asked: the programs that asked outlive a resize. */
	void release() {
		this.copies.values().forEach(TargetSurface::close);
		this.copies.clear();
		this.sources.clear();
		this.written = false;
	}
}
