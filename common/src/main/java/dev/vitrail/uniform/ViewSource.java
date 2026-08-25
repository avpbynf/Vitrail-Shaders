package dev.vitrail.uniform;

import org.joml.Matrix4fc;
import org.joml.Vector2f;
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
	 * The entities are the second family to set one, and by a far smaller amount. The game draws an
	 * armour piece, a decal and two offset pieces with the camera scaled a hair towards the viewer,
	 * so that they do not fight the depth of the body they cover; it is the same matrix modified,
	 * not a matrix of its own, and it is applied here because that is where the game applies it.
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
	 * The walk bob on its own, which is the matrix {@link #passModelView()} is built by multiplying
	 * the pass's own onto.
	 * <p>
	 * Published rather than kept, and it is not a convenience: a pass whose model view belongs to the
	 * DRAW rather than to the run has to form the same product in the shader, and the only piece of
	 * it that is not already there is this one. Which passes those are is
	 * {@link dev.vitrail.glsl.LegacyGlsl#readsDrawModelView}, and
	 * {@link dev.vitrail.glsl.LegacyGlsl#CAMERA_BOB} carries why the factor has to be a name.
	 * <p>
	 * The identity on a frame this engine could not split, exactly as the pass matrix is then the
	 * game's own. <strong>The hand is the exception to that sentence and to the one above.</strong>
	 * It is not placed by the frame's matrix at all, being drawn under a projection this engine
	 * builds, so it hands in the pose that projection was built with and is answered that;
	 * {@code dev.vitrail.render.ViewMatrices.passBob} carries which effects that pose leaves out and
	 * why. It is answered whatever the split says, too, so a hand program keeps a product that
	 * multiplies back on a frame where nothing else does.
	 */
	Matrix4fc cameraBob();

	/**
	 * The projection of the pass being drawn, which is the frame's for almost all of them and is not
	 * the same question as {@link #gbufferProjection()}.
	 * <p>
	 * <strong>The hand is why the two are apart.</strong> It is drawn under a projection of its own:
	 * the head-up field of view rather than the camera's, and a clip depth squeezed to an eighth so
	 * that an arm held in front of a wall is not cut in half by it. A pack reads that squeeze as
	 * {@code gl_ProjectionMatrix} and undoes it with {@code MC_HAND_DEPTH}, which is the same eighth
	 * written as a macro, while everything it reprojects against the world reads
	 * {@code gbufferProjection}. Answer the two with one matrix and one of the two families breaks:
	 * the frame's, and the hand lands somewhere in the world at the camera's field of view; the
	 * hand's, and every composite rebuilds the world through a volume nothing was drawn in.
	 * <p>
	 * That is Iris's split as well and not a shape of ours. Its transformer sends
	 * {@code gl_ProjectionMatrix} to {@code iris_ProjMat}
	 * ({@code pipeline/transform/transformer/VanillaTransformer.java:368}), which is the projection
	 * really bound at the draw and therefore the hand's while the hand is being drawn, and keeps
	 * {@code gbufferProjection} on the level's.
	 * <p>
	 * Set by the pass before it writes its block, like the model view and the depth convention beside
	 * it, and answered with the frame's whenever a pass has set nothing. What a pass hands in is in
	 * the volume the game draws in, so it goes through the same conversion the frame's does.
	 */
	Matrix4fc passProjection();

	Matrix4fc passProjectionInverse();

	/**
	 * The colour the game modulates the pass being drawn by, which enters what a pack reads as its
	 * vertex colour. White for every pass that has not set one.
	 * <p>
	 * The sky is why this exists: a sky disc is a mesh of positions, and its colour travels in the
	 * dynamic transforms of the draw. White is not a neutral stand in, because packs recognise
	 * vanilla stars by a colour whose channels are equal and above nought. Where the mesh does carry
	 * a colour the two multiply, which is what the game's own fragment shaders do with them.
	 */
	Vector4fc passColour();

	Matrix4fc gbufferProjection();

	Matrix4fc gbufferProjectionInverse();

	Matrix4fc gbufferPreviousModelView();

	Matrix4fc gbufferPreviousProjection();

	/**
	 * The four published shadow matrices are the pair the shadow map ON HAND was drawn with, moved
	 * onto this frame's camera. The map is drawn at the end of a frame for the next one, so its
	 * light direction and its grid cell are the previous frame's; but these matrices act on player
	 * space, which every frame measures from wherever its own camera stands, so the drawn matrix
	 * handed over as it is would ask about a point one frame of camera motion away from the one
	 * being shaded. What is left a frame late once that motion is added back is the sun angle alone.
	 * The {@code drawn} four are the pair as it stands, for the one stage that draws the map itself.
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

	/**
	 * The same volume in the convention the device rasterises in, for the one pass that draws the far
	 * terrain itself. {@code drawnShadowProjection} stands to {@code shadowProjection} exactly as
	 * this stands to {@code dhProjection}.
	 */
	Matrix4fc drawnDistantProjection();

	/**
	 * The multiply and the add that carry a depth the game rasterised into the volume above, in the
	 * convention the device rasterises in at both ends. False while the two volumes are the same
	 * one, which is every frame Distant Horizons has drawn nothing on.
	 * <p>
	 * Not published to a pack and not meant to be: this is the one value of this interface that
	 * belongs to a pass of the engine rather than to a name a pack reads.
	 * {@link ClipSpace#distantDepth} derives it and the off-game harness proves it.
	 */
	boolean distantDepthPair(Vector2f dest);

	Matrix4fc dhPreviousProjection();

	float dhNearPlane();

	float dhFarPlane();

	/**
	 * How far the far terrain reaches, in BLOCKS while Distant Horizons is drawing one and in
	 * CHUNKS while it is not. The change of unit is Iris's own and packs are written against it:
	 * without the mod the name answers {@code getEffectiveRenderDistance} and with it, that mod's
	 * own chunk setting times sixteen.
	 */
	int dhRenderDistance();

	float near();

	float far();

	/** {@code (clipA, clipB, readA, readB)} for the target being drawn into, see {@link ClipSpace}. */
	Vector4fc depthConvention();
}
