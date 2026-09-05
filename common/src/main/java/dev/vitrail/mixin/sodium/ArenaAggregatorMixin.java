package dev.vitrail.mixin.sodium;

import net.caffeinemc.mods.sodium.client.gpu.arena.ArenaAggregator;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Sizes Sodium 0.9.2's shared geometry arena at the pack's stride, the same answer
 * {@link ChunkMeshFormatsMixin} already gives everyone else.
 * <p>
 * 0.9.1 has no {@code ArenaAggregator}. 0.9.2 added one and baked
 * {@code ChunkMeshFormats.COMPACT} into it at construction, twenty bytes, then refused any other
 * width. The three readers that already asked {@code getCurrent()} still do: the chunk builder, the
 * chunk renderer, and a region's device resources. Those three see the pack's mesh. The allocator
 * did not, so the resources asked it for forty and it threw {@code Unsupported stride}.
 * <p>
 * The class is absent from the 0.9.1 the mod still accepts at runtime, which is why
 * {@code @Pseudo} lets 0.9.1 skip the mixin rather than refuse to load. The redirect is required
 * when the class is there: a silent miss would be the same crash the player already hit, arriving
 * a world late.
 * <p>
 * Construction is after {@code TerrainMesh.settle}, at the head of {@code initRenderer}, so
 * {@code getCurrent()} here is the format the builder is about to write. That is the same instant
 * {@code MixinSodiumWorldRendererInit} already chose, not a second one.
 */
@Pseudo
@Mixin(value = ArenaAggregator.class, remap = false)
public abstract class ArenaAggregatorMixin {

	@Redirect(
			method = "<init>",
			at = @At(
					value = "FIELD",
					opcode = Opcodes.GETSTATIC,
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats;COMPACT:Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;"),
			require = 1)
	private static ChunkVertexType vitrail$geometryFormat() {
		return ChunkMeshFormats.getCurrent();
	}
}