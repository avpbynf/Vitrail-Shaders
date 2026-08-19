package dev.vitrail.mixin;

import dev.vitrail.screen.VitrailDebugEntry;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts {@link VitrailDebugEntry} into the F3 screen's registry, at the end of the class
 * initializer that builds it, which is where Iris registers its own two entries: the registry is a
 * plain map behind a private {@code register}, the game offers no seam for a mod's entry, and
 * registering as the map is born means being there however early the first F3 opens.
 */
@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesMixin {

	@Shadow
	private static Identifier register(Identifier identifier, DebugScreenEntry entry) {
		throw new AssertionError();
	}

	@Inject(method = "<clinit>", at = @At("RETURN"))
	private static void vitrail$register(CallbackInfo ci) {
		register(VitrailDebugEntry.ID, new VitrailDebugEntry());
	}
}
