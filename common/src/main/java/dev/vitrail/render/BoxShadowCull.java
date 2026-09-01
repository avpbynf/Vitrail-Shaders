package dev.vitrail.render;

import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;

/**
 * Whether the DEFAULT and ADVANCED shadow culls give up their silhouette for a box around the
 * player.
 * <p>
 * <strong>The default is the silhouette, which is what Iris draws.</strong> Iris builds
 * {@code AdvancedShadowCullingFrustum} for a pack asking for Advanced and for one asking for
 * nothing without voxelising ({@code shadows/ShadowRenderer.java:302,372}), and so does this
 * engine.
 * <p>
 * <strong>The box was the default for one day, and the measurement is why it is not.</strong> It
 * was made the default on 31 August 2026 to stop leaf blocks popping at the sun silhouette. Three
 * things came out of measuring it the next morning, on the frozen bench under Complementary
 * Unbound at its stock profile, four readings on one camera and one jar.
 * <ul>
 * <li><strong>It takes the whole corpus, not the packs that ask for it.</strong> The commit read
 * as covering Advanced, and it took DEFAULT as well. At stock settings every pack of the corpus
 * IS default: each writes its {@code shadow.culling} under a coloured-light conditional that is
 * nought on every shipped profile, so the line is never live.</li>
 * <li><strong>It is often not even a box.</strong> The radius comes from the arbitration in
 * {@code PackValues.shadowCullPlan}, which drops it once the shadow distance reaches the render
 * distance, and a player who pushes the slider to its maximum puts it exactly there. The walk
 * then keeps everything.</li>
 * <li><strong>It costs.</strong> The silhouette keeps 322 sections against the box's 875, and the
 * frame runs 164.9 images a second against 144.4 on one scene, 178 against 128 on a denser one:
 * twelve to twenty-eight percent depending on what stands around, and never nothing.</li>
 * </ul>
 * <p>
 * <strong>What the box was hiding is a defect of ours, and it has its own cause now.</strong> The
 * light's walk reuses the visibility lists the camera's walk filled
 * ({@link dev.vitrail.sodium.ShadowTerrain}), so
 * anything that narrows the camera's visible set takes casters out of the shadow map with it. A
 * third-party zoom mod's smart occlusion does that, which is what the popping was; the workshop
 * dossier carries the negative control taken both ways. The answer is a separate traversal for
 * the light, not a wider walk, and it is not this file.
 * <p>
 * A file {@code vitrail/box-shadow-cull} in the game directory, or
 * {@code -Dvitrail.boxShadowCull=true}, puts the box back for a machine that needs to name the
 * old road. Off is the default, so a jar nobody has armed draws what Iris draws. Asked once and
 * the answer kept: it arms a launch, not a frame.
 */
public final class BoxShadowCull {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.boxShadowCull");

	private static final String ARM_FILE = "box-shadow-cull";

	/** Null until the game directory can be resolved, which is not true on the first calls. */
	private static Boolean armed;

	private static boolean announced;

	private BoxShadowCull() {
	}

	/**
	 * True while DEFAULT and ADVANCED are to walk a box around the player, the road of 31 August.
	 * False, which is the default, sweeps the camera volume along the light as Iris does.
	 */
	public static boolean asked() {
		boolean box = PROPERTY || file();
		announce(box);

		return box;
	}

	/**
	 * Said once and said BOTH WAYS. A line that only appeared when the file was there would make
	 * every reading taken without one unable to name the shape the cull walked with.
	 */
	private static void announce(boolean box) {
		if (announced) {
			return;
		}

		announced = true;
		if (box) {
			Vitrail.logger().warn("shadow-cull advanced=BOX file={} property=vitrail.boxShadowCull",
					ARM_FILE);
			return;
		}

		Vitrail.logger().info("shadow-cull advanced=SWEPT default");
	}

	private static boolean file() {
		if (armed != null) {
			return armed;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameDirectory == null) {
			return false;
		}

		armed = Files.isRegularFile(minecraft.gameDirectory.toPath()
				.resolve("vitrail").resolve(ARM_FILE));

		return armed;
	}
}
