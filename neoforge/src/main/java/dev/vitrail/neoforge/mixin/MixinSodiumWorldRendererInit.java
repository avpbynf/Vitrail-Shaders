package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.sodium.TerrainMesh;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one instant at which the chunk mesh format may change, and the reason it needs an instant of
 * its own.
 * <p>
 * Sodium reads the format in three places and only two of them are the section manager's
 * constructor, which asks twice: for the builder that writes meshes at its stride, and for the
 * renderer that binds its layout. The third is a region's device resources, built on demand at that
 * region's first upload and again after the region has dropped them, so it goes on asking all
 * through an ordinary session as the player moves. An answer free to move between two of those asks
 * would size one region's geometry arena at a stride nothing is writing; the arena multiplies
 * segment offsets by that stride, so uploads land in the wrong place, no side reports anything and
 * the world draws out of garbage.
 * <p>
 * <strong>Here nothing holding a stride is alive across the change.</strong> This method is the only
 * place the section manager is built, reached from {@code reload} and from {@code loadLevel}; it
 * deletes the previous manager and its regions, and every section is meshed again after it. So the
 * answer is taken once here and merely read everywhere else.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRendererInit {

	@Inject(method = "initRenderer", at = @At("HEAD"))
	private void vitrail$settle(CallbackInfo callback) {
		TerrainMesh.settle();
	}
}
