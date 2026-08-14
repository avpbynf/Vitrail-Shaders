package dev.vitrail.neoforge;

import dev.vitrail.platform.EngineStages;
import dev.vitrail.render.PbrAtlases;
import dev.vitrail.screen.SettingsScreen;
import dev.vitrail.Vitrail;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.FrameGraphSetupEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Which NeoForge event each stage of {@link EngineStages} is reached by. What happens at a stage is
 * written there and not here, because the Fabric module has to reach the same ones.
 */
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

		modBus.addListener(FMLClientSetupEvent.class, _ -> EngineStages.clientSetup());

		// Posted at the end of TextureAtlas.upload, once per atlas and once per resource reload,
		// with the stitched texture already made and no render pass open. It is where the sprites of
		// an atlas can be walked, which is the one thing the material maps need and the one thing
		// vanilla exposes nowhere: getTextures is NeoForge's own addition to TextureAtlas, so the
		// walk stays on this side of the module boundary and the common module is handed the result.
		modBus.addListener(TextureAtlasStitchedEvent.class, this::onAtlasStitched);

		NeoForge.EVENT_BUS.addListener(FrameGraphSetupEvent.class, event ->
				EngineStages.frameGraphSetup(event.getModelViewMatrix(), event.getCameraState().pos));

		// AfterOpaqueBlocks fires with the chunk passes done and not one entity drawn yet.
		//
		// The whole order this and the next two listeners hang off is one lambda of
		// LevelRenderer.addMainPass, and is worth having in one place because two of the names read
		// the wrong way round: the opaque chunk group, AfterOpaqueBlocks, executeSolid,
		// AfterOpaqueFeatures, executeTranslucent, AfterTranslucentFeatures, executeOutline, the
		// translucent chunk group, AfterTranslucentBlocks. The features are drawn before the water,
		// and the event named after the features fires before the one named after the blocks.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueBlocks.class,
				_ -> EngineStages.afterOpaqueBlocks());

		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterOpaqueFeatures.class,
				_ -> EngineStages.afterOpaqueFeatures());

		// The pair of events brackets exactly executeTranslucent.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterTranslucentFeatures.class,
				_ -> EngineStages.afterTranslucentFeatures());

		// AfterLevel fires in GameRenderer.renderLevel once LevelRenderer is done and
		// before anything else touches the main target, outside of any render pass the
		// game has open. That is the whole reason this hook is an event and not a mixin.
		NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.AfterLevel.class,
				_ -> EngineStages.afterLevel());

		// LoggingOut rather than LevelEvent.Unload: the second one is posted for the integrated
		// server's levels too, from the server thread, and nothing of what this frees may be touched
		// from there.
		NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,
				_ -> EngineStages.leaveWorld());

		// Stopping, not Stopped: the latter is posted after the renderer has been shut down,
		// and the targets have to be released while the device is still alive.
		NeoForge.EVENT_BUS.addListener(ClientStoppingEvent.class, _ -> EngineStages.closeClient());
	}

	/**
	 * Reads what the resource pack ships beside the sprites of an atlas the game has just stitched.
	 * <p>
	 * Everything the maps need is pulled apart here rather than passed along whole, and the reason is
	 * the module boundary: {@code getTextures} is NeoForge's own addition to {@code TextureAtlas} and
	 * the common module compiles against vanilla alone, so it could not walk the sprites itself.
	 */
	private void onAtlasStitched(TextureAtlasStitchedEvent event) {
		TextureAtlas atlas = event.getAtlas();
		PbrAtlases.stitched(atlas.location(), atlas.getTexture(), atlas.getTextures().values());
	}
}
