package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.List;

/**
 * The quad's normal, the tangent of its texture mapping and the handedness of the frame the two
 * build, in one word of the chunk mesh, and the text that undoes it.
 * <p>
 * <strong>Both halves of the bargain live here on purpose.</strong> The word is written by
 * {@code TerrainMesh} and read by the vertex prologue, which is generated GLSL, so the two sides
 * never meet in a compiler and nothing would say a word if they drifted apart. They are one subject
 * and they are kept in one file, where a change to either has the other under its eyes.
 * <p>
 * <strong>The bargain is Iris's</strong>, {@code NormalHelper.encodeNormalTangent}
 * ({@code NormalHelper.java:512-520}): the tangent is left at a right angle to the normal by the
 * encoder, so once the normal is known what is left of the tangent is one angle in the normal's own
 * plane and one sign. Iris spends twenty-four bits on the normal, octahedral and twelve bits an
 * axis, and its top byte on that angle.
 * <p>
 * <strong>Where the sign lives is this engine's own, and it is forced.</strong> Iris reads it out of
 * a material bit, {@code (a_LightAndData.z & 1u) != 0u} at {@code SodiumTransformer.java:231},
 * which leaves its own word entirely to the two directions. Nothing of this engine can ride the
 * material: a translucent quad reaches the encoder from the sorter under a material Sodium chose
 * itself, so the bit would be gone by then for every translucent quad and every fluid, and nothing
 * would say so. The sign therefore takes a bit of this word, and it is taken off the NORMAL rather
 * than off the angle, because the normal has bits to spare where the angle has none. Eleven bits on
 * the octahedral y instead of twelve still leaves the normal four times closer than the three signed
 * bytes this replaces, 0.09 degrees at worst against 0.39 and 0.03 on average against 0.17, where
 * taking the bit off the angle would have doubled the tangent's own error instead.
 * <p>
 * So: twelve bits of octahedral x, eleven of octahedral y, the sign, then the angle.
 * <p>
 * <strong>What the tangent pays for it, said plainly</strong>: it lands about twice as far out as
 * the three bytes it replaces, 0.90 degrees at worst against 0.39, which is the eight bit angle's
 * own quantum and therefore exactly what the reference spends. What it gains is that it comes back
 * at a right angle to the normal to seven decimal places, where three rounded bytes were a few
 * thousandths off it, and it is the tangent and not the bitangent that carries that lean into every
 * normal map on the terrain. All of these are measured by the off-game harness, which is the only
 * witness this arithmetic has.
 */
public final class TangentFrame {

	/** What the prologue calls the function that reads the normal back out of the word. */
	public static final String NORMAL_OF = "ofFrameNormal";

	/** And the one that reads the tangent back out of the word and that normal together. */
	public static final String TANGENT_OF = "ofFrameTangent";

	/**
	 * What the octahedral x is scaled by, which is what twelve bits hold once the sign is taken out
	 * of them. Iris's own, {@code NormalHelper.snorm12}.
	 */
	private static final int X_MAX = 2047;

	/** The same for the y, which has eleven bits here where Iris gives it twelve. */
	private static final int Y_MAX = 1023;

	/**
	 * The sum of absolute components under which a tangent is taken to lie flat against the whole
	 * plane and to have no angle to give, which is Iris's own {@code NormalHelper.EPS}. The encoder
	 * squares the tangent up against the normal before anything reaches here, so a unit vector in
	 * that plane never falls this low; it is the reference's own guard and it is kept as one.
	 */
	private static final float FLAT = 1.0E-20F;

	private TangentFrame() {
	}

	/**
	 * The seven floats of a frame into the word: the normal in the first three, the tangent in the
	 * next three, and the handedness in the last.
	 * <p>
	 * <strong>The angle is measured against the DECODED normal and not against the one the quad
	 * had.</strong> The basis the angle is read in is built from the normal, so the two sides have to
	 * build it from the same vector or the tangent comes back somewhere else entirely; the prologue
	 * has only the decoded one, so this reads the word back before going on.
	 * <p>
	 * <strong>That is a divergence, and this is what it works around.</strong> Iris hands
	 * {@code packDiamondByte} the un-quantised normal, {@code NormalHelper.java:512-520}, while its
	 * own patched text builds the basis out of the decoded one,
	 * {@code SodiumTransformer.java:230-231}. Frisvad's basis turns over on the sign of the normal's
	 * z, so where the two sides disagree about that sign the tangent comes back nearly reversed: the
	 * harness pairs them the reference's way over four hundred thousand frames and the worst tangent
	 * lands 179.9 degrees out. Following the reference here would be following it into that, so this
	 * pairs the two sides instead and the worst is 0.90.
	 */
	public static int pack(float[] frame) {
		int normal = packNormal(frame[0], frame[1], frame[2]);
		float[] decoded = normal(normal);

		return normal | (frame[6] >= 0.0F ? 1 << 23 : 0)
				| (packTangent(decoded, frame[3], frame[4], frame[5]) << 24);
	}

	/**
	 * The normal back out of the word, by the same arithmetic {@link #decode} emits. The two have to
	 * agree to the last bit, because {@link #pack} measures its angle in a basis built from what
	 * comes out of here and the prologue reads it in a basis built from its own copy.
	 */
	public static float[] normal(int word) {
		float x = (word << 20 >> 20) / (float) X_MAX;
		float y = (word << 9 >> 21) / (float) Y_MAX;
		float z = 1.0F - Math.abs(x) - Math.abs(y);
		float[] normal = z >= 0.0F
				? new float[] {x, y, z}
				: new float[] {(1.0F - Math.abs(y)) * sideOf(x),
						(1.0F - Math.abs(x)) * sideOf(y), z};
		normalise(normal);

		return normal;
	}

	/**
	 * The tangent and its handedness back out of the word and the normal, as four floats. Not
	 * reached by the engine, which leaves this to the prologue on the device; it is here so that the
	 * arithmetic the prologue is written from can be measured off the game.
	 */
	public static float[] tangent(float[] normal, int word) {
		float side = sideOf(normal[2]);
		float scale = -1.0F / (side + normal[2]);
		float across = normal[0] * normal[1] * scale;
		float[] first = {1.0F + side * normal[0] * normal[0] * scale, side * across,
				-side * normal[0]};
		float[] second = {across, side + normal[1] * normal[1] * scale, -normal[1]};

		float turn = (word >>> 24) * (1.0F / 256.0F);
		float along = 1.0F - 4.0F * Math.abs(turn - 0.5F);
		float away = (turn >= 0.5F ? 1.0F : -1.0F) * (1.0F - Math.abs(along));

		float[] tangent = {along * first[0] + away * second[0], along * first[1] + away * second[1],
				along * first[2] + away * second[2], (word >> 23 & 1) != 0 ? 1.0F : -1.0F};
		normalise(tangent);

		return tangent;
	}

	/**
	 * The two functions the vertex prologue is given to undo all this: the normal out of the word,
	 * and the tangent out of the word and that normal together.
	 * <p>
	 * <strong>Every line of it is the shape Iris patches into its own chunk stages</strong>,
	 * {@code SodiumTransformer.java:162-198}: the octahedral decode, Frisvad's basis for the normal,
	 * and the diamond angle read back in that basis. What differs is where the two numbers sit in
	 * the word.
	 *
	 * @param tangent whether the pack reads {@code at_tangent}. A pack that reads the normal alone
	 *                gets the first function and not the second, the word being one element either
	 *                way
	 */
	public static List<String> decode(boolean tangent) {
		List<String> lines = new ArrayList<>();

		// Twelve bits of octahedral x and eleven of y, each read back as a signed integer and
		// divided by what its own width can hold.
		lines.add("vec3 " + NORMAL_OF + "(uint word) {");
		lines.add("\tvec2 folded = vec2(bitfieldExtract(int(word), 0, 12),"
				+ " bitfieldExtract(int(word), 12, 11)) * vec2(1.0 / " + X_MAX + ".0, 1.0 / "
				+ Y_MAX + ".0);");
		lines.add("\tfloat up = 1.0 - abs(folded.x) - abs(folded.y);");
		lines.add("\tvec2 sides = vec2(folded.x >= 0.0 ? 1.0 : -1.0, folded.y >= 0.0 ? 1.0 : -1.0);");
		// The lower half of the octahedron is reflected over the diagonals, so it is unreflected
		// here. Leaving it out answers the mirror image of every downward normal.
		lines.add("\treturn normalize(vec3(up >= 0.0 ? folded"
				+ " : (1.0 - abs(folded.yx)) * sides, up));");
		lines.add("}");

		if (!tangent) {
			return List.copyOf(lines);
		}

		lines.add("vec4 " + TANGENT_OF + "(vec3 normal, uint word) {");
		// Frisvad: an orthonormal pair for the normal that has no branch on which axis it is nearest
		// and no singularity at either pole, which is what makes the angle below mean one direction.
		lines.add("\tfloat side = normal.z >= 0.0 ? 1.0 : -1.0;");
		lines.add("\tfloat scale = -1.0 / (side + normal.z);");
		lines.add("\tfloat across = normal.x * normal.y * scale;");
		lines.add("\tvec3 first = vec3(1.0 + side * normal.x * normal.x * scale,"
				+ " side * across, -side * normal.x);");
		lines.add("\tvec3 second = vec3(across, side + normal.y * normal.y * scale, -normal.y);");
		// The diamond: an angle round the unit square rather than round the circle, so that the
		// eight bits are spent evenly on a direction without a trigonometric function either side.
		lines.add("\tfloat turn = float(word >> 24u) * (1.0 / 256.0);");
		lines.add("\tfloat along = 1.0 - 4.0 * abs(turn - 0.5);");
		lines.add("\tfloat away = (turn >= 0.5 ? 1.0 : -1.0) * (1.0 - abs(along));");
		lines.add("\treturn vec4(normalize(along * first + away * second),"
				+ " ((word >> 23u) & 1u) != 0u ? 1.0 : -1.0);");
		lines.add("}");

		return List.copyOf(lines);
	}

	/**
	 * A unit vector as an octahedral pair, twelve bits of x in the low bits and eleven of y above
	 * them.
	 * <p>
	 * The sphere is projected onto the octahedron and then onto the plane, and the lower half is
	 * reflected over the diagonals so that the whole sphere fills the square. Iris does the same and
	 * with the same fold, {@code NormalHelper.encodeNormal} lines 438 to 464.
	 */
	private static int packNormal(float x, float y, float z) {
		float onto = 1.0F / (Math.abs(x) + Math.abs(y) + Math.abs(z));
		float px = x * onto;
		float py = y * onto;
		float ox = z > 0.0F ? px : (1.0F - Math.abs(py)) * sideOf(px);
		float oy = z > 0.0F ? py : (1.0F - Math.abs(px)) * sideOf(py);

		return (snorm(ox, X_MAX) & 0xFFF) | ((snorm(oy, Y_MAX) & 0x7FF) << 12);
	}

	/**
	 * The tangent as an angle round the unit square in the normal's own plane, eight bits of it.
	 * <p>
	 * The basis is Frisvad's, as the encoder already builds it for a tangent it had to substitute
	 * and as {@link #decode} builds it again, and the diamond is Iris's,
	 * {@code NormalHelper.packDiamondByte} lines 489 to 510: an angle round a square rather than
	 * round a circle, so that the eight bits are spent evenly on a direction with no trigonometric
	 * function on either side.
	 * <p>
	 * A tangent flat against the whole plane has no angle to give and takes the middle of the range,
	 * which is Iris's own answer for it.
	 */
	private static int packTangent(float[] normal, float x, float y, float z) {
		float side = sideOf(normal[2]);
		float scale = -1.0F / (side + normal[2]);
		float across = normal[0] * normal[1] * scale;
		float along = x * (1.0F + side * normal[0] * normal[0] * scale) + y * side * across
				+ z * -side * normal[0];
		float away = x * across + y * (side + normal[1] * normal[1] * scale) + z * -normal[1];

		float span = Math.abs(along) + Math.abs(away);
		if (span <= FLAT) {
			return 128;
		}

		float turn = away >= 0.0F
				? 0.25F * (1.0F - along / span) + 0.5F
				: 0.25F * (along / span) + 0.25F;

		return Math.round(turn * 256.0F) & 0xFF;
	}

	/** One component of an octahedral pair as a signed integer of that many bits, less the sign. */
	private static int snorm(float value, int max) {
		return Math.round(Math.clamp(value, -1.0F, 1.0F) * max);
	}

	/**
	 * Plus one for a number that is nought or above and minus one below it, which is what the
	 * octahedral fold multiplies by and which way round Frisvad's basis turns. Nought is on the
	 * positive side on both sides of the fold, here and in the prologue, so a component that lands
	 * exactly on it folds the same way twice.
	 */
	private static float sideOf(float value) {
		return value >= 0.0F ? 1.0F : -1.0F;
	}

	/** The first three components to unit length, the fourth left alone where there is one. */
	private static void normalise(float[] vector) {
		float length = (float) Math.sqrt(
				vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
		if (length > 0.0F) {
			vector[0] /= length;
			vector[1] /= length;
			vector[2] /= length;
		}
	}
}
