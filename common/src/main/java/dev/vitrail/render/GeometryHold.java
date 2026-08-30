package dev.vitrail.render;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Keeps one Vulkan render pass open across draws that write the same colour and depth images.
 * <p>
 * Iris binds {@code defaultFB} once and leaves it bound through solid, cutout, sky pieces and
 * entities ({@code pipeline/IrisRenderingPipeline.java:1383-1388}). Each of those is a
 * {@code createRenderPass} here, and closing one is {@code vkCmdEndRendering} plus a barrier: a GPU
 * stop OpenGL's bind is not. Consecutive programs that write the same colour and depth images, at
 * the same area, therefore keep the pass that is already recording and only switch pipeline.
 * <p>
 * Full-screen pack programs go through {@link #openFullscreen}: they join only when the images and
 * the area match and the new program samples none of the attachments still bound. Iris still issues
 * a separate GL draw per composite, often into a flipped target; folding two that do not depend on
 * each other is a Vulkan-only win. A later pass that samples what the last one wrote, that names
 * different attachments, that clears, that cannot name every sampled image, or a copy, a mip chain
 * or a depth conversion, ends the hold first. The encoder mixin is that door for every foreign
 * {@code createRenderPass}. This class's own open is the exception, so a matching program does not
 * flush itself.
 */
public final class GeometryHold {

	/** {@link #fit} found nothing in the way: the pass about to open joins the one recording. */
	private static final int JOINS = -1;

	/** Nothing was recording, so what ended the last hold is the cause and {@link #ended} holds it. */
	private static final int NO_HOLD = -2;

	private static final int COUNT_DIFFERS = -3;
	private static final int DEPTH_DIFFERS = -4;
	private static final int AREA_DIFFERS = -5;
	private static final int PLAN_INCOMPLETE = -6;
	private static final int CLEARS = -7;
	private static final int SAMPLES_HELD = -8;

	private static RenderPass current;
	private static GpuTexture[] colours;
	private static GpuTexture depth;
	private static int areaX;
	private static int areaY;
	private static int areaW;
	private static int areaH;
	private static boolean opening;

	/**
	 * What ended the hold, kept for the next open to report.
	 * <p>
	 * A family almost never reopens because of anything at its own open: it reopens because
	 * something between the two passes could not be recorded inside one, and by the time the next
	 * open runs that something is gone. Holding it here is what lets the census say which.
	 */
	private static Supplier<String> ended;

	private GeometryHold() {
	}

	/** True while this class is the one calling {@code createRenderPass}. */
	public static boolean opening() {
		return opening;
	}

	/**
	 * Opens a pass, or hands back the one already recording when it writes the same images.
	 * Load-ops on a reused descriptor are ignored: the first open already applied them.
	 */
	public static RenderPass open(CommandEncoder encoder, RenderPassDescriptor descriptor) {
		return open(encoder, descriptor, fit(descriptor));
	}

	/**
	 * Opens a pass for a full-screen pack program, or hands back the one already recording when it
	 * writes the same images, at the same area, and samples none of them.
	 * <p>
	 * Geometry {@link #open} does not ask this: those programs paint over the world rather than
	 * filter it. A later composite that samples what the hold still has attached would read an
	 * image the pass has not stored. {@code sampled} null means the sampler plan cannot name every
	 * image, and that is a refusal: a join would be a guess.
	 */
	public static RenderPass openFullscreen(CommandEncoder encoder, RenderPassDescriptor descriptor,
			List<GpuTexture> sampled) {
		return open(encoder, descriptor, fitFullscreen(descriptor, sampled));
	}

	private static RenderPass open(CommandEncoder encoder, RenderPassDescriptor descriptor, int fit) {
		if (fit == JOINS) {
			resetArea(current);

			return current;
		}

		// Named here and nowhere else: this is the one point that still knows both what is recording
		// and what is asking, and a count of passes without their causes is what a family's number
		// gets read into rather than read from. Only while a census is armed, the naming being the
		// only part of any of this that builds a string.
		if (PassTimings.censusArmed()) {
			PassTimings.censusReopen(descriptor.label(), name(fit));
		}

		// Null, the cause having just been reported: the next open owes its own.
		flush(null);
		opening = true;
		try {
			current = encoder.createRenderPass(descriptor);
			remember(descriptor);

			return current;
		} finally {
			opening = false;
		}
	}

	/**
	 * Whether {@link RenderPass#close()} should be skipped. Sodium, the sky and the entities all
	 * close in a try-with-resources; cancelling that close is what keeps the backend pass alive.
	 */
	public static boolean keep(RenderPass pass) {
		return pass == current;
	}

	/**
	 * Hands back the pass already recording when a leftover Immediate draw or Distant Horizons'
	 * {@code GenericObjectRenderer} writes the same images, with no clear of its own.
	 * <p>
	 * Iris leaves {@code defaultFB} bound; those draws then land in it. Here each one is a
	 * {@code createRenderPass}. Folding them into the hold that is already open is that bind.
	 * The leftover pass is never adopted: its viewport and scissor are whoever opened it, and
	 * keeping them is the band that copies the top of the screen onto the bottom. Only a hold
	 * this class opened is reused, and the area is reset first.
	 *
	 * @return the held pass, or {@code null} to let the encoder open a new one
	 */
	public static RenderPass leftover(RenderPassDescriptor descriptor) {
		// The NAME is asked last, and that order is what keeps this on the path every pass of the
		// frame takes: the encoder sends every createRenderPass through here, and reading a
		// descriptor's name means running the supplier that builds it, which for the game's own
		// immediate draws concatenates the pipeline it is drawing with into a string that is thrown
		// away one comparison later. Everything ahead of it compares pointers and numbers, so the
		// string is now only built for a pass that already writes the held images at the held area.
		if (opening || current == null || clears(descriptor) || fit(descriptor) != JOINS
				|| !leftoverLabel(descriptor)) {
			return null;
		}

		resetArea(current);

		return current;
	}

	/**
	 * Ends the hold so a copy, a clear, a mip chain, a depth conversion or a different framebuffer
	 * can run, naming what is ending it so the next family to open can say why it had to.
	 *
	 * @param cause what the census reports against the pass that opens next, or {@code null} where
	 *              the caller has already reported one of its own
	 */
	public static void flush(Supplier<String> cause) {
		RenderPass pass = current;
		// Only when something really was recording. A clear with no hold standing ends nothing, and
		// blaming it for the reopening that comes later would name a bystander.
		if (pass != null) {
			ended = cause;
		}

		current = null;
		colours = null;
		depth = null;
		if (pass != null) {
			pass.close();
		}
	}

	/**
	 * The four things a pass has to share with the one recording to join it, and which of them it
	 * failed: {@link #JOINS} when none, a colour index when that image differs, one of the negative
	 * codes otherwise.
	 * <p>
	 * One walk answering both questions, rather than a boolean here and a reason beside it: two
	 * walks would be two chances to drift apart, and the one the census reads would be the one
	 * nothing else exercises. Nothing here builds a string, which is what lets it stay on the path
	 * every geometry pass takes.
	 */
	private static int fit(RenderPassDescriptor descriptor) {
		if (current == null || colours == null) {
			return NO_HOLD;
		}

		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> listed =
				descriptor.colorAttachments();
		if (listed.size() != colours.length) {
			return COUNT_DIFFERS;
		}

		for (int index = 0; index < colours.length; index++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = listed.get(index);
			GpuTexture texture = texture(attachment);
			if (texture != colours[index]) {
				return index;
			}
		}

		GpuTexture nextDepth = descriptor.depthAttachment() == null
				? null
				: descriptor.depthAttachment().textureView().texture();
		if (nextDepth != depth) {
			return DEPTH_DIFFERS;
		}

		RenderPass.RenderArea area = descriptor.renderArea;
		boolean sameArea = area != null && area.x() == areaX && area.y() == areaY
				&& area.width() == areaW && area.height() == areaH;

		return sameArea ? JOINS : AREA_DIFFERS;
	}

	/**
	 * {@link #fit} plus the reasons a full-screen program cannot join even when the images match: a
	 * clear load-op, a sample of an image the hold still has attached, or a sampler plan that cannot
	 * name every image. Asked only when the images would otherwise join, so a first open is still
	 * {@link #NO_HOLD} rather than one of those.
	 */
	private static int fitFullscreen(RenderPassDescriptor descriptor, List<GpuTexture> sampled) {
		int fit = fit(descriptor);
		if (fit != JOINS) {
			return fit;
		}

		if (sampled == null) {
			return PLAN_INCOMPLETE;
		}

		if (clears(descriptor)) {
			return CLEARS;
		}

		return samplesHeld(sampled) ? SAMPLES_HELD : JOINS;
	}

	private static boolean samplesHeld(List<GpuTexture> sampled) {
		for (int at = 0; at < sampled.size(); at++) {
			GpuTexture texture = sampled.get(at);
			if (texture == null) {
				continue;
			}

			if (texture == depth) {
				return true;
			}

			for (GpuTexture colour : colours) {
				if (texture == colour) {
					return true;
				}
			}
		}

		return false;
	}

	/**
	 * What {@link #fit} found, in words, for the census line. Reached only while a census is armed.
	 * <p>
	 * The no-hold answer is the one that carries the frame's real story: the family did not fail any
	 * comparison, something between the two passes ended the hold before it ever got to ask.
	 */
	private static String name(int fit) {
		if (fit >= 0) {
			return "its colour image " + fit + " is not the one held";
		}

		return switch (fit) {
			case COUNT_DIFFERS -> "it writes a different number of colour images";
			case DEPTH_DIFFERS -> "its depth image is not the one held";
			case AREA_DIFFERS -> "its area is not the one held";
			case PLAN_INCOMPLETE -> "its sampler plan cannot name every image it samples";
			case CLEARS -> "it clears an attachment";
			case SAMPLES_HELD -> "it samples an attachment of the hold";
			case NO_HOLD -> ended == null ? "nothing was being held" : "the hold ended on " + ended.get();
			default -> "it joins";
		};
	}

	private static void remember(RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> listed =
				descriptor.colorAttachments();
		colours = new GpuTexture[listed.size()];
		for (int index = 0; index < listed.size(); index++) {
			colours[index] = texture(listed.get(index));
		}

		depth = descriptor.depthAttachment() == null
				? null
				: descriptor.depthAttachment().textureView().texture();
		RenderPass.RenderArea area = descriptor.renderArea;
		if (area == null) {
			areaX = 0;
			areaY = 0;
			areaW = 0;
			areaH = 0;
		} else {
			areaX = area.x();
			areaY = area.y();
			areaW = area.width();
			areaH = area.height();
		}
	}

	private static GpuTexture texture(
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment) {
		if (attachment == null) {
			return null;
		}

		GpuTextureView view = attachment.textureView();

		return view == null ? null : view.texture();
	}

	/**
	 * Sodium scissors each region inside the pass. Keeping the hold without clearing that scissor
	 * leaves the next family's draws clipped to the last region's rectangle: half the screen of
	 * stale colour, which is the band that comes and goes as chunks stream.
	 */
	private static void resetArea(RenderPass pass) {
		pass.disableScissor();
	}

	private static boolean leftoverLabel(RenderPassDescriptor descriptor) {
		Supplier<String> label = descriptor.label();
		if (label == null) {
			return false;
		}

		String name = label.get();

		return name != null && (name.startsWith("Immediate draw")
				|| name.equals("distantHorizons:GenericObjectRenderer"));
	}

	private static boolean clears(RenderPassDescriptor descriptor) {
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment
				: descriptor.colorAttachments()) {
			if (attachment != null && present(attachment.clearValue())) {
				return true;
			}
		}

		RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment =
				descriptor.depthAttachment();

		return depthAttachment != null && present(depthAttachment.clearValue());
	}

	private static boolean present(Object clear) {
		return switch (clear) {
			case Optional<?> colour -> colour.isPresent();
			case OptionalDouble depth -> depth.isPresent();
			case null, default -> false;
		};
	}
}
