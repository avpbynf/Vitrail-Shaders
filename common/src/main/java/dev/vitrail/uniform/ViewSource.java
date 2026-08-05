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

	/**
	 * The model view of the pass being drawn, which is the camera's for almost all of them and is
	 * not the same question as {@link #gbufferModelView()}.
	 * <p>
	 * <strong>The sky is why the two are apart.</strong> The game draws the sun, the moon, the stars
	 * and the sunrise band by pushing a rotation of the day onto its model view stack and drawing a
	 * quad that sits straight above the camera; the rotation IS where the sun is. A pack reads that
	 * matrix as {@code gl_ModelViewMatrix} and the camera's as {@code gbufferModelView}, and it uses
	 * both at once: BSL writes {@code gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex} to
	 * get a direction in world space out of a vertex the game placed. Answer the two with one matrix
	 * and the rotation cancels itself: the sun stops moving and sits at noon all night.
	 * <p>
	 * Set by the pass before it writes its block, like the depth convention beside it, and answered
	 * with the camera's whenever a pass has set nothing. What a pass hands in is the matrix the game
	 * built, and what comes back out carries the walk bob on top of it, for the reason the
	 * implementation spells out: this engine publishes the bob in the model view and not in the
	 * projection, so a pass matrix without it would be the one matrix of the frame that did not
	 * multiply back to what was drawn.
	 */
	Matrix4fc passModelView();

	Matrix4fc passModelViewInverse();

	/**
	 * The colour the game modulates the pass being drawn by, which a pack reads as its vertex
	 * colour where the mesh carries none. White for every pass that has not set one.
	 * <p>
	 * The sky is why this exists: a sky disc is a mesh of positions, and its colour travels in the
	 * dynamic transforms of the draw. White is not a neutral stand in, because packs recognise
	 * vanilla stars by a colour whose channels are equal and above nought.
	 */
	Vector4fc passColour();

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
