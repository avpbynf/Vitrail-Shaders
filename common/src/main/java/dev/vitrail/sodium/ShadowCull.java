package dev.vitrail.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum;

import org.joml.FrustumIntersection;

/**
 * The light's frustum with a box around the camera cut out of it, which is how a shadow distance
 * bounds a walk that is otherwise the pack's own volume.
 * <p>
 * <strong>A box and not a shorter frustum, and that is the whole reason this class exists.</strong>
 * The light's volume is built from the pack's half plane and is what the shadow map is DRAWN with;
 * narrowing it would move every texel of the map and change the picture. What a shadow distance
 * asks for is different and cheaper: keep the volume, and stop walking the world beyond a cube of
 * that many blocks around the player. Iris draws the same distinction with the same shape, an
 * axis-aligned cube tested on each axis independently, {@code shadows/frustum/BoxCuller.java}.
 * <p>
 * <strong>The coordinates are relative to the camera, and nothing here converts them.</strong>
 * Sodium subtracts the camera before it asks ({@code Viewport.isBoxVisibleDirect}, which is where
 * the float origin is made), so a cube centred on the player is a comparison against the distance
 * itself with no position to keep in step. Iris has a second method for exactly this reason,
 * {@code isCulledSodium} beside {@code isCulled}, and this class only ever needs the first.
 * <p>
 * <strong>Two of the four tests carry the box and two do not, which is Iris's placement and not a
 * shortcut.</strong> Its own frustum boxes {@code testAab} and {@code intersectAab}
 * ({@code shadows/frustum/advanced/AdvancedShadowCullingFrustum.java:423,428}) and leaves
 * {@code testSection} and {@code testSectionExpanded} to the planes alone ({@code :459-505}). It
 * costs nothing here because those two are not on this walk at all: the shadow stage asks
 * {@code finalizeRenderLists} to update immediately, which takes Sodium's out-of-graph road
 * ({@code RenderSectionManager.finalizeRenderLists} into {@code renderOutOfGraph}), and the
 * traversal there asks the viewport through {@code getBoxIntersectionDirect} and
 * {@code isBoxVisibleDirect} only ({@code tree/TraversableTree.java:212,262}). Boxing the section
 * tests would be dead weight on this path and a divergence on any other.
 */
public final class ShadowCull implements Frustum {

	private final Frustum planes;
	private final float distance;

	/**
	 * @param light    the light's own view projection, the volume the map is drawn with
	 * @param distance how far from the camera the walk still gathers, in blocks
	 */
	public ShadowCull(FrustumIntersection light, float distance) {
		this.planes = new SimpleFrustum(light);
		this.distance = distance;
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return inside(minX, minY, minZ, maxX, maxY, maxZ)
				&& this.planes.testAab(minX, minY, minZ, maxX, maxY, maxZ);
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return inside(minX, minY, minZ, maxX, maxY, maxZ)
				? this.planes.intersectAab(minX, minY, minZ, maxX, maxY, maxZ)
				: FrustumIntersection.OUTSIDE;
	}

	@Override
	public boolean testSection(float x, float y, float z) {
		return this.planes.testSection(x, y, z);
	}

	@Override
	public boolean testSectionExpanded(float x, float y, float z, float extend) {
		return this.planes.testSectionExpanded(x, y, z, extend);
	}

	/** Whether any part of a camera-relative box is still within the distance, on all three axes. */
	private boolean inside(float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ) {
		return maxX >= -this.distance && minX <= this.distance
				&& maxY >= -this.distance && minY <= this.distance
				&& maxZ >= -this.distance && minZ <= this.distance;
	}
}
