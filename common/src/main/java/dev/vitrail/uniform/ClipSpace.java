package dev.vitrail.uniform;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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
}
