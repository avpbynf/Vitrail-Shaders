package dev.vitrail.render;

import dev.vitrail.dh.DhDepth;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.uniform.BiomeCategory;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gathers what the machine is and hands it to {@link EngineDefines}, which is the one thing in
 * this engine that cannot be worked out without the game.
 * <p>
 * The biome symbols are the reason this exists. A pack writes {@code if (biome == BIOME_DESERT)}
 * in its GLSL and {@code in(biome, BIOME_SAVANNA, BIOME_SAVANNA_PLATEAU)} in its properties, so
 * the number behind the symbol and the number the {@code biome} uniform carries have to be the
 * same one. {@link BiomeClassifier} is that number, and it is asked here rather than copied, which
 * is why this takes the classifier the frame state already holds instead of walking the registry
 * on its own.
 * <p>
 * The catch is when. Biomes are a data pack registry, so there is nothing to walk until a world
 * has been joined, which is long after the client finishes starting up and reads its first pack.
 * {@link #stale()} exists for that: it says the installed table was built against a different
 * registry, and the pass that watches {@code options.txt} for changes watches it too, so joining a
 * world costs the same half second reload as editing a setting does and the symbols are right
 * afterwards.
 * <p>
 * Two biomes of different namespaces can carry the same path, and only the first of them gets the
 * symbol. Iris has the same limit, and a pack naming a modded biome is naming its path anyway.
 */
public final class PackDefines {

	/** Which registry the installed table was built from, so that a change of one is noticed. */
	private static volatile long installed;

	private PackDefines() {
	}

	/**
	 * Reads the machine and installs it. Called once before a pack is read, and never per frame:
	 * every symbol here is fixed for as long as the world is.
	 */
	public static void install(BiomeClassifier biomes) {
		EngineDefines.machine(gather(biomes));
		settle();
	}

	/**
	 * Takes this world's registry as the one the last read was against, without rebuilding the
	 * table. For a read that could not happen at all: an empty pack folder must not be looked at
	 * again every second for the rest of the session.
	 */
	public static void settle() {
		installed = stamp();
	}

	/**
	 * Whether the symbols the last pack was read against are the ones this world would give it.
	 * False for the whole of a session that never changes world, which is what keeps this from
	 * being a reload every second.
	 */
	public static boolean stale() {
		return stamp() != installed;
	}

	private static long stamp() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft == null ? null : minecraft.level;

		return level == null ? 0L : System.identityHashCode(level.registryAccess());
	}

	private static EngineDefines.Environment gather(BiomeClassifier biomes) {
		Minecraft minecraft = Minecraft.getInstance();
		GpuDevice device = RenderSystem.tryGetDevice();
		String vendor = "";
		String renderer = "";
		if (device != null) {
			// The device name is what OptiFine classifies as the renderer: the vendor string
			// names who made the chip, the device name names which one it is.
			vendor = device.getDeviceInfo().vendorName();
			renderer = device.getDeviceInfo().name();
		}

		int mipmap = minecraft == null ? 4 : minecraft.options.mipmapLevels().get();

		// Asked of the one class that knows whether the far terrain can be read at all, and not of
		// the loader's mod list. An installed Distant Horizons this engine cannot get a depth image
		// or a projection out of leaves the far terrain flat, and a pack told otherwise would light
		// a picture that has none.
		return new EngineDefines.Environment(EngineDefines.DEFAULT_MC_VERSION, os(), vendor,
				renderer, mipmap, DhDepth.present(), biomeIds(minecraft, biomes), categories());
	}

	private static Map<String, Integer> biomeIds(Minecraft minecraft, BiomeClassifier biomes) {
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (level == null) {
			// No world, no registry, and no symbol either. An invented number here would be a
			// number the biome uniform never answers with, which is worse than an undefined name:
			// the pack would compile and compare against nothing.
			return Map.of();
		}

		biomes.refresh(level);
		Map<String, Integer> ids = new LinkedHashMap<>();
		List<String> names = biomes.names();
		for (int id = 0; id < names.size(); id++) {
			ids.putIfAbsent(names.get(id), id);
		}

		return ids;
	}

	/** The category names in ordinal order, which is what makes CAT_DESERT the number carried. */
	private static List<String> categories() {
		return Arrays.stream(BiomeCategory.values())
				.map(category -> category.name().toUpperCase(Locale.ROOT))
				.toList();
	}

	private static EngineDefines.Os os() {
		return switch (Util.getPlatform()) {
			case WINDOWS -> EngineDefines.Os.WINDOWS;
			case OSX -> EngineDefines.Os.MAC;
			case LINUX -> EngineDefines.Os.LINUX;
			// Solaris has no OptiFine symbol of its own, so it lands on the same one as unknown.
			case SOLARIS, UNKNOWN -> EngineDefines.Os.OTHER;
		};
	}
}
