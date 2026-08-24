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

	/** Half the side of the box the shape keeps whole whatever the distance says, or negative. */
	private final float safeZone;

	/**
	 * @param planes   the shape the walk measures against before the box is cut out of it, which
	 *                 {@link ShadowCullFrustum#of} chooses from what the pack asked for
	 * @param distance how far from the camera the walk still gathers, in blocks
	 * @param safeZone the same pack's {@code voxelDistance}, or negative where it asked for no safe
	 *                 zone. Carried here and not left to the shape because the two boxes have to be
	 *                 weighed against each other, which neither can do alone
	 */
	public ShadowCull(Frustum planes, float distance, float safeZone) {
		this.planes = planes;
		this.distance = distance;
		this.safeZone = safeZone;
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
	 * <strong>The safe zone is the one box that outranks the distance</strong>, and only here.
	 * Under {@link dev.vitrail.pack.source.ShadowCullState#SAFE_ZONE} a box the safe zone holds
	 * whole answers {@code INSIDE} at Iris the moment the distance is anything but {@code OUTSIDE}
	 * ({@code shadows/frustum/advanced/SafeZoneCullingFrustum.java:74-78}), and that order is kept.
	 * It settles nothing while the safe zone is the shorter of the two, such a box being wholly
	 * within the distance as well. It is what a pack asking for a voxel grid WIDER than its shadow
	 * distance is owed, and {@code PackValues:347-351} works the two out apart without bounding
	 * either by the other.
	 * <p>
	 * <strong>Three packs of the corpus could not reach it until the storage images were served, and
	 * one always could.</strong> The width is a {@code const float voxelDistance}. Both Complementary
	 * write it behind their coloured lighting, which {@code lib/common.glsl} gates on
	 * {@code IRIS_FEATURE_CUSTOM_IMAGES} among other conditions and which
	 * {@code program/gbuffers_terrain} then declares the width behind, and Bliss writes it behind
	 * that flag directly, inside its own light volume switch ({@code lib/settings.glsl}). With no
	 * {@code IRIS_FEATURE_} posed at all those lines were dead, {@code ConstDirectives.read} keeping
	 * live lines only, and the box came out nought blocks wide while the STATE was still reached: the
	 * shape said safe zone and behaved as the plain sweep. The flag being posed, a pack that also
	 * turns its own switch on brings a width and the box has a side.
	 * <p>
	 * BSL is the fourth and it never depended on any of that: it declares the width on a line held
	 * by nothing but the stage it is in ({@code program/final.glsl}), so the value has always
	 * arrived, and its safe zone hangs off a setting of its own rather than off a feature flag. It
	 * is also the case the paragraph above says settles nothing, its box being shorter than the
	 * smallest shadow distance it offers.
	 * <p>
	 * <strong>{@link #testAab} carries the exception too, one layer down.</strong> Iris takes a box
	 * reaching into the safe zone AT ALL for visible there, without asking its planes
	 * ({@code SafeZoneCullingFrustum.java:58-60}, after the distance cut at :54-56), and
	 * {@link ShadowCullFrustum#testAab} answers exactly that, so the composition below is the same
	 * pair of questions in the same order. What is left to this method is the distance, which Iris
	 * asks first as well.
	 */
	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		if (!inside(minX, minY, minZ, maxX, maxY, maxZ)) {
			return FrustumIntersection.OUTSIDE;
		}

		int shape = this.planes.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);

		if (shape != FrustumIntersection.INSIDE) {
			return shape;
		}

		// The safe zone outranks the distance on a box it holds whole, which is Iris's order and not
		// a softening of the line above: its safe zone frustum answers INSIDE off that box alone the
		// moment the distance is anything but OUTSIDE (SafeZoneCullingFrustum.java:74-78).
		return within(this.distance, minX, minY, minZ, maxX, maxY, maxZ)
				|| (this.safeZone >= 0.0F
						&& within(this.safeZone, minX, minY, minZ, maxX, maxY, maxZ))
				? FrustumIntersection.INSIDE
				: FrustumIntersection.INTERSECT;
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

	/** Whether a camera-relative box is wholly within a half size, on all three axes. */
	private static boolean within(float half, float minX, float minY, float minZ,
			float maxX, float maxY, float maxZ) {
		return minX >= -half && maxX <= half
				&& minY >= -half && maxY <= half
				&& minZ >= -half && maxZ <= half;
	}
}
