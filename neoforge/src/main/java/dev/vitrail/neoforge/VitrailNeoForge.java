package dev.vitrail.neoforge;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.PackReport;
import dev.vitrail.render.PackChain;
import dev.vitrail.screen.SettingsScreen;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Vitrail.MOD_ID, dist = Dist.CLIENT)
public final class VitrailNeoForge {

	public VitrailNeoForge(IEventBus modBus, ModContainer container) {
		Vitrail.initClient(new NeoForgePlatform());

		// The Config button of the mod list, and the same button under the NeoForge icon of the
		// pause menu. A two argument lambda rather than a supplier, which is the overload it
		// would otherwise pick.
		container.registerExtensionPoint(IConfigScreenFactory.class,
				(mod, modListScreen) -> new SettingsScreen(modListScreen));

		VitrailKeys.register(modBus);
		PauseMenuEntry.register();

		modBus.addListener(FMLClientSetupEvent.class, this::onClientSetup);

		// AfterLevel fires in GameRenderer.renderLevel once LevelRenderer is done and
		// before anything else touches the main target, outside of any render pass the
		// game has open. That is the whole reason this hook is an event and not a mixin.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class, this::onAfterLevel);

		// Stopping, not Stopped: the latter is posted after the renderer has been shut down,
		// and the targets have to be released while the device is still alive.
		NeoForge.EVENT_BUS.addListener(ClientStoppingEvent.class, this::onClientStopping);
	}

	private void onClientSetup(FMLClientSetupEvent event) {
		Vitrail.logger().info("Client setup reached, Sodium is {}",
				Vitrail.platform().isModLoaded("sodium") ? "present" : "missing");

		// Reading every pack in the folder rather than one: this stage is judged by comparing
		// its counts against measurements taken from a whole corpus, and one line per pack is
		// what makes that comparison possible.
		PackReport.logAll(Vitrail.platform().gameDirectory());

		PackChain.load(Vitrail.platform().gameDirectory());
	}

	private void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
		// Nothing is drawn when no pack can be: the game's own image is a better answer than
		// anything this mod could put over it, and the reason is already said, once in the log
		// and again on the settings screen through PackChain.lastError.
		PackChain.draw(Vitrail.platform().gameDirectory());
	}

	private void onClientStopping(ClientStoppingEvent event) {
		PackChain.close();
	}
}
