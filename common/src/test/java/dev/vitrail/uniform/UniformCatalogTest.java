package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.LegacyGlsl;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.uniform.values.FrameSmoothed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the three name to source tables to what a pack relies on: the names it can read, that each
 * name is answered in the shape the table says, that a source is a pure function of the world it
 * is handed, that layering changes what it is meant to change and nothing else, and that a block
 * made of every name lands each value where the specification puts it.
 * <p>
 * The name list is what a pack can read today. Adding a name is free; removing or respelling one is
 * a pack that reads zero, which nothing else in the build would say.
 */
class UniformCatalogTest {

	private static final List<String> ENGINE_NAMES = words("""
			of_ModelViewMatrix of_ModelViewMatrixInverse of_ModelViewProjectionMatrix of_ProjectionMatrix
			of_ProjectionMatrixInverse of_NormalMatrix of_TextureMatrix viewWidth viewHeight aspectRatio
			of_DepthConv pi atlasSize renderStage anisotropicFiltering currentColorSpace
			textureFilteringMode chunkFadeTimeInv ambientOcclusionLevel noiseTextureResolution entityColor
			entityId blockEntityId currentRenderedItemId alphaTestRef
			near far cameraPosition previousCameraPosition eyeAltitude cameraPositionInt
			cameraPositionFract previousCameraPositionInt previousCameraPositionFract eyePosition
			relativeEyePosition
			gbufferModelView gbufferModelViewInverse gbufferPreviousModelView gbufferProjection
			gbufferProjectionInverse gbufferPreviousProjection
			shadowModelView shadowModelViewInverse shadowProjection shadowProjectionInverse
			dhProjection dhProjectionInverse dhPreviousProjection dhNearPlane dhFarPlane dhRenderDistance
			sunAngle shadowAngle sunPosition moonPosition upPosition shadowLightPosition endFlashPosition
			endFlashIntensity previousEndFlashIntensity
			frameCounter frameTime frameTimeCounter worldTime worldDay moonPhase currentDate currentTime
			currentYearTime
			rainStrength thunderStrength wetness skyColor fogColor fogStart fogEnd fogDensity fogMode
			fogShape of_Fog
			bedrockLevel heightLimit logicalHeightLimit hasCeiling hasSkylight ambientLight cloudHeight
			seaLevel biome biome_category biome_precipitation rainfall temperature
			isEyeInWater blindness darknessFactor darknessLightFactor nightVision screenBrightness
			playerMood constantMood eyeBrightness eyeBrightnessSmooth is_sneaking is_sprinting is_hurt
			is_invisible is_burning is_on_ground hideGUI isRightHanded isSpectator firstPersonCamera
			isElytraFlying isRiding feetInWater inSwimmingAnimation vehicleInWater heavyFog
			playerLookVector playerBodyVector vehicleId vehicleLookVector relativeVehiclePosition
			currentPlayerHealth maxPlayerHealth currentPlayerHunger maxPlayerHunger currentPlayerArmor
			maxPlayerArmor currentPlayerAir maxPlayerAir currentSelectedBlockPos currentSelectedBlockId
			lightningBoltPosition heldItemId heldItemId2 heldBlockLightValue heldBlockLightValue2
			modelViewMatrix projectionMatrix textureMatrix""");

	/** What a pass over the world adds to the engine table: the names a full screen pass cannot reach. */
	private static final Set<String> GEOMETRY_ONLY = Set.of("of_TexShrink", "centerDepthSmooth",
			LegacyGlsl.GLINT_ALPHA, LegacyGlsl.CAMERA_BOB, "of_PassColour");

	/** The names whose answer differs between a pass over a quad and a pass over the world. */
	private static final Set<String> GEOMETRY_CHANGES = Set.of("of_ModelViewMatrix",
			"of_ModelViewMatrixInverse", "of_ProjectionMatrix", "of_ProjectionMatrixInverse",
			"of_ModelViewProjectionMatrix", "of_NormalMatrix", "of_TextureMatrix", "modelViewMatrix",
			"projectionMatrix", "textureMatrix");

	/** And between a pass over the world seen from the camera and one seen from the light. */
	private static final Set<String> SHADOW_CHANGES = Set.of("of_ModelViewMatrix",
			"of_ModelViewMatrixInverse", "of_ProjectionMatrix", "of_ProjectionMatrixInverse",
			"of_ModelViewProjectionMatrix", "of_NormalMatrix", "modelViewMatrix", "projectionMatrix",
			"shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse");

	/** Names that step with the frame or the wall clock: their answer depends on what came before. */
	private static final Set<String> HISTORY = Set.of("wetness", "eyeBrightnessSmooth", "currentDate",
			"currentTime", "currentYearTime");

	@BeforeEach
	void forgetHistory() {
		FrameSmoothed.forgetAll();
	}

	private static List<String> words(String text) {
		return Arrays.asList(text.trim().split("\\s+", -1));
	}

	private static Map<String, UniformCatalog> catalogs() {
		return Map.of("engine", UniformCatalog.engine(), "geometry", UniformCatalog.geometry(),
				"shadowGeometry", UniformCatalog.shadowGeometry());
	}

	private static List<FakeWorld> worlds() {
		List<FakeWorld> worlds = new ArrayList<>();
		worlds.add(new FakeWorld());
		worlds.add(FakeWorld.distinct());
		for (int seed = 0; seed < 20; seed++) {
			worlds.add(FakeWorld.random(new Random(seed)));
		}

		return worlds;
	}

	private static boolean integral(UniformShape shape) {
		return shape == UniformShape.INT || shape == UniformShape.IVEC2 || shape == UniformShape.IVEC3
				|| shape == UniformShape.IVEC4;
	}

	private static List<Integer> snapshot(Val val) {
		List<Integer> bits = new ArrayList<>();
		bits.add(val.rank());
		for (int i = 0; i < val.rank(); i++) {
			bits.add(Float.floatToRawIntBits(val.f(i)));
			bits.add(val.i(i));
		}

		return bits;
	}

	private static List<Integer> read(UniformCatalog catalog, String name, WorldState world, int element) {
		Val val = new Val();
		catalog.source(name).read(world, val, element);

		return snapshot(val);
	}

	@Test
	void tablesAreBuiltOnceAndHandedOutAgain() {
		assertSame(UniformCatalog.engine(), UniformCatalog.engine());
		assertSame(UniformCatalog.geometry(), UniformCatalog.geometry());
		assertSame(UniformCatalog.shadowGeometry(), UniformCatalog.shadowGeometry());
		assertNotSame(UniformCatalog.engine(), UniformCatalog.geometry());
	}

	@Test
	void theEngineTableAnswersEveryNameAPackReadsToday() {
		Set<String> names = UniformCatalog.engine().names();

		List<String> missing = ENGINE_NAMES.stream().filter(name -> !names.contains(name)).toList();
		assertEquals(List.of(), missing);
	}

	@Test
	void eachLayerAnswersEverythingTheOneBelowItDoes() {
		Set<String> engine = UniformCatalog.engine().names();
		Set<String> geometry = UniformCatalog.geometry().names();
		Set<String> shadow = UniformCatalog.shadowGeometry().names();

		assertTrue(geometry.containsAll(engine));
		assertTrue(shadow.containsAll(geometry));
	}

	@Test
	void aPassOverTheWorldAddsExactlyTheNamesAQuadCannotReach() {
		Set<String> added = new TreeSet<>(UniformCatalog.geometry().names());
		added.removeAll(UniformCatalog.engine().names());

		assertEquals(new TreeSet<>(GEOMETRY_ONLY), added);

		Set<String> shadowAdded = new TreeSet<>(UniformCatalog.shadowGeometry().names());
		shadowAdded.removeAll(UniformCatalog.geometry().names());
		assertEquals(Set.of(), shadowAdded, "a shadow pass answers the same names from other matrices");
	}

	@Test
	void aPassOverTheWorldChangesTheSevenFixedFunctionNamesAndTheirThreeAliasesOnly() {
		assertEquals(GEOMETRY_CHANGES, changed(UniformCatalog.engine(), UniformCatalog.geometry()));
	}

	@Test
	void aPassFromTheLightChangesTheSixMatricesAndTheFourShadowNamesAndLeavesTheTextureMatrices() {
		Set<String> changed = changed(UniformCatalog.geometry(), UniformCatalog.shadowGeometry());

		assertEquals(SHADOW_CHANGES, changed);
		assertFalse(changed.contains("of_TextureMatrix"), "a light map coordinate is the same from either end");
		assertFalse(changed.contains("textureMatrix"));
	}

	private static Set<String> changed(UniformCatalog below, UniformCatalog above) {
		Set<String> changed = new TreeSet<>();
		for (String name : below.names()) {
			if (below.source(name) != above.source(name)) {
				changed.add(name);
			}
		}

		return changed;
	}

	@Test
	void layeringNeverChangesTheShapeOfAName() {
		for (String name : UniformCatalog.engine().names()) {
			assertEquals(UniformCatalog.engine().natural(name), UniformCatalog.geometry().natural(name), name);
			assertEquals(UniformCatalog.engine().natural(name), UniformCatalog.shadowGeometry().natural(name), name);
		}
	}

	@Test
	void everyNameHasASourceAndAShape() {
		catalogs().forEach((label, catalog) -> {
			for (String name : catalog.names()) {
				assertNotNull(catalog.source(name), label + " " + name);
				assertNotNull(catalog.natural(name), label + " " + name);
			}
		});
	}

	@Test
	void everySourceFillsTheRankOfTheShapeItIsRegisteredUnder() {
		// The declaration decides what is written, but the coercion rules take the value's rank on
		// trust: a source registered as a vec3 that filled a vec4 would be zero filled, truncated or
		// read as another shape without a word.
		List<FakeWorld> worlds = worlds();
		List<String> wrong = new ArrayList<>();

		catalogs().forEach((label, catalog) -> {
			for (String name : catalog.names()) {
				UniformShape shape = catalog.natural(name);
				for (FakeWorld world : worlds) {
					for (int element = 0; element < 8; element++) {
						Val val = new Val();
						catalog.source(name).read(world, val, element);

						if (val.rank() != shape.rank() || val.integral() != integral(shape)) {
							wrong.add(label + " " + name + " " + shape + " rank " + val.rank()
									+ " integral " + val.integral());
						}
					}
				}
			}
		});

		assertEquals(List.of(), wrong.stream().distinct().toList());
	}

	@Test
	void aSourceAnswersTheSameNumberTwiceInAFrame() {
		for (FakeWorld world : worlds()) {
			catalogs().forEach((label, catalog) -> {
				for (String name : catalog.names()) {
					assertEquals(read(catalog, name, world, 0), read(catalog, name, world, 0), label + " " + name);
				}
			});
		}
	}

	@Test
	void theOrderTheNamesAreAskedInMakesNoDifference() {
		// The sky names share scratch space, so a name that left something behind for the next would
		// show here as an answer that depends on who was asked first.
		for (FakeWorld world : worlds()) {
			catalogs().forEach((label, catalog) -> {
				List<String> names = new ArrayList<>(catalog.names());
				FrameSmoothed.forgetAll();
				Map<String, List<Integer>> forward = readAll(catalog, names, world);

				Collections.reverse(names);
				FrameSmoothed.forgetAll();
				assertEquals(forward, readAll(catalog, names, world), label + " reversed");

				Collections.shuffle(names, new Random(42));
				FrameSmoothed.forgetAll();
				assertEquals(forward, readAll(catalog, names, world), label + " shuffled");
			});
		}
	}

	private static Map<String, List<Integer>> readAll(UniformCatalog catalog, List<String> names, WorldState world) {
		Map<String, List<Integer>> answers = new TreeMap<>();
		for (String name : names) {
			answers.put(name, read(catalog, name, world, 0));
		}

		return answers;
	}

	@Test
	void anAnswerDependsOnTheWorldAskedAboutAndNotOnTheOneAskedAboutBefore() {
		// The sky and the composed matrices remember their last inputs so as not to rebuild them; what
		// they must never do is hand one world the answer to another. Names that step with time are
		// left out, their answer is meant to depend on what came before.
		List<FakeWorld> worlds = worlds();
		UniformCatalog catalog = UniformCatalog.shadowGeometry();

		Map<String, List<Integer>> fresh = new TreeMap<>();
		for (String name : catalog.names()) {
			if (!HISTORY.contains(name)) {
				fresh.put(name, read(catalog, name, worlds.getFirst(), 0));
			}
		}

		for (FakeWorld other : worlds) {
			for (String name : catalog.names()) {
				if (!HISTORY.contains(name)) {
					read(catalog, name, other, 0);
				}
			}

			for (String name : fresh.keySet()) {
				assertEquals(fresh.get(name), read(catalog, name, worlds.getFirst(), 0), name);
			}
		}
	}

	@Test
	void everyNameTheEngineAnswersLandsWhereTheSpecificationPutsIt() {
		for (FakeWorld world : worlds()) {
			catalogs().forEach((label, catalog) -> {
				FrameSmoothed.forgetAll();
				List<String> names = new ArrayList<>(catalog.names());
				Collections.sort(names);
				BlockCheck.verify(declare(catalog, names), catalog, world);

				Collections.shuffle(names, new Random(label.hashCode()));
				FrameSmoothed.forgetAll();
				BlockCheck.verify(declare(catalog, names), catalog, world);
			});
		}
	}

	private static List<TranslatedUnit.Uniform> declare(UniformCatalog catalog, List<String> names) {
		List<TranslatedUnit.Uniform> declared = new ArrayList<>();
		for (String name : names) {
			String declaration = BlockCheck.type(catalog.natural(name)) + " " + name
					+ (name.equals("of_TextureMatrix") ? "[8]" : "");
			declared.add(TranslatedUnit.Uniform.of(name, declaration));
		}

		return declared;
	}

	@Test
	void theCoreSpellingsAnswerWhatTheFixedFunctionSpellingsDoOnEveryLayer() {
		FakeWorld world = FakeWorld.distinct();
		catalogs().forEach((label, catalog) -> {
			assertEquals(read(catalog, "of_ModelViewMatrix", world, 0),
					read(catalog, "modelViewMatrix", world, 0), label);
			assertEquals(read(catalog, "of_ProjectionMatrix", world, 0),
					read(catalog, "projectionMatrix", world, 0), label);
			assertEquals(read(catalog, "of_TextureMatrix", world, 0),
					read(catalog, "textureMatrix", world, 0), label);
			assertEquals(UniformShape.MAT4, catalog.natural("modelViewMatrix"));
			assertEquals(UniformShape.MAT4, catalog.natural("projectionMatrix"));
			assertEquals(UniformShape.MAT4, catalog.natural("textureMatrix"));
		});
	}

	@Test
	void anUnknownNameHasNeitherASourceNorAShape() {
		assertNull(UniformCatalog.engine().source("noSuchUniform"));
		assertNull(UniformCatalog.engine().natural("noSuchUniform"));
		assertNull(UniformCatalog.engine().source(""));
	}

	@Test
	void theNamesOfATableCannotBeChangedThroughTheView() {
		Set<String> names = UniformCatalog.engine().names();

		assertThrows(UnsupportedOperationException.class, () -> names.add("x"));
		assertThrows(UnsupportedOperationException.class, () -> names.remove("near"));
	}

	@Test
	void aBuilderRefusesTheSameNameTwiceAndSaysWhich() {
		UniformCatalog.Builder builder = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F));

		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> builder.add("a", UniformShape.FLOAT, (world, out) -> out.set(2.0F)));

		assertEquals("a is registered twice in the same catalogue", refused.getMessage());
	}

	@Test
	void aLayerMayShadowANameOfTheTableBelowItButNotRegisterOneTwice() {
		UniformCatalog base = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F))
				.add("b", UniformShape.FLOAT, (world, out) -> out.set(2.0F))
				.build();

		UniformCatalog.Builder layer = UniformCatalog.builder(base)
				.add("a", UniformShape.VEC2, (world, out) -> out.set(3.0F, 4.0F));
		assertThrows(IllegalStateException.class,
				() -> layer.add("a", UniformShape.FLOAT, (world, out) -> out.set(5.0F)));

		UniformCatalog layered = layer.build();
		assertEquals(UniformShape.VEC2, layered.natural("a"));
		assertEquals(UniformShape.FLOAT, layered.natural("b"), "what was not shadowed is kept");
		assertEquals(UniformShape.FLOAT, base.natural("a"), "and the table below is not touched");

		Val val = new Val();
		layered.source("a").read(new FakeWorld(), val);
		assertEquals(3.0F, val.f(0));
	}

	@Test
	void aBuiltTableDoesNotFollowTheBuilderItCameFrom() {
		UniformCatalog.Builder builder = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F));
		UniformCatalog built = builder.build();

		builder.add("b", UniformShape.FLOAT, (world, out) -> out.set(1.0F));

		assertEquals(Set.of("a"), built.names());
	}

	@Test
	void anAliasIsASecondNameForWhatTheFirstHoldsAtThatPoint() {
		UniformCatalog.Builder builder = UniformCatalog.builder()
				.add("first", UniformShape.VEC3, (world, out) -> out.set(1.0F, 2.0F, 3.0F));
		builder.alias("second", "first");
		builder.alias("third", "second");
		UniformCatalog catalog = builder.build();

		assertEquals(UniformShape.VEC3, catalog.natural("third"));
		assertSame(catalog.source("first"), catalog.source("second"));
		assertSame(catalog.source("first"), catalog.source("third"));
	}

	@Test
	void anAliasOfANameNobodyRegisteredIsRefused() {
		UniformCatalog.Builder builder = UniformCatalog.builder();

		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> builder.alias("second", "first"));

		assertEquals("Cannot make second a second name for first, which is not registered", refused.getMessage());
	}

	@Test
	void anAliasTakesTheSourceOfTheLayerItIsMadeIn() {
		UniformCatalog base = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F))
				.build();
		UniformCatalog.Builder layer = UniformCatalog.builder(base)
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(2.0F));
		layer.alias("alias", "a");

		Val val = new Val();
		layer.build().source("alias").read(new FakeWorld(), val);
		assertEquals(2.0F, val.f(0), "the shadowing source, not the one it shadowed");
	}
}
