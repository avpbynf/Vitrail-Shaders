package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.uniform.FakeWorld;
import dev.vitrail.uniform.UniformCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.joml.Matrix4fc;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.jupiter.api.Test;

/**
 * Holds every name that is a plain read of one accessor to reading THAT accessor.
 * <p>
 * The world here gives every accessor a number of its own, so the mistakes this catches are the
 * ones a code review misses: {@code heldItemId2} read for {@code heldItemId}, the previous frame's
 * flash for this frame's, {@code maxPlayerHealth} for {@code currentPlayerHealth}, the drawn shadow
 * matrix for the published one. Where the name and the accessor are spelled alike the table is a
 * transcription; where they are not ({@code currentPlayerHealth} against {@code playerHealth},
 * {@code heldBlockLightValue} against {@code heldBlockLight}) it is the mapping that is under test.
 * The flags, which cannot be told apart by value, are switched on one at a time.
 */
class PassThroughValuesTest {

	private final UniformCatalog engine = UniformCatalog.engine();
	private final FakeWorld w = FakeWorld.distinct();

	private void scalar(String name, float expected) {
		assertArrayEquals(new float[] { expected }, ValueReads.floats(ValueReads.read(this.engine, name, this.w), 1),
				name);
	}

	private void integer(String name, int expected) {
		assertArrayEquals(new int[] { expected }, ValueReads.ints(ValueReads.read(this.engine, name, this.w), 1),
				name);
	}

	private void vec3(String name, Vector3fc expected) {
		assertArrayEquals(new float[] { expected.x(), expected.y(), expected.z() },
				ValueReads.floats(ValueReads.read(this.engine, name, this.w), 3), name);
	}

	private void vec3(String name, Vector3dc expected) {
		assertArrayEquals(new float[] { (float) expected.x(), (float) expected.y(), (float) expected.z() },
				ValueReads.floats(ValueReads.read(this.engine, name, this.w), 3), name);
	}

	private void vec4(String name, Vector4fc expected) {
		assertArrayEquals(new float[] { expected.x(), expected.y(), expected.z(), expected.w() },
				ValueReads.floats(ValueReads.read(this.engine, name, this.w), 4), name);
	}

	private void matrix(String name, Matrix4fc expected) {
		assertArrayEquals(ValueReads.columnMajor(expected),
				ValueReads.matrix(ValueReads.read(this.engine, name, this.w)), name);
	}

	@Test
	void theViewportAndTheClipPlanes() {
		scalar("viewWidth", this.w.viewWidth);
		scalar("viewHeight", this.w.viewHeight);
		scalar("near", this.w.near);
		scalar("far", this.w.far);
		scalar("dhNearPlane", this.w.dhNearPlane);
		scalar("dhFarPlane", this.w.dhFarPlane);
		integer("dhRenderDistance", this.w.dhRenderDistance);
	}

	@Test
	void theViewportRatioIsWidthOverHeightWithAFloorOfOneOnTheHeight() {
		this.w.viewWidth = 1920.0F;
		this.w.viewHeight = 1080.0F;
		scalar("aspectRatio", 1920.0F / 1080.0F);

		this.w.viewHeight = 0.5F;
		scalar("aspectRatio", 1920.0F);

		this.w.viewHeight = 0.0F;
		scalar("aspectRatio", 1920.0F);
	}

	@Test
	void theFrameAndTheClocks() {
		integer("frameCounter", this.w.frameCounter);
		scalar("frameTime", this.w.frameTime);
		scalar("frameTimeCounter", this.w.frameTimeCounter);
		integer("worldTime", (int) this.w.worldTime);
		integer("worldDay", (int) this.w.worldDay);
		integer("moonPhase", this.w.moonPhase);
	}

	@Test
	void theMatricesTheGbuffersAreDrawnWithAndTheirPredecessors() {
		matrix("gbufferModelView", this.w.gbufferModelView);
		matrix("gbufferModelViewInverse", this.w.gbufferModelViewInverse);
		matrix("gbufferPreviousModelView", this.w.gbufferPreviousModelView);
		matrix("gbufferProjection", this.w.gbufferProjection);
		matrix("gbufferProjectionInverse", this.w.gbufferProjectionInverse);
		matrix("gbufferPreviousProjection", this.w.gbufferPreviousProjection);
	}

	@Test
	void theShadowMatricesAreThePublishedPairAndNotTheDrawnOne() {
		matrix("shadowModelView", this.w.shadowModelView);
		matrix("shadowModelViewInverse", this.w.shadowModelViewInverse);
		matrix("shadowProjection", this.w.shadowProjection);
		matrix("shadowProjectionInverse", this.w.shadowProjectionInverse);
	}

	@Test
	void theDistantHorizonsMatrices() {
		matrix("dhProjection", this.w.dhProjection);
		matrix("dhProjectionInverse", this.w.dhProjectionInverse);
		matrix("dhPreviousProjection", this.w.dhPreviousProjection);
	}

	@Test
	void theDepthConventionIsTheTargetsFourNumbersInOrder() {
		vec4("of_DepthConv", this.w.depthConvention);
	}

	@Test
	void theEngineSettingsAndTheAtlas() {
		integer("renderStage", this.w.renderStage);
		integer("currentColorSpace", this.w.colorSpace);
		integer("textureFilteringMode", this.w.textureFilteringMode);
		// The anisotropy is a float on the way in and the whole number a pack compares on the way out.
		integer("anisotropicFiltering", (int) this.w.anisotropy);
		scalar("chunkFadeTimeInv", this.w.chunkFadeTimeInv);
		scalar("ambientOcclusionLevel", this.w.ambientOcclusionLevel);
		scalar("noiseTextureResolution", this.w.noiseTextureResolution);
		scalar("alphaTestRef", this.w.passAlphaTest);

		assertArrayEquals(new int[] { this.w.atlasWidth, this.w.atlasHeight },
				ValueReads.ints(ValueReads.read(this.engine, "atlasSize", this.w), 2), "atlasSize");
	}

	@Test
	void theConstantsThatNothingInTheWorldChanges() {
		scalar("pi", (float) Math.PI);
		vec4("entityColor", new Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
		integer("entityId", 0);
		integer("blockEntityId", -1);
		integer("currentRenderedItemId", -1);
	}

	@Test
	void theWeatherAndTheFog() {
		scalar("rainStrength", this.w.rainStrength);
		scalar("thunderStrength", this.w.thunderStrength);
		vec3("fogColor", new Vector3f(this.w.fogR, this.w.fogG, this.w.fogB));
		scalar("fogStart", this.w.fogStart);
		scalar("fogEnd", this.w.fogEnd);
		scalar("fogDensity", this.w.fogDensity);
		integer("fogMode", this.w.fogMode);
		integer("fogShape", this.w.fogShape);
	}

	@Test
	void theWorldAndTheBiome() {
		integer("bedrockLevel", this.w.bedrockLevel);
		integer("heightLimit", this.w.heightLimit);
		integer("logicalHeightLimit", this.w.logicalHeightLimit);
		scalar("ambientLight", this.w.ambientLight);
		scalar("cloudHeight", this.w.cloudHeight);
		integer("seaLevel", this.w.seaLevel);
		integer("biome", this.w.biomeId);
		integer("biome_category", this.w.biomeCategory);
		integer("biome_precipitation", this.w.biomePrecipitation);
		scalar("rainfall", this.w.rainfall);
		scalar("temperature", this.w.temperature);
	}

	@Test
	void theStateOfThePlayersEyesAndHead() {
		integer("isEyeInWater", this.w.isEyeInWater);
		scalar("blindness", this.w.blindness);
		scalar("darknessFactor", this.w.darknessFactor);
		scalar("darknessLightFactor", this.w.darknessLightFactor);
		scalar("nightVision", this.w.nightVision);
		scalar("screenBrightness", this.w.screenBrightness);
		scalar("playerMood", this.w.playerMood);
		scalar("constantMood", this.w.constantMood);

		assertArrayEquals(new int[] { this.w.eyeBrightnessBlock, this.w.eyeBrightnessSky },
				ValueReads.ints(ValueReads.read(this.engine, "eyeBrightness", this.w), 2), "block first, then sky");
	}

	@Test
	void theVectorsThePlayerAndTheirVehicleFace() {
		vec3("playerLookVector", this.w.playerLookVector);
		vec3("playerBodyVector", this.w.playerBodyVector);
		vec3("vehicleLookVector", this.w.vehicleLookVector);
		vec3("relativeVehiclePosition", this.w.relativeVehiclePosition);
		vec3("eyePosition", this.w.eyePosition);
		vec3("cameraPosition", this.w.cameraPosition);
		vec3("previousCameraPosition", this.w.previousCameraPosition);
		integer("vehicleId", this.w.vehicleId);
	}

	@Test
	void theHealthHungerArmorAndAirAreEachTheirOwnAccessorWithTheCurrentAndTheMaximumApart() {
		scalar("currentPlayerHealth", this.w.playerHealth);
		scalar("maxPlayerHealth", this.w.playerMaxHealth);
		scalar("currentPlayerHunger", this.w.playerHunger);
		scalar("maxPlayerHunger", this.w.playerMaxHunger);
		scalar("currentPlayerArmor", this.w.playerArmor);
		scalar("maxPlayerArmor", this.w.playerMaxArmor);
		scalar("currentPlayerAir", this.w.playerAir);
		scalar("maxPlayerAir", this.w.playerMaxAir);
	}

	@Test
	void whatTheCrosshairIsOnAndWhatTheHandsHold() {
		vec3("currentSelectedBlockPos", this.w.selectedBlockPos);
		integer("currentSelectedBlockId", this.w.selectedBlockId);
		vec4("lightningBoltPosition", this.w.lightningBoltPosition);
		integer("heldItemId", this.w.heldItemId);
		integer("heldItemId2", this.w.heldItemId2);
		integer("heldBlockLightValue", this.w.heldBlockLight);
		integer("heldBlockLightValue2", this.w.heldBlockLight2);
	}

	@Test
	void theFogDensityIsNeverNegative() {
		this.w.fogDensity = -0.5F;
		scalar("fogDensity", 0.0F);

		this.w.fogDensity = 0.375F;
		scalar("fogDensity", 0.375F);
	}

	@Test
	void everyFlagReadsItsOwnAccessorAndNoOtherOnesWhateverTheTypeThePackDeclared() {
		Map<String, Consumer<FakeWorld>> flags = new TreeMap<>();
		flags.put("is_sneaking", world -> world.sneaking = true);
		flags.put("is_sprinting", world -> world.sprinting = true);
		flags.put("is_hurt", world -> world.hurt = true);
		flags.put("is_invisible", world -> world.invisible = true);
		flags.put("is_burning", world -> world.burning = true);
		flags.put("is_on_ground", world -> world.onGround = true);
		flags.put("hideGUI", world -> world.hideGui = true);
		flags.put("isRightHanded", world -> world.rightHanded = true);
		flags.put("isSpectator", world -> world.spectator = true);
		flags.put("firstPersonCamera", world -> world.firstPerson = true);
		flags.put("isElytraFlying", world -> world.elytraFlying = true);
		flags.put("isRiding", world -> world.riding = true);
		flags.put("feetInWater", world -> world.feetInWater = true);
		flags.put("inSwimmingAnimation", world -> world.swimming = true);
		flags.put("vehicleInWater", world -> world.vehicleInWater = true);
		flags.put("heavyFog", world -> world.heavyFog = true);
		flags.put("hasCeiling", world -> world.hasCeiling = true);
		flags.put("hasSkylight", world -> world.hasSkylight = true);

		for (String on : flags.keySet()) {
			FakeWorld world = new FakeWorld();
			// The defaults of a fake world include a right hand, a first person camera and a sky.
			world.rightHanded = false;
			world.firstPerson = false;
			world.hasSkylight = false;
			flags.get(on).accept(world);

			List<String> reading = new ArrayList<>();
			for (String name : flags.keySet()) {
				int value = ValueReads.ints(ValueReads.read(this.engine, name, world), 1)[0];
				if (value != 0) {
					reading.add(name + "=" + value);
				}
			}

			assertEquals(List.of(on + "=1"), reading, "with only " + on + " set");
		}
	}
}
