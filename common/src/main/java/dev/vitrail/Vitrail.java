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

	/**
	 * The declared version with the branch name cut off it, which is what the two compiled stores
	 * name their edition after and key their entries by.
	 * <p>
	 * A build made off a topic branch declares that branch in its version,
	 * {@code 0.10.0-dev.fix.shadow-band} where {@code dev} declares {@code 0.10.0-dev}, so that a
	 * jar, a log line and a screenshot say which branch made them. A cache edition must not follow
	 * it there. Both stores delete every edition but their own when they open, so a bench swapping
	 * between two branch builds would throw the other one's store away on every swap and compile
	 * every pack from cold again: on this machine that is most of a gigabyte of work bought by a
	 * name. What a version says in a cache key is a claim about the translator and the compiler,
	 * and neither of them changes because a branch does, so every build between two releases goes
	 * on sharing one folder exactly as it did before there was a suffix to share it despite.
	 * <p>
	 * The cut is at the first dot after the dash, which is where the build appends and the only
	 * place a dot can follow: a version is three numbers and then {@code -alpha}, {@code -beta},
	 * {@code -dev} or nothing at all, so a released one comes back unchanged.
	 */
	public static String cacheVersion() {
		String version = platform().modVersion();
		int dash = version.indexOf('-');
		if (dash < 0) {
			return version;
		}

		int dot = version.indexOf('.', dash);
		return dot < 0 ? version : version.substring(0, dot);
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
