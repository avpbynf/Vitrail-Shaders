package dev.vitrail.render;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import org.joml.Vector4fc;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Builds the descriptor a render pass is opened from. Minecraft 26.2 fills a descriptor in place
 * and 26.3 builds a record out of a builder; the attachments, their clears and the area are the
 * same on both, and this class is the one spelling the engine writes against.
 * <p>
 * The 26.3 half: every call lands on the game's builder, and {@link #build} is its own. One thing
 * the record does that the 26.2 descriptor did not is fill in an area where none was set, the
 * extent of the first attachment, so a built descriptor always carries one.
 */
public final class PassDescriptor {

	private final RenderPassDescriptor.Builder builder;

	private PassDescriptor(Supplier<String> label) {
		this.builder = RenderPassDescriptor.builder(label);
	}

	/** Starts a descriptor for a pass labelled as {@code label} says. */
	public static PassDescriptor create(Supplier<String> label) {
		return new PassDescriptor(label);
	}

	/** Attaches a colour image, loaded as it stands. */
	public PassDescriptor withColorAttachment(GpuTextureView view) {
		this.builder.withColorAttachment(view);
		return this;
	}

	/** Attaches a colour image, emptied to {@code clear} where one is given. */
	public PassDescriptor withColorAttachment(GpuTextureView view, Optional<Vector4fc> clear) {
		this.builder.withColorAttachment(view, clear);
		return this;
	}

	/** Holds a colour slot no image stands behind, so the next one keeps its index. */
	public PassDescriptor withUnusedColorAttachment() {
		this.builder.withUnusedColorAttachment();
		return this;
	}

	/** Attaches a depth image, loaded as it stands. */
	public PassDescriptor withDepthAttachment(GpuTextureView view) {
		this.builder.withDepthAttachment(view);
		return this;
	}

	/** Attaches a depth image, emptied to {@code clear} where one is given. */
	public PassDescriptor withDepthAttachment(GpuTextureView view, OptionalDouble clear) {
		this.builder.withDepthAttachment(view, clear);
		return this;
	}

	/** Restricts the pass to one area of its attachments. */
	public PassDescriptor withRenderArea(RenderPass.RenderArea area) {
		this.builder.withRenderArea(area);
		return this;
	}

	/** The descriptor as the game's encoder takes it. */
	public RenderPassDescriptor build() {
		return this.builder.build();
	}
}
