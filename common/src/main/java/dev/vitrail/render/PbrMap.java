package dev.vitrail.render;

import net.minecraft.util.ARGB;

import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.function.IntUnaryOperator;

/**
 * One of the two maps a resource pack ships beside a block texture: {@code bricks_n.png} for the
 * surface and {@code bricks_s.png} for the material it is made of.
 * <p>
 * <strong>Nothing here decodes anything, and that is the whole shape of the feature.</strong> The
 * pack samples these texels itself and reads its own convention out of them, so what this engine
 * owes is the texels, unchanged, at the sprite's own place in the atlas. Iris owes exactly the same
 * and does exactly that: {@code samplers/IrisSamplers.java:215-216} binds the two names to a
 * texture and to nothing else, with no transform on the way in.
 * <p>
 * <strong>What does not carry over is the mipmap, and it is the one place a formula has to be
 * translated rather than copied.</strong> The game averages a colour: {@code ARGB.meanLinear} takes
 * the three colour channels through the sRGB curve, averages in light, and comes back
 * ({@code util/ARGB.java:38-50}). That is right for an albedo and wrong for everything here, where
 * red and green are the two components of a vector and blue is a coverage. Iris averages these
 * channel by channel in plain arithmetic instead ({@code pbr/mipmap/LinearBlendFunction.java}), and
 * so does {@link #blend}.
 * <p>
 * The specular map goes one step further under labPBR, and for a reason the encoding forces: its
 * green channel is a value below 230 and a metal index above it, its blue a porosity below 65 and a
 * subsurface amount above it. Averaging across that boundary invents a material that is in neither
 * class, so those channels are averaged only among the texels of the class that wins the quad
 * ({@code pbr/format/LabPBRTextureFormat.java:13-18}). That only applies where the resource pack
 * declares the format, which is what {@code labPbr} carries down from
 * {@link PbrAtlases#labPbr(net.minecraft.server.packs.resources.ResourceManager)}: with no
 * declaration Iris falls back to the plain average for both maps
 * ({@code pbr/loader/AtlasPBRLoader.java:188-198}).
 */
enum PbrMap {

	/**
	 * The surface: two components of a tangent space normal, an ambient occlusion and a height. Its
	 * missing value is the flat one, straight from Iris's {@code PBRType.NORMAL}: a normal pointing
	 * out of the face, nothing occluded, and the height at the top of its range. Read as an ordinary
	 * colour it is the pale blue of every normal map's empty area.
	 */
	NORMALS("normals", "_n", ARGB.color(0xFF, 0x7F, 0x7F, 0xFF)),

	/**
	 * The material: smoothness, reflectance, porosity or subsurface, and emission. Its missing value
	 * is nought in every channel, again Iris's, and every one of the four reads as the absence of the
	 * thing it names.
	 */
	SPECULAR("specular", "_s", ARGB.color(0, 0, 0, 0));

	/**
	 * The three boundaries labPBR draws inside a channel of the specular map. A quad straddling one
	 * of them holds two different materials and not one material twice, so the average is taken
	 * inside the class that wins rather than across the pair.
	 */
	private static final IntUnaryOperator METAL = value -> value < 230 ? 0 : value - 229;
	private static final IntUnaryOperator POROSITY = value -> value < 65 ? 0 : 1;
	private static final IntUnaryOperator EMISSION = value -> value < 255 ? 0 : 1;

	private final String sampler;
	private final String suffix;
	private final int missing;

	PbrMap(String sampler, String suffix, int missing) {
		this.sampler = sampler;
		this.suffix = suffix;
		this.missing = missing;
	}

	/** The name a pack declares this map under, which is the same word in every pack of the corpus. */
	String sampler() {
		return this.sampler;
	}

	/** What is appended to a sprite's own path to find this map beside it. */
	String suffix() {
		return this.suffix;
	}

	/** What a sprite the pack ships no map for reads, as a colour a clear can be given. */
	Vector4fc missing() {
		return new Vector4f(ARGB.red(this.missing) / 255.0F, ARGB.green(this.missing) / 255.0F,
				ARGB.blue(this.missing) / 255.0F, ARGB.alpha(this.missing) / 255.0F);
	}

	/**
	 * Whether a sampler may blend two neighbouring texels of this map, which decides how it is read
	 * rather than how it is built.
	 * <p>
	 * False only for the specular map under labPBR, and for the same reason its mipmap is taken by
	 * class: a filter that mixes a metal with a dielectric produces neither
	 * ({@code pbr/format/LabPBRTextureFormat.java:21-24}). Iris answers the same question at the
	 * same place and takes the same two branches ({@code pipeline/IrisRenderingPipeline.java:860-867}).
	 *
	 * @param labPbr whether the resource pack declares the labPBR format
	 */
	boolean interpolates(boolean labPbr) {
		return this != SPECULAR || !labPbr;
	}

	/**
	 * One texel of the next mip level, from the four it covers. Colours are ARGB, which is what
	 * {@code NativeImage.getPixel} hands out and takes back in 26.2.
	 *
	 * @param labPbr whether the resource pack declares the labPBR format
	 */
	int blend(int first, int second, int third, int fourth, boolean labPbr) {
		if (this == SPECULAR && labPbr) {
			return ARGB.color(
					byClass(EMISSION, ARGB.alpha(first), ARGB.alpha(second), ARGB.alpha(third),
							ARGB.alpha(fourth)),
					mean(ARGB.red(first), ARGB.red(second), ARGB.red(third), ARGB.red(fourth)),
					byClass(METAL, ARGB.green(first), ARGB.green(second), ARGB.green(third),
							ARGB.green(fourth)),
					byClass(POROSITY, ARGB.blue(first), ARGB.blue(second), ARGB.blue(third),
							ARGB.blue(fourth)));
		}

		return ARGB.color(
				mean(ARGB.alpha(first), ARGB.alpha(second), ARGB.alpha(third), ARGB.alpha(fourth)),
				mean(ARGB.red(first), ARGB.red(second), ARGB.red(third), ARGB.red(fourth)),
				mean(ARGB.green(first), ARGB.green(second), ARGB.green(third), ARGB.green(fourth)),
				mean(ARGB.blue(first), ARGB.blue(second), ARGB.blue(third), ARGB.blue(fourth)));
	}

	private static int mean(int first, int second, int third, int fourth) {
		return (first + second + third + fourth) / 4;
	}

	/**
	 * The average of the texels whose class wins the quad, the others left out entirely. Ties go to
	 * the earliest of the four, which is the rule Iris's own selection ends on
	 * ({@code pbr/mipmap/DiscreteBlendFunction.java:17-26}).
	 */
	private static int byClass(IntUnaryOperator classOf, int first, int second, int third,
			int fourth) {
		int[] values = {first, second, third, fourth};
		int[] classes = {classOf.applyAsInt(first), classOf.applyAsInt(second),
				classOf.applyAsInt(third), classOf.applyAsInt(fourth)};

		int winner = classes[0];
		int best = 0;
		for (int candidate = 0; candidate < classes.length; candidate++) {
			int count = 0;
			for (int against : classes) {
				if (against == classes[candidate]) {
					count++;
				}
			}

			if (count > best) {
				best = count;
				winner = classes[candidate];
			}
		}

		int sum = 0;
		int taken = 0;
		for (int index = 0; index < values.length; index++) {
			if (classes[index] == winner) {
				sum += values[index];
				taken++;
			}
		}

		return sum / taken;
	}
}
