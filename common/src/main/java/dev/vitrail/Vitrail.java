package dev.vitrail;

import dev.vitrail.platform.VitrailPlatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point shared by every loader module. A loader module calls
 * {@link #initClient(VitrailPlatform)} once, as early as it can, and everything after
 * that goes through {@link #platform()} rather than through loader classes.
 */
public final class Vitrail {

	public static final String MOD_ID = "vitrail";
	public static final String MOD_NAME = "Vitrail";

	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

	/** Where the build writes the commit, and absent in a jar built from a release version. */
	private static final String BUILD_STAMP = "/vitrail-build-identity";

	/** Read once, because it is a fact about the jar and cannot change under a running game. */
	private static final String BUILD_IDENTITY = readBuildIdentity();

	private static VitrailPlatform platform;

	private Vitrail() {
	}

	public static Logger logger() {
		return LOGGER;
	}

	/**
	 * The declared version with the branch name cut off it, which is the version half of what the
	 * two compiled stores name their edition after and key their entries by.
	 * <p>
	 * A build made off a topic branch declares that branch in its version,
	 * {@code 0.10.0-dev.fix.shadow-band} where {@code dev} declares {@code 0.10.0-dev}, so that a
	 * jar, a log line and a screenshot say which branch made them. A cache edition does not follow
	 * it there. What a version says in a cache key is a claim about the translator and the
	 * compiler, and neither of them changes because a branch does. What does change between two
	 * builds of one version is the commit, and {@link #buildIdentity()} is where that is named, so
	 * a branch in the folder as well would be a second name for the same build and a longer
	 * directory holding the same units.
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

	/**
	 * The commit this jar was built from, for a development build, and empty for a release and for
	 * anything running outside a jar the build wrote.
	 * <p>
	 * Every build made between two releases declares one version number, and both disk caches name
	 * their edition after that number: with nothing else in the name, a build is served the units
	 * its predecessor wrote, so a translator changed without the number moving goes unseen on
	 * screen and the build that happens to carry a new define is the only one that translates
	 * afresh. This is what separates them, and it is a fact about the build rather than about the
	 * version, so the build writes it into the jar and commits it nowhere.
	 * <p>
	 * A release leaves it empty on purpose. A player's caches are worth keeping across every jar of
	 * one version, since nothing but a release changes what those jars compile, and a name per jar
	 * would buy a cold load for nothing. A developer's are worth nothing across two builds.
	 * <p>
	 * The short hash, or the short hash and {@code .dirty} when the tree the build read carried
	 * edits: a jar built over edits is described by no commit. Two of those off one commit still
	 * share an edition, which is the one case left where a build reads what another wrote.
	 */
	public static String buildIdentity() {
		return BUILD_IDENTITY;
	}

	/**
	 * What every edition of this version and this game begins with, and the whole of what a RELEASE
	 * build names its own.
	 * <p>
	 * It is also what the caches sweep by: an edition outside this family holds keys nothing can
	 * ever ask for again, and one inside it belongs to another build of the same version.
	 */
	public static String cacheEditionFamily() {
		return plain(cacheVersion()) + "+mc" + plain(platform().minecraftVersion());
	}

	/**
	 * What the two disk caches name their directory after: the family, and for a development build
	 * the commit it was built from.
	 * <p>
	 * One place rather than one per cache, because the two answer the same question and a folder
	 * that disagreed with the other would be the defect all over again in half the engine.
	 */
	public static String cacheEdition() {
		String build = buildIdentity();

		return build.isEmpty() ? cacheEditionFamily() : cacheEditionFamily() + "+" + plain(build);
	}

	/** Whatever a directory name cannot hold, replaced so that a name is never two names. */
	private static String plain(String text) {
		return text.replaceAll("[^A-Za-z0-9._-]", "_");
	}

	private static String readBuildIdentity() {
		try (InputStream stamp = Vitrail.class.getResourceAsStream(BUILD_STAMP)) {
			if (stamp == null) {
				return "";
			}

			return new String(stamp.readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException | RuntimeException ignored) {
			// The release answer, which is the edition every build shared before the stamp existed:
			// a slow launch at worst, and never a failure to start. It cannot be anything louder
			// either, since this runs in a static initialiser and a throw here would be an Error.
			return "";
		}
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
