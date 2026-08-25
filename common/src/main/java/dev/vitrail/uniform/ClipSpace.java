package dev.vitrail.uniform;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * The clip space conversion, and the two conventions a target can carry.
 * <p>
 * <strong>The matrix we draw with is not the matrix we publish.</strong> Minecraft 26.2 rasterises
 * with a reversed Z over zero to one on both backends: {@code Projection} swaps the near and far
 * planes before building the matrix, and the Vulkan device reports {@code isZZeroToOne}. A pack is
 * written against the OpenGL form, z from minus one to one with the near plane at minus one, and
 * it is written against that form everywhere: in the matrices it reads, in the depth it samples,
 * and in the {@code gl_FragCoord.z} it inspects.
 * <p>
 * Iris undoes the whole thing at the source, ending with a {@code glClipControl} back to the old
 * volume. That half is not available here, and not because the call is missing: Vulkan has no
 * minus one to one clip volume at all, short of an extension the game does not enable. So the
 * matrix is decoupled from the pipeline instead, which is an identity rather than an approximation
 * and is the reason the conversion has a test of its own.
 */
public final class ClipSpace {

	/** (clipA, clipB, readA, readB) for a target the game owns: reversed Z. */
	public static final Vector4f REVERSED = new Vector4f(-0.5F, 0.5F, -1.0F, 1.0F);

	/** (clipA, clipB, readA, readB) for a target we own: forward 0..1. */
	public static final Vector4f FORWARD = new Vector4f(0.5F, 0.5F, 1.0F, 0.0F);

	private ClipSpace() {
	}

	/**
	 * Turns the matrix the pipeline draws with, which on this backend is reversed Z over 0..1, into
	 * the OpenGL form a pack expects, by replacing the z row with {@code rowW - 2 * rowZ}. An
	 * identity, not an approximation: it holds for the composed matrix, walk bob and nausea
	 * included.
	 * <p>
	 * Why the composed matrix and not only the projection: the replacement is a multiplication on
	 * the <em>left</em> by a matrix that is the identity except for its z row, and everything the
	 * game applies to the projection afterwards, the walk bob, the damage tilt, the nausea and the
	 * portal, is applied on the right. Left and right multiplications do not interfere, so the
	 * conversion may be handed whatever was captured, and rebuilding the matrix from the field of
	 * view instead would silently throw all four of those away.
	 * <p>
	 * The opposite combination, {@code 2 * rowZ - rowW}, is wrong on both terms and it circulates.
	 * The harness settles it against JOML at twenty sets of bounds, which is the only reason to
	 * trust either.
	 */
	public static Matrix4f toLegacyDepth(Matrix4fc rendered, Matrix4f dest) {
		float m02 = rendered.m03() - 2.0F * rendered.m02();
		float m12 = rendered.m13() - 2.0F * rendered.m12();
		float m22 = rendered.m23() - 2.0F * rendered.m22();
		float m32 = rendered.m33() - 2.0F * rendered.m32();

		// Read out of the source before anything is written, so that converting a matrix onto
		// itself is allowed.
		dest.set(rendered);
		dest.m02(m02);
		dest.m12(m12);
		dest.m22(m22);
		dest.m32(m32);

		return dest;
	}

	/**
	 * The pair that turns a depth the game rasterised into the same point's depth in the volume
	 * Distant Horizons rasterises its far terrain in: {@code far = pair.x * world + pair.y}.
	 * <p>
	 * <strong>Why a pair and not a matrix.</strong> The two volumes share every row but one.
	 * {@code RenderUtil.setDhProjectionMatrix} overwrites the z row of the game's own matrix with
	 * clip planes of its own and touches nothing else, and {@code render/ViewMatrices} splices that
	 * row into the frame's matrix the same way. So the two projections have the same w row, and the
	 * clip w a vertex comes out with is the same number under both. Write the eye distance the game
	 * measures as {@code t}: the game's depth is {@code -m22 + m32 / t}, the far terrain's is
	 * {@code -scale + offset / t}, and eliminating {@code t} between the two leaves an affine map in
	 * the depth alone. There is nothing to approximate and no matrix to invert.
	 * <p>
	 * <strong>It holds under the walk bob, which is the half that is easy to get wrong.</strong>
	 * The bob, the damage tilt, the nausea and the portal are all applied to the RIGHT of the
	 * projection, so they multiply the two IMAGES this pair stands between identically and cancel
	 * out of the ratio: what plays the part of {@code t} above stops being the eye distance and
	 * becomes the bobbed one, the same number for both. The pair is therefore exact for a walking
	 * player and not merely close, which a derivation written on a still camera would never have
	 * said.
	 * <p>
	 * That is not the claim {@code render/ViewMatrices.advanceDistantVolume} makes, and the two only
	 * look as though they disagree. That one compares the volume this engine rasterises the far
	 * terrain in against the volume this mod would have rasterised it in, and those two really do
	 * part under the bob, because this mod overwrites the row of a matrix the bob has already been
	 * multiplied into. This one compares the two images that exist.
	 * <p>
	 * <strong>The premise is asked of the matrix rather than assumed of it.</strong> Four entries
	 * carry it: the z row has to be nought outside its own two terms, so that the depth is a
	 * function of the eye distance alone, and the w row has to be the perspective's, so that the
	 * clip w is the same number under both. They hold on every frame the engine splits the bob out
	 * of the projection, which is the ordinary one. They do NOT hold on a frame it could not, where
	 * {@code render/CapturedProjection} hands back the composed matrix and the bob's own terms sit
	 * in that row: this answers false there, and the far terrain's water goes back to being drawn
	 * against the far terrain alone, which is where it stood before any of this.
	 * <p>
	 * <strong>Two things the caller owes, and neither is optional.</strong> A game depth of nought
	 * is the clear value of a reversed Z and means nothing was drawn there, so it has to be left
	 * alone rather than converted: it stands for the game's own far plane, which is far nearer than
	 * this mod's, and converting it would put a lid over every LOD past the game's render distance.
	 * And the result has to be clamped, because everything closer than this mod's near plane
	 * converts to more than one, and that plane is at most seven and a half blocks out:
	 * {@code core/util/RenderUtil.setDhProjectionMatrix} caps it there with a {@code Math.min}, and
	 * only while this mod is not overriding the near plane on the player's height.
	 *
	 * @param rendered the frame's own projection as the device took it, reversed Z over zero to one
	 * @param scale    the z term of the row this mod drew with, {@code dh/DhDepth} reads it
	 * @param offset   the w term of that row, nought while this mod has drawn no frame
	 * @param dest     filled with the multiply and then the add, and left alone when this answers
	 *                 false
	 * @return false when there is no conversion to be had: this mod having no volume yet, a
	 *         projection with no perspective in it, or a frame whose projection carries terms the
	 *         derivation above has no place for
	 */
	public static boolean distantDepth(Matrix4fc rendered, float scale, float offset,
			Vector2f dest) {
		float worldOffset = rendered.m32();
		if (offset == 0.0F || worldOffset == 0.0F || rendered.m02() != 0.0F
				|| rendered.m12() != 0.0F || rendered.m23() != -1.0F || rendered.m33() != 0.0F) {
			return false;
		}

		float multiply = offset / worldOffset;
		dest.set(multiply, rendered.m22() * multiply - scale);

		return true;
	}
}
