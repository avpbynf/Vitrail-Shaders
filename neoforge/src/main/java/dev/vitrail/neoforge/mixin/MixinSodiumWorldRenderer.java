package dev.vitrail.neoforge.mixin;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the two pieces of state the shadow stage walks the world with: the section manager the
 * light's cull runs against, and the fog the camera's own walk was bounded by, so both walks of a
 * frame measure distance the same way.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface MixinSodiumWorldRenderer {

	@Accessor("renderSectionManager")
	RenderSectionManager vitrail$renderSectionManager();

	@Accessor("lastFogParameters")
	FogParameters vitrail$lastFogParameters();
}
