package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.source.ShadowCullState;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformGaps;
import dev.vitrail.uniform.expr.CustomUniforms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/**
 * Holds the shadow distance arbitration to Iris's: who decides how far from the camera the light
 * still gathers the world, in blocks, and when nobody does.
 * <p>
 * A {@link PackValues} is made by reading a whole pack, so these tests reach it through its private
 * constructor and hand its frame state the directives of a few {@code const} lines, which is
 * everything the arbitration reads. The class is the only place the two units meet (the pack's half
 * plane in BLOCKS, the player's setting in CHUNKS), so every case says which unit it is in.
 */
class PackValuesTest {

	private static final float NO_BOUND = -1.0F;

	/** A name Iris does not answer either, and two names answered by a stand-in for two different reasons. */
	private static final String UNANSWERABLE = "farPlane";
	private static final String FIRST_STAND_IN = "currentColorSpace";
	private static final String SECOND_STAND_IN = "constantMood";

	private static PackDirectives directives(String... declarations) {
		BitSet live = new BitSet();
		live.set(0, declarations.length);
		ExpandedUnit unit = new ExpandedUnit("composite.fsh", List.of(declarations), "460", ExpansionStats.NONE, live,
				Map.of());

		return PackDirectives.builder().accept(unit).build();
	}

	private static PackValues values(String... declarations) {
		try {
			Constructor<PackValues> constructor = PackValues.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			PackValues values = constructor.newInstance();
			Field state = PackValues.class.getDeclaredField("state");
			state.setAccessible(true);
			((FrameState) state.get(values)).directives(directives(declarations));

			return values;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("PackValues could not be built for the test", e);
		}
	}

	private static PackValues values(ShadowCullState cull, String... declarations) {
		PackValues values = values(declarations);
		try {
			Field field = PackValues.class.getDeclaredField("shadowCull");
			field.setAccessible(true);
			field.set(values, cull);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("the cull state could not be set for the test", e);
		}

		return values;
	}

	// ---- the world's own bound ----

	@Test
	void aPackThatSaysNothingLetsThePlayersSettingDecideInChunksTimesSixteen() {
		PackValues values = values();

		assertEquals(128.0F, values.shadowRenderDistance(8, 12), "eight chunks of a twelve chunk world");
		assertEquals(16.0F, values.shadowRenderDistance(1, 12));
	}

	@Test
	void zeroIsADistanceAndNotTheAbsenceOfOne() {
		// A player who drags the setting to the bottom asks for a map with nothing in it. Told apart from
		// no bound by the sentinel and not by the sign, which is why the sentinel is minus one.
		assertEquals(0.0F, values().shadowRenderDistance(0, 12));
		assertEquals(NO_BOUND, values().shadowRenderDistance(12, 12));
	}

	@Test
	void aSettingThatReachesTheLoadedWorldIsNoBoundAtAll() {
		PackValues values = values();

		assertEquals(NO_BOUND, values.shadowRenderDistance(12, 12), "exactly the world: 192 blocks are not < 192");
		assertEquals(NO_BOUND, values.shadowRenderDistance(13, 12));
		assertEquals(NO_BOUND, values.shadowRenderDistance(32, 12), "the default of 32 chunks lands here");
		assertEquals(176.0F, values.shadowRenderDistance(11, 12), "one chunk under is a bound");
		assertEquals(NO_BOUND, values.shadowRenderDistance(8, 0), "a world of nothing has nothing to bound");
	}

	@Test
	void aPackThatDeclaresAMultiplierIsBoundedAtItsOwnHalfPlaneInBlocksWhateverTheSlider() {
		PackValues values = values("const float shadowDistance = 128.0;", "const float shadowDistanceRenderMul = 1.0;");

		assertEquals(128.0F, values.shadowRenderDistance(2, 12), "the player asked for 32 blocks and is not asked");
		assertEquals(128.0F, values.shadowRenderDistance(32, 12));
		assertEquals(NO_BOUND, values.shadowRenderDistance(2, 8), "128 blocks reach the edge of an 8 chunk world");
	}

	@Test
	void theMultiplierScalesTheHalfPlaneAndAZeroMultiplierIsAZeroDistance() {
		assertEquals(100.0F, values("const float shadowDistance = 200.0;", "const float shadowDistanceRenderMul = 0.5;")
				.shadowRenderDistance(32, 12));
		assertEquals(NO_BOUND, values("const float shadowDistance = 160.0;", "const float shadowDistanceRenderMul = 2.0;")
				.shadowRenderDistance(32, 12), "320 blocks past a 192 block world");
		assertEquals(0.0F, values("const float shadowDistance = 160.0;", "const float shadowDistanceRenderMul = 0.0;")
				.shadowRenderDistance(32, 12), "zero is a distance");
	}

	@Test
	void aNegativeMultiplierIsTheSameCaseAsNeverDeclaringOne() {
		// Iris branches on the sign of the multiplier alone, so a pack that declares minus one and a pack
		// that says nothing are both governed by the player.
		PackValues declared = values("const float shadowDistance = 128.0;", "const float shadowDistanceRenderMul = -3.0;");
		PackValues silent = values("const float shadowDistance = 128.0;");

		assertEquals(silent.shadowRenderDistance(8, 12), declared.shadowRenderDistance(8, 12));
		assertEquals(128.0F, declared.shadowRenderDistance(8, 12), "the player's eight chunks, not the pack's 128 * -3");
	}

	// ---- what moves ----

	@Test
	void anEntityMultiplierOfOneOrLessThanNoughtLeavesTheBoundTheWorldsOwn() {
		PackValues one = values("const float shadowDistance = 128.0;", "const float shadowDistanceRenderMul = 1.0;",
				"const float entityShadowDistanceMul = 1.0;");
		assertEquals(one.shadowRenderDistance(8, 12), one.entityShadowDistance(8, 12));

		PackValues negative = values("const float shadowDistance = 128.0;", "const float shadowDistanceRenderMul = 1.0;",
				"const float entityShadowDistanceMul = -2.0;");
		assertEquals(128.0F, negative.entityShadowDistance(5, 12), "the pack's own 128, and not the 80 of the player's five chunks");
	}

	@Test
	void theTwoMultipliersAreMultipliedTogetherAndNotTheSmallerOfTheTwo() {
		// Half the world and half again for the mobs is a quarter.
		PackValues quarter = values("const float shadowDistance = 160.0;", "const float shadowDistanceRenderMul = 0.5;",
				"const float entityShadowDistanceMul = 0.5;");
		assertEquals(40.0F, quarter.entityShadowDistance(32, 12));
		assertEquals(80.0F, quarter.shadowRenderDistance(32, 12), "and the world's own is still the half");

		PackValues reaching = values("const float shadowDistance = 100.0;", "const float shadowDistanceRenderMul = 1.0;",
				"const float entityShadowDistanceMul = 3.0;");
		assertEquals(NO_BOUND, reaching.entityShadowDistance(32, 12), "300 blocks past a 192 block world");
	}

	@Test
	void thePlayersSettingSwallowsThePacksEntityMultiplier() {
		// The world multiplier is minus one when the player governs, so the product is negative whatever the
		// entity one says and the casters that move are bounded exactly as the world is.
		PackValues values = values("const float shadowDistance = 160.0;", "const float entityShadowDistanceMul = 0.25;");

		assertEquals(128.0F, values.entityShadowDistance(8, 12));
		assertEquals(values.shadowRenderDistance(8, 12), values.entityShadowDistance(8, 12));
	}

	@Test
	void anEntityMultiplierOfZeroUnderAPlayerGovernedWorldIsANegativeZeroBoundNotThePlayers() {
		// -1 * 0 is -0.0, which is not less than nought, so the pack's own half plane times -0.0 is
		// what is answered. Iris multiplies the same way. Characterised: mobs then never reach the map.
		PackValues values = values("const float shadowDistance = 160.0;", "const float entityShadowDistanceMul = 0.0;");

		assertEquals(Float.floatToIntBits(-0.0F), Float.floatToIntBits(values.entityShadowDistance(8, 12)));
	}

	// ---- what a settings screen greys out ----

	@Test
	void theSliderIsGreyedOnlyWhereThePackDeclaredAMultiplierItMeans() {
		assertEquals(OptionalInt.empty(), values("const float shadowDistance = 128.0;").forcedShadowRenderDistanceChunks());
		assertEquals(OptionalInt.empty(), values("const float shadowDistance = 128.0;",
				"const float shadowDistanceRenderMul = -1.0;").forcedShadowRenderDistanceChunks(),
				"declared but negative is the player's case");
	}

	@Test
	void theForcedDistanceIsInChunksRoundedUpFromTheBlocks() {
		assertEquals(OptionalInt.of(10), forced(160.0F, 1.0F));
		assertEquals(OptionalInt.of(11), forced(161.0F, 1.0F), "161 blocks are more than ten chunks");
		assertEquals(OptionalInt.of(1), forced(1.0F, 1.0F));
		assertEquals(OptionalInt.of(0), forced(0.0F, 1.0F));
		assertEquals(OptionalInt.of(5), forced(160.0F, 0.5F), "the product is what is asked for");
		assertEquals(OptionalInt.of(0), forced(160.0F, 0.0F), "a zero multiplier is declared and means nought");
	}

	@Test
	void theBlocksAreTruncatedToAnIntegerBeforeTheyAreRoundedUpToChunks() {
		// 160.5 blocks are more than ten chunks, and the cast to int drops the half first, so the slider
		// says ten. Characterised as it stands: a difference of a fraction of a block.
		assertEquals(OptionalInt.of(10), forced(160.5F, 1.0F));
		assertEquals(OptionalInt.of(11), forced(161.0F, 1.0F));
	}

	@Test
	void knownBug_aDistanceOfThreeBillionBlocksOverflowsTheRoundUpToANegativeNumberOfChunks() {
		// (int) saturates at 2,147,483,647 and adding fifteen to that wraps. No pack asks for this.
		OptionalInt chunks = forced(3.0e9F, 1.0F);

		assertTrue(chunks.isPresent());
		assertTrue(chunks.getAsInt() < 0, "a negative number of chunks: " + chunks.getAsInt());
	}

	private static OptionalInt forced(float distance, float multiplier) {
		return values("const float shadowDistance = " + String.format(Locale.ROOT, "%.1f", distance) + ";",
				"const float shadowDistanceRenderMul = " + String.format(Locale.ROOT, "%.1f", multiplier) + ";")
				.forcedShadowRenderDistanceChunks();
	}

	// ---- what the light's walk is bounded by ----

	private static ShadowCullPlan plan(PackValues values, int user, int world) {
		return values.shadowCullPlan(user, world, new Vector3f(), new Matrix4f(), false);
	}

	@Test
	void theDefaultAndAdvancedWalksTakeTheWorldsArbitratedBoundAndNoSafeZone() {
		for (ShadowCullState state : new ShadowCullState[] {ShadowCullState.DEFAULT, ShadowCullState.ADVANCED}) {
			PackValues values = values(state, "const float shadowDistance = 128.0;", "const float shadowDistanceRenderMul = 1.0;");
			ShadowCullPlan plan = plan(values, 2, 12);

			assertEquals(state, plan.state());
			assertEquals(values.shadowRenderDistance(2, 12), plan.bound(), state.name());
			assertEquals(-1.0F, plan.safeZone(), state.name());
		}
	}

	@Test
	void theDistanceWalkReadsThePacksOwnProductAndNeverThePlayersSetting() {
		PackValues values = values(ShadowCullState.DISTANCE, "const float shadowDistance = 128.0;",
				"const float shadowDistanceRenderMul = 1.0;");

		assertEquals(128.0F, plan(values, 2, 12).bound());
		assertEquals(128.0F, plan(values, 32, 12).bound(), "the setting is not consulted");
		assertEquals(-1.0F, plan(values, 2, 12).safeZone());
	}

	@Test
	void theDistanceWalkIsLooseWhereTheProductIsNotPositiveOrReachesPastTheWorld() {
		String[] pack = {"const float shadowDistance = 192.0;", "const float shadowDistanceRenderMul = 1.0;"};
		assertEquals(192.0F, plan(values(ShadowCullState.DISTANCE, pack), 2, 12).bound(),
				"exactly the world's 192 is kept: this walk reaches past on > and not on >=");
		assertEquals(NO_BOUND, plan(values(ShadowCullState.DISTANCE, pack), 2, 11).bound(), "192 past a 176 block world");
		assertEquals(NO_BOUND, plan(values(ShadowCullState.DISTANCE, "const float shadowDistance = 128.0;",
				"const float shadowDistanceRenderMul = 0.0;"), 2, 12).bound(), "zero is not positive");
		assertEquals(NO_BOUND, plan(values(ShadowCullState.DISTANCE, "const float shadowDistance = 128.0;"), 2, 12).bound(),
				"no multiplier is minus one, a negative product");
	}

	@Test
	void theSafeZoneWalkForcesAMultiplierOfOneBeforeThePlayersSettingCouldBeRead() {
		String[] pack = {"const float shadowDistance = 128.0;", "const float voxelDistance = 48.0;"};
		ShadowCullPlan plan = plan(values(ShadowCullState.SAFE_ZONE, pack), 2, 12);

		assertEquals(128.0F, plan.bound(), "the half plane, times a multiplier the pack never declared, one");
		assertEquals(48.0F, plan.safeZone());
		assertEquals(128.0F, plan(values(ShadowCullState.SAFE_ZONE, pack), 32, 12).bound(),
				"the player's number cannot reach it either");

		String[] scaled = {"const float shadowDistance = 128.0;", "const float voxelDistance = 48.0;",
				"const float shadowDistanceRenderMul = 0.5;"};
		ShadowCullPlan half = plan(values(ShadowCullState.SAFE_ZONE, scaled), 2, 12);
		assertEquals(64.0F, half.bound());
		assertEquals(24.0F, half.safeZone());
	}

	@Test
	void theSafeZoneWalkIsNotCappedAgainstTheLoadedWorld() {
		ShadowCullPlan plan = plan(values(ShadowCullState.SAFE_ZONE, "const float shadowDistance = 512.0;",
				"const float voxelDistance = 48.0;"), 2, 12);

		assertEquals(512.0F, plan.bound(), "512 blocks of a 192 block world: this state does not drop the bound");
	}

	@Test
	void thePlanCarriesTheCamerasVolumeAndAUnitLightAndTheVoxeliseFlag() {
		PackValues values = values();
		Vector3f light = new Vector3f();
		Matrix4f camera = new Matrix4f().zero();
		ShadowCullPlan plan = values.shadowCullPlan(8, 12, light, camera, true);

		assertTrue(plan.voxelised());
		assertEquals(1.0F, plan.light().length(), 1.0e-6F, "a direction");
		assertEquals(new Matrix4f(), plan.camera(), "the published projection times the model view, both identity here");
		assertFalse(Float.isNaN(plan.bound()));
	}

	@Test
	void theResolutionsAreTheDirectivesOwnNumbers() {
		assertEquals(1024, values().shadowResolution(), "Iris's default map");
		assertEquals(2048, values("const int shadowMapResolution = 2048;").shadowResolution());
		assertEquals(256, values().noiseResolution());
		assertEquals(512, values("const int noiseTextureResolution = 512;").noiseResolution());
	}

	// ---- what a block could not be given ----

	@SuppressWarnings("unchecked")
	private static PackValues declaring(String... names) {
		PackValues values = values();
		try {
			Field declared = PackValues.class.getDeclaredField("declared");
			declared.setAccessible(true);
			((Set<String>) declared.get(values)).addAll(List.of(names));
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("the declared names could not be set for the test", e);
		}

		return values;
	}

	@Test
	void anUnansweredNameIsThePacksOwnIfItDeclaredItThenNobodysThenTheEngines() {
		assumeTrue(UniformGaps.unanswerable(UNANSWERABLE) != null, UNANSWERABLE + " is no longer a name nobody answers");
		PackValues values = declaring("own");

		PackValues.Gaps gaps = values.classify(List.of("zeta", UNANSWERABLE, "own", "alpha"));

		assertEquals(List.of("own"), gaps.pack());
		assertEquals(List.of(UNANSWERABLE), gaps.nobody());
		assertEquals(List.of("zeta", "alpha"), gaps.engine(), "the engine's own debt keeps the order it was asked in");
	}

	@Test
	void aNameThePackDeclaresItselfIsThePacksEvenWhereNoEngineAnswersItEither() {
		assumeTrue(UniformGaps.unanswerable(UNANSWERABLE) != null, UNANSWERABLE + " is no longer a name nobody answers");
		PackValues.Gaps gaps = declaring(UNANSWERABLE).classify(List.of(UNANSWERABLE));

		assertEquals(List.of(UNANSWERABLE), gaps.pack(), "the declaration is checked first");
		assertEquals(List.of(), gaps.nobody());
	}

	@Test
	void theStandInGroupsAreNamedInTheOrderTheMembersCameInAndAreNotModifiable() {
		assumeTrue(UniformGaps.standIn(FIRST_STAND_IN) != null && UniformGaps.standIn(SECOND_STAND_IN) != null
				&& !UniformGaps.standIn(FIRST_STAND_IN).equals(UniformGaps.standIn(SECOND_STAND_IN)),
				"the two stand-ins this test names are no longer two different reasons");

		List<String> forward = List.copyOf(PackValues.standIns(List.of("other", FIRST_STAND_IN, SECOND_STAND_IN)).keySet());
		List<String> backward = List.copyOf(PackValues.standIns(List.of(SECOND_STAND_IN, "other", FIRST_STAND_IN)).keySet());

		assertEquals(List.of(UniformGaps.standIn(FIRST_STAND_IN), UniformGaps.standIn(SECOND_STAND_IN)), forward);
		assertEquals(List.of(UniformGaps.standIn(SECOND_STAND_IN), UniformGaps.standIn(FIRST_STAND_IN)), backward);
		assertEquals(Map.of(), PackValues.standIns(List.of("other")), "a name answered properly is not listed");
		assertThrows(UnsupportedOperationException.class,
				() -> PackValues.standIns(List.of(FIRST_STAND_IN)).put("x", List.of()));
	}

	// -- the version of the frame's values -------------------------------------------------------

	/**
	 * Values a frame can be advanced over: no pack was read, so the custom uniforms a load declares
	 * are the none it declares when a pack has no line for one.
	 */
	private static PackValues advanceable() {
		PackValues values = values();
		try {
			Field customs = PackValues.class.getDeclaredField("customs");
			customs.setAccessible(true);
			customs.set(values, CustomUniforms.builder().build(UniformCatalog.engine(), new ArrayList<>()));
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("the custom uniforms could not be set for the test", e);
		}

		return values;
	}

	@Test
	void theVersionMovesWithTheFrameAndWithALeftWorldAndWithNothingElse() {
		PackValues values = advanceable();
		long first = values.version();

		// What a pass sets beside its block is compared by whoever writes the block, and is no
		// reason for the frame to have changed.
		values.passAlphaTest(0.5F);
		values.passColour(new Vector4f(1, 0, 0, 1));
		values.modelView(new Matrix4f(), null);
		values.projection(new Matrix4f());
		assertEquals(first, values.version());
		assertEquals(first, values.version(), "asking does not move it");

		values.advance();
		long advanced = values.version();
		assertTrue(advanced != first, "a frame advanced is another frame");

		values.leaveWorld();
		assertTrue(values.version() != advanced, "a world left is another frame");
	}

	@Test
	void theVersionOfTwoAdvancesIsNotTheVersionOfEither() {
		PackValues values = advanceable();
		values.advance();
		long once = values.version();
		values.advance();

		assertTrue(values.version() != once);
	}
}
