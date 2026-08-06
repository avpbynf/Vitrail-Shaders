package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.BlockEntityOrigin;
import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.EntityDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Where a submission's origin becomes a draw's origin, and where two origins are kept out of one
 * draw.
 * <p>
 * <strong>The second half is the one that is easy to miss.</strong> A group reuses an existing draw
 * whenever the prepared render type it would make compares equal to one it already holds, and a
 * prepared render type is a record of the pipeline, the output target, the dynamic transforms, the
 * scissor and the textures. Two of those are the same for everything drawn in one level: the
 * transforms are written once per identical value, and every ordinary entity piece is drawn with the
 * frame's own camera. So the comparison comes down to the pipeline and the texture, and there is a
 * real pair that matches on both: a player head, which is a block entity wearing the player's skin,
 * and the player. Left alone, the two would share a draw and the door would have to answer for both
 * at once with one program.
 * <p>
 * The answer is to refuse the reuse rather than to try to describe it, which costs one draw in that
 * pair and nothing anywhere else. It is guarded by {@link EntityDraw#wanted()}: this is a change to
 * how the game groups its own geometry, and it has no business happening when nothing is going to
 * read it.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class RenderTypeFeatureRendererGroupMixin {

	@Shadow
	@Final
	private List<StagedVertexBuffer.Draw> draws;

	@WrapOperation(method = "getOrAddDraw", require = 1,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;"
					+ "appendDraw(Lcom/mojang/blaze3d/vertex/VertexFormat;"
					+ "Lcom/mojang/blaze3d/PrimitiveTopology;"
					+ "Lcom/mojang/blaze3d/vertex/VertexSorting;)"
					+ "Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;"))
	private StagedVertexBuffer.Draw vitrail$mark(StagedVertexBuffer buffer, VertexFormat format,
			PrimitiveTopology topology, VertexSorting sorting,
			Operation<StagedVertexBuffer.Draw> original) {
		StagedVertexBuffer.Draw draw = original.call(buffer, format, topology, sorting);
		((BlockEntityOrigin) draw).vitrail$fromBlockEntity(BlockEntityGeometry.building());

		return draw;
	}

	@WrapOperation(method = "getOrAddDraw", require = 1,
			at = @At(value = "INVOKE", target = "Ljava/util/List;indexOf(Ljava/lang/Object;)I"))
	private int vitrail$keepApart(List<?> types, Object prepared, Operation<Integer> original) {
		int found = original.call(types, prepared);
		if (found == -1 || !EntityDraw.wanted()) {
			return found;
		}

		// Minus one rather than a search for a later match with the right origin: the two are the
		// same picture and one of them is a draw more, on a pair that is rare to begin with.
		return ((BlockEntityOrigin) this.draws.get(found)).vitrail$fromBlockEntity()
				== BlockEntityGeometry.building() ? found : -1;
	}
}
