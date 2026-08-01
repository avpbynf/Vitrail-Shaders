package dev.vitrail.neoforge;

import dev.vitrail.Vitrail;
import dev.vitrail.pack.PackReport;
import dev.vitrail.render.PackFinalPass;
import dev.vitrail.render.ShaderChain;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Vitrail.MOD_ID, dist = Dist.CLIENT)
public final class VitrailNeoForge {

	public VitrailNeoForge(IEventBus modBus) {
		Vitrail.initClient(new NeoForgePlatform());

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

		ShaderChain.loadShaders();

		// Reading every pack in the folder rather than one: this stage is judged by comparing
		// its counts against measurements taken from a whole corpus, and one line per pack is
		// what makes that comparison possible.
		PackReport.logAll(Vitrail.platform().gameDirectory());

		PackFinalPass.load(Vitrail.platform().gameDirectory());
	}

	private void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
		// The pack's own pass when there is one, and the hand written chain otherwise. Running
		// both would have the second read what the first wrote, which is a chain nobody asked
		// for and an image neither of them describes.
		if (!PackFinalPass.draw(Vitrail.platform().gameDirectory())) {
			ShaderChain.draw();
		}
	}

	private void onClientStopping(ClientStoppingEvent event) {
		PackFinalPass.close();
		ShaderChain.close();
	}
}
