package dev.vitrail.mixin.game;

import dev.vitrail.render.GlintSplit;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes, as the game makes them, which texture each combined model render type was made for, so
 * that {@code GlintSplit} can hand back its plain half.
 * <p>
 * Here and not at the submission, because the texture is an argument here and a private field of a
 * package private record there. The two factories are the only ones a model with a glint is
 * submitted through: armour in {@code EquipmentLayerRenderer}, the trident and the shield in their
 * special renderers and the thrown trident in its own.
 */
@Mixin(RenderTypes.class)
public abstract class RenderTypesGlintMixin {

	@Inject(method = "armorCutoutNoCullGlint", at = @At("RETURN"), require = 1)
	private static void vitrail$armour(Identifier texture, CallbackInfoReturnable<RenderType> callback) {
		GlintSplit.armour(callback.getReturnValue(), texture);
	}

	@Inject(method = "entitySolidGlint", at = @At("RETURN"), require = 1)
	private static void vitrail$entity(Identifier texture, CallbackInfoReturnable<RenderType> callback) {
		GlintSplit.entity(callback.getReturnValue(), texture);
	}
}
