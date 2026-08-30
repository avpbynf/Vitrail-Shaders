package dev.vitrail.render;

import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Colour and depth views the currently recording Vitrail pass attached.
 * <p>
 * {@code VulkanRenderPass} keeps the label and the area, not the attachments, and
 * {@link GeometryHold} forgets them before {@code close} runs. The encoder mixin fills this at
 * {@code createRenderPass} and takes it when that pass actually ends, which is also when a hold
 * that joined several programs finally closes.
 */
public final class PassImages {

	private static final GpuTextureView[] NONE = new GpuTextureView[0];

	private static GpuTextureView[] colours = NONE;

	private static GpuTextureView depth;

	private PassImages() {
	}

	/** Remembers the attachments of the Vitrail pass that is about to begin recording. */
	public static void remember(RenderPassDescriptor descriptor) {
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> listed =
				descriptor.colorAttachments();
		int count = 0;
		for (int index = 0; index < listed.size(); index++) {
			if (view(listed.get(index)) != null) {
				count++;
			}
		}

		GpuTextureView[] next = count == 0 ? NONE : new GpuTextureView[count];
		int at = 0;
		for (int index = 0; index < listed.size(); index++) {
			GpuTextureView colour = view(listed.get(index));
			if (colour != null) {
				next[at++] = colour;
			}
		}

		colours = next;
		RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment =
				descriptor.depthAttachment();
		depth = depthAttachment == null ? null : depthAttachment.textureView();
	}

	/**
	 * The views the closing pass attached, then forgotten. Empty when this close did not go
	 * through {@link #remember}, in which case the caller must not emit an incomplete image list.
	 */
	public static Snapshot take() {
		Snapshot snapshot = new Snapshot(colours, depth);
		colours = NONE;
		depth = null;

		return snapshot;
	}

	private static GpuTextureView view(
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment) {
		return attachment == null ? null : attachment.textureView();
	}

	/** Colour views then optional depth, as {@link #take} handed them over. */
	public static final class Snapshot {

		private final GpuTextureView[] colours;

		private final GpuTextureView depth;

		Snapshot(GpuTextureView[] colours, GpuTextureView depth) {
			this.colours = colours;
			this.depth = depth;
		}

		public GpuTextureView[] colours() {
			return this.colours;
		}

		public GpuTextureView depth() {
			return this.depth;
		}

		public boolean empty() {
			return this.colours.length == 0 && this.depth == null;
		}
	}
}
