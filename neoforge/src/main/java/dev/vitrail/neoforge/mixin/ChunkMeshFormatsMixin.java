package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.sodium.TerrainMesh;

import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Substitutes the chunk mesh format, which is the only way a pack's terrain program can be given a
 * block id.
 * <p>
 * One method and no state of its own: everything that needs to know the format asks here, the chunk
 * builder for the stride it writes at, the region for the size of its geometry arena, and the
 * renderer for the layout it binds. Iris does the same thing with two {@code ModifyArg}s on the two
 * calls, which only works while there are two.
 * <p>
 * <strong>The answer may change while the game runs</strong>, and all three read it from a
 * constructor the reload rebuilds, so none of them is left holding the old one. What has to stay
 * true is that nothing is meshed between the change and the reload; {@code TerrainDraw.wanted} asks
 * for both in that order.
 */
@Mixin(value = ChunkMeshFormats.class, remap = false)
public abstract class ChunkMeshFormatsMixin {

	@Inject(method = "getCurrent", at = @At("HEAD"), cancellable = true)
	private static void vitrail$format(CallbackInfoReturnable<ChunkVertexType> callback) {
		ChunkVertexType ours = TerrainMesh.current();
		if (ours != null) {
			callback.setReturnValue(ours);
		}
	}
}
