package dev.vitrail.mixin;

import dev.vitrail.sodium.TerrainVertex;
import dev.vitrail.render.BlockStateIds;
import dev.vitrail.render.TerrainDraw;
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
 * {@link dev.vitrail.sodium.TerrainVertex} says why that is the only place that survives.
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

	/**
	 * The table this reading was last taken against, or -1 for none. A flag of the process would
	 * report the first pack of the session and stay silent for every pack after it, which is the one
	 * moment the reading is worth taking: the numbers are the pack's own and no two packs share them.
	 */
	@Unique
	private static final java.util.concurrent.atomic.AtomicInteger vitrail$said =
			new java.util.concurrent.atomic.AtomicInteger(-1);

	@Unique
	private int vitrail$id = BlockStateIds.NONE;

	/** Where the fluid stands in its section and what it emits, taken with the id. */
	@Unique
	private int vitrail$origin;

	/**
	 * <strong>Every parameter of the target, in order, and not a prefix of them.</strong> Mixin
	 * refuses a truncated list outright, "Invalid descriptor", and the ten below are the price of
	 * reading the third. Writing {@code Object} for one of them compiles and matches nothing at all.
	 * <p>
	 * {@code require = 1} is what makes the next Sodium release say so at load rather than at
	 * leisure: unrequired, a descriptor that stops matching fails in silence and shows up as water
	 * that is no longer water. It says out loud what this config now defaults to.
	 */
	@Inject(method = "render", at = @At("HEAD"), require = 1)
	private void vitrail$take(LevelSlice level, BlockState blockState, FluidState fluidState,
			BlockPos pos, BlockPos origin, TranslucentGeometryCollector collector,
			ChunkModelBuilder builder, Material material, ColorProvider<FluidState> colours,
			FluidModel model, CallbackInfo callback) {
		BlockState fluidBlock = fluidState == null ? null : fluidState.createLegacyBlock();
		this.vitrail$id = fluidBlock == null ? BlockStateIds.NONE : BlockStateIds.packed(fluidBlock);

		// The fluid's own light and not that of the block sharing its position, for the reason the
		// class comment gives about the id: a waterlogged stair is two things to draw, and these
		// quads are the water's.
		this.vitrail$origin = pos == null
				? 0
				: TerrainVertex.pack(pos.getX(), pos.getY(), pos.getZ(),
						fluidBlock == null ? 0 : fluidBlock.getLightEmission());

		// Said once for the first fluid meshed under each table, because every link after it is
		// invisible: a number that never leaves this method looks exactly like a number that reached
		// the shader and was ignored, and the two are a mixin apart.
		//
		// And not said at all when the mesh was never extended, which is exactly the case this line
		// exists to tell apart: the id below is computed and then goes nowhere, and printing it
		// beside a startup line saying the mesh carries no block id would be the engine
		// contradicting itself inside one log.
		int table = BlockStateIds.generation();
		if (TerrainDraw.asked() && vitrail$said.getAndSet(table) != table) {
			// The table is named, because this line is not the only one of the run: two loads
			// of the same pack print it twice, word for word, and nothing else would say which
			// reading belongs to which table.
			Vitrail.logger().info("The first fluid meshed against block table {} is {} and it "
					+ "carries the packed id {}", table,
					fluidBlock == null ? "nothing" : fluidBlock, this.vitrail$id);
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
		return TerrainVertex.stamp(TerrainVertex.stampOrigin(vertices, this.vitrail$origin), this.vitrail$id);
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
		return TerrainVertex.stamp(TerrainVertex.stampOrigin(vertices, this.vitrail$origin), this.vitrail$id);
	}
}
