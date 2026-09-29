package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the matrices a pack reads to what the textbook says they are, computed a second time in
 * double precision by {@link DoubleMatrix} and never through JOML.
 * <p>
 * Everything here runs without a device: {@link ViewMatrices} keeps its matrices in plain fields
 * and its only static neighbour is {@link ShadowAmortisation}, whose state is put back around
 * every test. The tolerances are contracts, not guesses. Float matrices of order one composed a
 * few times agree with the double reading to 1.5e-6 at worst (measured on the code as it
 * stands, and every failure message says the error it saw), and the tolerance below is an order of
 * magnitude and more above that; a failure means a wrong formula, not rounding.
 */
class ViewMatricesTest {

	private static final double FOV = Math.toRadians(70.0);
	private static final double ASPECT = 16.0 / 9.0;
	private static final double NEAR = 0.05;
	private static final int CHUNKS = 12;
	private static final double FAR = CHUNKS * 16.0;

	/** Order-one matrices, a few float roundings each. */
	private static final double TOLERANCE = 2.0e-5;

	/** A point within a hundred blocks of the camera, through a pair whose shift is a few blocks. */
	private static final double LOOKUP_TOLERANCE = 1.0e-5;

	@BeforeEach
	void forgetTheKeptMap() {
		ShadowAmortisation.forget();
	}

	@AfterEach
	void putTheStaticsBack() {
		ShadowAmortisation.setFrames(ShadowAmortisation.DEFAULT_FRAMES);
		ShadowAmortisation.forget();
	}

	// ---- fixtures ----

	/** The game's projection, reversed Z over zero to one, written from the formula. */
	private static Matrix4f rendered(double far) {
		double[][] m = DoubleMatrix.reversedPerspective(FOV, ASPECT, NEAR, far);

		return new Matrix4f().m00((float) m[0][0]).m11((float) m[1][1]).m22((float) m[2][2])
				.m23(-1.0F).m32((float) m[2][3]).m33(0.0F);
	}

	private static Matrix4f viewRotation(double yawDegrees, double pitchDegrees) {
		return new Matrix4f().rotateX((float) Math.toRadians(pitchDegrees))
				.rotateY((float) Math.toRadians(yawDegrees));
	}

	private static double[][] viewRotationReference(double yawDegrees, double pitchDegrees) {
		return DoubleMatrix.mul(DoubleMatrix.rotationX((float) Math.toRadians(pitchDegrees)),
				DoubleMatrix.rotationY((float) Math.toRadians(yawDegrees)));
	}

	/** The walk bob and a tilt: a translation and a turn about z, neither of which commutes with a view. */
	private static Matrix4f bob() {
		return new Matrix4f().translate(0.3F, -0.2F, 0.0F).rotateZ(0.02F);
	}

	private static double[][] bobReference() {
		return DoubleMatrix.mul(DoubleMatrix.translation(0.3F, -0.2F, 0.0), DoubleMatrix.rotationZ(0.02F));
	}

	private static void advance(ViewMatrices view, double yaw, double pitch, Matrix4fc bob) {
		view.advance(viewRotation(yaw, pitch), bob, rendered(FAR), (float) FAR, CHUNKS);
	}

	private static void assertMatrix(String what, double[][] expected, Matrix4fc actual) {
		double error = DoubleMatrix.maxDifference(expected, actual);
		assertTrue(error <= TOLERANCE, () -> what + ": max abs error " + error + " against a tolerance of "
				+ TOLERANCE + " (measured on the code as it stands: at most 1.5e-6, in the sky angle)");
	}

	private static void assertSameElements(String what, Matrix4fc expected, Matrix4fc actual) {
		assertEquals(new Matrix4f(expected), new Matrix4f(actual), what + ": bit for bit");
	}

	private static Matrix4f copy(Matrix4fc source) {
		return new Matrix4f(source);
	}

	// ---- advance ----

	@Test
	void theModelViewIsTheBobOnTheLeftOfTheViewRotation() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 35.0, -20.0, bob());

		double[][] expected = DoubleMatrix.mul(bobReference(), viewRotationReference(35.0, -20.0));
		assertMatrix("bob * view", expected, view.gbufferModelView());

		double[][] wrongWay = DoubleMatrix.mul(viewRotationReference(35.0, -20.0), bobReference());
		assertTrue(DoubleMatrix.maxDifference(wrongWay, view.gbufferModelView()) > 0.05,
				"the two orders are different matrices, so the assertion above tells them apart");
		assertSameElements("the bob is kept as it came", bob(), view.cameraBob());
	}

	@Test
	void theProjectionIsTheGameProjectionPutIntoTheOpenGlVolume() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, new Matrix4f());

		assertMatrix("published projection", DoubleMatrix.legacyPerspective(FOV, ASPECT, NEAR, FAR),
				view.gbufferProjection());
		assertMatrix("rendered projection", DoubleMatrix.reversedPerspective(FOV, ASPECT, NEAR, FAR),
				view.rendered());
	}

	@Test
	void thePublishedDepthRunsFromMinusOneAtTheNearPlaneToOneAtTheFarOne() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, new Matrix4f());

		double[][] published = DoubleMatrix.of(view.gbufferProjection());
		for (double distance : new double[] {NEAR, 1.0, 10.0, 100.0, FAR}) {
			double[] clip = DoubleMatrix.apply(published, 0.0, 0.0, -distance, 1.0);
			double depth = clip[2] / clip[3];
			double expected = 1.0 - 2.0 * NEAR * (FAR - distance) / (distance * (FAR - NEAR));
			assertEquals(expected, depth, 2.0e-5, "depth at " + distance + " blocks");
		}

		double[] atNear = DoubleMatrix.apply(published, 0.0, 0.0, -NEAR, 1.0);
		double[] atFar = DoubleMatrix.apply(published, 0.0, 0.0, -FAR, 1.0);
		assertEquals(-1.0, atNear[2] / atNear[3], 2.0e-5, "the near plane");
		assertEquals(1.0, atFar[2] / atFar[3], 2.0e-5, "the far plane");
	}

	@Test
	void theInversesInvertThePublishedMatricesAndNotTheRenderedOnes() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 123.0, 40.0, bob());

		assertMatrix("modelView * inverse", DoubleMatrix.identity(),
				new Matrix4f(view.gbufferModelView()).mul(view.gbufferModelViewInverse()));
		assertMatrix("projection * inverse", DoubleMatrix.identity(),
				new Matrix4f(view.gbufferProjection()).mul(view.gbufferProjectionInverse()));

		Matrix4f renderedOnTheInverse = new Matrix4f(view.rendered()).mul(view.gbufferProjectionInverse());
		assertTrue(DoubleMatrix.maxDifference(DoubleMatrix.identity(), renderedOnTheInverse) > 0.5,
				"the published inverse does not invert the rendered matrix: the two are told apart");
	}

	@Test
	void theFirstFrameHasThePresentAsItsHistoryAndNotAZeroMatrix() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 10.0, 5.0, bob());

		assertSameElements("previous model view", view.gbufferModelView(), view.gbufferPreviousModelView());
		assertSameElements("previous projection", view.gbufferProjection(), view.gbufferPreviousProjection());
		assertSameElements("previous rendered", view.rendered(), view.previousRendered());
	}

	@Test
	void everyLaterFrameHasTheFrameBeforeAsItsHistory() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 10.0, 5.0, bob());
		Matrix4f firstView = copy(view.gbufferModelView());
		Matrix4f firstProjection = copy(view.gbufferProjection());
		Matrix4f firstRendered = copy(view.rendered());

		view.advance(viewRotation(50.0, 8.0), new Matrix4f(), rendered(FAR * 2.0), (float) FAR * 2.0F, CHUNKS);
		Matrix4f secondView = copy(view.gbufferModelView());
		Matrix4f secondProjection = copy(view.gbufferProjection());
		Matrix4f secondRendered = copy(view.rendered());

		assertSameElements("frame 2 previous model view", firstView, view.gbufferPreviousModelView());
		assertSameElements("frame 2 previous projection", firstProjection, view.gbufferPreviousProjection());
		assertSameElements("frame 2 previous rendered", firstRendered, view.previousRendered());
		assertNotEquals(firstProjection, secondProjection, "the two frames differ, so the check above is not vacuous");

		view.advance(viewRotation(70.0, 9.0), new Matrix4f(), rendered(FAR), (float) FAR, CHUNKS);
		assertSameElements("frame 3 previous model view", secondView, view.gbufferPreviousModelView());
		assertSameElements("frame 3 previous projection", secondProjection, view.gbufferPreviousProjection());
		assertSameElements("frame 3 previous rendered", secondRendered, view.previousRendered());
	}

	@Test
	void aResetSeedsTheNextFrameWithItselfAgain() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 10.0, 5.0, bob());
		advance(view, 30.0, 6.0, bob());
		assertNotEquals(new Matrix4f(view.gbufferModelView()), new Matrix4f(view.gbufferPreviousModelView()));

		view.reset();
		advance(view, 200.0, -3.0, bob());

		assertSameElements("model view after a reset", view.gbufferModelView(), view.gbufferPreviousModelView());
		assertSameElements("rendered after a reset", view.rendered(), view.previousRendered());
	}

	@Test
	void thePreviousRenderedMatrixKeepsTheDeviceVolumeWhilePreviousProjectionIsPublished() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, new Matrix4f());
		advance(view, 1.0, 0.0, new Matrix4f());

		// The two are the same camera in two volumes, and reading a depth sample through the wrong one is
		// silent: they part in exactly the z row.
		assertEquals((float) (NEAR / (FAR - NEAR)), view.previousRendered().m22(), 1.0e-9F);
		assertEquals((float) (-(FAR + NEAR) / (FAR - NEAR)), view.gbufferPreviousProjection().m22(), 1.0e-5F);
		assertEquals(view.previousRendered().m00(), view.gbufferPreviousProjection().m00(), 0.0F,
				"every row but z is shared");
	}

	@Test
	void nearIsTheGamesFixedPlaneAndFarIsWhatTheFrameWasTold() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, new Matrix4f());

		assertEquals(0.05F, view.near());
		assertEquals((float) FAR, view.far());
	}

	@Test
	void theDistantRenderDistanceIsInChunksUntilDistantHorizonsAnswersInBlocks() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, new Matrix4f());
		assertEquals(CHUNKS, view.dhRenderDistance(), "no answer yet: the game's own, in chunks");

		view.advanceDistant(7.5F, 4096.0F, 2048);
		assertEquals(2048, view.dhRenderDistance(), "the mod's own, in blocks");
		assertEquals(7.5F, view.dhNearPlane());
		assertEquals(4096.0F, view.dhFarPlane());

		view.advanceDistant(ViewMatrices.FALLBACK_PLANE, ViewMatrices.FALLBACK_PLANE, -1);
		assertEquals(CHUNKS, view.dhRenderDistance(), "rendering switched off: back to chunks");
		assertEquals(0.01F, view.dhNearPlane());
		assertEquals(0.01F, view.dhFarPlane());
	}

	// ---- the shadow pair ----

	private static void shadow(ViewMatrices view, float angle, float pathRotation, float interval,
			Vector3d camera, float distance, float near, float far, boolean drewLastFrame) {
		view.advanceShadow(angle, pathRotation, interval, camera, distance, near, far, false, 0.0F, 0.0F,
				drewLastFrame);
	}

	private static ViewMatrices framed() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, new Matrix4f());

		return view;
	}

	/** The light's view as OptiFine builds it, from the requirement and not from the production code. */
	private static double[][] shadowViewReference(float angle, float pathRotation, float interval,
			double x, double y, double z) {
		double sky = angle < 0.25F ? angle + 0.75 : angle - 0.25;
		double[][] turn = DoubleMatrix.mul(DoubleMatrix.rotationX(Math.PI / 2.0),
				DoubleMatrix.rotationZ(-sky * 2.0 * Math.PI),
				DoubleMatrix.rotationX(Math.toRadians(pathRotation)));
		if (interval == 0.0F) {
			return turn;
		}

		return DoubleMatrix.mul(turn, DoubleMatrix.translation(snap(x, interval), snap(y, interval), snap(z, interval)));
	}

	/** The float rounded camera, its remainder in the interval (which keeps its sign) and half an interval back. */
	private static double snap(double coordinate, float interval) {
		return ((double) (float) coordinate) % interval - interval / 2.0;
	}

	@Test
	void theLightPointsStraightUpAtNoonAndWestAtSunset() {
		// Noon is a shadow angle of a quarter of a turn (FrameState hands the sun angle plus ninety degrees
		// over three hundred and sixty), and the light's own +z axis points at the light.
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 0.0F, new Vector3d(), 160.0F, -100.0F, 100.0F, true);
		double[] noon = DoubleMatrix.apply(DoubleMatrix.of(view.drawnShadowModelViewInverse()), 0.0, 0.0, 1.0, 0.0);
		assertEquals(0.0, noon[0], 1.0e-6);
		assertEquals(1.0, noon[1], 1.0e-6);
		assertEquals(0.0, noon[2], 1.0e-6);

		shadow(view, 0.5F, 0.0F, 0.0F, new Vector3d(), 160.0F, -100.0F, 100.0F, true);
		double[] sunset = DoubleMatrix.apply(DoubleMatrix.of(view.drawnShadowModelViewInverse()), 0.0, 0.0, 1.0, 0.0);
		assertEquals(-1.0, sunset[0], 1.0e-6, "the sun sets in the west, which is minus x");
		assertEquals(0.0, sunset[1], 1.0e-6);
		assertEquals(0.0, sunset[2], 1.0e-6);
	}

	@Test
	void theShadowViewIsTheSunPathThenTheTiltThenTheGridSnapInThatOrder() {
		ViewMatrices view = framed();
		Vector3d camera = new Vector3d(123.4, 70.6, -987.3);
		shadow(view, 0.375F, -40.0F, 2.0F, camera, 160.0F, -100.05F, 156.0F, true);

		assertMatrix("shadow model view", shadowViewReference(0.375F, -40.0F, 2.0F, 123.4, 70.6, -987.3),
				view.drawnShadowModelView());
		assertMatrix("inverse", DoubleMatrix.identity(),
				new Matrix4f(view.drawnShadowModelView()).mul(view.drawnShadowModelViewInverse()));
	}

	@Test
	void theSkyAngleIsContinuousAcrossTheSeamAtAQuarterTurn() {
		// Just under a quarter is the branch that adds three quarters; a quarter and over takes a quarter
		// away. The two agree modulo a whole turn, which is why the picture has no jump there, and also why
		// nothing can tell where the seam is put: only the offset itself is pinned, at noon and at sunset.
		ViewMatrices view = framed();
		for (float angle : new float[] {0.0F, 0.1F, 0.2499F, 0.25F, 0.2501F, 0.5F, 0.75F, 0.999F, 1.0F}) {
			shadow(view, angle, 0.0F, 0.0F, new Vector3d(), 160.0F, -100.0F, 100.0F, true);
			assertMatrix("angle " + angle, shadowViewReference(angle, 0.0F, 0.0F, 0, 0, 0),
					view.drawnShadowModelView());
		}
	}

	@Test
	void theEndFlashReplacesTheSunPathAndTheTiltWithItsTwoAngles() {
		ViewMatrices view = framed();
		view.advanceShadow(0.5F, -40.0F, 0.0F, new Vector3d(), 160.0F, -100.0F, 100.0F, true, 25.0F, 110.0F, true);

		double[][] expected = DoubleMatrix.mul(DoubleMatrix.rotationX(Math.toRadians(-25.0)),
				DoubleMatrix.rotationY(Math.toRadians(110.0)));
		assertMatrix("end flash view", expected, view.drawnShadowModelView());
	}

	@Test
	void theGridSnapKeepsTheSignOfTheRemainderJustAsJavaComputesIt() {
		// A negative camera has a negative remainder, so the offset is not in [-half, half) the way the
		// original algorithm reads. Packs are written against that, so it is the behaviour and not a slip.
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 4.0F, new Vector3d(-5.0, -0.5, 7.0), 160.0F, -100.0F, 100.0F, true);

		// -5 % 4 = -1, -0.5 % 4 = -0.5, 7 % 4 = 3, and half of the interval is 2 back from each.
		assertMatrix("negative camera", shadowViewReference(0.25F, 0.0F, 4.0F, -5.0, -0.5, 7.0),
				view.drawnShadowModelView());
		double[][] bare = shadowViewReference(0.25F, 0.0F, 0.0F, 0, 0, 0);
		double[] offset = DoubleMatrix.apply(
				DoubleMatrix.mul(DoubleMatrix.invert(bare), DoubleMatrix.of(view.drawnShadowModelView())),
				0.0, 0.0, 0.0, 1.0);
		assertEquals(-3.0, offset[0], 1.0e-5, "-5 % 4 - 2");
		assertEquals(-2.5, offset[1], 1.0e-5, "-0.5 % 4 - 2");
		assertEquals(1.0, offset[2], 1.0e-5, "7 % 4 - 2");
	}

	@Test
	void anIntervalOfZeroDoesNotSnapAtAll() {
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 0.0F, new Vector3d(123.4, 5.5, -77.7), 160.0F, -100.0F, 100.0F, true);
		assertMatrix("unsnapped", shadowViewReference(0.25F, 0.0F, 0.0F, 0, 0, 0), view.drawnShadowModelView());

		shadow(view, 0.25F, 0.0F, -0.0F, new Vector3d(123.4, 5.5, -77.7), 160.0F, -100.0F, 100.0F, true);
		assertMatrix("negative zero is zero", shadowViewReference(0.25F, 0.0F, 0.0F, 0, 0, 0),
				view.drawnShadowModelView());
	}

	@Test
	void aCameraOutAtThirtyMillionIsSnappedOnItsFloatRoundedPositionAsIrisDoes() {
		// A float carries 24 bits, so at 29,999,999.6 the spacing is two blocks and the camera is 30,000,000.
		// The snap is written against that rounded number, which is Iris's own arithmetic and the reason it
		// is kept; what it costs is that the offset cannot move inside two blocks out here. Characterised,
		// not endorsed: the last assertion shows how far the rounded reading is from the true remainder.
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(29_999_999.6, 64.0, -29_999_999.6), 160.0F, -100.0F, 100.0F,
				true);

		assertMatrix("snap on the float rounded camera",
				shadowViewReference(0.25F, 0.0F, 2.0F, 29_999_999.6, 64.0, -29_999_999.6),
				view.drawnShadowModelView());

		double[][] bare = shadowViewReference(0.25F, 0.0F, 0.0F, 0, 0, 0);
		double[] offset = DoubleMatrix.apply(
				DoubleMatrix.mul(DoubleMatrix.invert(bare), DoubleMatrix.of(view.drawnShadowModelView())),
				0.0, 0.0, 0.0, 1.0);
		assertEquals(-1.0, offset[0], 1.0e-6, "30,000,000 % 2 - 1");
		double trueRemainder = 29_999_999.6 % 2.0 - 1.0;
		assertTrue(Math.abs(offset[0] - trueRemainder) > 0.5,
				"the double remainder would be " + trueRemainder + ": the float rounding is the whole difference");
	}

	@Test
	void theOrthographicBoxIsTwiceTheDistanceAcrossAndUsesTheDeclaredPlanes() {
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 160.0F, -100.05F, 156.0F, true);

		assertMatrix("projection", DoubleMatrix.legacyOrtho(320.0, 320.0, -100.05F, 156.0F),
				view.drawnShadowProjection());
		assertMatrix("inverse", DoubleMatrix.identity(),
				new Matrix4f(view.drawnShadowProjection()).mul(view.drawnShadowProjectionInverse()));
	}

	@Test
	void aPlaneOfMinusOneIsTheRenderDistanceInBlocksEitherSide() {
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 100.0F, -1.0F, -1.0F, true);

		// Twelve chunks are 192 blocks, and the near plane is behind the light by the same reach.
		assertMatrix("both planes at the sentinel", DoubleMatrix.legacyOrtho(200.0, 200.0, -192.0, 192.0),
				view.drawnShadowProjection());

		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 100.0F, -1.0F, 156.0F, true);
		assertMatrix("only the near plane at the sentinel", DoubleMatrix.legacyOrtho(200.0, 200.0, -192.0, 156.0),
				view.drawnShadowProjection());

		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 100.0F, -100.05F, -1.0F, true);
		assertMatrix("only the far plane at the sentinel", DoubleMatrix.legacyOrtho(200.0, 200.0, -100.05, 192.0),
				view.drawnShadowProjection());

		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 100.0F, -1.5F, 156.0F, true);
		assertMatrix("minus one and a half is a distance", DoubleMatrix.legacyOrtho(200.0, 200.0, -1.5, 156.0),
				view.drawnShadowProjection());
	}

	@Test
	void theSentinelReachIsSixteenTimesWhateverUnitTheDistantDistanceIsIn() {
		// Iris multiplies the game's distance in CHUNKS and the mod's in BLOCKS by the same sixteen, so
		// with the far terrain up a plane of -1 reaches sixteen times its distance in blocks. Pinned as
		// it stands: the source says that is Iris's own arithmetic and the pack asks for it.
		ViewMatrices view = framed();
		view.advanceDistant(7.5F, 4096.0F, 1024);
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 100.0F, -1.0F, -1.0F, true);

		assertMatrix("sentinel with the distant distance", DoubleMatrix.legacyOrtho(200.0, 200.0, -16384.0, 16384.0),
				view.drawnShadowProjection());
	}

	@Test
	void aShadowDistanceOfZeroPublishesAnInfiniteBox() {
		// Nothing rejects a pack's zero, and the published matrix carries what the division makes of it.
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 0.0F, -100.0F, 100.0F, true);

		assertEquals(Float.POSITIVE_INFINITY, view.drawnShadowProjection().m00());
		assertEquals(Float.POSITIVE_INFINITY, view.drawnShadowProjection().m11());
		assertEquals(-0.01F, view.drawnShadowProjection().m22(), 1.0e-9F, "the depth range is still finite");
	}

	@Test
	void aShadowDistanceOfAMillionBlocksIsStillAnInvertibleBox() {
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 1.0e6F, -100.0F, 100.0F, true);

		assertEquals(1.0e-6F, view.drawnShadowProjection().m00(), 1.0e-12F);
		assertEquals(1.0e6F, view.drawnShadowProjectionInverse().m00(), 1.0F);
	}

	@Test
	void knownBug_aShadowDistanceOfTenToTheThirtyLosesItsInverseToUnderflow() {
		// The inverse is taken through the determinant, which is a product of the diagonal and underflows to
		// zero in a float long before the matrix itself stops being representable. Nothing a pack asks for
		// is anywhere near this, so it is pinned and not reported as a defect that matters.
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(), 1.0e30F, -100.0F, 100.0F, true);

		assertEquals(1.0e-30F, view.drawnShadowProjection().m00(), 1.0e-36F, "the matrix itself is finite");
		assertFalse(Float.isFinite(view.drawnShadowProjectionInverse().m00()), "its inverse is not");
	}

	@Test
	void aNaNCameraPoisonsThePublishedPairForTheFrameAndTheNextOne() {
		// knownBug_: nothing guards the camera, so one frame with a NaN position writes NaN into the fresh
		// pair, and the next frame's anchor copies it, so the published pair is NaN for two frames. The
		// third frame is clean because the anchor has moved on to a fresh pair.
		ViewMatrices view = framed();
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(1, 2, 3), 160.0F, -100.0F, 100.0F, true);
		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(Double.NaN, 2, 3), 160.0F, -100.0F, 100.0F, true);
		assertTrue(Float.isNaN(view.shadowModelView().m30()), "the frame with the NaN camera");

		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(1, 2, 3), 160.0F, -100.0F, 100.0F, true);
		assertTrue(Float.isNaN(view.shadowModelView().m30()), "the next frame is still handed the NaN pair");

		shadow(view, 0.25F, 0.0F, 2.0F, new Vector3d(1, 2, 3), 160.0F, -100.0F, 100.0F, true);
		assertFalse(Float.isNaN(view.shadowModelView().m30()), "and the one after is clean");
	}

	@Test
	void theFirstFrameHasNoMapSoThePublishedPairIsTheFreshOne() {
		ViewMatrices view = framed();
		shadow(view, 0.375F, -20.0F, 2.0F, new Vector3d(50.5, 60.5, 70.5), 160.0F, -100.05F, 156.0F, true);

		assertSameElements("model view", view.drawnShadowModelView(), view.shadowModelView());
		assertSameElements("model view inverse", view.drawnShadowModelViewInverse(), view.shadowModelViewInverse());
		assertSameElements("projection", view.drawnShadowProjection(), view.shadowProjection());
		assertSameElements("projection inverse", view.drawnShadowProjectionInverse(), view.shadowProjectionInverse());
	}

	/**
	 * The invariant the published pair exists for: a lookup of a world point through the pair, measured
	 * from the camera of the frame doing the measuring, lands on the same light space position the map
	 * was drawn at, which is that point measured from the camera of the frame that drew it.
	 */
	private static void assertLookupLands(ViewMatrices view, Matrix4fc drawn, Vector3d drawnCamera,
			Vector3d camera, String what) {
		double[][] published = DoubleMatrix.of(view.shadowModelView());
		double[][] drawnView = DoubleMatrix.of(drawn);
		double[][] offsets = {{5.0, 1.0, -3.0}, {-40.0, 12.0, 9.5}, {0.0, 0.0, 0.0}, {100.0, -30.0, 100.0}};
		for (double[] offset : offsets) {
			double wx = drawnCamera.x + offset[0];
			double wy = drawnCamera.y + offset[1];
			double wz = drawnCamera.z + offset[2];
			double[] now = DoubleMatrix.apply(published, wx - camera.x, wy - camera.y, wz - camera.z, 1.0);
			double[] then = DoubleMatrix.apply(drawnView, wx - drawnCamera.x, wy - drawnCamera.y,
					wz - drawnCamera.z, 1.0);
			for (int axis = 0; axis < 3; axis++) {
				assertEquals(then[axis], now[axis], LOOKUP_TOLERANCE, what + ", axis " + axis + " of the point "
						+ Arrays.toString(offset) + " (measured on the code as it stands: at most 3.6e-7)");
			}
		}
	}

	@Test
	void thePublishedPairSendsALookupToWhereTheMapWasDrawn() {
		ViewMatrices view = framed();
		Vector3d first = new Vector3d(1000.25, 64.5, -2000.75);
		shadow(view, 0.3F, -30.0F, 2.0F, first, 160.0F, -100.05F, 156.0F, true);
		Matrix4f drawnFirst = copy(view.shadowModelView());
		Matrix4f drawnProjection = copy(view.shadowProjection());
		Matrix4f drawnProjectionInverse = copy(view.shadowProjectionInverse());

		Vector3d second = new Vector3d(1003.75, 64.25, -1994.75);
		shadow(view, 0.3F, -30.0F, 2.0F, second, 160.0F, -100.05F, 156.0F, true);

		assertLookupLands(view, drawnFirst, first, second, "one frame on");
		double[][] moved = DoubleMatrix.mul(DoubleMatrix.of(drawnFirst), DoubleMatrix.translation(3.5, -0.25, 6.0));
		assertMatrix("the drawn pair moved onto this camera, on the right", moved, view.shadowModelView());
		assertMatrix("its inverse", DoubleMatrix.identity(),
				new Matrix4f(view.shadowModelView()).mul(view.shadowModelViewInverse()));
		assertSameElements("the projection is the one the map was drawn with", drawnProjection,
				view.shadowProjection());
		assertSameElements("and its inverse", drawnProjectionInverse, view.shadowProjectionInverse());
	}

	@Test
	void theMotionIsAddedBeforeTheLightTurnsThePointNotAfter() {
		ViewMatrices view = framed();
		Vector3d first = new Vector3d(0.0, 0.0, 0.0);
		shadow(view, 0.5F, 0.0F, 0.0F, first, 160.0F, -100.0F, 100.0F, true);
		Matrix4f drawnFirst = copy(view.shadowModelView());
		shadow(view, 0.5F, 0.0F, 0.0F, new Vector3d(10.0, 0.0, 0.0), 160.0F, -100.0F, 100.0F, true);

		double[][] before = DoubleMatrix.mul(DoubleMatrix.of(drawnFirst), DoubleMatrix.translation(10.0, 0.0, 0.0));
		double[][] after = DoubleMatrix.mul(DoubleMatrix.translation(10.0, 0.0, 0.0), DoubleMatrix.of(drawnFirst));
		assertMatrix("translated on the right", before, view.shadowModelView());
		assertTrue(DoubleMatrix.maxDifference(after, view.shadowModelView()) > 5.0,
				"on the left is a different place on the ground, and the sunset light tells them apart");
	}

	@Test
	void theCameraDifferenceIsTakenInDoublesBeforeItIsCastToFloatOutAtThirtyMillion() {
		ViewMatrices view = framed();
		Vector3d first = new Vector3d(29_999_990.25, 64.0, -29_999_995.5);
		shadow(view, 0.3F, 0.0F, 2.0F, first, 160.0F, -100.05F, 156.0F, true);
		Matrix4f drawnFirst = copy(view.shadowModelView());

		Vector3d second = new Vector3d(29_999_991.75, 64.0, -29_999_994.25);
		shadow(view, 0.3F, 0.0F, 2.0F, second, 160.0F, -100.05F, 156.0F, true);

		// 1.5 and 1.25 blocks: a float subtraction of the two rounded positions would say 2 and 0.
		assertLookupLands(view, drawnFirst, first, second, "out at thirty million");
		double[][] moved = DoubleMatrix.mul(DoubleMatrix.of(drawnFirst), DoubleMatrix.translation(1.5, 0.0, 1.25));
		assertMatrix("the double difference", moved, view.shadowModelView());
	}

	@Test
	void theAnchorStaysOnTheFrameThatDrewTheMapWhileTheMapIsKept() {
		ViewMatrices view = framed();
		Vector3d drawnAt = new Vector3d(100.0, 64.0, 100.0);
		shadow(view, 0.3F, 0.0F, 2.0F, drawnAt, 160.0F, -100.05F, 156.0F, true);
		Matrix4f drawnPair = copy(view.shadowModelView());

		// The frames after it did not draw a map: drewLastFrame says so, and the anchor must not follow.
		Vector3d camera = new Vector3d(103.25, 64.0, 100.0);
		shadow(view, 0.3F, 0.0F, 2.0F, camera, 160.0F, -100.05F, 156.0F, true);
		camera = new Vector3d(106.5, 64.0, 100.0);
		shadow(view, 0.3F, 0.0F, 2.0F, camera, 160.0F, -100.05F, 156.0F, false);
		Matrix4f freshOfThatFrame = copy(view.drawnShadowModelView());
		camera = new Vector3d(109.75, 64.0, 104.25);
		shadow(view, 0.3F, 0.0F, 2.0F, camera, 160.0F, -100.05F, 156.0F, false);

		// The map on hand was drawn at frame one's camera; it is the anchor two frames of not-drawing
		// later, so the published pair is that pair moved by the whole distance since, not by one frame's.
		assertLookupLands(view, drawnPair, drawnAt, camera, "kept for two frames");
		assertMatrix("moved by nine and three quarters, and four and a quarter",
				DoubleMatrix.mul(DoubleMatrix.of(drawnPair), DoubleMatrix.translation(9.75, 0.0, 4.25)),
				view.shadowModelView());
		assertNotEquals(new Matrix4f(drawnPair), freshOfThatFrame, "the fresh pairs differ, so the check is real");
	}

	@Test
	void theStageDrawsThroughTheFreshPairOnAFrameThatFillsAndThroughThePublishedOneOnAFrameThatKeeps() {
		ShadowAmortisation.setFrames(2);
		ViewMatrices view = framed();

		// A frame that fills the map: the plan says draw, and the stage reports it did.
		Vector3d first = new Vector3d(10.0, 64.0, 10.0);
		boolean drewLast = ShadowAmortisation.beginFrame(first, 0.3F, true);
		shadow(view, 0.3F, 0.0F, 2.0F, first, 160.0F, -100.05F, 156.0F, drewLast);
		assertTrue(ShadowAmortisation.drawTerrainThisFrame());
		assertNotSame(view.shadowModelView(), view.drawnShadowModelView(), "fills: the fresh pair");
		ShadowAmortisation.drawn();

		// One block on, so the next frame keeps the map it has, and a mob is drawn through the pair that
		// matches the terrain in it: the published one, which is the drawn pair moved onto this camera.
		Vector3d second = new Vector3d(11.0, 64.0, 10.0);
		drewLast = ShadowAmortisation.beginFrame(second, 0.3F, true);
		shadow(view, 0.3F, 0.0F, 2.0F, second, 160.0F, -100.05F, 156.0F, drewLast);
		assertTrue(drewLast, "the stage reported the fill");
		assertFalse(ShadowAmortisation.drawTerrainThisFrame(), "the map is kept");
		assertSame(view.shadowModelView(), view.drawnShadowModelView());
		assertSame(view.shadowModelViewInverse(), view.drawnShadowModelViewInverse());
		assertSame(view.shadowProjection(), view.drawnShadowProjection());
		assertSame(view.shadowProjectionInverse(), view.drawnShadowProjectionInverse());

		// A world change forgets the map, and the next frame draws through the fresh pair again.
		view.reset();
		assertTrue(ShadowAmortisation.drawTerrainThisFrame());
		assertNotSame(view.shadowModelView(), view.drawnShadowModelView());
	}

	// ---- the distant volume ----

	@Test
	void withoutARowTheDistantVolumeIsTheFramesOwnAndThereIsNoDepthPair() {
		ViewMatrices view = framed();
		view.advanceDistantVolume(0.0F, 0.0F);

		assertSameElements("published", view.gbufferProjection(), view.dhProjection());
		assertSameElements("drawn", view.rendered(), view.drawnDistantProjection());
		assertMatrix("published inverse", DoubleMatrix.identity(),
				new Matrix4f(view.dhProjection()).mul(view.dhProjectionInverse()));
		assertFalse(view.distantDepthPair(new Vector2f()));
	}

	@Test
	void aRowReplacesTheZRowOfTheRenderedMatrixAndClearsTheTermsBesideIt() {
		ViewMatrices view = new ViewMatrices();
		Matrix4f dirty = rendered(FAR).m02(0.3F).m12(-0.2F);
		view.advance(viewRotation(0.0, 0.0), new Matrix4f(), dirty, (float) FAR, CHUNKS);
		float scale = (float) (7.5 / (4096.0 - 7.5));
		float offset = (float) (7.5 * 4096.0 / (4096.0 - 7.5));
		view.advanceDistantVolume(scale, offset);

		Matrix4fc drawn = view.drawnDistantProjection();
		assertEquals(scale, drawn.m22());
		assertEquals(offset, drawn.m32());
		assertEquals(0.0F, drawn.m02(), "nought, even though the frame carried a term there");
		assertEquals(0.0F, drawn.m12());
		assertEquals(dirty.m00(), drawn.m00(), "every other term is the frame's");
		assertEquals(dirty.m23(), drawn.m23());

		// Published through the same conversion as the frame's own: the z row becomes w - 2 z.
		assertEquals(-1.0F - 2.0F * scale, view.dhProjection().m22(), 1.0e-6F);
		assertEquals(-2.0F * offset, view.dhProjection().m32(), 1.0e-4F);
		assertMatrix("published inverse", DoubleMatrix.identity(),
				new Matrix4f(view.dhProjection()).mul(view.dhProjectionInverse()));
	}

	@Test
	void theDistantDepthPairSendsTheGameDepthToTheFarTerrainDepthAtEveryDistance() {
		ViewMatrices view = framed();
		double nearDh = 7.5;
		double farDh = 4096.0;
		view.advanceDistantVolume((float) (nearDh / (farDh - nearDh)), (float) (nearDh * farDh / (farDh - nearDh)));

		Vector2f pair = new Vector2f();
		assertTrue(view.distantDepthPair(pair));
		for (double distance : new double[] {10.0, 50.0, 100.0, 180.0}) {
			double game = NEAR * (FAR - distance) / (distance * (FAR - NEAR));
			double far = nearDh * (farDh - distance) / (distance * (farDh - nearDh));
			assertEquals(far, pair.x * game + pair.y, 2.0e-4, "far terrain depth at " + distance + " blocks");
		}
	}

	@Test
	void theDistantHistoryIsSeededWithItselfAndThenLagsOneFrame() {
		ViewMatrices view = framed();
		view.advanceDistantVolume(0.0F, 0.0F);
		assertSameElements("first frame", view.dhProjection(), view.dhPreviousProjection());

		Matrix4f first = copy(view.dhProjection());
		view.advance(viewRotation(0.0, 0.0), new Matrix4f(), rendered(FAR * 2.0), (float) FAR * 2.0F, CHUNKS);
		view.advanceDistantVolume(0.0F, 0.0F);
		assertSameElements("second frame", first, view.dhPreviousProjection());
		assertNotEquals(first, new Matrix4f(view.dhProjection()));
	}

	@Test
	void aFrameWithABobInItsRowGivesNoDistantDepthPair() {
		ViewMatrices view = new ViewMatrices();
		view.advance(viewRotation(0.0, 0.0), new Matrix4f(), rendered(FAR).m02(0.1F), (float) FAR, CHUNKS);
		view.advanceDistantVolume(0.01F, 7.5F);

		assertFalse(view.distantDepthPair(new Vector2f()));
	}

	// ---- the pass's own matrices ----

	@Test
	void aPassMatrixIsMultipliedOntoTheFramesBobAndInvertedOnce() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 35.0, -20.0, bob());
		Matrix4f own = new Matrix4f().rotateY(0.7F).translate(1.0F, 2.0F, 3.0F);

		assertSame(view.gbufferModelView(), view.passModelView(), "no pass matrix yet: the camera's");
		view.passModelView(own, null);

		assertMatrix("bob * matrix", DoubleMatrix.mul(bobReference(), DoubleMatrix.rotationY(0.7F),
				DoubleMatrix.translation(1.0, 2.0, 3.0)), view.passModelView());
		assertMatrix("inverse", DoubleMatrix.identity(),
				new Matrix4f(view.passModelView()).mul(view.passModelViewInverse()));
		assertSameElements("the bob a shader forms cameraBob() * m from", bob(), view.cameraBob());
	}

	@Test
	void theHandsBobIsItsOwnAndOnlyWhileTheMatrixIsGiven() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, bob());
		Matrix4f handBob = new Matrix4f().translate(0.1F, 0.0F, 0.0F);
		Matrix4f own = new Matrix4f().rotateX(0.3F);

		view.passModelView(own, handBob);
		assertMatrix("hand bob * matrix", DoubleMatrix.mul(DoubleMatrix.translation(0.1F, 0.0, 0.0),
				DoubleMatrix.rotationX(0.3F)), view.passModelView());
		assertSameElements("cameraBob follows the pass", handBob, view.cameraBob());

		// A bob with no matrix is dropped: the pass is drawn under the frame's camera.
		view.passModelView(null, handBob);
		assertSame(view.gbufferModelView(), view.passModelView());
		assertSameElements("the frame's own bob again", bob(), view.cameraBob());
	}

	@Test
	void theFrameBoundaryDropsEveryPassMatrixAndTheColourButNotTheAlphaReference() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, bob());
		view.passModelView(new Matrix4f().rotateX(0.3F), new Matrix4f().translate(0.1F, 0.0F, 0.0F));
		view.passProjection(rendered(FAR * 3.0));
		view.passColour(new Vector4f(0.2F, 0.4F, 0.6F, 0.8F));
		view.passAlphaTest(0.1F);
		assertNotSame(view.gbufferProjection(), view.passProjection());

		advance(view, 0.0, 0.0, bob());

		assertSame(view.gbufferModelView(), view.passModelView());
		assertSame(view.gbufferModelViewInverse(), view.passModelViewInverse());
		assertSame(view.gbufferProjection(), view.passProjection());
		assertSame(view.gbufferProjectionInverse(), view.passProjectionInverse());
		assertSameElements("bob", bob(), view.cameraBob());
		assertEquals(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector4f(view.passColour()));
		assertEquals(0.1F, view.passAlphaTest(), "never dropped");
	}

	@Test
	void aPassProjectionGoesThroughTheSameConversionAsTheFramesAndCarriesNoBob() {
		ViewMatrices view = new ViewMatrices();
		advance(view, 0.0, 0.0, bob());
		view.passProjection(rendered(FAR * 3.0));

		assertMatrix("converted", DoubleMatrix.legacyPerspective(FOV, ASPECT, NEAR, FAR * 3.0), view.passProjection());
		assertMatrix("inverse", DoubleMatrix.identity(),
				new Matrix4f(view.passProjection()).mul(view.passProjectionInverse()));

		view.passProjection(null);
		assertSame(view.gbufferProjection(), view.passProjection());
	}

	@Test
	void theColourIsWhiteForNullAndTheGivenOneOtherwise() {
		ViewMatrices view = new ViewMatrices();
		assertEquals(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector4f(view.passColour()));

		Vector4f given = new Vector4f(0.25F, 0.5F, 0.75F, 0.125F);
		view.passColour(given);
		assertEquals(given, new Vector4f(view.passColour()));
		given.set(9.0F);
		assertEquals(0.25F, view.passColour().x(), "copied, not held");

		view.passColour(null);
		assertEquals(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector4f(view.passColour()));
	}

	@Test
	void theDepthConventionIsWhateverThePassSaidAndReversedBeforeAnyDoes() {
		ViewMatrices view = new ViewMatrices();
		assertEquals(new Vector4f(-0.5F, 0.5F, -1.0F, 1.0F), new Vector4f(view.depthConvention()));

		view.convention(new Vector4f(0.5F, 0.5F, 1.0F, 0.0F));
		assertEquals(new Vector4f(0.5F, 0.5F, 1.0F, 0.0F), new Vector4f(view.depthConvention()));
	}
}
