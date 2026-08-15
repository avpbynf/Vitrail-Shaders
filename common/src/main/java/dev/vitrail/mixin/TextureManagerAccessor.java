package dev.vitrail.mixin;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Opens the game's own register of loaded textures, so that an image bound to a sampler can be
 * named rather than described.
 * <p>
 * <strong>The label a texture carries is not an answer here.</strong> A texture loaded from a
 * resource is created with its identifier as its label ({@code ReloadableTexture.java:42}), but the
 * backend keeps it only while validation layers are on: {@code VulkanDevice.java:200} stores the
 * empty string otherwise, and this engine runs against a device that has them off. Reading the
 * label would print nothing at all and read as an image with no name.
 * <p>
 * This register is the other end of the same fact and holds under any device: it is what
 * {@code RenderSetup.prepareTextures} looks a render type's texture up in
 * ({@code RenderSetup.java:98}), so the view it hands a draw is one of the views held here.
 */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {

	@Accessor("byPath")
	Map<Identifier, AbstractTexture> vitrail$byPath();
}
