package dev.vitrail.mixin;

import dev.vitrail.screen.VitrailDebugEntry;

import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Shows {@link VitrailDebugEntry} on the F3 overlay without a hand's turn of configuration, the
 * way Iris shows its own: registering an entry only lists it on the F3 configuration screen, and a
 * line nobody has switched on answers no screenshot. The status is written only while the entry
 * has none, so a player who turns the line off stays obeyed - their choice is a status too, and it
 * is theirs from then on.
 */
@Mixin(DebugScreenEntryList.class)
public abstract class DebugScreenEntryListMixin {

	@Shadow
	@Final
	private Map<Identifier, DebugScreenEntryStatus> allStatuses;

	@Inject(method = "rebuildCurrentList", at = @At("HEAD"))
	private void vitrail$showByDefault(CallbackInfo ci) {
		this.allStatuses.putIfAbsent(VitrailDebugEntry.ID, DebugScreenEntryStatus.IN_OVERLAY);
	}
}
