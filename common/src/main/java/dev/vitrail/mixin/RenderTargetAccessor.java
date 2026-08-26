package dev.vitrail.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the four texture fields of a render target, for the swap the render scale performs on the
 * game's main target: the width and the height are public already, the textures and their views are
 * protected, and nothing else of the class is touched.
 * <p>
 * Setting these fields moves no GPU memory and closes nothing. Whoever installs a set of textures
 * here stays their owner and frees them itself; the target's own {@code destroyBuffers} must only
 * ever run while the target holds the set it allocated. That is why the swap is bracketed inside
 * one frame, and why a swap a dying frame left standing is put back at the HEAD of the next one:
 * the game's resize check runs before its first clear, and meeting scaled numbers there it would
 * destroy the stand-in textures as its own.
 */
@Mixin(RenderTarget.class)
public interface RenderTargetAccessor {

	@Accessor("colorTexture")
	GpuTexture vitrail$colorTexture();

	@Accessor("colorTexture")
	void vitrail$colorTexture(GpuTexture texture);

	@Accessor("colorTextureView")
	GpuTextureView vitrail$colorTextureView();

	@Accessor("colorTextureView")
	void vitrail$colorTextureView(GpuTextureView view);

	@Accessor("depthTexture")
	GpuTexture vitrail$depthTexture();

	@Accessor("depthTexture")
	void vitrail$depthTexture(GpuTexture texture);

	@Accessor("depthTextureView")
	GpuTextureView vitrail$depthTextureView();

	@Accessor("depthTextureView")
	void vitrail$depthTextureView(GpuTextureView view);
}
