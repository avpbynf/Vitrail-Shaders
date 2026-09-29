package dev.vitrail.sodium;

/**
 * The arithmetic of {@link TerrainMesh} that touches no Sodium type: the tangent frame of a quad once
 * it is seven floats, and the offset of a vertex from the middle of its block.
 * <p>
 * Split off so that it can be run where Sodium is not on the class path, which is every unit test.
 * Without the split, a tangent that leans on the normal, a handedness that comes out the wrong way
 * round or a block middle that rounds the other way is only visible as a normal map lit inside out
 * on some quads of a running game, and no test can say which.
 * <p>
 * What stays in {@link TerrainMesh} is what reads the fields of Sodium's vertex: the frame's Newell
 * sums, the tangent of one triangle and the middle of the sprite. The frame is
 * {@code (nx, ny, nz, tx, ty, tz, handedness)} throughout. Where a comment below speaks of "the
 * javadoc above" it means the one on {@code TerrainMesh.midBlock}.
 */
final class TerrainGeometry {

	private TerrainGeometry() {
	}

	/**
	 * The squared length under which {@link #orthogonalise} takes a tangent to have had nothing left
	 * of it once the normal was subtracted, which is Iris's own {@code NormalHelper.EPS} weighed the
	 * way Iris weighs it, on the square and not on the length.
	 */
	private static final float FLATTENED = 1.0E-20F;

	/**
	 * Takes the normal's own direction out of the tangent, so that what a pack reads is at a right
	 * angle to {@code gl_Normal} on every quad and not only where the mapping happens to be affine.
	 * <p>
	 * <strong>Iris does this to every tangent of the chunk mesh, and to that mesh
	 * alone</strong>: {@code NormalHelper.packDiamondByte} lines 489 to 510 subtracts
	 * {@code n * dot(n, tangent)}, and what it stores is an angle in the plane that leaves, which its
	 * own patched vertex stage turns back into {@code normalize(p.x * t1 + p.y * t2)} of a basis built
	 * from the normal ({@code SodiumTransformer} lines 191 to 198). That packing is reached through
	 * {@code NormalHelper.encodeNormalTangent} lines 512 to 520 and through nothing else, and its
	 * only callers are {@code XHFPTerrainVertex} lines 26 and 132. The entity, text and glyph
	 * tangents take the other road, {@code NormalHelper.computeTangent} straight into
	 * {@code NormI8.pack} ({@code NormalHelper.java:246}, {@code :333} and {@code :414}), which
	 * stores the direction the mapping gave and never projects it onto the normal's plane at all.
	 * <p>
	 * A pack reading the terrain under Iris therefore always reads a unit vector exactly
	 * perpendicular to the normal, and <strong>it is the tangent itself, not the
	 * bitangent, that carries the defect</strong>: the first column of the frame a normal map is
	 * read through is {@code at_tangent.xyz}, and the packs that normalise it normalise its length
	 * and not its direction. A vector leaning towards the normal is still leaning afterwards, and it
	 * tilts the whole frame with it.
	 * <p>
	 * The bitangent is not the argument, and it is worth saying because it looks as though it should
	 * be. Four packs write {@code cross(at_tangent.xyz, gl_Normal.xyz) * at_tangent.w} word for word,
	 * measured over the eight, and all four scale what comes out of it to unit length before using
	 * it - two in a {@code normalize} and two in an {@code inversesqrt(max(dot(b, b), 1e-8))} of
	 * their own - so none of them depends on its length. Nor does this change its direction: what is
	 * subtracted is a multiple of the normal, and the normal crossed with itself is nought. What the
	 * normalising here does is give that cross product a length of one as well, and the handedness
	 * bit goes on meaning what {@link #handedness} says it means.
	 * <p>
	 * <strong>Nothing of that difference is left in the quantisation.</strong> Storing the vector
	 * itself as three rounded bytes brings the dot product with the normal back at a few thousandths
	 * rather than at nought, and the work below is then undone by the rounding.
	 * {@code TerrainMesh.Extra#TANGENT_FRAME} stores the angle in the plane, which is what Iris stores, so
	 * what a pack reads is perpendicular to the last bit on both engines and this projection is what
	 * decides the angle rather than a step on the way to it.
	 */
	static void orthogonalise(float[] frame) {
		float along = frame[0] * frame[3] + frame[1] * frame[4] + frame[2] * frame[5];
		frame[3] -= frame[0] * along;
		frame[4] -= frame[1] * along;
		frame[5] -= frame[2] * along;
		if (frame[3] * frame[3] + frame[4] * frame[4] + frame[5] * frame[5] > FLATTENED) {
			normalise(frame, 3);

			return;
		}

		basis(frame);
	}

	/**
	 * The first axis of Frisvad's basis for the normal already in the frame, which is what Iris
	 * substitutes when the projection above leaves nothing, {@code NormalHelper.onbFromUnitNormal}
	 * lines 468 to 479.
	 * <p>
	 * Written out rather than reasoned out, because the point of that construction is that it holds
	 * at both poles without a branch on which axis the normal is nearest. It is not the same axis as
	 * {@link #perpendicular}'s and the two are not interchangeable: this one is reached with a
	 * tangent that came out parallel to the normal, where that one is reached with no tangent at all.
	 */
	private static void basis(float[] frame) {
		float side = frame[2] >= 0.0F ? 1.0F : -1.0F;
		float scale = -1.0F / (side + frame[2]);
		frame[3] = 1.0F + side * frame[0] * frame[0] * scale;
		frame[4] = side * frame[0] * frame[1] * scale;
		frame[5] = -side * frame[0];
		normalise(frame, 3);
	}

	/** Three of those floats to unit length, or to the up axis when there is no length to speak of. */
	static void normalise(float[] frame, int at) {
		float length = (float) Math.sqrt(frame[at] * frame[at] + frame[at + 1] * frame[at + 1]
				+ frame[at + 2] * frame[at + 2]);
		if (length < 1.0E-9F) {
			frame[at] = 0.0F;
			frame[at + 1] = 1.0F;
			frame[at + 2] = 0.0F;

			return;
		}

		frame[at] /= length;
		frame[at + 1] /= length;
		frame[at + 2] /= length;
	}

	/**
	 * Which way the third axis of the frame turns, which is MINUS the sign of the texture area.
	 * <p>
	 * Seven of the corpus's eight packs read that sign, four of them written exactly as
	 * {@code cross(at_tangent.xyz, gl_Normal.xyz) * at_tangent.w}, which is Iris's convention: that
	 * cross product is the true bitangent for {@code w = +1} and its opposite for {@code w = -1}, so
	 * {@code w} is what corrects the chirality rather than a fixed negation. Body Camera is the
	 * eighth: it takes {@code .xyz} alone and crosses it with the normal unscaled, which is the
	 * {@code +1} chirality applied to every quad whatever this answers.
	 * <p>
	 * Iris reaches the same value from the other end,
	 * {@code sign(dot(bitangent, tangent x normal))} in {@code NormalHelper.computeTangent}, which
	 * reduces to this for a quad whose corners are in one plane. Written the other way round it is
	 * not a subtle error: every normal map on the terrain has its green channel inverted and lights
	 * a bump as a dent.
	 * <p>
	 * <strong>Every place that writes the sign goes through here, and one lies outside this
	 * file</strong>: the {@code at_tangent} entry of {@code VertexPrologue.BETTER_DEFAULTS}, which
	 * answers the same question for a mesh carrying no tangent at all. Nothing checks that the two
	 * agree, so a hand that turns one has to turn the other.
	 */
	static float handedness(float textureArea) {
		return textureArea < 0.0F ? 1.0F : -1.0F;
	}

	/**
	 * Any unit vector at a right angle to the normal already in the frame. The direction only: the
	 * sign is whatever a triangle of this quad measured, or the frame's starting value when none
	 * could. Being at a right angle already, it is what {@link #orthogonalise} leaves alone.
	 * <p>
	 * <strong>Iris answers this case with whatever tangent it last managed to compute.</strong> When
	 * both triangles refuse, {@code NormalHelper.computeTangent} returns before it writes its output
	 * vector, so {@code XHFPTerrainVertex} keeps the one it already held. That field belongs to the
	 * encoder, and Sodium builds one per pass and facing and reuses it for every section a worker
	 * meshes, so what is carried in is an earlier quad of the same bucket and need not belong to
	 * this mesh at all. Before any tangent has been computed it holds {@code (0,1,0)} with a
	 * handedness of {@code +1}, which is the one place the reference states the value this file
	 * starts from. {@code encodeNormalTangent} takes the normal's component out of every tangent it
	 * packs, not only a carried one, but in a facing bucket that projection changes nothing: the
	 * carried tangent lies at a right angle to the shared normal already, so the carried direction
	 * is exactly what the pack reads. Only a carried tangent parallel to the normal leaves nothing,
	 * and there an axis of a basis built from that normal takes its place: with the starting value
	 * above, that is the first quads of the two vertical buckets, not every quad that gets here.
	 * <p>
	 * So the difference is real twice over: this answer depends on the quad alone where that one
	 * depends on the order the bucket was filled in, and on the quads where both engines do
	 * substitute, the axis is not the same axis, Frisvad's basis against a cross with the less
	 * aligned of the first two axes. Nothing in 26.2 makes the carry-over impossible, so it is a
	 * divergence rather than a choice.
	 */
	static void perpendicular(float[] frame) {
		// Crossed with the less aligned of the x and y axes, so the result is never a zero.
		boolean upright = Math.abs(frame[1]) < Math.abs(frame[0]);
		frame[3] = upright ? -frame[2] : 0.0F;
		frame[4] = upright ? 0.0F : frame[2];
		frame[5] = upright ? frame[0] : -frame[1];
		normalise(frame, 3);
	}

	/**
	 * One axis of that offset, from the corner of the block to the vertex, in sixty-fourths. Floored
	 * and not truncated, which is the rounding Iris's own cast performs on its own argument
	 * everywhere that argument is positive; the javadoc above says where the two part all the same.
	 */
	static int offset(int block, float vertex) {
		return (int) Math.floor((block + 0.5F - vertex) * 64.0F);
	}
}
