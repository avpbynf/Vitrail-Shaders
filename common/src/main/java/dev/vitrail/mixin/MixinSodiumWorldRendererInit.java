package dev.vitrail.mixin;

import dev.vitrail.render.EntityMesh;
import dev.vitrail.sodium.TerrainMesh;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one instant at which either mesh format may change, and the reason they need an instant of
 * their own.
 * <p>
 * The chunk mesh is the harder of the two and the rest of this note is about it; the entity one wants
 * the same instant for a reason of its own, {@code EntityMesh} saying which, and takes it here rather
 * than opening a second door onto the same frame boundary.
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
 * constructor asks {@code ChunkMeshFormats.getCurrent}.</strong> That is narrower than saying
 * nothing holding a stride is alive here, and it is what was measured: this runs before
 * {@code deleteRendererState}, so the previous manager and its regions are still standing, and an
 * exhaustive pass over the 688 classes of the mod jar inside 0.9.1 finds no caller of that method on
 * the road between. Everything that will ask for the format is built afterwards, and every section
 * is meshed again after that.
 * <p>
 * <strong>{@code require = 1} because this injection is now the only writer of the answer</strong>,
 * and the mixin config leaves injections optional by default. What a Sodium rename would otherwise
 * cost is not silence: the mesh would stay on Sodium's own, the first chunk pass would find a format
 * a terrain program cannot read, and the pack would be put away with two errors in the log. It is
 * that the failure would land a world late and look like a defect of the pack, where refusing to
 * load names the one thing that actually moved.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class MixinSodiumWorldRendererInit {

	@Inject(method = "initRenderer", at = @At("HEAD"), require = 1)
	private void vitrail$settle(CallbackInfo callback) {
		TerrainMesh.settle();
		EntityMesh.settle();
	}
}
