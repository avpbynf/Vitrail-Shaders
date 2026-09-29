package dev.vitrail.mixin.game;

import dev.vitrail.render.PackChain;

import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.phase.SimpleFeatureRenderPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Submits the cracks over a block being mined into the breaking overlay phase while a pack draws,
 * which is where 26.2 submitted all of them.
 * <p>
 * 26.3 sends the cracks over an opaque block to the solid phase instead, and keeps the breaking
 * overlay phase for a translucent one ({@code SubmitNodeCollection.submitBreakingBlockModel}, and
 * {@code submitCrumblingOverlay} for a block entity). The row that draws them with the pack's
 * {@code gbuffers_damagedblock} is bound to the translucent features, because the pipeline blends,
 * and it is there that the game executes the breaking overlay phase
 * ({@code FeatureRenderDispatcher.executeTranslucent}, after the translucent blocks and items). Met
 * in the solid features, the draw went back to the game, onto its own picture, which the pack's
 * image covers: every crack over stone, wood or dirt was gone, and the cracks over glass stayed.
 * <p>
 * The move comes with 26.3's order independent transparency, under which the breaking overlay
 * phase IS the order independent one ({@code SubmitNodeCollection}'s constructor), and 26.3 sends
 * only a translucent block's cracks through it, with a crumbling pipeline of its own for that pass
 * ({@code RenderPipelines.OIT_CRUMBLING}). While a pack draws that transparency is off
 * ({@code GameRendererTransparencyMixin}), so the phase this hands back is the plain one, executed
 * exactly where 26.2 executed it. Without a pack nothing changes.
 * <p>
 * The choice is made in two methods and both are edited. One is the game's own
 * {@code submitBreakingBlockModel(PoseStack, List, int, boolean)}. The other is the overload that
 * Fabric API's renderer module adds, which takes the block's mesh as well: a copy of the game's
 * body that builds the module's own {@code ExtendedBlockModelSubmit}, and the method the module
 * sends every crack over a block in the level to in place of the game's. The module is in Fabric
 * API and in the copy of it that Sodium carries, so on Fabric the overload decides and the game's
 * method is never called for a block in the level. Editing the game's method alone would leave
 * every crack over an opaque block in the solid features there, under the pack's image. The module
 * has no overload of {@code submitCrumblingOverlay}, so that one needs no second road.
 * <p>
 * Two rules of Mixin decide how the overload is reached. A method another mixin merged is selected
 * only by its full descriptor: a name alone selects the first method of that name in the class,
 * which is the game's, and a wildcard skips a merged method. And a merged method may be injected
 * into only by a mixin of strictly higher priority than the one that merged it
 * ({@code InjectionPoint.checkPriority}). The module's mixin has the default 1000, so this one has
 * 1100: at 1000 or below the injector is refused when the class is applied, and the game does not
 * start. Neither rule depends on which config applied first, since Mixin merges every mixin's
 * methods into a class before it resolves any injection target. The priority changes nothing for
 * the game's method, which nothing merged, and no other Vitrail mixin injects where these do.
 * <p>
 * The overload is optional: NeoForge has no such module, and where Fabric API is not loaded the
 * descriptor selects nothing, which the injector's {@code require = 0} lets pass. The game's method
 * is not optional, and its injector requires it.
 */
@Mixin(value = SubmitNodeCollection.class, priority = 1100)
public abstract class SubmitNodeCollectionCrumblingMixin {

	@ModifyVariable(method = "submitBreakingBlockModel"
			+ "(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V",
			at = @At("STORE"), require = 1)
	private SimpleFeatureRenderPhase vitrail$breakingBlock(SimpleFeatureRenderPhase chosen) {
		return vitrail$overlay(chosen);
	}

	// require = 0 against this config's default of one, and expect = 0 so a debug run that counts
	// injections does not object either: the method exists only where Fabric API's renderer module
	// is loaded, and NeoForge, or Fabric without the module, has none to edit. The descriptor names
	// the module's Mesh type as a string and loads nothing, so the class need not be present. The
	// phase is the one local of its type in the overload, as it is in the game's method.
	@ModifyVariable(method = "submitBreakingBlockModel"
			+ "(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;"
			+ "Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/Mesh;IZ)V",
			at = @At("STORE"), require = 0, expect = 0)
	private SimpleFeatureRenderPhase vitrail$breakingBlockMesh(SimpleFeatureRenderPhase chosen) {
		return vitrail$overlay(chosen);
	}

	@ModifyVariable(method = "submitCrumblingOverlay", at = @At("STORE"), require = 1)
	private SimpleFeatureRenderPhase vitrail$crumblingOverlay(SimpleFeatureRenderPhase chosen) {
		return vitrail$overlay(chosen);
	}

	private SimpleFeatureRenderPhase vitrail$overlay(SimpleFeatureRenderPhase chosen) {
		return PackChain.drawingPack()
				? ((SubmitNodeCollection) (Object) this).breakingOverlay
				: chosen;
	}
}
