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
 * <strong>What makes this instant the safe one is that nothing between it and the section manager's
 * constructor reads the format.</strong> That is weaker than saying nothing holding a stride is
 * alive here, and it is what was measured: this runs before {@code deleteRendererState}, so the
 * previous manager and its regions are still standing, and an exhaustive pass over the 688 classes
 * of the 0.9.1 jar finds no reader of {@code ChunkMeshFormats.getCurrent} on that road. Everything
 * that will read the format afterwards is built after it, and every section is meshed again.
 * <p>
 * <strong>{@code require = 1} because this injection is now the only writer of the answer.</strong>
 * A rename on Sodium's side would otherwise leave it silent rather than absent: the mesh would stay
 * on Sodium's own for the whole run, no terrain program would ever draw, and nothing would be
 * printed, since the line that announces the decision is on the other side of this call.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRendererInit {

	@Inject(method = "initRenderer", at = @At("HEAD"), require = 1)
	private void vitrail$settle(CallbackInfo callback) {
		TerrainMesh.settle();
	}
}
