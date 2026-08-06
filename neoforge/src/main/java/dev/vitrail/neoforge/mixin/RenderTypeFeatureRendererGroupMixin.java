package dev.vitrail.neoforge.mixin;

import dev.vitrail.neoforge.BlockEntityMark;
import dev.vitrail.neoforge.BlockEntityOrigin;
import dev.vitrail.render.BlockEntityGeometry;
import dev.vitrail.render.EntityDraw;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Where a submission's origin becomes a draw's origin, and where two origins are kept out of one
 * draw.
 * <p>
 * <strong>The second half is the one that is easy to miss, and there are TWO ways into it, not
 * one.</strong> The obvious one is the reuse inside {@code getOrAddDraw}, and it is narrower than it
 * looks: a group takes an existing draw when the prepared render type it would make compares equal
 * to one it already holds, AND the group may reorder, which a strictly ordered one may not, AND the
 * type consolidates consecutive geometry. The one that costs a review is above it.
 * {@code getVertexBuilder} hands back {@code lastDraw} without
 * calling {@code getOrAddDraw} at all whenever the previous submission carried the SAME
 * {@code RenderType} instance and that type consolidates, which every quad type does; render types
 * are memoized per texture, and the storages batch by them, so a run of submissions of one type is
 * the ordinary case rather than the exception. Guarding only the first way leaves the second wide
 * open, and what comes through it is a whole batch drawn under the origin of whichever submission
 * happened to be first.
 * <p>
 * Both are answered the same way, by refusing the reuse rather than by trying to describe it: the
 * head of {@code getVertexBuilder} drops {@code lastDraw} when the origin has changed, which sends
 * the call into {@code getOrAddDraw}, and {@code indexOf} there refuses a match of the other origin.
 * <p>
 * <strong>What it costs is one draw per alternation and not one draw in all</strong>, and the reason
 * is worth knowing before anybody prices it lower: {@code indexOf} answers with the FIRST match, so
 * a refusal appends a second entry equal to it, and the next lookup finds the first one again and
 * refuses again. Geometry that really alternates therefore pays a draw each time it comes back, not
 * once. Nothing that does not alternate pays anything.
 * <p>
 * <strong>No pair of the vanilla game is known to reach it, and that is not a reason to leave it
 * open.</strong> Which pieces of the game are and are not rows of the table is {@code EntityDraw}'s
 * to say and is not repeated here; what matters at this end is that the cost of being wrong is
 * silent and the cost of the guard is one draw. The table is keyed by pipeline, a texture is all
 * that separates two of its rows, and nothing anywhere promises that no mob will ever share a sheet
 * with a block entity.
 * <p>
 * Both are guarded by {@link EntityDraw#wanted()}: this is a change to how the game groups its own
 * geometry, and it has no business happening when nothing is going to read it.
 */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
public abstract class RenderTypeFeatureRendererGroupMixin {

	@Shadow
	@Final
	private List<StagedVertexBuffer.Draw> draws;

	/** The draw the last submission of this group went into, or null when there was none. */
	@Shadow
	private StagedVertexBuffer.Draw lastDraw;

	/**
	 * Drops the shortcut when the origin has changed, which is the whole of the second way in.
	 * <p>
	 * Null and not a decision of our own: what follows in the method is the group's own logic, and
	 * dropping the last draw is exactly the state it is already written to handle, the first
	 * submission of a group. It then goes through {@code getOrAddDraw}, where both the mark and the
	 * refusal of a foreign match live, so the two handlers below stay the only place that decides
	 * anything.
	 */
	@Inject(method = "getVertexBuilder", at = @At("HEAD"), require = 1)
	private void vitrail$breakTheRun(RenderType renderType,
			CallbackInfoReturnable<VertexConsumer> callback) {
		if (this.lastDraw != null && EntityDraw.wanted()
				&& ((BlockEntityOrigin) this.lastDraw).vitrail$fromBlockEntity()
						!= BlockEntityGeometry.building()) {
			this.lastDraw = null;
		}
	}

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
		((BlockEntityMark) draw).vitrail$fromBlockEntity(BlockEntityGeometry.building());

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
