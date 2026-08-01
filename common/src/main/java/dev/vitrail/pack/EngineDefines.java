package dev.vitrail.pack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The symbols the engine has to define before a pack sees its own code.
 * <p>
 * Packs branch on these to decide what they can use, so a missing one does not fail loudly: it
 * quietly takes the pack down a fallback path meant for a renderer from ten years ago. The
 * table is ordered because it is emitted in order, and an ordered emission is a diffable one.
 */
public final class EngineDefines {

	/**
	 * Kept as OptiFine's packed form, major times 10000 plus minor times 100 plus patch. It is
	 * a Minecraft version rather than ours, because that is what packs compare against.
	 */
	public static final int DEFAULT_MC_VERSION = 12111;

	private EngineDefines() {
	}

	public static Map<String, String> table(int mcVersion) {
		Map<String, String> defines = new LinkedHashMap<>();

		defines.put("MC_VERSION", Integer.toString(mcVersion));
		defines.put("MC_GL_VERSION", "460");
		defines.put("MC_GLSL_VERSION", "460");
		defines.put("MC_OS_WINDOWS", "");
		defines.put("MC_GL_VENDOR_OTHER", "");
		defines.put("MC_GL_RENDERER_OTHER", "");
		defines.put("MC_RENDER_QUALITY", "1.0");
		defines.put("MC_SHADOW_QUALITY", "1.0");
		defines.put("MC_HAND_DEPTH", "0.125");
		defines.put("MC_NORMAL_MAP", "");
		defines.put("MC_SPECULAR_MAP", "");

		// Packs gate their modern paths on this rather than on a feature test. Claiming it is
		// how a pack is told that includes, compute passes and the newer uniforms are available;
		// it is also a promise, and every one of those has to work before it can stay.
		defines.put("IS_IRIS", "");

		defines.put("MC_RENDER_STAGE_NONE", "0");
		defines.put("MC_RENDER_STAGE_SKY", "1");
		defines.put("MC_RENDER_STAGE_SUNSET", "2");
		defines.put("MC_RENDER_STAGE_CUSTOM_SKY", "3");
		defines.put("MC_RENDER_STAGE_SUN", "4");
		defines.put("MC_RENDER_STAGE_MOON", "5");
		defines.put("MC_RENDER_STAGE_STARS", "6");
		defines.put("MC_RENDER_STAGE_VOID", "7");
		defines.put("MC_RENDER_STAGE_TERRAIN_SOLID", "8");
		defines.put("MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED", "9");
		defines.put("MC_RENDER_STAGE_TERRAIN_CUTOUT", "10");
		defines.put("MC_RENDER_STAGE_ENTITIES", "11");
		defines.put("MC_RENDER_STAGE_BLOCK_ENTITIES", "12");
		defines.put("MC_RENDER_STAGE_DESTROY", "13");
		defines.put("MC_RENDER_STAGE_OUTLINE", "14");
		defines.put("MC_RENDER_STAGE_DEBUG", "15");
		defines.put("MC_RENDER_STAGE_HAND_SOLID", "16");
		defines.put("MC_RENDER_STAGE_TERRAIN_TRANSLUCENT", "17");
		defines.put("MC_RENDER_STAGE_TRIPWIRE", "18");
		defines.put("MC_RENDER_STAGE_PARTICLES", "19");
		defines.put("MC_RENDER_STAGE_CLOUDS", "20");
		defines.put("MC_RENDER_STAGE_RAIN_SNOW", "21");
		defines.put("MC_RENDER_STAGE_WORLD_BORDER", "22");
		defines.put("MC_RENDER_STAGE_HAND_TRANSLUCENT", "23");

		// Distant Horizons block kinds. No pack declares them and several read them, so leaving
		// them out turns a working pack into a wall of undeclared identifiers.
		defines.put("DH_BLOCK_UNKNOWN", "0");
		defines.put("DH_BLOCK_LEAVES", "1");
		defines.put("DH_BLOCK_STONE", "2");
		defines.put("DH_BLOCK_WOOD", "3");
		defines.put("DH_BLOCK_METAL", "4");
		defines.put("DH_BLOCK_DIRT", "5");
		defines.put("DH_BLOCK_LAVA", "6");
		defines.put("DH_BLOCK_DEEPSLATE", "7");
		defines.put("DH_BLOCK_SNOW", "8");
		defines.put("DH_BLOCK_SAND", "9");
		defines.put("DH_BLOCK_TERRACOTTA", "10");
		defines.put("DH_BLOCK_NETHER_STONE", "11");
		defines.put("DH_BLOCK_WATER", "12");
		defines.put("DH_BLOCK_GRASS", "13");
		defines.put("DH_BLOCK_AIR", "14");
		defines.put("DH_BLOCK_ILLUMINATED", "15");

		return defines;
	}

	public static int count() {
		return table(DEFAULT_MC_VERSION).size();
	}
}
