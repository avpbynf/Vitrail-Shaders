package dev.vitrail.render;

import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;

import java.nio.file.Files;

/**
 * Whether Advanced shadow culling goes back to the camera volume swept along the light.
 * <p>
 * The default is a box around the player. Complementary's Low profile asks for Advanced, and the
 * sweep on this engine pops single leaf blocks at the sun silhouette. Iris Advanced does not. The
 * named divergence and what it costs the image live on
 * {@code dev.vitrail.sodium.ShadowCullFrustum#of}.
 * <p>
 * A file {@code vitrail/swept-shadow-cull} in the game directory, or
 * {@code -Dvitrail.sweptShadowCull=true} where somebody has a launcher to type it in, puts the old
 * sweep back so a reading can name the state it was taken under. Off is the default, so a jar
 * nobody has armed is the box that holds. Asked once and the answer kept: it arms a launch, not a
 * frame.
 */
public final class SweptShadowCull {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.sweptShadowCull");

	private static final String ARM_FILE = "swept-shadow-cull";

	/** Null until the game directory can be resolved, which is not true on the first calls. */
	private static Boolean armed;

	private static boolean announced;

	private SweptShadowCull() {
	}

	/**
	 * True while Advanced is to sweep along the light, the old road. False, which is the default,
	 * keeps the player box.
	 */
	public static boolean asked() {
		boolean sweep = PROPERTY || file();
		announce(sweep);

		return sweep;
	}

	/**
	 * Said once and said BOTH WAYS. A line that only appeared when the file was there would make
	 * every reading taken without one unable to name the shape Advanced walked with.
	 */
	private static void announce(boolean sweep) {
		if (announced) {
			return;
		}

		announced = true;
		if (sweep) {
			Vitrail.logger().warn("shadow-cull advanced=SWEPT file={} property=vitrail.sweptShadowCull",
					ARM_FILE);
			return;
		}

		Vitrail.logger().info("shadow-cull advanced=BOX default");
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
