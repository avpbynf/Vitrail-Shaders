package dev.vitrail.mixin.game;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The one way a render type is made, which 26.3 keeps package private. {@code GlintSplit} makes the
 * item's glint pass through it, the render type 26.2 had and 26.3 folded into the item's own.
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {

	@Invoker("create")
	static RenderType vitrail$create(String name, RenderSetup state) {
		throw new AssertionError("an invoker's body is replaced as the class is loaded");
	}
}
