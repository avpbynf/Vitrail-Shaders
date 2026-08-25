package dev.vitrail.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.joml.Vector4f;

import java.util.EnumMap;
import java.util.Map;

/**
 * The one-texel constants a geometry program samples where a draw brings no image of its own:
 * opaque black, opaque white, and one texel per material map holding what the absence of that map
 * means. The values are Iris's fallbacks, and the reasons each name gets the colour it gets stay
 * with the code that answers the name, in {@link GeometryProgram}.
 * <p>
 * One set for the whole device, never one per program. They are constants of the engine, identical
 * in every program that reads them, and when each program owned a copy, building that copy cost
 * one standalone clear per texel, each ending on the backend's full memory barrier. Every release
 * put all of them back on the bill at once: a dimension change, a world join or a pack switch
 * re-prepares every program the scene draws, and the frame that did so was measured paying about
 * ninety of those clears. Each one is a full GPU stop, and on the backend issue 161 was measured
 * on it tends to cost a queue submission of its own, so that frame is a good part of the hitch at
 * the portal. The steady frame is not what this moves: it never paid these clears twice.
 * <p>
 * Allocated and cleared once, on the first program that asks, and kept across every release: five
 * one-texel textures are not a cost a release needs to claw back, and surviving it is what spares
 * the rebuilds. {@link PackChain#close} is the one caller that really frees them, the same rule
 * the far terrain's corner rings follow.
 */
final class ConstantTextures {

	/** One pixel each, for a name the resolving step has no answer for. */
	private static final GpuFormat FORMAT = GpuFormat.RGBA8_UNORM;

	private static final Vector4f OPAQUE_BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	private static final Vector4f OPAQUE_WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);

	private static ConstantTextures instance;

	private final TextureTarget black;
	private final TextureTarget white;
	private final Map<PbrMap, TextureTarget> flatMaps = new EnumMap<>(PbrMap.class);

	/**
	 * Allocates and clears the whole set, which records into the frame's command buffer: the caller
	 * holds no pass open, see {@link #ready}.
	 */
	private ConstantTextures(GpuDevice device) {
		this.black = new TextureTarget("Vitrail terrain black", 1, 1, false, FORMAT);
		this.white = new TextureTarget("Vitrail terrain white", 1, 1, false, FORMAT);
		for (PbrMap map : PbrMap.values()) {
			this.flatMaps.put(map, new TextureTarget("Vitrail terrain " + map.sampler(), 1, 1,
					false, FORMAT));
		}

		CommandEncoder encoder = device.createCommandEncoder();
		encoder.clearColorTexture(this.black.getColorTexture(), OPAQUE_BLACK);
		encoder.clearColorTexture(this.white.getColorTexture(), OPAQUE_WHITE);
		this.flatMaps.forEach((map, target) ->
				encoder.clearColorTexture(target.getColorTexture(), map.missing()));
	}

	/** The device's set, made on the first ask. */
	static ConstantTextures of(GpuDevice device) {
		if (instance == null) {
			instance = new ConstantTextures(device);
		}

		return instance;
	}

	/**
	 * Whether the set already exists, asked before a pass is held open across a prepare: making it
	 * clears textures, which the encoder refuses while a pass records.
	 */
	static boolean ready() {
		return instance != null;
	}

	/** Called when the client shuts down, while the device is still alive. */
	static void close() {
		if (instance == null) {
			return;
		}

		instance.black.destroyBuffers();
		instance.white.destroyBuffers();
		instance.flatMaps.values().forEach(TextureTarget::destroyBuffers);
		instance = null;
	}

	GpuTextureView black() {
		return this.black.getColorTextureView();
	}

	GpuTextureView white() {
		return this.white.getColorTextureView();
	}

	/** One texel of what the absence of this material map means. */
	GpuTextureView flat(PbrMap map) {
		return this.flatMaps.get(map).getColorTextureView();
	}
}
