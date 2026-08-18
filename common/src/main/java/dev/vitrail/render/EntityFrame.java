package dev.vitrail.render;

/**
 * The direction the texture's U axis runs in over an entity POLYGON, and the normal that direction
 * is squared against, neither of which any one vertex knows.
 * <p>
 * <strong>Both roads into the entity mesh come through here, and that is the point.</strong> A mob
 * is written by Sodium at the game's own stride and converted by
 * {@link dev.vitrail.sodium.EntityMeshSerializer}; a block entity, a held item and the hand are
 * written vertex by vertex through {@code BufferBuilder} and filled by
 * {@link dev.vitrail.mixin.BufferBuilderMixin}. The two see completely different memory and would
 * have written this arithmetic twice, which for a tangent is not a tidiness argument: the handedness
 * in the fourth component decides whether a bump lights as a bump or as a dent, so two copies that
 * drifted apart would light one half of the picture inside out and nothing would say a word.
 * <p>
 * The other name the polygon answers, {@code mc_midTexCoord}, is not here and does not need to be:
 * it is the mean of the corners' texture coordinates, which each road works out as it walks them
 * and which has nothing in it to get wrong.
 * <p>
 * <strong>Nothing here names an API of the game</strong>, which is what lets the off-game harness
 * compile it and measure the numbers. That harness is the only witness this arithmetic has: the
 * values reach the shader as bytes read straight off the element, so no generated text stands
 * between the two sides and there is nothing in the game to read back and compare.
 * <p>
 * The arithmetic is Iris's, {@code vertices/NormalHelper.computeTangent} and
 * {@code computeTangentSmooth}, and it is the standard tangent of a triangle: the two texture
 * gradients inverted onto the two edges. What differs is spelled out where it differs.
 */
public final class EntityFrame {

	/**
	 * What a polygon that has no tangent to give is written with, which is the axis
	 * {@code VertexPrologue} hands a mesh carrying no tangent at all.
	 * <p>
	 * A tangent of nought is not harmless: every pack normalises the one it reads, and
	 * {@code normalize(vec3(0))} is a division by nought whose NaN travels into the colour through
	 * the whole tangent frame.
	 */
	public static final int FLAT = pack(1.0F, 0.0F, 0.0F, 1.0F);

	/** What one signed byte of a normalised component holds, which is the element's own scale. */
	private static final float RANGE = 127.0F;

	/**
	 * The squared length below which a vector is taken to be nought rather than normalised. On the
	 * square so that no root is taken of a degenerate case on the way to finding out.
	 */
	private static final float TINY = 1.0E-12F;

	private EntityFrame() {
	}

	/**
	 * The quad's own normal into {@code into}, or {@code false} where its corners give none.
	 * <p>
	 * Taken across the two DIAGONALS rather than off two edges, which is Iris's own
	 * ({@code NormalHelper.computeFaceNormalManual}) and is what makes it the whole quad's answer
	 * rather than one corner's: a quad whose four corners are not quite coplanar has two edge
	 * normals and one diagonal normal.
	 * <p>
	 * <strong>The refusal is this engine's own and Iris has no such branch.</strong> A quad of no
	 * area gives a cross product of nought, and normalising that is a NaN that reaches the colour
	 * through the tangent frame; the caller keeps the normal the game wrote instead. Nothing of the
	 * game's own entity geometry is known to draw one, so this is a guard rather than a case.
	 */
	public static boolean faceNormal(float[] into, float x0, float y0, float z0, float x1, float y1,
			float z1, float x2, float y2, float z2, float x3, float y3, float z3) {
		float ax = x2 - x0;
		float ay = y2 - y0;
		float az = z2 - z0;
		float bx = x3 - x1;
		float by = y3 - y1;
		float bz = z3 - z1;

		float nx = ay * bz - az * by;
		float ny = az * bx - ax * bz;
		float nz = ax * by - ay * bx;
		float square = nx * nx + ny * ny + nz * nz;
		if (square <= TINY) {
			return false;
		}

		float scale = (float) (1.0 / Math.sqrt(square));
		into[0] = nx * scale;
		into[1] = ny * scale;
		into[2] = nz * scale;

		return true;
	}

	/**
	 * The tangent of a polygon, from its normal and the first three of its corners, packed as the
	 * element carries it.
	 * <p>
	 * The first three and no more, whether the polygon has three corners or four: three corners
	 * already give both texture gradients, and this is Iris's own reading as well.
	 *
	 * @param flatten whether the corners are first flattened onto the plane the normal stands on,
	 *                which is what Iris does for a polygon lit from a normal per corner
	 *                ({@code NormalHelper.computeTangentSmooth}) and not for one lit flat. Left out
	 *                where it is owed, a smooth shaded corner's tangent leans by however far its own
	 *                normal leans from the polygon's
	 */
	public static int tangent(float normalX, float normalY, float normalZ, boolean flatten,
			float x0, float y0, float z0, float u0, float v0,
			float x1, float y1, float z1, float u1, float v1,
			float x2, float y2, float z2, float u2, float v2) {
		float px0 = x0;
		float py0 = y0;
		float pz0 = z0;
		float px1 = x1;
		float py1 = y1;
		float pz1 = z1;
		float px2 = x2;
		float py2 = y2;
		float pz2 = z2;
		if (flatten) {
			float onto0 = x0 * normalX + y0 * normalY + z0 * normalZ;
			float onto1 = x1 * normalX + y1 * normalY + z1 * normalZ;
			float onto2 = x2 * normalX + y2 * normalY + z2 * normalZ;
			px0 -= onto0 * normalX;
			py0 -= onto0 * normalY;
			pz0 -= onto0 * normalZ;
			px1 -= onto1 * normalX;
			py1 -= onto1 * normalY;
			pz1 -= onto1 * normalZ;
			px2 -= onto2 * normalX;
			py2 -= onto2 * normalY;
			pz2 -= onto2 * normalZ;
		}

		float edge1x = px1 - px0;
		float edge1y = py1 - py0;
		float edge1z = pz1 - pz0;
		float edge2x = px2 - px0;
		float edge2y = py2 - py0;
		float edge2z = pz2 - pz0;

		float du1 = u1 - u0;
		float dv1 = v1 - v0;
		float du2 = u2 - u0;
		float dv2 = v2 - v0;

		// Three corners sharing a texture coordinate leave no gradient to invert. Iris carries the
		// same guard and the same value for it, and what comes out of it is an edge rather than
		// anything meaningful: the point is to keep the branch out of the division.
		float span = du1 * dv2 - du2 * dv1;
		float scale = span == 0.0F ? 1.0F : 1.0F / span;

		float tx = scale * (dv2 * edge1x - dv1 * edge2x);
		float ty = scale * (dv2 * edge1y - dv1 * edge2y);
		float tz = scale * (dv2 * edge1z - dv1 * edge2z);
		float square = tx * tx + ty * ty + tz * tz;
		if (square <= TINY) {
			return FLAT;
		}

		float unit = (float) (1.0 / Math.sqrt(square));
		tx *= unit;
		ty *= unit;
		tz *= unit;

		// The bitangent the texture really runs along, against the one this frame would predict from
		// the tangent and the normal. Where the two point apart the third axis turns the other way,
		// and that is the whole of what the fourth component says: a mirrored piece of a skin lights
		// its bumps as dents without it.
		float bx = scale * (-du2 * edge1x + du1 * edge2x);
		float by = scale * (-du2 * edge1y + du1 * edge2y);
		float bz = scale * (-du2 * edge1z + du1 * edge2z);
		float side = bx * (ty * normalZ - tz * normalY) + by * (tz * normalX - tx * normalZ)
				+ bz * (tx * normalY - ty * normalX);

		return pack(tx, ty, tz, side < 0.0F ? -1.0F : 1.0F);
	}

	/**
	 * Four normalised components into one word, low byte first, which is how the element is laid out
	 * and how the game writes the normal beside it.
	 * <p>
	 * <strong>Rounded where Iris and the game both truncate</strong> ({@code NormI8.pack},
	 * {@code BufferBuilder.normalIntValue}), which is what {@code TangentFrame.snorm} already does
	 * for the chunk mesh: truncation pulls every component toward nought by up to a whole step where
	 * rounding pulls it by half of one. It is a quantisation and not a convention, so both engines
	 * read the same direction either way, to within four tenths of a degree.
	 */
	public static int pack(float x, float y, float z, float w) {
		return (snorm(x) & 0xFF) | ((snorm(y) & 0xFF) << 8) | ((snorm(z) & 0xFF) << 16)
				| ((snorm(w) & 0xFF) << 24);
	}

	/** One normalised component back out of a word, by its place in it. */
	public static float unpack(int word, int component) {
		return (byte) (word >> (component * 8)) * (1.0F / RANGE);
	}

	/** One component as the signed byte the element holds it in. */
	private static int snorm(float value) {
		return Math.round(Math.clamp(value, -1.0F, 1.0F) * RANGE);
	}
}
