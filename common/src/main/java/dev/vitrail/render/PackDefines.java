package dev.vitrail.render;

import dev.vitrail.dh.DhDepth;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.uniform.BiomeCategory;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.biome.Biomes;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Gathers what the machine is and hands it to {@link EngineDefines}, which is the one thing in
 * this engine that cannot be worked out without the game.
 * <p>
 * The biome symbols are the reason this exists. A pack writes {@code if (biome == BIOME_DESERT)}
 * in its GLSL and {@code in(biome, BIOME_SAVANNA, BIOME_SAVANNA_PLATEAU)} in its properties, so
 * the number behind the symbol and the number the {@code biome} uniform carries have to be the
 * same one. {@link BiomeClassifier} is that number, and it is asked here rather than copied. Its
 * table is filled from the {@code Biomes} class as it initialises, so the symbols exist from the
 * first read of the first pack, before any world does; that is Iris's arrangement, whose map is
 * filled the same way ({@code mixin/MixinBiomes.java:14-22}) and whose defines are written from it
 * ({@code shaderpack/IrisDefines.java:28}).
 * <p>
 * The world-join reload stays even though the biome symbols no longer need it, because the
 * symbols were never the only thing riding it: {@code block.properties} may name block TAGS, and
 * {@code BlockStateIds} can only resolve those once the world's own registries exist
 * ({@code BlockStateIds.java:86-88}, resolving through {@code BuiltInRegistries.BLOCK.getTags()}
 * at {@code :249}). At startup there are none, so the first read of a session still has to be
 * made again when a world arrives, and {@link #stale()}'s registry stamp is what notices that.
 * <p>
 * Distant Horizons is the one thing here a player can change without leaving the world, from that
 * mod's own screen, so {@link #stale()} watches it beside the registry rather than trusting what
 * was true when the pack was read.
 */
public final class PackDefines {

	/** Which registry the last read was against, so that joining a world is noticed. */
	private static volatile long installed;

	/** And whether the far terrain was there, which the player can change without leaving. */
	private static volatile boolean distant;

	private PackDefines() {
	}

	/**
	 * Reads the machine and installs it. Called once before a pack is read, and never per frame:
	 * every symbol here is fixed for as long as the world is.
	 */
	public static void install() {
		EngineDefines.machine(gather());
		settle();
	}

	/**
	 * Takes this world's registry as the one the last read was against, without rebuilding
	 * anything. For a read that could not happen at all: an empty pack folder must not be looked
	 * at again every second for the rest of the session.
	 */
	public static void settle() {
		installed = stamp();
		distant = DhDepth.present();
	}

	/**
	 * Whether the pack was last read against a world that is not this one, which is what decides
	 * the one reload nobody can ask for.
	 */
	public static boolean stale() {
		return stamp() != installed || distantHorizonsMoved();
	}

	/**
	 * Whether it is the far terrain that moved rather than the world, which is the other half of
	 * what Iris does with {@code DISTANT_HORIZONS}: it reads DH's rendering switch every frame
	 * and reloads on the flip, {@code compat/dh/DHCompatInternal.java:143} and {@code :148}.
	 * Without this the symbol would be whatever it was when the pack was read, and a player who
	 * switches that mod's rendering off would keep a pack lighting a far terrain that is no longer
	 * drawn.
	 * <p>
	 * What {@link #settle()} records is the live answer and not the one {@link #gather} compiled
	 * with, so a flip inside the half second a pack takes to read is not noticed until the next
	 * flip. Recording what the read compiled with instead would mean recording nothing when the
	 * read could not happen at all, and that is the case this pair exists to keep out of a reload
	 * every frame.
	 */
	public static boolean distantHorizonsMoved() {
		return DhDepth.present() != distant;
	}

	private static long stamp() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft == null ? null : minecraft.level;

		return level == null ? 0L : System.identityHashCode(level.registryAccess());
	}

	private static EngineDefines.Environment gather() {
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

		// Asked of the one class that knows whether the far terrain reaches the pack at all, and not
		// of the loader's mod list. An installed Distant Horizons this engine cannot get a depth
		// image or a projection out, or whose own rendering is switched off, leaves the far terrain
		// flat, and a pack told otherwise would light a picture that has none.
		return new EngineDefines.Environment(EngineDefines.DEFAULT_MC_VERSION, os(), vendor,
				renderer, mipmap, DhDepth.present(), biomeIds(), categories());
	}

	private static Map<String, Integer> biomeIds() {
		// Touches the game's class so that its registration has run by now: the table fills as
		// Biomes initialises, nothing on a client's boot path promises that has happened before
		// the first pack is read, and an empty table here would write no symbol and notice
		// nothing later. Initialising it is free of everything but interning some keys.
		Objects.requireNonNull(Biomes.THE_VOID);

		Map<String, Integer> ids = new LinkedHashMap<>();
		List<String> names = BiomeClassifier.names();
		for (int id = 0; id < names.size(); id++) {
			ids.put(names.get(id), id);
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
