package dev.vitrail.neoforge;

import dev.vitrail.neoforge.sodium.ShadowTerrain;
import dev.vitrail.pack.source.PackReport;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.screen.SettingsScreen;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.framegraph.FramePass;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import org.joml.Matrix4fc;

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

		// The frame graph is built at the top of LevelRenderer.render, before the clear, the sky
		// and the main pass, and a pass added here is executed before all three: the graph keeps
		// the order it was given for anything nothing else depends on. That is where the shadow
		// map belongs, since the first gbuffers program of the frame already reads it.
		//
		// It has to say disableCulling, and that is not a precaution. The graph keeps a pass only
		// when something it writes is reachable from an imported resource, and what this one writes
		// is a target of ours the graph has never heard of.
		NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, this::onFrameGraphSetup);

		// AfterOpaqueFeatures fires once the opaque terrain, the entities, the block entities
		// and the opaque particles are drawn, and before anything translucent is. That is
		// where the OptiFine model puts the deferred stage, and it is where Iris puts it too.
		// No render pass is open there, and the game itself relies on that: it clears the
		// entity outline target four lines later, which refuses outright inside a pass.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueFeatures.class,
				this::onAfterOpaqueFeatures);

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

	/**
	 * Runs the half of the pack's chain that belongs before the world's translucents: the begins,
	 * the prepares, the scene seed and the deferred stage.
	 * <p>
	 * Not a refinement of where it used to run. BSL's {@code gbuffers_water} reads {@code gaux1},
	 * which its own {@code deferred} writes, and discards every fragment where it reads nought: with
	 * the whole chain running after the world, that read found a clear colour and the water was
	 * thrown away in its entirety.
	 */
	private void onFrameGraphSetup(FrameGraphSetupEvent event) {
		if (!TerrainDraw.shadows()) {
			return;
		}

		FramePass pass = event.getFrameGrapBuilder().addPass("vitrail_shadow");
		pass.disableCulling();

		// Read here and not in the task: the event's own values are what this frame was set up
		// with, and by the time the graph runs the camera state has been walked over by everything
		// between the two.
		Matrix4fc modelView = event.getModelViewMatrix();
		Vec3 camera = event.getCameraState().pos;
		pass.executes(() -> ShadowTerrain.draw(modelView, camera));
	}

	private void onAfterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
		PackChain.drawBeforeTranslucents();
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
