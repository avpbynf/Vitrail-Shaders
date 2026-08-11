package dev.vitrail.neoforge;

import dev.vitrail.neoforge.sodium.ShadowTerrain;
import dev.vitrail.render.EntityDraw;
import dev.vitrail.render.HandDraw;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.TerrainDraw;
import dev.vitrail.screen.SettingsScreen;
import dev.vitrail.Vitrail;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
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
				(_, modListScreen) -> new SettingsScreen(modListScreen));

		VitrailKeys.register(modBus);
		MenuEntry.register();

		modBus.addListener(FMLClientSetupEvent.class, this::onClientSetup);

		// The frame graph event no longer carries a pass of ours, only a reading: the shadow map is
		// drawn at the end of the frame, for the next one, and what the stage needs from here is
		// the model view and the camera position this frame was set up with.
		NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, this::onFrameGraphSetup);

		// AfterOpaqueBlocks fires with the chunk passes done and not one entity drawn yet. It is
		// the only moment the world's depth is the pack's own geometry and nothing else, which is
		// what the scene seed needs to tell a mob standing in front of a wall from the wall.
		//
		// The whole order this and the next two listeners hang off is one lambda of
		// LevelRenderer.addMainPass, and is worth having in one place because two of the names read
		// the wrong way round: the opaque chunk group, AfterOpaqueBlocks, executeSolid,
		// AfterOpaqueFeatures, executeTranslucent, AfterTranslucentFeatures, executeOutline, the
		// translucent chunk group, AfterTranslucentBlocks. The features are drawn before the water,
		// and the event named after the features fires before the one named after the blocks.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueBlocks.class,
				this::onAfterOpaqueBlocks);

		// AfterOpaqueFeatures fires once the opaque terrain, the entities, the block entities
		// and the opaque particles are drawn, and before anything translucent is. That is
		// where the OptiFine model puts the deferred stage, and it is where Iris puts it too.
		// No render pass is open there, and the game itself relies on that: three lines later
		// it copies a depth between two targets, which refuses outright inside a pass. The
		// clear of the entity outline target is the same argument at the OTHER event, four
		// lines after AfterOpaqueBlocks.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueFeatures.class,
				this::onAfterOpaqueFeatures);

		// The pair of events brackets exactly executeTranslucent, which is where the game draws
		// the translucent features, the player's own body among them. They go into the game's
		// target, which the pack's final overwrites, so without the layer they simply vanish.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterTranslucentFeatures.class,
				this::onAfterTranslucentFeatures);

		// AfterLevel fires in GameRenderer.renderLevel once LevelRenderer is done and
		// before anything else touches the main target, outside of any render pass the
		// game has open. That is the whole reason this hook is an event and not a mixin.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class, this::onAfterLevel);

		// Leaving a world is the other moment everything a pack costs may be handed back, and the
		// only one the player reaches twenty times a session. LoggingOut rather than
		// LevelEvent.Unload: the second one is posted for the integrated server's levels too, from
		// the server thread, and nothing of what this frees may be touched from there.
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
				this::onLoggingOut);

		// Stopping, not Stopped: the latter is posted after the renderer has been shut down,
		// and the targets have to be released while the device is still alive.
		NeoForge.EVENT_BUS.addListener(ClientStoppingEvent.class, this::onClientStopping);
	}

	private void onClientSetup(FMLClientSetupEvent event) {
		Vitrail.logger().info("Client setup reached, Sodium is {}",
				Vitrail.platform().isModLoaded("sodium") ? "present" : "missing");

		// The report of the pack goes with the reading of it, in PackChain, where which pack is
		// being drawn is known.
		PackChain.load(Vitrail.platform().gameDirectory());
	}

	private void onFrameGraphSetup(FrameGraphSetupEvent event) {
		if (!TerrainDraw.shadows()) {
			return;
		}

		ShadowTerrain.capture(event.getModelViewMatrix(), event.getCameraState().pos);
	}

	/**
	 * Keeps the world's depth as the pack's own geometry left it, the last moment at which it is
	 * that and nothing else. The scene seed is cut against it, and what it buys is every entity the
	 * game draws in front of a block.
	 */
	private void onAfterOpaqueBlocks(RenderLevelStageEvent.AfterOpaqueBlocks event) {
		PackChain.markGeometryDepth();

		// And opens the one window the entities are served in. It has to be a window, because the
		// screen is drawn by the same feature renderers, with the same pipelines and into the same
		// target, out of a submit storage GameRenderer hands them after the level: nothing about one
		// of those draws says it is not an entity, and only the moment does. The hand used to be the
		// other one this kept out and no longer is: HandDraw submits it inside the level with a mark
		// of its own, so it is served rather than excluded.
		EntityDraw.opaqueFeatures(true);
	}

	/**
	 * Runs the half of the pack's chain that belongs before the world's translucents: the begins,
	 * the prepares, the scene seed and the deferred stage. Then redirects the game's translucent
	 * features into the layer that hands them to the pack's image.
	 * <p>
	 * Not a refinement of where it used to run. BSL's {@code gbuffers_water} reads {@code gaux1},
	 * which its own {@code deferred} writes, and discards every fragment where it reads nought: with
	 * the whole chain running after the world, that read found a clear colour and the water was
	 * thrown away in its entirety.
	 */
	private void onAfterOpaqueFeatures(RenderLevelStageEvent.AfterOpaqueFeatures event) {
		// First, and before anything of this engine draws: everything after this point is either the
		// world's translucents or, once the level returns, the screen.
		EntityDraw.opaqueFeatures(false);

		// Then the hand's solid half, and the order of these two lines is the whole of where it
		// belongs in the frame: after the game's own opaque features, which the window above has just
		// closed, and BEFORE the deferred stage the next line runs. That is exactly where Iris puts
		// it, between renderTranslucentFeatures and its own beginTranslucents
		// (mixin/MixinLevelRenderer.java:277-283, pipeline/IrisRenderingPipeline.java:1060-1073).
		// Drawn after the deferreds instead, the hand would write gbuffers nothing would ever read.
		HandDraw.drawSolid();
		PackChain.drawBeforeTranslucents();
		PackChain.openFeatures();

		// Last, and after the deferred stage above rather than beside it: what this window opens is
		// the half of the entities that blends onto the target that stage has just composed.
		EntityDraw.translucentFeatures(true);
	}

	private void onAfterTranslucentFeatures(RenderLevelStageEvent.AfterTranslucentFeatures event) {
		// First, and before the layer is composed: closing the window closes any pass a group left
		// open, and composing opens one of its own where the encoder allows only one at a time.
		EntityDraw.translucentFeatures(false);
		PackChain.closeFeatures();
	}

	private void onAfterLevel(RenderLevelStageEvent.AfterLevel event) {
		// The hand's blending half, before the chain and never after it: what it draws has to be in
		// the picture the composites read, and this event is the last moment it can be. Iris draws it
		// at the same place, one line ahead of its own finalizeLevelRendering
		// (mixin/MixinLevelRenderer.java:170-179).
		HandDraw.drawTranslucent();

		// Nothing is drawn when no pack can be: the game's own image is a better answer than
		// anything this mod could put over it, and the reason is already said, once in the log
		// and again on the settings screen through PackChain.lastError.
		PackChain.draw(Vitrail.platform().gameDirectory());

		// After the chain and not before: the composites above read the map the previous frame
		// drew, and this draws the next frame's over it. The end of the frame is the whole
		// culling design, see ShadowTerrain.
		ShadowTerrain.draw();
	}

	/**
	 * Posted by {@code Minecraft.disconnect} while the level is still standing and the device still
	 * alive, which is what makes it the place to hand back what a pack costs.
	 */
	private void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
		PackChain.leaveWorld();
	}

	private void onClientStopping(ClientStoppingEvent event) {
		PackChain.close();
		// Its own line and not part of the chain's: what it holds is a second feature renderer and
		// the buffers under it, which belong to the session rather than to a pack, and which have to
		// go back while the device is still alive.
		HandDraw.close();
	}
}
