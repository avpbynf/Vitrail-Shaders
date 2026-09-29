package dev.vitrail.sodium;

import dev.vitrail.pack.source.ShadowCullState;
import dev.vitrail.render.ShadowCullPlan;
import dev.vitrail.render.BoxShadowCull;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

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

	/** What Sodium pads a section's half size to, which is where its own frustum starts. */
	private static final float SECTION_HALF_SIZE = Viewport.CHUNK_SECTION_PADDED_RADIUS;

	/** The half spaces and the safe zone box, where all of the arithmetic of this shape lives. */
	private final SweptVolume volume;

	private ShadowCullFrustum(Matrix4fc camera, Vector3fc light, float safeZone) {
		this.volume = new SweptVolume(camera, light, safeZone);
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
	 * ({@code :163-165}) <em>or</em>, here alone, an image load / store still standing on that
	 * program: Iris computes that half and reads it nowhere, its {@code setUsesImages} having no
	 * caller. A bound wider than the loaded
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

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return this.volume.testAab(minX, minY, minZ, maxX, maxY, maxZ);
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		return this.volume.intersectAab(minX, minY, minZ, maxX, maxY, maxZ);
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
