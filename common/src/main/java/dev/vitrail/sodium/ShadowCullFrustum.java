package dev.vitrail.sodium;

import dev.vitrail.pack.source.ShadowCullState;
import dev.vitrail.render.ShadowCullPlan;
import dev.vitrail.render.BoxShadowCull;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;

/**
 * The camera's own volume swept along the light, which is what the world is walked against for the
 * shadow map instead of the box that map is drawn in.
 * <p>
 * The idea is L. Spiro's and it reaches here through Iris
 * ({@code shadows/frustum/advanced/AdvancedShadowCullingFrustum.java}): if a section cannot drop
 * anything onto what the camera can see, nothing it casts will ever be sampled, so it need not be
 * drawn. Take the camera's six clipping planes; keep the ones whose inward normal points towards the
 * light, since those are the far side of the volume as the light sees it; drop the ones facing the
 * light, since a caster in front of those still casts into the volume; and close the silhouette with
 * a plane swept along the light for every edge between a kept plane and a dropped one. The
 * assumption it rests on is the same one Iris states: the map is sampled for direct shadowing and
 * for volumetrics, and not for light that bounces off a caster the camera cannot see.
 * <p>
 * <strong>The distance is not this class's business.</strong> How far from the camera the walk still
 * gathers is a box, it is arbitrated between the pack and the player where every other distance is,
 * and {@link ShadowCull} is what wraps it round whichever shape sits inside. This one carries only
 * the shape, plus the one box that is not a bound but a keep: the safe zone.
 *
 * <h2>The conversion, and what citing Iris word for word would cost</h2>
 *
 * The planes are pulled out of a view projection, so the extraction depends entirely on the clip
 * volume that matrix targets, and the engine draws in one volume while a pack reads another.
 * <ul>
 * <li><strong>What Iris supposes</strong>: OpenGL, z from minus one to one, so the volume is
 * {@code -w <= z_c <= w}, and its two z planes are {@code rowW + rowZ} for the near side and
 * {@code rowW - rowZ} for the far one ({@code BaseClippingPlanes.java:32-35}, which asks for them by
 * transposing the matrix and transforming {@code (0,0,-1,1)} and {@code (0,0,1,1)}).</li>
 * <li><strong>What this engine RASTERISES with</strong>: Vulkan, z from zero to one, and REVERSED,
 * near at one and far at nought. Iris's two lines are false against that matrix.</li>
 * <li><strong>What is handed in, and therefore the conversion this file applies</strong>: the
 * PUBLISHED view projection, which {@code ViewMatrices} has already put into Iris's volume, once a
 * frame, through {@link dev.vitrail.uniform.ClipSpace#toLegacyDepth} ({@code ViewMatrices:205} for
 * the frame's own). So the conversion is upstream and is an identity rather than an approximation,
 * and <strong>this file applies none: Iris's six lines are taken exactly as they stand.</strong>
 * That is the whole of the answer, and it is written out because both ways of getting it wrong are
 * inviting.</li>
 * </ul>
 *
 * <h3>Worked, on the frame's own numbers, and on both ways of getting it wrong</h3>
 *
 * Take the game's perspective at ninety degrees, aspect one, near a twentieth of a block and far
 * five hundred and twelve. The drawn matrix, near and far swapped and the zero to one flag set, has
 * {@code rowZ = (0, 0, 9.7666e-5, 0.0500049)} and {@code rowW = (0, 0, -1, 0)}: a point five
 * hundredths in front of the eye lands at {@code z_ndc = 1} and one five hundred and twelve blocks
 * out at {@code z_ndc = 0}, which is the reversal. The published matrix replaces that z row with
 * {@code rowW - 2 * rowZ}, so what reaches this file is
 * {@code rowZ = (0, 0, -1.000195, -0.1000098)} beside the same {@code rowW}.
 * <ul>
 * <li><strong>As written</strong>: the far plane is {@code rowW - rowZ = (0, 0, 1.95332e-4,
 * 0.1000098)}, which reads {@code z >= -512}, and the near plane is
 * {@code rowW + rowZ = (0, 0, -2.000195, -0.1000098)}, which reads {@code z <= -0.05}. Both faces
 * are where the volume really has them.</li>
 * <li><strong>Converting a second time</strong>, which is what a reader who knows this engine's
 * rasteriser will reach for, asks for {@code rowZ} and {@code rowW - rowZ} instead. The far plane
 * comes out right by accident, being the same {@code 2 * rowZ_drawn} either way; the near plane
 * becomes {@code rowW - 2 * rowZ_drawn} and reads {@code z <= -0.09999}, which is
 * {@code 2nf / (n + f)} in place of {@code n}. A band a twentieth of a block thick in front of the
 * eye is culled that should not be, and nothing on screen is ever going to say so.</li>
 * <li><strong>Handing the DRAWN matrix to Iris's lines</strong> is the loud one: the far slot holds
 * {@code rowW - rowZ_drawn}, which reads {@code z <= -0.05} and is the near plane, and the near slot
 * holds {@code rowW + rowZ_drawn}, which reads {@code z <= +0.05} and is the near plane again,
 * displaced a tenth of a block. The volume is bounded twice on the side the eye is and not at all on
 * the other: the far plane is gone, and a section two thousand blocks out, centre
 * {@code (0, -40, -2000)}, is kept where the published matrix drops it on
 * {@code 1.95332e-4 * -1990.875 = -0.3888 < -0.1000098}. Keeping a section too many costs a draw and
 * not a pixel, so the cull silently stops paying for itself.</li>
 * </ul>
 *
 * <h3>Which space each test happens in, and why nothing else needs converting</h3>
 *
 * The planes come out in CAMERA RELATIVE WORLD space, because the model view they are pulled through
 * carries the camera's rotation and no translation, exactly as Iris's does. That is also the space
 * Sodium hands its boxes in ({@code Viewport.isBoxVisibleDirect}, which is where the float origin is
 * made), and the space the light vector is asked for. So the sweep, the half space tests and the
 * safe zone are all one space, and the light's own clip volume never enters any of them. The shadow
 * map is drawn in a volume of its own, forward over zero to one, and that has no bearing here: no
 * test below reads the light's projection at all.
 *
 * <h3>Worked, on three sections</h3>
 *
 * Same camera, at the world origin, looking down {@code -Z}, and the light straight overhead,
 * {@code (0, 1, 0)}. The bottom face is the only one whose normal points towards the light, so it is
 * the only back face; the four faces standing across the light are kept as they stand; the top face
 * looks at the light and is dropped. The four planes swept off the bottom face reproduce the four
 * kept faces exactly, the sweep of a plane along a direction it already contains being itself. The
 * volume is therefore the camera's frustum with its lid taken off, which is the right answer with
 * the sun overhead. Sections are tested at Sodium's padded half size, 9.125.
 * <ul>
 * <li>In front and below, centre {@code (0, -40, -100)}: the bottom plane
 * {@code (0, 0.7071, -0.7071, 0)} takes its highest y, {@code -30.875}, and its nearest z,
 * {@code -109.125}, giving {@code -21.83 + 77.16 = 55.33 >= 0}. Kept, as it must be, being in plain
 * sight.</li>
 * <li>Behind the camera, centre {@code (0, -40, 100)}: the left plane {@code (-0.7071, 0, -0.7071,
 * 0)} gives {@code 6.45 - 64.25 = -57.80 < 0}. Dropped. Under the light's own box alone it would be
 * kept, and that one section is the whole of what this class buys.</li>
 * <li>Above the camera and out of its view, centre {@code (0, 80, -20)}: the camera's own top plane
 * {@code (0, -0.7071, -0.7071, 0)} gives {@code -50.12 + 20.59 = -29.53 < 0}, so the camera cannot
 * see it, while the bottom plane gives {@code 63.02 + 20.59 = 83.61 >= 0} and every other kept plane
 * passes. Kept, and it has to be: with the sun overhead it drops its shadow straight down into what
 * the camera is looking at.</li>
 * </ul>
 *
 * <h2>Where this parts from Iris</h2>
 *
 * <strong>The section tests take Sodium's meaning of the expanded size, and Iris takes another
 * one.</strong> Iris reads the argument of {@code testSectionExpanded} as the section's half size
 * ({@code AdvancedShadowCullingFrustum.java}, {@code minX = originX - extend}), while Sodium's own
 * frustum bakes {@code CHUNK_SECTION_PADDED_RADIUS} into its plane constants and reads the argument
 * as what is added ON TOP of it ({@code viewport/frustum/SimpleFrustum.java}), which is what the one
 * caller passes. Sodium's meaning is taken because the contract is Sodium's and this engine compiles
 * against it. It settles nothing on this walk either way: the shadow stage asks
 * {@code finalizeRenderLists} to update immediately, and the traversal that takes reaches the
 * viewport through {@code isBoxVisibleDirect} and {@code getBoxIntersectionDirect} alone, which is
 * the note {@link ShadowCull} carries about the same pair of methods.
 */
public final class ShadowCullFrustum implements Frustum {

	/** Six faces, and one swept plane per edge between a kept face and a dropped one. */
	private static final int MAX_PLANES = 13;

	/**
	 * The four faces that share an edge with each face, by axis pair, which is Iris's
	 * {@code NeighboringPlaneSet} written as a table ({@code shadows/frustum/advanced}). Indexed by
	 * the face's own index shifted right once, so the two faces of an axis share a row: a face never
	 * shares an edge with its opposite, and does share one with all four of the others.
	 */
	private static final int[][] NEIGHBOURS = {
		{2, 3, 4, 5},
		{0, 1, 4, 5},
		{0, 1, 2, 3},
	};

	/** What Sodium pads a section's half size to, which is where its own frustum starts. */
	private static final float SECTION_HALF_SIZE = Viewport.CHUNK_SECTION_PADDED_RADIUS;

	private static final Matrix4f TRANSPOSED = new Matrix4f();
	private static final Vector4f[] FACES = {
			new Vector4f(), new Vector4f(), new Vector4f(),
			new Vector4f(), new Vector4f(), new Vector4f()
	};
	private static final Vector3f EDGE = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();
	private static final Vector3f POINT = new Vector3f();
	private static final Vector3f FRONT_NORMAL = new Vector3f();

	private final float[][] planes = new float[MAX_PLANES][4];
	private final boolean[] back = new boolean[6];
	private final Vector3f light = new Vector3f();

	/** Half the side of the box the safe zone keeps whatever the sweep says, or negative for none. */
	private final float safeZone;

	private int planeCount;

	private ShadowCullFrustum(Matrix4fc camera, Vector3fc light, float safeZone) {
		this.safeZone = safeZone;
		this.light.set(light);

		build(camera);
	}

	/**
	 * The frustum the pack's own state asks for, wrapped in whatever distance bounds the walk.
	 * <p>
	 * The four arms are Iris's {@code createShadowFrustum}
	 * ({@code shadows/ShadowRenderer.java:298-372}), split between here and
	 * {@code PackValues.shadowCullPlan}, which holds the distances because that is where every other
	 * distance is arbitrated. What is left here is the choice of SHAPE.
	 * {@link dev.vitrail.pack.source.ShadowCullState#DISTANCE}, and
	 * {@link dev.vitrail.pack.source.ShadowCullState#DEFAULT} where the shadow program voxelises,
	 * keep a box around the player and no planes, which is Iris's
	 * {@code BoxCullingFrustum} ({@code :302-323}). Voxelisation is a geometry stage present
	 * <em>or</em> an image load / store still standing on that program ({@code :163-165},
	 * {@code setUsesImages}), not a {@code .gsh} this engine binds. A bound wider than the loaded
	 * world, or not positive, drops the box too and keeps everything, which is Iris's
	 * {@code NonCullingFrustum} ({@code :317-318}), not the light's own volume.
	 * {@link dev.vitrail.pack.source.ShadowCullState#SAFE_ZONE} still sweeps along the light.
	 * <p>
	 * <strong>Advanced and the silent default sweep, which is what Iris does.</strong> Iris builds
	 * {@code AdvancedShadowCullingFrustum} ({@code shadows/ShadowRenderer.java:372}) for both, a
	 * pack that wrote nothing landing there unless it voxelises ({@code :302}), and so does this.
	 * The box those two took for one day is behind
	 * {@link dev.vitrail.render.BoxShadowCull}, which carries what it cost and why it is no
	 * longer the road.
	 *
	 * @param plan what the pack asked for and what the frame is aimed at
	 */
	public static Chosen of(ShadowCullPlan plan) {
		boolean boxAsked = BoxShadowCull.asked();
		boolean box = plan.state() == ShadowCullState.DISTANCE
				|| (plan.state() == ShadowCullState.DEFAULT && plan.voxelised())
				|| ((plan.state() == ShadowCullState.DEFAULT
						|| plan.state() == ShadowCullState.ADVANCED) && boxAsked);

		Frustum frustum;
		String shape;
		if (box) {
			frustum = AlwaysVisible.INSTANCE;
			shape = plan.bound() < 0.0F ? "NONE" : "BOX";
		} else if (plan.state() == ShadowCullState.SAFE_ZONE) {
			frustum = new ShadowCullFrustum(plan.camera(), plan.light(), plan.safeZone());
			shape = "SWEPT";
		} else {
			frustum = new ShadowCullFrustum(plan.camera(), plan.light(), -1.0F);
			shape = "SWEPT";
		}

		String token = token(plan, shape);
		return plan.bound() < 0.0F ? new Chosen(frustum, token)
				: new Chosen(new ShadowCull(frustum, plan.bound(), plan.safeZone()), token);
	}

	/**
	 * What the walk ended up measuring against, and the compact token the overlay and the log
	 * print for it. The two travel together so that the line cannot name a shape the walk did not
	 * use.
	 */
	public record Chosen(Frustum frustum, String culling) {
	}

	/** Pack state, shape, then {@code r=} bound and {@code z=} safe zone when those apply. */
	private static String token(ShadowCullPlan plan, String shape) {
		StringBuilder line = new StringBuilder();
		line.append(plan.state().name()).append(' ').append(shape);
		if (plan.bound() >= 0.0F) {
			line.append(" r=").append(num(plan.bound()));
		}
		if (plan.state() == ShadowCullState.SAFE_ZONE && plan.safeZone() >= 0.0F) {
			line.append(" z=").append(num(plan.safeZone()));
		}
		return line.toString();
	}

	private static String num(float value) {
		int whole = (int) value;
		return whole == value ? Integer.toString(whole) : Float.toString(value);
	}

	private void build(Matrix4fc camera) {
		Vector4f[] faces = faces(camera);

		// The faces whose inward normal points towards the light, which are the far side of the
		// camera's volume as the light sees it and the only ones that still bound the casters. A
		// normal exactly across the light bounds nothing and cuts nothing either, so it is kept
		// without being counted as back, which is Iris's reading of the degenerate case.
		for (int index = 0; index < faces.length; index++) {
			Vector4f face = faces[index];
			float towards = face.x * this.light.x + face.y * this.light.y + face.z * this.light.z;
			this.back[index] = towards > 0.0F;

			if (towards >= 0.0F) {
				add(face.x, face.y, face.z, face.w);
			}
		}

		// And the silhouette: every edge between a kept face and a dropped one, swept along the light.
		for (int index = 0; index < faces.length; index++) {
			if (!this.back[index]) {
				continue;
			}

			for (int neighbour : NEIGHBOURS[index >>> 1]) {
				if (!this.back[neighbour]) {
					edge(faces[index], faces[neighbour]);
				}
			}
		}
	}

	/**
	 * The camera's six faces, as plane equations in camera relative world space.
	 * <p>
	 * {@code (a, b, c, w)} stands for {@code ax + by + cz + w >= 0} being inside, which is what makes
	 * the test below a dot product against {@code (x, y, z, 1)}. The four sides are Iris's lines as
	 * they stand; the two z faces are this engine's volume, and the class note carries the difference
	 * and what ignoring it costs.
	 * <p>
	 * The normalisation is over all four components, which is Iris's as well
	 * ({@code BaseClippingPlanes.java:16}). It scales a plane rather than moving it, so no test
	 * changes; what it is not is a plane in the metric sense, and no distance may be read off one.
	 */
	private static Vector4f[] faces(Matrix4fc camera) {
		TRANSPOSED.set(camera).transpose();
		face(FACES[0], TRANSPOSED, -1.0F, 0.0F, 0.0F, 1.0F);
		face(FACES[1], TRANSPOSED, 1.0F, 0.0F, 0.0F, 1.0F);
		face(FACES[2], TRANSPOSED, 0.0F, -1.0F, 0.0F, 1.0F);
		face(FACES[3], TRANSPOSED, 0.0F, 1.0F, 0.0F, 1.0F);
		// The far face, rowW - rowZ, and the near one, rowW + rowZ. Iris's own two lines, and
		// they are right here because the matrix is already in Iris's volume. The class note
		// carries what a second conversion would move and what the drawn matrix would lose.
		face(FACES[4], TRANSPOSED, 0.0F, 0.0F, -1.0F, 1.0F);
		face(FACES[5], TRANSPOSED, 0.0F, 0.0F, 1.0F, 1.0F);

		return FACES;
	}

	private static void face(Vector4f into, Matrix4fc transposed, float x, float y, float z,
			float w) {
		into.set(x, y, z, w).mul(transposed).normalize();
	}

	/**
	 * The plane that sweeps the edge shared by two faces along the light.
	 * <p>
	 * Its normal has to stand across the light, which is what makes it a sweep rather than a tilt, so
	 * it is the edge direction crossed with the light. Its distance is then fixed by asking for a
	 * point on the edge, which is where the two faces and a third plane through the origin normal to
	 * the edge meet. That last step is Iris's, itself taken from Graphics Gems by way of a Stack
	 * Overflow answer it credits in place ({@code AdvancedShadowCullingFrustum.addEdgePlane}).
	 */
	private void edge(Vector4f back, Vector4f front) {
		EDGE.set(back.x, back.y, back.z).cross(front.x, front.y, front.z);
		NORMAL.set(EDGE).cross(this.light);
		FRONT_NORMAL.set(front.x, front.y, front.z);
		POINT.set(EDGE).cross(back.x, back.y, back.z).mul(-front.w)
				.add(FRONT_NORMAL.cross(EDGE).mul(-back.w))
				.mul(1.0F / EDGE.lengthSquared());

		add(NORMAL.x, NORMAL.y, NORMAL.z, -NORMAL.dot(POINT));
	}

	private void add(float x, float y, float z, float w) {
		float[] plane = this.planes[this.planeCount];
		plane[0] = x;
		plane[1] = y;
		plane[2] = z;
		plane[3] = w;
		this.planeCount += 1;
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		// The safe zone is a KEEP and not a bound, so it stands beside the sweep rather than in front
		// of it, which is the shape Iris gives it (SafeZoneCullingFrustum.testAab): a section reaching
		// into the pack's own voxel grid is drawn whatever the sweep says of it, because the pack
		// samples that grid from places the sweep knows nothing about.
		return within(minX, minY, minZ, maxX, maxY, maxZ)
				|| visible(minX, minY, minZ, maxX, maxY, maxZ);
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		// Wholly inside the safe zone answers INSIDE and not INTERSECT, which is Iris's own line
		// (SafeZoneCullingFrustum.intersectAab:76-78). It buys the walk the right to stop testing a
		// whole subtree; answered INTERSECT it would still keep everything, one test at a time.
		if (this.safeZone >= 0.0F && minX >= -this.safeZone && maxX <= this.safeZone
				&& minY >= -this.safeZone && maxY <= this.safeZone
				&& minZ >= -this.safeZone && maxZ <= this.safeZone) {
			return FrustumIntersection.INSIDE;
		}

		if (within(minX, minY, minZ, maxX, maxY, maxZ)) {
			return FrustumIntersection.INTERSECT;
		}

		return corners(minX, minY, minZ, maxX, maxY, maxZ);
	}

	@Override
	public boolean testSection(float originX, float originY, float originZ) {
		return testSectionExpanded(originX, originY, originZ, 0.0F);
	}

	/**
	 * @param extend what is added to Sodium's own padded half size, and not the half size itself.
	 *               The class note carries why, and where Iris reads the same argument otherwise
	 */
	@Override
	public boolean testSectionExpanded(float originX, float originY, float originZ, float extend) {
		float half = SECTION_HALF_SIZE + extend;

		return testAab(originX - half, originY - half, originZ - half, originX + half,
				originY + half, originZ + half);
	}

	/**
	 * Whether a camera relative box reaches into the safe zone at all, which is false outright where
	 * there is no safe zone: nothing reaches into a cube that is not there.
	 */
	private boolean within(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return this.safeZone >= 0.0F
				&& maxX >= -this.safeZone && minX <= this.safeZone
				&& maxY >= -this.safeZone && minY <= this.safeZone
				&& maxZ >= -this.safeZone && minZ <= this.safeZone;
	}

	/**
	 * Whether a box reaches into the swept volume, which is the hot path: it is what the walk asks,
	 * once per section per frame, and it stops at the first plane that rejects.
	 * <p>
	 * The dot product is written out rather than fused. Iris reaches for {@code Math.fma} and falls
	 * back to exactly this when the machine has told the virtual machine it has no instruction for
	 * it, and an unfused machine pays a software emulation for the fused call, so the plain form is
	 * the one that is never the slow answer. What it costs is a rounding, on a section sitting
	 * exactly on a plane.
	 */
	private boolean visible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		for (int index = 0; index < this.planeCount; index++) {
			float[] plane = this.planes[index];
			float x = plane[0] < 0.0F ? minX : maxX;
			float y = plane[1] < 0.0F ? minY : maxY;
			float z = plane[2] < 0.0F ? minZ : maxZ;

			if (plane[0] * x + plane[1] * y + plane[2] * z < -plane[3]) {
				return false;
			}
		}

		return true;
	}

	/**
	 * The same, answering whether the box is wholly inside as well, for the callers that ask.
	 * <p>
	 * The two halves of the loop are not symmetric and must not be made so. The near corner is
	 * tested against EVERY plane, because that is the test that culls and any plane may still be the
	 * one that rejects. The far corner only decides between INSIDE and INTERSECT, and one plane
	 * having put it outside already settles that, so the rest of its dot products answer a question
	 * nobody is asking any more. Written as a guarded assignment rather than the {@code &=} it reads
	 * like, since that operator on booleans does not short circuit: it would evaluate the second dot
	 * product for all thirteen planes of every box the shadow walk hands over.
	 */
	private int corners(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		boolean inside = true;

		for (int index = 0; index < this.planeCount; index++) {
			float[] plane = this.planes[index];
			float x = plane[0] < 0.0F ? minX : maxX;
			float y = plane[1] < 0.0F ? minY : maxY;
			float z = plane[2] < 0.0F ? minZ : maxZ;

			if (plane[0] * x + plane[1] * y + plane[2] * z < -plane[3]) {
				return FrustumIntersection.OUTSIDE;
			}

			if (inside) {
				inside = plane[0] * (plane[0] < 0.0F ? maxX : minX)
						+ plane[1] * (plane[1] < 0.0F ? maxY : minY)
						+ plane[2] * (plane[2] < 0.0F ? maxZ : minZ) + plane[3] >= 0.0F;
			}
		}

		return inside ? FrustumIntersection.INSIDE : FrustumIntersection.INTERSECT;
	}

	/**
	 * Iris's {@code NonCullingFrustum} for Sodium's contract: every box is inside, so the walk
	 * is bounded only by the cube {@link ShadowCull} wraps around this, or by nothing when that
	 * cube is not there.
	 */
	private static final class AlwaysVisible implements Frustum {

		private static final AlwaysVisible INSTANCE = new AlwaysVisible();

		@Override
		public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY,
				float maxZ) {
			return true;
		}

		@Override
		public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY,
				float maxZ) {
			return FrustumIntersection.INSIDE;
		}

		@Override
		public boolean testSection(float originX, float originY, float originZ) {
			return true;
		}

		@Override
		public boolean testSectionExpanded(float originX, float originY, float originZ,
				float extend) {
			return true;
		}
	}
}
