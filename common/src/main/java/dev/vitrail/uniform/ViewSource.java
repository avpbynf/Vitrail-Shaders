package dev.vitrail.uniform;

import org.joml.Matrix4fc;
import org.joml.Vector4fc;

/**
 * The view geometry, in the convention we publish to packs. Split out of {@link WorldState} because
 * it is the only part that comes from a captured matrix rather than from a game object, and because
 * it is owned by a different piece of work.
 * <p>
 * Everything here is already in the OpenGL form: a projection handed out of this interface has been
 * through {@link ClipSpace#toLegacyDepth}, and an inverse is the inverse of what was published and
 * never the conversion of an inverse. The two are not the same matrix and only one of them makes a
 * reprojection come back to where it started.
 */
public interface ViewSource {

	Matrix4fc gbufferModelView();

	Matrix4fc gbufferModelViewInverse();

	Matrix4fc gbufferProjection();

	Matrix4fc gbufferProjectionInverse();

	Matrix4fc gbufferPreviousModelView();

	Matrix4fc gbufferPreviousProjection();

	Matrix4fc shadowModelView();

	Matrix4fc shadowModelViewInverse();

	Matrix4fc shadowProjection();

	Matrix4fc shadowProjectionInverse();

	Matrix4fc dhProjection();

	Matrix4fc dhProjectionInverse();

	Matrix4fc dhPreviousProjection();

	float dhNearPlane();

	float dhFarPlane();

	int dhRenderDistanceChunks();

	float near();

	float far();

	/** {@code (clipA, clipB, readA, readB)} for the target being drawn into, see {@link ClipSpace}. */
	Vector4fc depthConvention();
}
