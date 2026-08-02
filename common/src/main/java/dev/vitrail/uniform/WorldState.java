package dev.vitrail.uniform;

import org.joml.Vector3dc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;

/**
 * Everything the catalogue is allowed to read.
 * <p>
 * Primitives, JOML and String only: no Minecraft type crosses this line, which is what lets the
 * whole catalogue run in the off-game harness against a fixture. Implemented in
 * {@code dev.vitrail.render.FrameState} against the game, and in the harness against a scripted
 * fixture.
 * <p>
 * Adding an accessor here is how a value gets what it needs; reaching around the interface is not.
 * A source that cannot be answered from what is declared here leaves its uniform at zero and named,
 * which is the failure this whole design prefers over a plausible number.
 */
public interface WorldState extends ViewSource {

	// frame and time

	int frameCounter();

	/** The previous frame, in seconds, quantised to the millisecond. Also the smoothing step. */
	float frameTime();

	/** Accumulated frame durations, wrapping at 3600, never the wall clock. */
	float frameTimeCounter();

	float partialTick();

	long gameTime();

	// viewport

	float viewWidth();

	float viewHeight();

	// camera positions, in the published convention

	/** Shifted to stay inside a float, X and Z only. */
	Vector3dc cameraPosition();

	/** Shifted by the same amount as {@link #cameraPosition()}, or reprojection tears. */
	Vector3dc previousCameraPosition();

	Vector3dc cameraPositionUnshifted();

	Vector3dc previousCameraPositionUnshifted();

	// world

	long worldTime();

	long worldDay();

	int moonPhase();

	float sunAngleDegrees();

	float moonAngleDegrees();

	float sunPathRotation();

	float rainStrength();

	float thunderStrength();

	int skyColorPacked();

	float cloudHeight();

	int bedrockLevel();

	int heightLimit();

	int logicalHeightLimit();

	boolean hasCeiling();

	boolean hasSkylight();

	float ambientLight();

	/** 0 overworld, -1 nether, 1 end, 2 other. */
	int dimensionOrdinal();

	int seaLevel();

	int biomeId();

	/** A {@link BiomeCategory} ordinal. */
	int biomeCategory();

	/** 0 none, 1 rain, 2 snow. */
	int biomePrecipitation();

	float rainfall();

	float temperature();

	// end flash, needed by the celestial values

	boolean hasEndFlash();

	/**
	 * Whether the pack asked for its shadows to follow the End flash, which it does by writing
	 * {@code endFlashShadows=true}. A pack that never asked keeps the sun and the moon lighting it
	 * in the End, whatever the flash is doing; the flash's own position is published either way.
	 */
	boolean endFlashShadows();

	float endFlashXAngleDegrees();

	float endFlashYAngleDegrees();

	float endFlashIntensity();

	float previousEndFlashIntensity();

	// fog, from the game's own fog data

	float fogR();

	float fogG();

	float fogB();

	float fogA();

	float fogStart();

	float fogEnd();

	float fogDensity();

	int fogMode();

	int fogShape();

	boolean heavyFog();

	// player

	int isEyeInWater();

	Vector3dc eyePosition();

	Vector3fc playerLookVector();

	Vector3fc playerBodyVector();

	float blindness();

	float darknessFactor();

	float nightVision();

	float darknessLightFactor();

	int cameraEntityTickCount();

	float screenBrightness();

	float playerMood();

	float constantMood();

	int eyeBrightnessBlock();

	int eyeBrightnessSky();

	boolean sneaking();

	boolean sprinting();

	boolean hurt();

	boolean invisible();

	boolean burning();

	boolean onGround();

	boolean hideGui();

	boolean rightHanded();

	boolean spectator();

	boolean firstPerson();

	boolean elytraFlying();

	boolean riding();

	boolean feetInWater();

	boolean swimming();

	boolean vehicleInWater();

	int vehicleId();

	Vector3dc vehicleLookVector();

	Vector3dc relativeVehiclePosition();

	/** -1 outside survival, which is the sentinel a pack tests for. */
	float playerHealth();

	float playerMaxHealth();

	float playerHunger();

	float playerMaxHunger();

	float playerArmor();

	float playerMaxArmor();

	float playerAir();

	float playerMaxAir();

	/** {@code vec3(-256)} when nothing is aimed at. */
	Vector3fc selectedBlockPos();

	int selectedBlockId();

	Vector4fc lightningBoltPosition();

	int heldItemId();

	int heldItemId2();

	int heldBlockLight();

	int heldBlockLight2();

	// engine settings and atlas

	int atlasWidth();

	int atlasHeight();

	int renderStage();

	float anisotropy();

	int colorSpace();

	int textureFilteringMode();

	float chunkFadeTimeInv();

	// pack directives, read once per pack load

	float wetnessHalfLife();

	/**
	 * Always 200 deciseconds. Iris carries {@code drynessHalfLife} as a final field that both the
	 * {@code wetnessHalflife} and the {@code drynessHalflife} directives fail to reach, so no pack
	 * can change it, and packs are written against that. Never return the pack's own directive
	 * here: doing so would make the fall of {@code wetness} four times too fast on the packs that
	 * declare both.
	 */
	float drynessHalfLife();

	float eyeBrightnessHalfLife();

	float centerDepthHalfLife();

	float ambientOcclusionLevel();

	float noiseTextureResolution();

	// shadow directives, needed by the shadow matrices

	float shadowDistance();

	float shadowNearPlane();

	float shadowFarPlane();

	float shadowIntervalSize();
}
