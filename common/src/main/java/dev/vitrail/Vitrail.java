package dev.vitrail;

import dev.vitrail.platform.VitrailPlatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point shared by every loader module. A loader module calls
 * {@link #initClient(VitrailPlatform)} once, as early as it can, and everything after
 * that goes through {@link #platform()} rather than through loader classes.
 */
public final class Vitrail {

	public static final String MOD_ID = "vitrail";
	public static final String MOD_NAME = "Vitrail";

	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

	private static VitrailPlatform platform;

	private Vitrail() {
	}

	public static Logger logger() {
		return LOGGER;
	}

	public static VitrailPlatform platform() {
		if (platform == null) {
			throw new IllegalStateException("Vitrail has not been initialised by a loader module");
		}

		return platform;
	}

	public static void initClient(VitrailPlatform loaderPlatform) {
		if (platform != null) {
			throw new IllegalStateException("Vitrail has already been initialised on " + platform.loaderName());
		}

		platform = loaderPlatform;

		LOGGER.info("Vitrail {} starting on {} {}, Minecraft {}",
				loaderPlatform.modVersion(),
				loaderPlatform.loaderName(),
				loaderPlatform.loaderVersion(),
				loaderPlatform.minecraftVersion());
	}
}
