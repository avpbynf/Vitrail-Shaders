package dev.vitrail.render;

import dev.vitrail.pack.source.ShadowCullState;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Everything the light's walk needs to know before it can choose a shape to measure a section
 * against, gathered in one reading of the frame.
 * <p>
 * One record rather than five calls, and that is not tidiness: the light vector, the camera's volume
 * and the pack's distances all have to come from the SAME frame. Read one at a time across the
 * boundary the shadow stage sits on, the camera's volume would be this frame's and the light's
 * direction the next one's, and a volume swept along a light the map is not drawn from keeps and
 * drops the wrong sections without a line on screen saying so.
 * <p>
 * The two matrices are the caller's own scratch, written into and handed back, because this is built
 * once a frame at the end of the frame and nothing here is worth an allocation.
 *
 * @param state    which of the four shapes the pack asked for
 * @param light    where the light stands, a unit vector from the origin in world space
 * @param camera   the camera's view projection, the volume the sweep starts from, in the OpenGL clip
 *                 convention. See {@code dev.vitrail.sodium.ShadowCullFrustum} for why the
 *                 convention is load bearing and what reading the drawn matrix would cost
 * @param bound    how far from the camera the walk still gathers, in blocks, or negative where
 *                 nothing bounds it. Already arbitrated between the pack and the player by
 *                 {@link PackValues#shadowCullPlan}, and it is the box
 *                 {@code dev.vitrail.sodium.ShadowCull} carries whichever shape sits inside it
 * @param safeZone the inner box of the safe zone state, which is kept whatever the sweep says of it,
 *                 or negative for the three states that have none
 */
public record ShadowCullPlan(ShadowCullState state, Vector3f light, Matrix4f camera, float bound,
		float safeZone) {
}
