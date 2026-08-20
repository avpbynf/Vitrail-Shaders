package dev.vitrail.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;

import org.joml.FrustumIntersection;

/**
 * Whichever shape the light walks with, with a box around the camera cut out of it, which is how a
 * shadow distance bounds a walk that is otherwise the pack's own.
 * <p>
 * <strong>A box and not a shorter frustum, and that is the whole reason this class exists.</strong>
 * The shape inside is either the light's own volume, built from the pack's half plane and what the
 * shadow map is DRAWN with, or the camera's volume swept along the light
 * ({@link ShadowCullFrustum}), and narrowing either would change which casters reach the map rather
 * than how far the walk goes. What a shadow distance asks for is different and cheaper: keep the
 * shape, and stop walking the world beyond a cube of that many blocks around the player. Iris draws
 * the same distinction with the same shape, an axis-aligned cube tested on each axis independently,
 * {@code shadows/frustum/BoxCuller.java}, and hangs it off each of its own frustums the same way.
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
	 * @param planes   the shape the walk measures against before the box is cut out of it, which
	 *                 {@link ShadowCullFrustum#of} chooses from what the pack asked for
	 * @param distance how far from the camera the walk still gathers, in blocks
	 */
	public ShadowCull(Frustum planes, float distance) {
		this.planes = planes;
		this.distance = distance;
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return inside(minX, minY, minZ, maxX, maxY, maxZ)
				&& this.planes.testAab(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/**
	 * <strong>A whole answer only from a box wholly within the distance</strong>, which is how Iris
	 * combines the two ({@code AdvancedShadowCullingFrustum.java:442-444}: {@code INSIDE} from
	 * both, or {@code INTERSECT}). It reads like a detail and is not one. Sodium takes
	 * {@code INSIDE} for "this shape holds the whole subtree", raises {@code INSIDE_FRUSTUM} on it,
	 * and nothing below that node is measured against the shape again
	 * ({@code chunk/tree/TraversableTree.java:197,210-221}). Its own render distance is still
	 * tested on every node ({@code :224-243}), which is why the leak is bounded by the tree and not
	 * by the world; what escapes is every section under such a box, however far past the shadow
	 * distance it lies, and each one is drawn into the map.
	 * <p>
	 * <strong>Where this parts from Iris, and it is the one state that does.</strong> Under
	 * {@link dev.vitrail.pack.source.ShadowCullState#SAFE_ZONE} a box wholly inside the safe zone
	 * answers {@code INSIDE} at Iris whatever the distance says short of {@code OUTSIDE}
	 * ({@code shadows/frustum/advanced/SafeZoneCullingFrustum.java:74-78}), where this downgrades it
	 * like any other. It costs nothing while the safe zone is the shorter of the two, such a box
	 * being wholly within the distance as well, and it drops sections Iris would draw once
	 * {@code voxelDistance} passes {@code shadowDistance}, which {@code PackValues:331-332} works
	 * out apart without bounding either by the other. <strong>Iris is not of one mind on that box
	 * itself</strong>: its {@code testAab} cuts by distance FIRST ({@code :54-56}) and drops what
	 * its own {@code intersectAab} keeps whole, so there is no single behaviour of the reference to
	 * follow here. What is written is the one the walk can hold to on every one of its four tests.
	 */
	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (!inside(minX, minY, minZ, maxX, maxY, maxZ)) {
			return FrustumIntersection.OUTSIDE;
		}

		int shape = this.planes.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);

		return shape == FrustumIntersection.INSIDE && !within(minX, minY, minZ, maxX, maxY, maxZ)
				? FrustumIntersection.INTERSECT
				: shape;
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

	/** Whether a camera-relative box is wholly within the distance, on all three axes. */
	private boolean within(float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ) {
		return minX >= -this.distance && maxX <= this.distance
				&& minY >= -this.distance && maxY <= this.distance
				&& minZ >= -this.distance && maxZ <= this.distance;
	}
}
