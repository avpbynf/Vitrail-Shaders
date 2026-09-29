package dev.vitrail.uniform;

import java.util.Random;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * A {@link WorldState} for tests: one public field per accessor and nothing behind them, so that
 * a test scripts exactly the numbers a source may read and a read allocates nothing.
 * <p>
 * {@code new FakeWorld()} is a daytime overworld with the camera at rest. {@link #distinct()} gives
 * every accessor a number of its own, which is what tells a source that reads its neighbour's
 * accessor from one that reads its own, and {@link #random(Random)} draws a whole world from a
 * seed. A field is what the accessor of the same name returns; the joml fields are held by
 * reference, so a test changes them in place.
 */
public final class FakeWorld implements WorldState {

	public final Matrix4f gbufferModelView = new Matrix4f();
	public final Matrix4f gbufferModelViewInverse = new Matrix4f();
	public final Matrix4f passModelView = new Matrix4f();
	public final Matrix4f passModelViewInverse = new Matrix4f();
	public final Matrix4f cameraBob = new Matrix4f();
	public final Matrix4f passProjection = new Matrix4f();
	public final Matrix4f passProjectionInverse = new Matrix4f();
	public final Vector4f passColour = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	public float passAlphaTest = 0.1F;
	public final Matrix4f gbufferProjection = new Matrix4f();
	public final Matrix4f gbufferProjectionInverse = new Matrix4f();
	public final Matrix4f gbufferPreviousModelView = new Matrix4f();
	public final Matrix4f gbufferPreviousProjection = new Matrix4f();
	public final Matrix4f shadowModelView = new Matrix4f();
	public final Matrix4f shadowModelViewInverse = new Matrix4f();
	public final Matrix4f shadowProjection = new Matrix4f();
	public final Matrix4f shadowProjectionInverse = new Matrix4f();
	public final Matrix4f drawnShadowModelView = new Matrix4f();
	public final Matrix4f drawnShadowModelViewInverse = new Matrix4f();
	public final Matrix4f drawnShadowProjection = new Matrix4f();
	public final Matrix4f drawnShadowProjectionInverse = new Matrix4f();
	public final Matrix4f dhProjection = new Matrix4f();
	public final Matrix4f dhProjectionInverse = new Matrix4f();
	public final Matrix4f drawnDistantProjection = new Matrix4f();
	public final Matrix4f dhPreviousProjection = new Matrix4f();
	public float dhNearPlane = 0.01F;
	public float dhFarPlane = 0.01F;
	public int dhRenderDistance = 0;
	public float near = 0.05F;
	public float far = 1024.0F;
	public final Vector4f depthConvention = new Vector4f(0.5F, 0.5F, 1.0F, 0.0F);
	public int frameCounter = 1;
	public float frameTime = 0.016F;
	public float frameTimeCounter = 12.5F;
	public float partialTick = 0.5F;
	public long gameTime = 6000L;
	public float glintAlpha = 1.0F;
	public float viewWidth = 1920.0F;
	public float viewHeight = 1080.0F;
	public final Vector3d cameraPosition = new Vector3d();
	public final Vector3d previousCameraPosition = new Vector3d();
	public final Vector3d cameraPositionUnshifted = new Vector3d();
	public final Vector3d previousCameraPositionUnshifted = new Vector3d();
	public long worldTime = 6000L;
	public long worldDay = 3L;
	public int moonPhase = 0;
	public float sunAngleDegrees = 0.0F;
	public float moonAngleDegrees = 180.0F;
	public float sunPathRotation = 0.0F;
	public float rainStrength = 0.0F;
	public float thunderStrength = 0.0F;
	public int skyColorPacked = 0;
	public float cloudHeight = 192.0F;
	public int bedrockLevel = -64;
	public int heightLimit = 384;
	public int logicalHeightLimit = 384;
	public boolean hasCeiling = false;
	public boolean hasSkylight = true;
	public float ambientLight = 0.0F;
	public int dimensionOrdinal = 0;
	public int seaLevel = 63;
	public int biomeId = 0;
	public int biomeCategory = 5;
	public int biomePrecipitation = 0;
	public float rainfall = 0.4F;
	public float temperature = 0.8F;
	public boolean hasEndFlash = false;
	public boolean endFlashShadows = false;
	public float endFlashXAngleDegrees = 0.0F;
	public float endFlashYAngleDegrees = 0.0F;
	public float endFlashIntensity = 0.0F;
	public float previousEndFlashIntensity = 0.0F;
	public float fogR = 0.7F;
	public float fogG = 0.8F;
	public float fogB = 1.0F;
	public float fogA = 1.0F;
	public float fogStart = 0.0F;
	public float fogEnd = 256.0F;
	public float fogDensity = 0.0F;
	public int fogMode = 0;
	public int fogShape = 0;
	public boolean heavyFog = false;
	public int isEyeInWater = 0;
	public final Vector3d eyePosition = new Vector3d();
	public final Vector3f playerLookVector = new Vector3f(0.0F, 0.0F, -1.0F);
	public final Vector3f playerBodyVector = new Vector3f(0.0F, 0.0F, -1.0F);
	public float blindness = 0.0F;
	public float darknessFactor = 0.0F;
	public float nightVision = 0.0F;
	public float darknessLightFactor = 0.0F;
	public int cameraEntityTickCount = 0;
	public float screenBrightness = 0.5F;
	public float playerMood = 0.0F;
	public float constantMood = 0.0F;
	public int eyeBrightnessBlock = 0;
	public int eyeBrightnessSky = 240;
	public boolean sneaking = false;
	public boolean sprinting = false;
	public boolean hurt = false;
	public boolean invisible = false;
	public boolean burning = false;
	public boolean onGround = false;
	public boolean hideGui = false;
	public boolean rightHanded = true;
	public boolean spectator = false;
	public boolean firstPerson = true;
	public boolean elytraFlying = false;
	public boolean riding = false;
	public boolean feetInWater = false;
	public boolean swimming = false;
	public boolean vehicleInWater = false;
	public int vehicleId = 0;
	public final Vector3d vehicleLookVector = new Vector3d();
	public final Vector3d relativeVehiclePosition = new Vector3d();
	public float playerHealth = 1.0F;
	public float playerMaxHealth = 20.0F;
	public float playerHunger = 1.0F;
	public float playerMaxHunger = 20.0F;
	public float playerArmor = 0.0F;
	public float playerMaxArmor = 20.0F;
	public float playerAir = 1.0F;
	public float playerMaxAir = 300.0F;
	public final Vector3f selectedBlockPos = new Vector3f(-256.0F, -256.0F, -256.0F);
	public int selectedBlockId = 0;
	public final Vector4f lightningBoltPosition = new Vector4f();
	public int heldItemId = -1;
	public int heldItemId2 = -1;
	public int heldBlockLight = 0;
	public int heldBlockLight2 = 0;
	public int atlasWidth = 1024;
	public int atlasHeight = 1024;
	public int renderStage = 0;
	public float anisotropy = 1.0F;
	public int colorSpace = 0;
	public int textureFilteringMode = 0;
	public float chunkFadeTimeInv = 0.0F;
	public float wetnessHalfLife = 10.0F;
	public float drynessHalfLife = 200.0F;
	public float eyeBrightnessHalfLife = 10.0F;
	public float centerDepthHalfLife = 1.0F;
	public float ambientOcclusionLevel = 1.0F;
	public float noiseTextureResolution = 256.0F;
	public float shadowDistance = 128.0F;
	public float shadowNearPlane = 0.05F;
	public float shadowFarPlane = 256.0F;
	public float shadowIntervalSize = 2.0F;

	/** What {@link #distantDepthPair} answers, and the pair it fills in when that is true. */
	public boolean distantDepthPairAnswer;
	public final Vector2f distantDepthPairValue = new Vector2f();

	@Override public Matrix4fc gbufferModelView() { return this.gbufferModelView; }
	@Override public Matrix4fc gbufferModelViewInverse() { return this.gbufferModelViewInverse; }
	@Override public Matrix4fc passModelView() { return this.passModelView; }
	@Override public Matrix4fc passModelViewInverse() { return this.passModelViewInverse; }
	@Override public Matrix4fc cameraBob() { return this.cameraBob; }
	@Override public Matrix4fc passProjection() { return this.passProjection; }
	@Override public Matrix4fc passProjectionInverse() { return this.passProjectionInverse; }
	@Override public Vector4fc passColour() { return this.passColour; }
	@Override public float passAlphaTest() { return this.passAlphaTest; }
	@Override public Matrix4fc gbufferProjection() { return this.gbufferProjection; }
	@Override public Matrix4fc gbufferProjectionInverse() { return this.gbufferProjectionInverse; }
	@Override public Matrix4fc gbufferPreviousModelView() { return this.gbufferPreviousModelView; }
	@Override public Matrix4fc gbufferPreviousProjection() { return this.gbufferPreviousProjection; }
	@Override public Matrix4fc shadowModelView() { return this.shadowModelView; }
	@Override public Matrix4fc shadowModelViewInverse() { return this.shadowModelViewInverse; }
	@Override public Matrix4fc shadowProjection() { return this.shadowProjection; }
	@Override public Matrix4fc shadowProjectionInverse() { return this.shadowProjectionInverse; }
	@Override public Matrix4fc drawnShadowModelView() { return this.drawnShadowModelView; }
	@Override public Matrix4fc drawnShadowModelViewInverse() { return this.drawnShadowModelViewInverse; }
	@Override public Matrix4fc drawnShadowProjection() { return this.drawnShadowProjection; }
	@Override public Matrix4fc drawnShadowProjectionInverse() { return this.drawnShadowProjectionInverse; }
	@Override public Matrix4fc dhProjection() { return this.dhProjection; }
	@Override public Matrix4fc dhProjectionInverse() { return this.dhProjectionInverse; }
	@Override public Matrix4fc drawnDistantProjection() { return this.drawnDistantProjection; }
	@Override public Matrix4fc dhPreviousProjection() { return this.dhPreviousProjection; }
	@Override public float dhNearPlane() { return this.dhNearPlane; }
	@Override public float dhFarPlane() { return this.dhFarPlane; }
	@Override public int dhRenderDistance() { return this.dhRenderDistance; }
	@Override public float near() { return this.near; }
	@Override public float far() { return this.far; }
	@Override public Vector4fc depthConvention() { return this.depthConvention; }
	@Override public int frameCounter() { return this.frameCounter; }
	@Override public float frameTime() { return this.frameTime; }
	@Override public float frameTimeCounter() { return this.frameTimeCounter; }
	@Override public float partialTick() { return this.partialTick; }
	@Override public long gameTime() { return this.gameTime; }
	@Override public float glintAlpha() { return this.glintAlpha; }
	@Override public float viewWidth() { return this.viewWidth; }
	@Override public float viewHeight() { return this.viewHeight; }
	@Override public Vector3dc cameraPosition() { return this.cameraPosition; }
	@Override public Vector3dc previousCameraPosition() { return this.previousCameraPosition; }
	@Override public Vector3dc cameraPositionUnshifted() { return this.cameraPositionUnshifted; }
	@Override public Vector3dc previousCameraPositionUnshifted() { return this.previousCameraPositionUnshifted; }
	@Override public long worldTime() { return this.worldTime; }
	@Override public long worldDay() { return this.worldDay; }
	@Override public int moonPhase() { return this.moonPhase; }
	@Override public float sunAngleDegrees() { return this.sunAngleDegrees; }
	@Override public float moonAngleDegrees() { return this.moonAngleDegrees; }
	@Override public float sunPathRotation() { return this.sunPathRotation; }
	@Override public float rainStrength() { return this.rainStrength; }
	@Override public float thunderStrength() { return this.thunderStrength; }
	@Override public int skyColorPacked() { return this.skyColorPacked; }
	@Override public float cloudHeight() { return this.cloudHeight; }
	@Override public int bedrockLevel() { return this.bedrockLevel; }
	@Override public int heightLimit() { return this.heightLimit; }
	@Override public int logicalHeightLimit() { return this.logicalHeightLimit; }
	@Override public boolean hasCeiling() { return this.hasCeiling; }
	@Override public boolean hasSkylight() { return this.hasSkylight; }
	@Override public float ambientLight() { return this.ambientLight; }
	@Override public int dimensionOrdinal() { return this.dimensionOrdinal; }
	@Override public int seaLevel() { return this.seaLevel; }
	@Override public int biomeId() { return this.biomeId; }
	@Override public int biomeCategory() { return this.biomeCategory; }
	@Override public int biomePrecipitation() { return this.biomePrecipitation; }
	@Override public float rainfall() { return this.rainfall; }
	@Override public float temperature() { return this.temperature; }
	@Override public boolean hasEndFlash() { return this.hasEndFlash; }
	@Override public boolean endFlashShadows() { return this.endFlashShadows; }
	@Override public float endFlashXAngleDegrees() { return this.endFlashXAngleDegrees; }
	@Override public float endFlashYAngleDegrees() { return this.endFlashYAngleDegrees; }
	@Override public float endFlashIntensity() { return this.endFlashIntensity; }
	@Override public float previousEndFlashIntensity() { return this.previousEndFlashIntensity; }
	@Override public float fogR() { return this.fogR; }
	@Override public float fogG() { return this.fogG; }
	@Override public float fogB() { return this.fogB; }
	@Override public float fogA() { return this.fogA; }
	@Override public float fogStart() { return this.fogStart; }
	@Override public float fogEnd() { return this.fogEnd; }
	@Override public float fogDensity() { return this.fogDensity; }
	@Override public int fogMode() { return this.fogMode; }
	@Override public int fogShape() { return this.fogShape; }
	@Override public boolean heavyFog() { return this.heavyFog; }
	@Override public int isEyeInWater() { return this.isEyeInWater; }
	@Override public Vector3dc eyePosition() { return this.eyePosition; }
	@Override public Vector3fc playerLookVector() { return this.playerLookVector; }
	@Override public Vector3fc playerBodyVector() { return this.playerBodyVector; }
	@Override public float blindness() { return this.blindness; }
	@Override public float darknessFactor() { return this.darknessFactor; }
	@Override public float nightVision() { return this.nightVision; }
	@Override public float darknessLightFactor() { return this.darknessLightFactor; }
	@Override public int cameraEntityTickCount() { return this.cameraEntityTickCount; }
	@Override public float screenBrightness() { return this.screenBrightness; }
	@Override public float playerMood() { return this.playerMood; }
	@Override public float constantMood() { return this.constantMood; }
	@Override public int eyeBrightnessBlock() { return this.eyeBrightnessBlock; }
	@Override public int eyeBrightnessSky() { return this.eyeBrightnessSky; }
	@Override public boolean sneaking() { return this.sneaking; }
	@Override public boolean sprinting() { return this.sprinting; }
	@Override public boolean hurt() { return this.hurt; }
	@Override public boolean invisible() { return this.invisible; }
	@Override public boolean burning() { return this.burning; }
	@Override public boolean onGround() { return this.onGround; }
	@Override public boolean hideGui() { return this.hideGui; }
	@Override public boolean rightHanded() { return this.rightHanded; }
	@Override public boolean spectator() { return this.spectator; }
	@Override public boolean firstPerson() { return this.firstPerson; }
	@Override public boolean elytraFlying() { return this.elytraFlying; }
	@Override public boolean riding() { return this.riding; }
	@Override public boolean feetInWater() { return this.feetInWater; }
	@Override public boolean swimming() { return this.swimming; }
	@Override public boolean vehicleInWater() { return this.vehicleInWater; }
	@Override public int vehicleId() { return this.vehicleId; }
	@Override public Vector3dc vehicleLookVector() { return this.vehicleLookVector; }
	@Override public Vector3dc relativeVehiclePosition() { return this.relativeVehiclePosition; }
	@Override public float playerHealth() { return this.playerHealth; }
	@Override public float playerMaxHealth() { return this.playerMaxHealth; }
	@Override public float playerHunger() { return this.playerHunger; }
	@Override public float playerMaxHunger() { return this.playerMaxHunger; }
	@Override public float playerArmor() { return this.playerArmor; }
	@Override public float playerMaxArmor() { return this.playerMaxArmor; }
	@Override public float playerAir() { return this.playerAir; }
	@Override public float playerMaxAir() { return this.playerMaxAir; }
	@Override public Vector3fc selectedBlockPos() { return this.selectedBlockPos; }
	@Override public int selectedBlockId() { return this.selectedBlockId; }
	@Override public Vector4fc lightningBoltPosition() { return this.lightningBoltPosition; }
	@Override public int heldItemId() { return this.heldItemId; }
	@Override public int heldItemId2() { return this.heldItemId2; }
	@Override public int heldBlockLight() { return this.heldBlockLight; }
	@Override public int heldBlockLight2() { return this.heldBlockLight2; }
	@Override public int atlasWidth() { return this.atlasWidth; }
	@Override public int atlasHeight() { return this.atlasHeight; }
	@Override public int renderStage() { return this.renderStage; }
	@Override public float anisotropy() { return this.anisotropy; }
	@Override public int colorSpace() { return this.colorSpace; }
	@Override public int textureFilteringMode() { return this.textureFilteringMode; }
	@Override public float chunkFadeTimeInv() { return this.chunkFadeTimeInv; }
	@Override public float wetnessHalfLife() { return this.wetnessHalfLife; }
	@Override public float drynessHalfLife() { return this.drynessHalfLife; }
	@Override public float eyeBrightnessHalfLife() { return this.eyeBrightnessHalfLife; }
	@Override public float centerDepthHalfLife() { return this.centerDepthHalfLife; }
	@Override public float ambientOcclusionLevel() { return this.ambientOcclusionLevel; }
	@Override public float noiseTextureResolution() { return this.noiseTextureResolution; }
	@Override public float shadowDistance() { return this.shadowDistance; }
	@Override public float shadowNearPlane() { return this.shadowNearPlane; }
	@Override public float shadowFarPlane() { return this.shadowFarPlane; }
	@Override public float shadowIntervalSize() { return this.shadowIntervalSize; }
	@Override
	public boolean distantDepthPair(Vector2f dest) {
		if (this.distantDepthPairAnswer) {
			dest.set(this.distantDepthPairValue);
		}

		return this.distantDepthPairAnswer;
	}

	/** Every accessor answers a number no other accessor answers; the flags stay false. */
	public static FakeWorld distinct() {
		FakeWorld w = new FakeWorld();
		fill(w.gbufferModelView, 1000);
		fill(w.gbufferModelViewInverse, 2000);
		fill(w.passModelView, 3000);
		fill(w.passModelViewInverse, 4000);
		fill(w.cameraBob, 5000);
		fill(w.passProjection, 6000);
		fill(w.passProjectionInverse, 7000);
		w.passColour.set(8.0F, 8.25F, 8.5F, 8.75F);
		w.passAlphaTest = 109.25F;
		fill(w.gbufferProjection, 10000);
		fill(w.gbufferProjectionInverse, 11000);
		fill(w.gbufferPreviousModelView, 12000);
		fill(w.gbufferPreviousProjection, 13000);
		fill(w.shadowModelView, 14000);
		fill(w.shadowModelViewInverse, 15000);
		fill(w.shadowProjection, 16000);
		fill(w.shadowProjectionInverse, 17000);
		fill(w.drawnShadowModelView, 18000);
		fill(w.drawnShadowModelViewInverse, 19000);
		fill(w.drawnShadowProjection, 20000);
		fill(w.drawnShadowProjectionInverse, 21000);
		fill(w.dhProjection, 22000);
		fill(w.dhProjectionInverse, 23000);
		fill(w.drawnDistantProjection, 24000);
		fill(w.dhPreviousProjection, 25000);
		w.dhNearPlane = 126.25F;
		w.dhFarPlane = 127.25F;
		w.dhRenderDistance = 1028;
		w.near = 129.25F;
		w.far = 130.25F;
		w.depthConvention.set(31.0F, 31.25F, 31.5F, 31.75F);
		w.frameCounter = 1032;
		w.frameTime = 133.25F;
		w.frameTimeCounter = 134.25F;
		w.partialTick = 135.25F;
		w.gameTime = 100036L;
		w.glintAlpha = 137.25F;
		w.viewWidth = 138.25F;
		w.viewHeight = 139.25F;
		w.cameraPosition.set(40.0, 40.25, 40.5);
		w.previousCameraPosition.set(41.0, 41.25, 41.5);
		w.cameraPositionUnshifted.set(42.0, 42.25, 42.5);
		w.previousCameraPositionUnshifted.set(43.0, 43.25, 43.5);
		w.worldTime = 100044L;
		w.worldDay = 100045L;
		w.moonPhase = 1046;
		w.sunAngleDegrees = 147.25F;
		w.moonAngleDegrees = 148.25F;
		w.sunPathRotation = 149.25F;
		w.rainStrength = 150.25F;
		w.thunderStrength = 151.25F;
		w.skyColorPacked = 1052;
		w.cloudHeight = 153.25F;
		w.bedrockLevel = 1054;
		w.heightLimit = 1055;
		w.logicalHeightLimit = 1056;
		w.ambientLight = 159.25F;
		w.dimensionOrdinal = 1060;
		w.seaLevel = 1061;
		w.biomeId = 1062;
		w.biomeCategory = 1063;
		w.biomePrecipitation = 1064;
		w.rainfall = 165.25F;
		w.temperature = 166.25F;
		w.endFlashXAngleDegrees = 169.25F;
		w.endFlashYAngleDegrees = 170.25F;
		w.endFlashIntensity = 171.25F;
		w.previousEndFlashIntensity = 172.25F;
		w.fogR = 173.25F;
		w.fogG = 174.25F;
		w.fogB = 175.25F;
		w.fogA = 176.25F;
		w.fogStart = 177.25F;
		w.fogEnd = 178.25F;
		w.fogDensity = 179.25F;
		w.fogMode = 1080;
		w.fogShape = 1081;
		w.isEyeInWater = 1083;
		w.eyePosition.set(84.0, 84.25, 84.5);
		w.playerLookVector.set(85.0F, 85.25F, 85.5F);
		w.playerBodyVector.set(86.0F, 86.25F, 86.5F);
		w.blindness = 187.25F;
		w.darknessFactor = 188.25F;
		w.nightVision = 189.25F;
		w.darknessLightFactor = 190.25F;
		w.cameraEntityTickCount = 1091;
		w.screenBrightness = 192.25F;
		w.playerMood = 193.25F;
		w.constantMood = 194.25F;
		w.eyeBrightnessBlock = 1095;
		w.eyeBrightnessSky = 1096;
		w.vehicleId = 1112;
		w.vehicleLookVector.set(113.0, 113.25, 113.5);
		w.relativeVehiclePosition.set(114.0, 114.25, 114.5);
		w.playerHealth = 215.25F;
		w.playerMaxHealth = 216.25F;
		w.playerHunger = 217.25F;
		w.playerMaxHunger = 218.25F;
		w.playerArmor = 219.25F;
		w.playerMaxArmor = 220.25F;
		w.playerAir = 221.25F;
		w.playerMaxAir = 222.25F;
		w.selectedBlockPos.set(123.0F, 123.25F, 123.5F);
		w.selectedBlockId = 1124;
		w.lightningBoltPosition.set(125.0F, 125.25F, 125.5F, 125.75F);
		w.heldItemId = 1126;
		w.heldItemId2 = 1127;
		w.heldBlockLight = 1128;
		w.heldBlockLight2 = 1129;
		w.atlasWidth = 1130;
		w.atlasHeight = 1131;
		w.renderStage = 1132;
		w.anisotropy = 233.25F;
		w.colorSpace = 1134;
		w.textureFilteringMode = 1135;
		w.chunkFadeTimeInv = 236.25F;
		w.wetnessHalfLife = 237.25F;
		w.drynessHalfLife = 238.25F;
		w.eyeBrightnessHalfLife = 239.25F;
		w.centerDepthHalfLife = 240.25F;
		w.ambientOcclusionLevel = 241.25F;
		w.noiseTextureResolution = 242.25F;
		w.shadowDistance = 243.25F;
		w.shadowNearPlane = 244.25F;
		w.shadowFarPlane = 245.25F;
		w.shadowIntervalSize = 246.25F;
		w.distantDepthPairAnswer = true;
		w.distantDepthPairValue.set(3.5F, 0.25F);

		return w;
	}

	/** Every accessor drawn from the seed, in ranges a real frame stays inside. */
	public static FakeWorld random(Random rng) {
		FakeWorld w = new FakeWorld();
		randomise(w.gbufferModelView, rng);
		randomise(w.gbufferModelViewInverse, rng);
		randomise(w.passModelView, rng);
		randomise(w.passModelViewInverse, rng);
		randomise(w.cameraBob, rng);
		randomise(w.passProjection, rng);
		randomise(w.passProjectionInverse, rng);
		w.passColour.set(rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F),
				rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F));
		w.passAlphaTest = rng.nextFloat(-500.0F, 500.0F);
		randomise(w.gbufferProjection, rng);
		randomise(w.gbufferProjectionInverse, rng);
		randomise(w.gbufferPreviousModelView, rng);
		randomise(w.gbufferPreviousProjection, rng);
		randomise(w.shadowModelView, rng);
		randomise(w.shadowModelViewInverse, rng);
		randomise(w.shadowProjection, rng);
		randomise(w.shadowProjectionInverse, rng);
		randomise(w.drawnShadowModelView, rng);
		randomise(w.drawnShadowModelViewInverse, rng);
		randomise(w.drawnShadowProjection, rng);
		randomise(w.drawnShadowProjectionInverse, rng);
		randomise(w.dhProjection, rng);
		randomise(w.dhProjectionInverse, rng);
		randomise(w.drawnDistantProjection, rng);
		randomise(w.dhPreviousProjection, rng);
		w.dhNearPlane = rng.nextFloat(-500.0F, 500.0F);
		w.dhFarPlane = rng.nextFloat(-500.0F, 500.0F);
		w.dhRenderDistance = rng.nextInt(-300, 4000);
		w.near = rng.nextFloat(-500.0F, 500.0F);
		w.far = rng.nextFloat(-500.0F, 500.0F);
		w.depthConvention.set(rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F),
				rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F));
		w.frameCounter = rng.nextInt(-300, 4000);
		w.frameTime = rng.nextFloat(-500.0F, 500.0F);
		w.frameTimeCounter = rng.nextFloat(-500.0F, 500.0F);
		w.partialTick = rng.nextFloat(-500.0F, 500.0F);
		w.gameTime = rng.nextLong(0L, 5_000_000L);
		w.glintAlpha = rng.nextFloat(-500.0F, 500.0F);
		w.viewWidth = rng.nextFloat(-500.0F, 500.0F);
		w.viewHeight = rng.nextFloat(-500.0F, 500.0F);
		w.cameraPosition.set(rng.nextDouble(-3e7, 3e7), rng.nextDouble(-64.0, 320.0),
				rng.nextDouble(-3e7, 3e7));
		w.previousCameraPosition.set(rng.nextDouble(-3e7, 3e7), rng.nextDouble(-64.0, 320.0),
				rng.nextDouble(-3e7, 3e7));
		w.cameraPositionUnshifted.set(rng.nextDouble(-3e7, 3e7), rng.nextDouble(-64.0, 320.0),
				rng.nextDouble(-3e7, 3e7));
		w.previousCameraPositionUnshifted.set(rng.nextDouble(-3e7, 3e7), rng.nextDouble(-64.0, 320.0),
				rng.nextDouble(-3e7, 3e7));
		w.worldTime = rng.nextLong(0L, 5_000_000L);
		w.worldDay = rng.nextLong(0L, 5_000_000L);
		w.moonPhase = rng.nextInt(-300, 4000);
		w.sunAngleDegrees = rng.nextFloat(-500.0F, 500.0F);
		w.moonAngleDegrees = rng.nextFloat(-500.0F, 500.0F);
		w.sunPathRotation = rng.nextFloat(-500.0F, 500.0F);
		w.rainStrength = rng.nextFloat(-500.0F, 500.0F);
		w.thunderStrength = rng.nextFloat(-500.0F, 500.0F);
		w.skyColorPacked = rng.nextInt(-300, 4000);
		w.cloudHeight = rng.nextFloat(-500.0F, 500.0F);
		w.bedrockLevel = rng.nextInt(-300, 4000);
		w.heightLimit = rng.nextInt(-300, 4000);
		w.logicalHeightLimit = rng.nextInt(-300, 4000);
		w.hasCeiling = rng.nextBoolean();
		w.hasSkylight = rng.nextBoolean();
		w.ambientLight = rng.nextFloat(-500.0F, 500.0F);
		w.dimensionOrdinal = rng.nextInt(-300, 4000);
		w.seaLevel = rng.nextInt(-300, 4000);
		w.biomeId = rng.nextInt(-300, 4000);
		w.biomeCategory = rng.nextInt(-300, 4000);
		w.biomePrecipitation = rng.nextInt(-300, 4000);
		w.rainfall = rng.nextFloat(-500.0F, 500.0F);
		w.temperature = rng.nextFloat(-500.0F, 500.0F);
		w.hasEndFlash = rng.nextBoolean();
		w.endFlashShadows = rng.nextBoolean();
		w.endFlashXAngleDegrees = rng.nextFloat(-500.0F, 500.0F);
		w.endFlashYAngleDegrees = rng.nextFloat(-500.0F, 500.0F);
		w.endFlashIntensity = rng.nextFloat(-500.0F, 500.0F);
		w.previousEndFlashIntensity = rng.nextFloat(-500.0F, 500.0F);
		w.fogR = rng.nextFloat(-500.0F, 500.0F);
		w.fogG = rng.nextFloat(-500.0F, 500.0F);
		w.fogB = rng.nextFloat(-500.0F, 500.0F);
		w.fogA = rng.nextFloat(-500.0F, 500.0F);
		w.fogStart = rng.nextFloat(-500.0F, 500.0F);
		w.fogEnd = rng.nextFloat(-500.0F, 500.0F);
		w.fogDensity = rng.nextFloat(-500.0F, 500.0F);
		w.fogMode = rng.nextInt(-300, 4000);
		w.fogShape = rng.nextInt(-300, 4000);
		w.heavyFog = rng.nextBoolean();
		w.isEyeInWater = rng.nextInt(-300, 4000);
		w.eyePosition.set(rng.nextDouble(-3e7, 3e7), rng.nextDouble(-64.0, 320.0),
				rng.nextDouble(-3e7, 3e7));
		w.playerLookVector.set(rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F),
				rng.nextFloat(-1.0F, 1.0F));
		w.playerBodyVector.set(rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F),
				rng.nextFloat(-1.0F, 1.0F));
		w.blindness = rng.nextFloat(-500.0F, 500.0F);
		w.darknessFactor = rng.nextFloat(-500.0F, 500.0F);
		w.nightVision = rng.nextFloat(-500.0F, 500.0F);
		w.darknessLightFactor = rng.nextFloat(-500.0F, 500.0F);
		w.cameraEntityTickCount = rng.nextInt(-300, 4000);
		w.screenBrightness = rng.nextFloat(-500.0F, 500.0F);
		w.playerMood = rng.nextFloat(-500.0F, 500.0F);
		w.constantMood = rng.nextFloat(-500.0F, 500.0F);
		w.eyeBrightnessBlock = rng.nextInt(-300, 4000);
		w.eyeBrightnessSky = rng.nextInt(-300, 4000);
		w.sneaking = rng.nextBoolean();
		w.sprinting = rng.nextBoolean();
		w.hurt = rng.nextBoolean();
		w.invisible = rng.nextBoolean();
		w.burning = rng.nextBoolean();
		w.onGround = rng.nextBoolean();
		w.hideGui = rng.nextBoolean();
		w.rightHanded = rng.nextBoolean();
		w.spectator = rng.nextBoolean();
		w.firstPerson = rng.nextBoolean();
		w.elytraFlying = rng.nextBoolean();
		w.riding = rng.nextBoolean();
		w.feetInWater = rng.nextBoolean();
		w.swimming = rng.nextBoolean();
		w.vehicleInWater = rng.nextBoolean();
		w.vehicleId = rng.nextInt(-300, 4000);
		w.vehicleLookVector.set(rng.nextDouble(-3e7, 3e7), rng.nextDouble(-64.0, 320.0),
				rng.nextDouble(-3e7, 3e7));
		w.relativeVehiclePosition.set(rng.nextDouble(-3e7, 3e7), rng.nextDouble(-64.0, 320.0),
				rng.nextDouble(-3e7, 3e7));
		w.playerHealth = rng.nextFloat(-500.0F, 500.0F);
		w.playerMaxHealth = rng.nextFloat(-500.0F, 500.0F);
		w.playerHunger = rng.nextFloat(-500.0F, 500.0F);
		w.playerMaxHunger = rng.nextFloat(-500.0F, 500.0F);
		w.playerArmor = rng.nextFloat(-500.0F, 500.0F);
		w.playerMaxArmor = rng.nextFloat(-500.0F, 500.0F);
		w.playerAir = rng.nextFloat(-500.0F, 500.0F);
		w.playerMaxAir = rng.nextFloat(-500.0F, 500.0F);
		w.selectedBlockPos.set(rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F),
				rng.nextFloat(-1.0F, 1.0F));
		w.selectedBlockId = rng.nextInt(-300, 4000);
		w.lightningBoltPosition.set(rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F),
				rng.nextFloat(-1.0F, 1.0F), rng.nextFloat(-1.0F, 1.0F));
		w.heldItemId = rng.nextInt(-300, 4000);
		w.heldItemId2 = rng.nextInt(-300, 4000);
		w.heldBlockLight = rng.nextInt(-300, 4000);
		w.heldBlockLight2 = rng.nextInt(-300, 4000);
		w.atlasWidth = rng.nextInt(-300, 4000);
		w.atlasHeight = rng.nextInt(-300, 4000);
		w.renderStage = rng.nextInt(-300, 4000);
		w.anisotropy = rng.nextFloat(-500.0F, 500.0F);
		w.colorSpace = rng.nextInt(-300, 4000);
		w.textureFilteringMode = rng.nextInt(-300, 4000);
		w.chunkFadeTimeInv = rng.nextFloat(-500.0F, 500.0F);
		w.wetnessHalfLife = rng.nextFloat(-500.0F, 500.0F);
		w.drynessHalfLife = rng.nextFloat(-500.0F, 500.0F);
		w.eyeBrightnessHalfLife = rng.nextFloat(-500.0F, 500.0F);
		w.centerDepthHalfLife = rng.nextFloat(-500.0F, 500.0F);
		w.ambientOcclusionLevel = rng.nextFloat(-500.0F, 500.0F);
		w.noiseTextureResolution = rng.nextFloat(-500.0F, 500.0F);
		w.shadowDistance = rng.nextFloat(-500.0F, 500.0F);
		w.shadowNearPlane = rng.nextFloat(-500.0F, 500.0F);
		w.shadowFarPlane = rng.nextFloat(-500.0F, 500.0F);
		w.shadowIntervalSize = rng.nextFloat(-500.0F, 500.0F);
		w.distantDepthPairAnswer = rng.nextBoolean();
		w.distantDepthPairValue.set(rng.nextFloat(), rng.nextFloat());

		return w;
	}

	/** Sixteen numbers that no other matrix of a distinct world shares, column by column. */
	private static void fill(Matrix4f m, int seed) {
		m.set(new float[] {
				seed + 0.5F, seed + 1.5F, seed + 2.5F, seed + 3.5F,
				seed + 4.5F, seed + 5.5F, seed + 6.5F, seed + 7.5F,
				seed + 8.5F, seed + 9.5F, seed + 10.5F, seed + 11.5F,
				seed + 12.5F, seed + 13.5F, seed + 14.5F, seed + 15.5F });
	}

	private static void randomise(Matrix4f m, Random rng) {
		float[] values = new float[16];
		for (int i = 0; i < values.length; i++) {
			values[i] = rng.nextFloat(-10.0F, 10.0F);
		}

		m.set(values);
	}
}
