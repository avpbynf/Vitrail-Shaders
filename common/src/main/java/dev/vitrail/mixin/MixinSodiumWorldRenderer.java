package dev.vitrail.mixin;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the section manager the light's cull runs against. The camera's fog is not taken with
 * it: the light's walk is bounded by the render distance and by the pack's, and by no fog of the
 * camera's, which is the note {@code ShadowTerrain} carries beside the walk.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface MixinSodiumWorldRenderer {

	@Accessor("renderSectionManager")
	RenderSectionManager vitrail$renderSectionManager();
}
