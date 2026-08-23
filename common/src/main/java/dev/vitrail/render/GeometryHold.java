package dev.vitrail.render;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4fc;

import java.util.List;
import java.util.Optional;

/**
 * Keeps one Vulkan render pass open across geometry that Iris would have drawn into the same FBO.
 * <p>
 * Iris binds {@code defaultFB} once and leaves it bound through solid, cutout, sky pieces and
 * entities ({@code pipeline/IrisRenderingPipeline.java:1383-1388}). Each of those is a
 * {@code createRenderPass} here, and closing one is {@code vkCmdEndRendering} plus a barrier: a GPU
 * stop OpenGL's bind is not. Consecutive programs that write the same colour and depth images, at
 * the same area, therefore keep the pass that is already recording and only switch pipeline.
 * <p>
 * A later pass that samples what the last one wrote, or that names different attachments, or that
 * is a composite, a copy or a clear, ends the hold first. The encoder mixin is that door: every
 * foreign {@code createRenderPass}, copy or clear flushes. This class's own open is the exception,
 * so a matching geometry program does not flush itself.
 */
public final class GeometryHold {

	private static RenderPass current;
	private static GpuTexture[] colours;
	private static GpuTexture depth;
	private static int areaX;
	private static int areaY;
	private static int areaW;
	private static int areaH;
	private static boolean opening;

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
		if (matches(descriptor)) {
			return current;
		}

		flush();
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

	/** Ends the hold so a copy, a clear, a composite or a different framebuffer can run. */
	public static void flush() {
		RenderPass pass = current;
		current = null;
		colours = null;
		depth = null;
		if (pass != null) {
			pass.close();
		}
	}

	private static boolean matches(RenderPassDescriptor descriptor) {
		if (current == null || colours == null) {
			return false;
		}

		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> listed =
				descriptor.colorAttachments();
		if (listed.size() != colours.length) {
			return false;
		}

		for (int index = 0; index < colours.length; index++) {
			RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = listed.get(index);
			GpuTexture texture = texture(attachment);
			if (texture != colours[index]) {
				return false;
			}
		}

		GpuTexture nextDepth = descriptor.depthAttachment() == null
				? null
				: descriptor.depthAttachment().textureView().texture();
		if (nextDepth != depth) {
			return false;
		}

		RenderPass.RenderArea area = descriptor.renderArea;
		return area != null && area.x() == areaX && area.y() == areaY && area.width() == areaW
				&& area.height() == areaH;
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
}
