package dev.vitrail.render;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.joml.Vector4fc;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Builds the descriptor a render pass is opened from. Minecraft 26.2 fills a descriptor in place
 * and 26.3 builds a record out of a builder; the attachments, their clears and the area are the
 * same on both, and this class is the one spelling the engine writes against.
 * <p>
 * The 26.2 half: every call lands on the game's own descriptor as it always did, and
 * {@link #build} hands that very object back.
 */
public final class PassDescriptor {

	private final RenderPassDescriptor descriptor;

	private PassDescriptor(Supplier<String> label) {
		this.descriptor = RenderPassDescriptor.create(label);
	}

	/** Starts a descriptor for a pass labelled as {@code label} says. */
	public static PassDescriptor create(Supplier<String> label) {
		return new PassDescriptor(label);
	}

	/** Attaches a colour image, loaded as it stands. */
	public PassDescriptor withColorAttachment(GpuTextureView view) {
		this.descriptor.withColorAttachment(view);
		return this;
	}

	/** Attaches a colour image, emptied to {@code clear} where one is given. */
	public PassDescriptor withColorAttachment(GpuTextureView view, Optional<Vector4fc> clear) {
		this.descriptor.withColorAttachment(view, clear);
		return this;
	}

	/** Holds a colour slot no image stands behind, so the next one keeps its index. */
	public PassDescriptor withUnusedColorAttachment() {
		this.descriptor.withUnusedColorAttachment();
		return this;
	}

	/** Attaches a depth image, loaded as it stands. */
	public PassDescriptor withDepthAttachment(GpuTextureView view) {
		this.descriptor.withDepthAttachment(view);
		return this;
	}

	/** Attaches a depth image, emptied to {@code clear} where one is given. */
	public PassDescriptor withDepthAttachment(GpuTextureView view, OptionalDouble clear) {
		this.descriptor.withDepthAttachment(view, clear);
		return this;
	}

	/** Restricts the pass to one area of its attachments. */
	public PassDescriptor withRenderArea(RenderPass.RenderArea area) {
		this.descriptor.withRenderArea(area);
		return this;
	}

	/** The descriptor as the game's encoder takes it. */
	public RenderPassDescriptor build() {
		return this.descriptor;
	}
}
