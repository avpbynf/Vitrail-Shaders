package dev.vitrail.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The block atlas sampler a pack's terrain is drawn with, in the gbuffers and in the shadow map
 * alike, and it is NEAREST where the game's own is LINEAR.
 * <p>
 * <strong>The game filters the atlas, and a pack cannot afford it.</strong> The game builds
 * {@code chunkLayerSampler} as CLAMP_TO_EDGE on both axes, LINEAR for min and mag, and the
 * player's anisotropy, in {@code LevelRenderer} where it draws the chunk layers. Iris throws that
 * away and binds its own for every terrain draw: the same sampler in every respect but NEAREST for
 * min and mag ({@code pipeline/programs/SodiumShader.java:131}, built by
 * {@code samplers/IrisSamplers.java:249-254}). Packs are written against Iris, so NEAREST is what
 * they expect, and this engine was passing the game's straight through.
 * <p>
 * <strong>What the filter can decide is a silhouette, not a colour.</strong> Foliage is cutout: the
 * fragment is discarded on the atlas's alpha, so interpolating that alpha makes whether a fragment
 * survives depend on where a sample falls INSIDE a texel rather than on which texel it falls in.
 * That is the whole of what the two samplers can differ by, and measured against Iris on one scene
 * at three camera heights the two engines shaded the trees alike under either: this is parity with
 * what Iris binds, not the cure of a picture anybody has seen. Mipmaps stay on and both backends of
 * the game blend between levels under a NEAREST min filter, so what goes is the blend within one
 * level and nothing else.
 * <p>
 * The anisotropy is the player's, read the way the game and Iris both read it, and the cache is
 * keyed by it so that changing the setting builds one more sampler rather than reusing a stale one.
 * Iris additionally forces it to one for a pack that cannot bear it, which this engine has no
 * equivalent of and does not pretend to. Released when the client closes, the one moment the device
 * is known to be going away with it.
 */
public final class TerrainSampler {

	/**
	 * One sampler per anisotropy the player has asked for. Small and bounded: the option offers a
	 * handful of values and nothing else reaches this map.
	 */
	private static final Map<Integer, GpuSampler> BY_ANISOTROPY = new HashMap<>();

	private TerrainSampler() {
	}

	/**
	 * The sampler every terrain draw of a pack binds, built once per anisotropy.
	 * <p>
	 * Null before there is a device to build one on, which the callers already handle by falling
	 * back to what the game handed them: an atlas filtered the game's way is wrong, and no atlas at
	 * all is a black world.
	 *
	 * @return the sampler, or null before the device exists
	 */
	public static GpuSampler get() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || RenderSystem.tryGetDevice() == null) {
			return null;
		}

		int anisotropy = minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
				? minecraft.options.maxAnisotropyValue()
				: 1;

		return BY_ANISOTROPY.computeIfAbsent(anisotropy, asked ->
				RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE,
						AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, asked,
						OptionalDouble.empty()));
	}

	/**
	 * Closes what was built, at the one moment the device is going away with it.
	 * <p>
	 * A sampler outliving its device is a handle into freed memory, and the map is static, so
	 * nothing else would ever drop these.
	 */
	public static void release() {
		BY_ANISOTROPY.values().forEach(GpuSampler::close);
		BY_ANISOTROPY.clear();
	}
}
