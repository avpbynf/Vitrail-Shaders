package dev.vitrail.fabric;

import dev.vitrail.platform.EngineStages;
import dev.vitrail.Vitrail;

import net.fabricmc.api.ClientModInitializer;

/**
 * Where Fabric hands this mod the game, and the one stage of {@link EngineStages} that is not
 * reached by a mixin.
 * <p>
 * Fabric Loader calls this at the end of the {@code Minecraft} constructor, which is what makes it
 * the counterpart of NeoForge's client setup rather than merely the earliest thing available: the
 * graphics device is built in that constructor, so the backend this reports is the one the session
 * really came up on. A loader that called this any earlier would report {@code unknown} and the
 * line would be worse than absent, which is why the first thing to check in a Fabric log is that
 * the backend has a name.
 */
public final class VitrailFabric implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		Vitrail.initClient(new FabricPlatform());
		EngineStages.clientSetup();
	}
}
