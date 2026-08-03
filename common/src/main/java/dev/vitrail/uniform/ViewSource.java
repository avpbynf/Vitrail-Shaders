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

	/**
	 * The four published shadow matrices are the pair the shadow map ON HAND was drawn with, which
	 * is one frame older than the camera: the map is drawn at the end of a frame for the next one.
	 * A sampling pass that used the fresh pair instead would miss the map by one frame of camera
	 * motion, which reads as the whole lit picture flickering whenever the player moves. The
	 * {@code drawn} four are the fresh pair, for the one stage that draws the map itself.
	 */
	Matrix4fc shadowModelView();

	Matrix4fc shadowModelViewInverse();

	Matrix4fc shadowProjection();

	Matrix4fc shadowProjectionInverse();

	Matrix4fc drawnShadowModelView();

	Matrix4fc drawnShadowModelViewInverse();

	Matrix4fc drawnShadowProjection();

	Matrix4fc drawnShadowProjectionInverse();

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
