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

	// require = 0 against this config's default of one, and what it tolerates is precisely the
	// target going away: RETURN cannot fail to match inside a class initializer that exists, so the
	// only thing left for the guard to catch is the game losing this class or its initializer. The
	// two places in this package where that is worth tolerating are this one and the status beside
	// it: what is lost is a line of the F3 overlay, the picture is drawn exactly the same without
	// it, and the absence is plain to whoever opens the screen. Refusing to start the game over a
	// debug entry would cost more than the entry.
	@Inject(method = "<clinit>", at = @At("RETURN"), require = 0)
	private static void vitrail$register(CallbackInfo ci) {
		register(VitrailDebugEntry.ID, new VitrailDebugEntry());
	}
}
