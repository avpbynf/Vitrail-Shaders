package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.ClipSpace;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the two matrices the motion vector pass composes to what the geometry says a static world
 * point does between two frames.
 * <p>
 * The pass writes, for every pixel, where that pixel's surface stood in the previous frame minus
 * where it is now, in pixels of the render size. The second reading here is the whole pipeline in
 * double precision and shares nothing with it: a world point, two cameras with their own rotation,
 * bob, field of view and position; each frame projects the point on its own; the vector is the
 * difference of the two pixels. The fragment stage is emulated line by line from the composed float
 * matrices, so what is compared is what the shader would compute, and a wrong order of the camera
 * translation, a previous matrix taken from the wrong frame or a translation lost at the edge of the
 * world shows as pixels and not as a rounding.
 */
class MotionVectorsTest {

	private static final int WIDTH = 1920;
	private static final int HEIGHT = 1080;
	private static final double ASPECT = (double) WIDTH / HEIGHT;
	private static final double NEAR = 0.05;
	private static final double FAR = 192.0;

	/** Twelve times the worst error measured over the scenarios below, 1.6e-4 pixels. */
	private static final double TOLERANCE_PIXELS = 2.0e-3;

	@AfterEach
	void putTheStaticsBack() {
		ShadowAmortisation.forget();
	}

	/** One frame's camera: how it turns, bobs, sees and where it stands. */
	private record Frame(double yaw, double pitch, double fovDegrees, boolean bobbing, Vector3d camera) {

		Matrix4f view() {
			return new Matrix4f().rotateX((float) Math.toRadians(this.pitch))
					.rotateY((float) Math.toRadians(this.yaw));
		}

		Matrix4f bob() {
			return this.bobbing ? new Matrix4f().translate(0.3F, -0.2F, 0.0F).rotateZ(0.02F) : new Matrix4f();
		}

		/** The game's own projection, reversed Z over 0..1, cast to float like the game's. */
		Matrix4f rendered() {
			double[][] m = DoubleMatrix.reversedPerspective(Math.toRadians(this.fovDegrees), ASPECT, NEAR, FAR);

			return new Matrix4f().m00((float) m[0][0]).m11((float) m[1][1]).m22((float) m[2][2])
					.m23(-1.0F).m32((float) m[2][3]).m33(0.0F);
		}

		/** Player space to view space, in double from the same angles. */
		double[][] modelViewReference() {
			double[][] bob = this.bobbing
					? DoubleMatrix.mul(DoubleMatrix.translation(0.3F, -0.2F, 0.0), DoubleMatrix.rotationZ(0.02F))
					: DoubleMatrix.identity();

			return DoubleMatrix.mul(bob, DoubleMatrix.rotationX((float) Math.toRadians(this.pitch)),
					DoubleMatrix.rotationY((float) Math.toRadians(this.yaw)));
		}

		double[][] renderedReference() {
			return DoubleMatrix.reversedPerspective(Math.toRadians(this.fovDegrees), ASPECT, NEAR, FAR);
		}

		/** Where a world point lands: {u, v, device depth, clip w}, straight from the definition. */
		double[] project(double wx, double wy, double wz) {
			double[] clip = DoubleMatrix.apply(DoubleMatrix.mul(renderedReference(), modelViewReference()),
					wx - this.camera.x, wy - this.camera.y, wz - this.camera.z, 1.0);

			return new double[] {clip[0] / clip[3] * 0.5 + 0.5, clip[1] / clip[3] * 0.5 + 0.5,
					clip[2] / clip[3], clip[3]};
		}

		/** The world point whose view space position in this frame is (x, y, -distance). */
		double[] pointAt(double x, double y, double distance) {
			double[] player = DoubleMatrix.apply(DoubleMatrix.invert(modelViewReference()), x, y, -distance, 1.0);

			return new double[] {player[0] + this.camera.x, player[1] + this.camera.y, player[2] + this.camera.z};
		}
	}

	/** The composed pair, the way the pass composes it, from two frames advanced through ViewMatrices. */
	private static Matrix4f[] composed(Frame before, Frame now) {
		ViewMatrices view = new ViewMatrices();
		view.advance(before.view(), before.bob(), before.rendered(), (float) FAR, 12);
		view.advance(now.view(), now.bob(), now.rendered(), (float) FAR, 12);

		Matrix4f fromScreen = new Matrix4f();
		Matrix4f toPreviousClip = new Matrix4f();
		MotionVectors.compose(view, now.camera, before.camera, fromScreen, toPreviousClip);

		return new Matrix4f[] {fromScreen, toPreviousClip};
	}

	/**
	 * The fragment stage, line by line, on the composed matrices. {@code depth} is the device depth the
	 * shader recovers from the image, {@code (texel - readB) / readA}.
	 *
	 * @return the vector in pixels, or null where the shader writes the zero vector for a point behind
	 *         the previous camera
	 */
	private static double[] shader(Matrix4f[] pair, double u, double v, double depth) {
		double[] here = DoubleMatrix.apply(DoubleMatrix.of(pair[0]), u * 2.0 - 1.0, v * 2.0 - 1.0, depth, 1.0);
		double[] before = DoubleMatrix.apply(DoubleMatrix.of(pair[1]), here[0] / here[3], here[1] / here[3],
				here[2] / here[3], 1.0);
		if (before[3] <= 0.0) {
			return null;
		}

		return new double[] {(before[0] / before[3] * 0.5 + 0.5 - u) * WIDTH,
				(before[1] / before[3] * 0.5 + 0.5 - v) * HEIGHT};
	}

	/** The depth as the pack window holds it and as the shader puts it back into the device volume. */
	private static double throughTheImage(double deviceDepth) {
		float texel = (float) (ClipSpace.REVERSED.z * deviceDepth + ClipSpace.REVERSED.w);

		return (texel - ClipSpace.REVERSED.w) / ClipSpace.REVERSED.z;
	}

	/** View space points (x, y, distance) that are on screen in both frames of every scenario below. */
	private static final double[][] POINTS = {
			{0.0, 0.0, 5.0}, {1.5, -0.7, 3.0}, {-6.0, 2.0, 12.0}, {20.0, -8.0, 40.0}, {-30.0, 10.0, 100.0},
			{55.0, -25.0, 150.0}};

	/**
	 * Every point of {@link #POINTS}, seen from {@code now}, must reproject to where {@code before}
	 * saw it; the worst error in pixels is returned so that a test can say what it measured.
	 */
	private static double worstPixelError(Frame before, Frame now, boolean throughTheImage) {
		Matrix4f[] pair = composed(before, now);
		double worst = 0.0;
		for (double[] point : POINTS) {
			double[] world = now.pointAt(point[0], point[1], point[2]);
			double[] here = now.project(world[0], world[1], world[2]);
			double[] there = before.project(world[0], world[1], world[2]);
			assertTrue(there[3] > 0.0, "the point is in front of the previous camera");

			double[] got = shader(pair, here[0], here[1], throughTheImage ? throughTheImage(here[2]) : here[2]);
			double expectedX = (there[0] - here[0]) * WIDTH;
			double expectedY = (there[1] - here[1]) * HEIGHT;
			worst = Math.max(worst, Math.max(Math.abs(got[0] - expectedX), Math.abs(got[1] - expectedY)));
		}

		return worst;
	}

	private static void assertReprojects(String what, Frame before, Frame now, double tolerancePixels,
			String measured) {
		double error = worstPixelError(before, now, false);
		assertTrue(error <= tolerancePixels, what + ": worst error " + error + " px against a tolerance of "
				+ tolerancePixels + " (measured on the code as it stands: " + measured + ")");
	}

	private static double expectedMagnitude(Frame before, Frame now) {
		double biggest = 0.0;
		for (double[] point : POINTS) {
			double[] world = now.pointAt(point[0], point[1], point[2]);
			double[] here = now.project(world[0], world[1], world[2]);
			double[] there = before.project(world[0], world[1], world[2]);
			biggest = Math.max(biggest, Math.max(Math.abs((there[0] - here[0]) * WIDTH),
					Math.abs((there[1] - here[1]) * HEIGHT)));
		}

		return biggest;
	}

	@Test
	void aTurningCameraReprojectsEveryPointToWhereThePreviousFrameSawIt() {
		Frame before = new Frame(10.0, 5.0, 70.0, false, new Vector3d(100.0, 64.0, 200.0));
		Frame now = new Frame(12.5, 4.0, 70.0, false, new Vector3d(100.0, 64.0, 200.0));

		assertTrue(expectedMagnitude(before, now) > 20.0, "a real turn is dozens of pixels, so a tolerance of a few thousandths is tight");
		assertReprojects("turning", before, now, TOLERANCE_PIXELS, "7.2e-5 px");
	}

	@Test
	void aWalkingCameraWritesVectorsEvenWithoutTurningBecauseTheTranslationIsApplied() {
		// The failure that looks most like success: without the camera's own motion in the pair, a player
		// walking in a straight line writes nought and a turning one writes correct vectors.
		Frame before = new Frame(30.0, 0.0, 70.0, false, new Vector3d(100.0, 64.0, 200.0));
		Frame now = new Frame(30.0, 0.0, 70.0, false, new Vector3d(100.4, 64.0, 200.3));

		assertTrue(expectedMagnitude(before, now) > 20.0, "walking moves the near points by dozens of pixels");
		assertReprojects("walking", before, now, TOLERANCE_PIXELS, "3.4e-5 px");
	}

	@Test
	void aTurnAndAWalkTogetherPutTheTranslationBeforeThePreviousRotation() {
		Frame before = new Frame(75.0, -20.0, 70.0, false, new Vector3d(-40.0, 70.0, 12.0));
		Frame now = new Frame(80.0, -18.0, 70.0, false, new Vector3d(-39.2, 70.05, 12.6));

		assertTrue(expectedMagnitude(before, now) > 20.0);
		assertReprojects("turn and walk", before, now, TOLERANCE_PIXELS, "1.4e-4 px");
	}

	@Test
	void theWalkBobIsPartOfBothModelViewsAndDoesNotBreakTheReprojection() {
		Frame before = new Frame(75.0, -20.0, 70.0, true, new Vector3d(-40.0, 70.0, 12.0));
		Frame now = new Frame(80.0, -18.0, 70.0, true, new Vector3d(-39.2, 70.05, 12.6));

		assertReprojects("bobbing", before, now, TOLERANCE_PIXELS, "1.6e-4 px");
	}

	@Test
	void theFieldOfViewOfThePreviousFrameIsUsedForThePreviousProjection() {
		// Sprinting widens the field of view, so the two frames carry two projections, and the previous
		// one has to be the rendered matrix of the frame before and not a copy of this frame's.
		Frame before = new Frame(10.0, 0.0, 70.0, false, new Vector3d(0.0, 64.0, 0.0));
		Frame now = new Frame(10.0, 0.0, 78.0, false, new Vector3d(0.0, 64.0, 0.0));

		assertTrue(expectedMagnitude(before, now) > 20.0, "the field of view alone moves the picture");
		assertReprojects("field of view", before, now, TOLERANCE_PIXELS, "3.4e-5 px");
	}

	@Test
	void aCameraAtThirtyMillionTakesTheDifferenceInDoublesAndReprojectsAsAtTheOrigin() {
		// The two positions are 29,999,990.5 and 29,999,990.8: as floats they are both 29,999,990 and their
		// difference is nought, so a difference taken after the cast writes no motion at all.
		Frame before = new Frame(30.0, 0.0, 70.0, false, new Vector3d(29_999_990.5, 64.0, -29_999_995.25));
		Frame now = new Frame(30.0, 0.0, 70.0, false, new Vector3d(29_999_990.8, 64.0, -29_999_995.05));

		assertTrue(expectedMagnitude(before, now) > 20.0);
		assertReprojects("thirty million", before, now, TOLERANCE_PIXELS, "3.2e-5 px");
	}

	@Test
	void theFirstFrameHasNoHistoryAndEveryVectorIsNought() {
		ViewMatrices view = new ViewMatrices();
		Frame only = new Frame(30.0, 10.0, 70.0, true, new Vector3d(5.0, 64.0, 5.0));
		view.advance(only.view(), only.bob(), only.rendered(), (float) FAR, 12);

		Matrix4f fromScreen = new Matrix4f();
		Matrix4f toPreviousClip = new Matrix4f();
		MotionVectors.compose(view, only.camera, only.camera, fromScreen, toPreviousClip);
		Matrix4f[] pair = {fromScreen, toPreviousClip};

		for (double[] point : POINTS) {
			double[] world = only.pointAt(point[0], point[1], point[2]);
			double[] here = only.project(world[0], world[1], world[2]);
			double[] vector = shader(pair, here[0], here[1], here[2]);
			assertEquals(0.0, vector[0], TOLERANCE_PIXELS, "x at " + point[2] + " blocks");
			assertEquals(0.0, vector[1], TOLERANCE_PIXELS, "y at " + point[2] + " blocks");
		}
	}

	@Test
	void aPointBehindThePreviousCameraHasNoPreviousPixelAndTheShaderWritesNought() {
		// The camera moved back ten blocks. A point five blocks in front of it now was five blocks BEHIND
		// it a frame ago, where its clip w is negative and there is no pixel to point at.
		Frame before = new Frame(0.0, 0.0, 70.0, false, new Vector3d(0.0, 64.0, 0.0));
		Frame now = new Frame(0.0, 0.0, 70.0, false, new Vector3d(0.0, 64.0, 10.0));
		Matrix4f[] pair = composed(before, now);

		double[] world = now.pointAt(0.0, 0.0, 5.0);
		double[] here = now.project(world[0], world[1], world[2]);
		double[] there = before.project(world[0], world[1], world[2]);
		assertEquals(-5.0, there[3], 1.0e-6, "the previous clip w is minus the distance behind");

		assertEquals(null, shader(pair, here[0], here[1], here[2]), "so the shader takes its early out");
	}

	@Test
	void theZRowOfThePreviousMatrixNeverReachesTheVectorSoItsVolumeDoesNotMatter() {
		// The class comment says the pair is the rendered one and never the published one, and that holds
		// for the matrix a depth sample goes through (the test above on fromScreen fails without it). The
		// previous side is different: the conversion to the OpenGL volume only rewrites the z row, and the
		// shader reads x, y and w of the result and throws z away. So pairing the previous PUBLISHED
		// projection changes nothing a vector can show; pinned so that it is known and not rediscovered.
		Frame before = new Frame(75.0, -20.0, 70.0, true, new Vector3d(-40.0, 70.0, 12.0));
		Frame now = new Frame(80.0, -18.0, 78.0, true, new Vector3d(-39.2, 70.05, 12.6));
		ViewMatrices view = new ViewMatrices();
		view.advance(before.view(), before.bob(), before.rendered(), (float) FAR, 12);
		view.advance(now.view(), now.bob(), now.rendered(), (float) FAR, 12);

		Matrix4f fromScreen = new Matrix4f();
		Matrix4f toPreviousClip = new Matrix4f();
		MotionVectors.compose(view, now.camera, before.camera, fromScreen, toPreviousClip);
		Matrix4f onThePublishedVolume = new Matrix4f(view.gbufferPreviousProjection())
				.mul(view.gbufferPreviousModelView())
				.translate((float) (now.camera.x - before.camera.x), (float) (now.camera.y - before.camera.y),
						(float) (now.camera.z - before.camera.z));

		double worst = 0.0;
		for (double[] point : POINTS) {
			double[] world = now.pointAt(point[0], point[1], point[2]);
			double[] here = now.project(world[0], world[1], world[2]);
			double[] one = shader(new Matrix4f[] {fromScreen, toPreviousClip}, here[0], here[1], here[2]);
			double[] other = shader(new Matrix4f[] {fromScreen, onThePublishedVolume}, here[0], here[1], here[2]);
			worst = Math.max(worst, Math.max(Math.abs(one[0] - other[0]), Math.abs(one[1] - other[1])));
		}

		assertTrue(worst <= TOLERANCE_PIXELS, "worst difference " + worst + " px (measured on the code as it "
				+ "stands: exactly 0, the x, y and w rows come out bit for bit the same)");
	}

	@Test
	void theDepthImagePutsTheDeviceDepthBackWithinOneFloatStepOfOne() {
		// PackDepth writes readA * d + readB and this pass undoes it from the same two constants. Near the
		// far plane the device depth is a few ten thousandths and the image is a float next to one, so the
		// absolute error is a float step below one and the relative error grows with distance.
		double worstAbsolute = 0.0;
		for (double distance : new double[] {0.05, 1.0, 10.0, 50.0, 100.0, 150.0, 190.0}) {
			double device = NEAR * (FAR - distance) / (distance * (FAR - NEAR));
			double back = throughTheImage(device);
			worstAbsolute = Math.max(worstAbsolute, Math.abs(back - device));
		}

		assertTrue(worstAbsolute <= 6.0e-8, "worst absolute error " + worstAbsolute + " against a float step "
				+ "below one (5.96e-8), measured on the code as it stands: 2.5e-8");
		double atThreeQuarters = throughTheImage(0.75);
		assertEquals(0.75, atThreeQuarters, 0.0, "exact where the image holds the value exactly");
	}

	@Test
	void theRealisticPathThroughTheDepthImageStaysWithinAFractionOfAPixel() {
		// The same scenarios with the depth taken through the image like the real pass does: the far
		// points carry the float step of the image, and this is what that costs in pixels.
		Frame before = new Frame(75.0, -20.0, 70.0, true, new Vector3d(-40.0, 70.0, 12.0));
		Frame now = new Frame(80.0, -18.0, 70.0, true, new Vector3d(-39.2, 70.05, 12.6));

		double error = worstPixelError(before, now, true);
		assertTrue(error <= 5.0e-3, "worst error through the image " + error + " px against a tolerance of 0.005 "
				+ "(measured on the code as it stands: 2.8e-4 px)");
	}
}
