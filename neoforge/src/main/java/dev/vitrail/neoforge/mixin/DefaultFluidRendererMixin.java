package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.sodium.TerrainVertex;
import dev.vitrail.render.BlockStateIds;
import dev.vitrail.Vitrail;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.model.color.ColorProvider;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.core.BlockPos;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives a fluid quad the number {@code block.properties} gave the fluid, which is what tells a pack
 * that water is water.
 * <p>
 * Fluids never went through {@code BlockRenderer} at all: they have a renderer of their own, and its
 * push takes a {@code Material} rather than the int the block path hands over, so there was nowhere
 * to put an id even before the translucent sorter took the quad. On the vertices there is, and
 * {@link dev.vitrail.neoforge.sodium.TerrainVertex} says why that is the only place that survives.
 * <p>
 * The state is taken at the head of {@code render} and kept on the renderer, because
 * {@code writeQuad} is handed the position and the model but not what is standing there. That is
 * safe without any locking: Sodium builds chunks on several threads and each of them owns its own
 * {@code BlockRenderCache}, so each owns this renderer, and one instance is only ever inside one
 * {@code render} at a time.
 * <p>
 * <strong>The fluid's own block and not the block at that position.</strong> A waterlogged stair is
 * one block state holding two things to draw, and the id a pack matches {@code minecraft:water}
 * against is the water's, not the stair's. {@code createLegacyBlock} is what turns the fluid state
 * back into the block state the pack's own table was built from.
 */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class DefaultFluidRendererMixin {

	@Unique
	private static final java.util.concurrent.atomic.AtomicBoolean vitrail$said =
			new java.util.concurrent.atomic.AtomicBoolean();

	@Unique
	private int vitrail$id = BlockStateIds.NONE;

	/**
	 * <strong>Every parameter of the target, in order, and not a prefix of them.</strong> Mixin
	 * refuses a truncated list outright, "Invalid descriptor", and the ten below are the price of
	 * reading the third. Writing {@code Object} for one of them compiles and matches nothing at all.
	 * <p>
	 * {@code require = 1} is what makes the next Sodium release say so at load rather than at
	 * leisure: with the {@code defaultRequire: 0} this config carries, a descriptor that stops
	 * matching fails in silence and shows up as water that is no longer water.
	 */
	@Inject(method = "render", at = @At("HEAD"), require = 1)
	private void vitrail$take(LevelSlice level, BlockState blockState, FluidState fluidState,
			BlockPos pos, BlockPos origin, TranslucentGeometryCollector collector,
			ChunkModelBuilder builder, Material material, ColorProvider<FluidState> colours,
			FluidModel model, CallbackInfo callback) {
		this.vitrail$id = fluidState == null
				? BlockStateIds.NONE
				: BlockStateIds.packed(fluidState.createLegacyBlock());

		// Said once for the first fluid meshed, because every link after it is invisible: a number
		// that never leaves this method looks exactly like a number that reached the shader and was
		// ignored, and the two are a mixin apart.
		if (vitrail$said.compareAndSet(false, true)) {
			Vitrail.logger().info("The first fluid meshed is {} and it carries the packed id {}",
					fluidState == null ? "nothing" : fluidState.createLegacyBlock(), this.vitrail$id);
		}
	}

	/** The path taken when nothing sorts this quad, which is every fluid the pack draws opaquely. */
	@ModifyArg(
			method = "writeQuad",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/"
							+ "ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/"
							+ "chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/"
							+ "sodium/client/render/chunk/terrain/material/Material;)V"),
			index = 0, require = 1)
	private ChunkVertexEncoder.Vertex[] vitrail$id(ChunkVertexEncoder.Vertex[] vertices) {
		return TerrainVertex.stamp(vertices, this.vitrail$id);
	}

	/** The path every water surface really takes, the sorter's. */
	@ModifyArg(
			method = "writeQuad",
			at = @At(value = "INVOKE",
					target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/"
							+ "TranslucentGeometryCollector;appendQuad([Lnet/caffeinemc/mods/sodium/"
							+ "client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;"
							+ "Lnet/caffeinemc/mods/sodium/client/model/quad/properties/"
							+ "ModelQuadFacing;I)Z"),
			index = 0, require = 1)
	private ChunkVertexEncoder.Vertex[] vitrail$sortedId(ChunkVertexEncoder.Vertex[] vertices) {
		return TerrainVertex.stamp(vertices, this.vitrail$id);
	}
}
