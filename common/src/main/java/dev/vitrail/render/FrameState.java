package dev.vitrail.render;

import dev.vitrail.dh.DhDepth;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.sodium.ShadowTerrain;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.values.FrameSmoothed;
import dev.vitrail.uniform.WorldState;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.EndFlashState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * What the engine can answer this frame, read from the game once and handed to the catalogue.
 * <p>
 * The whole point of the split is that everything on the other side of {@link WorldState} runs
 * without Minecraft, so this is the only place a game type is named. Nothing here is computed on
 * being read: {@link #advance()} is called once per frame, before the first block is written, and
 * every pass of that frame then sees the same numbers. A value that stepped on being read would
 * give two passes of one frame two different times, and the previous frame's matrices would stop
 * meaning anything at all.
 * <p>
 * Reading the whole frame into fields is also what makes the null checks bearable. There is one
 * place that decides there is no level and no player rather than forty, and a getter that cannot
 * fail is a getter nobody has to read twice.
 * <p>
 * Several values are reproduced from Iris (commit b0ae41c) rather than worked out, because a pack
 * is written against what Iris does and not against what the documentation says it does. Where
 * that behaviour is a bug the comment says so, and the bug stays: correcting it would make the
 * image diverge from the one the pack was tuned on.
 */
public final class FrameState implements WorldState {

	/** OptiFine's counter wraps at an hour, which keeps a float's precision usable for noise. */
	private static final float COUNTER_WRAP = 3600.0F;

	/** And the frame counter wraps here. Both numbers are observable by a pack. */
	private static final int FRAME_WRAP = 720720;

	/** What {@code currentSelectedBlockPos} says when the player is not looking at a block. */
	private static final Vector3f NOTHING_SELECTED = new Vector3f(-256.0F);

	/** Iris's own constants for the two maxima that are not read off the player. */
	private static final float MAX_HUNGER = 20.0F;
	private static final float MAX_ARMOR = 50.0F;

	/** What a pass drawn with the fog switched off is told, and what every pass that runs is. */
	private static final int FOG_OFF = 0;
	private static final int FOG_SHAPE_OFF = -1;

	private final ViewMatrices view = new ViewMatrices();

	/** Refilled every frame by the reading of Distant Horizons, and never read outside it. */
	private final Vector2f distantPlanes = new Vector2f();
	private final CameraShift shift = new CameraShift();

	private PackDirectives directives = PackDirectives.defaults();

	/**
	 * What the pass about to write its block draws, which every pass says for itself. Set beside the
	 * depth convention and never carried over: a value left standing from the last pass would be
	 * read by the whole of the chain, since {@code renderStage} is in the table a full screen pass
	 * shares with a geometry one.
	 */
	private RenderStage stage = RenderStage.NONE;

	/** From the pack's own properties file rather than from its GLSL, hence not a directive. */
	private boolean endFlashShadows;

	/** Which world the last frame was in, by identity, so that a change of one is noticed. */
	private Object lastLevel;

	// frame and time
	private long lastFrameNanos;
	private boolean timed;
	private int frameCounter;
	private float frameTime;
	private float frameTimeCounter;
	private float partialTick;
	private long gameTime;
	private float glintAlpha = 1.0F;
	private float viewWidth;
	private float viewHeight;

	// world
	private long worldTime;
	private long worldDay;
	private int moonPhase;
	private float sunAngleDegrees;
	private float moonAngleDegrees;
	private float rainStrength;
	private float thunderStrength;
	private int skyColorPacked;
	private float cloudHeight = 192.0F;
	private int bedrockLevel;
	private int heightLimit = 256;
	private int logicalHeightLimit = 256;
	private boolean hasCeiling;
	private boolean hasSkylight = true;
	private float ambientLight;
	private int dimensionOrdinal;
	private int seaLevel;
	private int biomeId;
	private int biomeCategory;
	private int biomePrecipitation;
	private float rainfall;
	private float temperature;

	// end flash
	private boolean hasEndFlash;
	private float endFlashXAngle;
	private float endFlashYAngle;
	private float endFlashIntensity;
	private float previousEndFlashIntensity;

	// fog
	private final Vector4f fogColor = new Vector4f();
	private float fogStart;
	private float fogEnd;
	private boolean heavyFog;

	// player
	private int isEyeInWater;
	private final Vector3d eyePosition = new Vector3d();
	private final Vector3f playerLookVector = new Vector3f();
	private final Vector3f playerBodyVector = new Vector3f();
	private float blindness;
	private float darknessFactor;
	private float nightVision;
	private float darknessLightFactor;
	private int cameraEntityTickCount;
	private float screenBrightness;
	private float playerMood;
	private int eyeBrightnessBlock;
	private int eyeBrightnessSky;
	private boolean sneaking;
	private boolean sprinting;
	private boolean hurt;
	private boolean invisible;
	private boolean burning;
	private boolean onGround;
	private boolean hideGui;
	private boolean rightHanded = true;
	private boolean spectator;
	private boolean firstPerson = true;
	private boolean elytraFlying;
	private boolean riding;
	private boolean feetInWater;
	private boolean swimming;
	private boolean vehicleInWater;
	private final Vector3d vehicleLookVector = new Vector3d();
	private final Vector3d relativeVehiclePosition = new Vector3d();
	private float playerHealth = -1.0F;
	private float playerMaxHealth = -1.0F;
	private float playerHunger = -1.0F;
	private float playerArmor = -1.0F;
	private float playerAir = -1.0F;
	private float playerMaxAir = -1.0F;
	private final Vector3f selectedBlockPos = new Vector3f(NOTHING_SELECTED);
	private final Vector4f lightningBoltPosition = new Vector4f();
	/** The tick the position beside this was searched for on, so a frame does not do it again. */
	private long lightningTick = Long.MIN_VALUE;
	private int heldBlockLight;
	private int heldBlockLight2;

	// engine
	private int atlasWidth;
	private int atlasHeight;
	private float anisotropy;
	private int textureFilteringMode;
	private float chunkFadeTimeInv;

	/**
	 * A new frame state is a new history, and the catalogue's smoothed values are the one piece of
	 * it that does not live here: the catalogue is built once for the process while this is built
	 * once per pack, so nothing else would drop them and a reloaded pack would spend its first
	 * seconds fading from a number the previous one left behind.
	 */
	public FrameState() {
		FrameSmoothed.forgetAll();
	}

	/**
	 * Replaces the pack's directives. Called on a pack load and on nothing else, because that is
	 * the only time they change: a directive is a {@code const} in the pack's own source, read
	 * once when it is expanded.
	 */
	public void directives(PackDirectives directives) {
		this.directives = directives;
	}

	/**
	 * What the pack declared about itself. Most of it reaches a shader through the table above; the
	 * handful that does not, the shadow map's own resolution first among them, is read from here by
	 * whoever allocates what the directive describes.
	 */
	public PackDirectives directives() {
		return this.directives;
	}

	/**
	 * The one thing the pack says about itself that is not in its GLSL. Set on a pack load, next to
	 * the directives, and for the same reason.
	 */
	public void endFlashShadows(boolean endFlashShadows) {
		this.endFlashShadows = endFlashShadows;
	}

	/** The view geometry, which comes from a captured matrix rather than from a game object. */
	public ViewMatrices view() {
		return this.view;
	}

	/**
	 * Which depth convention the target a pass draws into carries, {@link ClipSpace#REVERSED} for
	 * one the game owns and {@link ClipSpace#FORWARD} for one that is ours. Set by the pass before
	 * it writes its block; it is a property of where the pass draws, not of the frame.
	 */
	public void convention(Vector4fc convention) {
		this.view.convention(convention);
	}

	/**
	 * The model view the pass about to write its block draws with, or null for the camera's. Set by
	 * the pass beside its convention, and for the same reason: both are properties of where the pass
	 * draws rather than of the frame.
	 */
	public void passModelView(Matrix4fc matrix, Matrix4fc bob) {
		this.view.passModelView(matrix, bob);
	}

	/**
	 * The projection the pass about to write its block draws under, or null for the frame's. Set
	 * beside the model view above and answering the same kind of question.
	 */
	public void passProjection(Matrix4fc matrix) {
		this.view.passProjection(matrix);
	}

	/** The colour the pass modulates its draw by, or null for white. Set beside the two above. */
	public void passColour(Vector4fc colour) {
		this.view.passColour(colour);
	}

	/** What the pass about to write its block draws. Set beside the three above, and for once. */
	public void renderStage(RenderStage stage) {
		this.stage = stage;
	}

	/**
	 * Called once per frame, before anything reads the state. The named point the rest of the
	 * engine's idea of a frame boundary hangs off.
	 */
	@SuppressWarnings("ReferenceEquality")
	public void advance() {
		advanceClock();

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (level == null) {
			CapturedProjection.clear();
			CameraBob.clear();

			return;
		}

		if (this.lastLevel != level) {
			// A different world, so nothing carried over from the previous frame means anything:
			// the camera has jumped a dimension's worth of coordinates and the shift, the history
			// and every motion vector built from them would be one frame of nonsense. The clock is
			// part of that and the half easiest to leave out, which only shows on a dimension the
			// pack serves out of the directory it was already loaded from: nothing is read again
			// there, so the first frame on the far side would publish a frameTime measuring the
			// whole change, seconds of it, into every value a pack integrates over one.
			this.lastLevel = level;
			reset();
		}

		GameRenderer renderer = minecraft.gameRenderer;
		Camera camera = renderer.mainCamera();
		CameraRenderState cameraState = renderer.gameRenderState().levelRenderState.cameraRenderState;
		this.partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		this.gameTime = renderer.gameRenderState().levelRenderState.gameTime;
		// The frame's own snapshot of the setting and not a second reading of the option: this is the
		// very field the game hands its globals block for its own glint shader
		// (renderer/GameRenderer.java:419 out of :623), so a pack's glint fades with the slider on the
		// same frame the game's would have.
		this.glintAlpha = (float) renderer.gameRenderState().optionsRenderState.glintStrength;

		RenderTarget main = renderer.mainRenderTarget();
		if (main != null) {
			// The main target and not the window. A pack divides by these to step across a
			// texture, and what it steps across is the target that was drawn into: the game only
			// brings the target to the window's size where it builds a frame, and the panorama
			// capture goes round that path, resizing the target to 4096 square itself in
			// Minecraft.grabPanoramixScreenshot.
			this.viewWidth = main.width;
			this.viewHeight = main.height;
		}

		// The shift moves first. Two of the values below are differences against the camera, the
		// lightning bolt and the vehicle, and reading them against last frame's position would
		// give both of them a lag that only shows while the player moves.
		this.shift.advance(cameraState.pos);

		readWorld(level, camera);
		readFog(cameraState);
		readPlayer(minecraft, level, camera);
		readBiome(minecraft, level);
		readEngine(minecraft);
		advanceView(minecraft, cameraState);

		// Cleared last, so that a frame in which nothing captured falls back rather than
		// publishing the frame before it. The bob goes with it and for the same reason: a frame
		// that took none must not be handed the last one's, or the world would keep swinging after
		// the player had stopped.
		CapturedProjection.clear();
		CameraBob.clear();
	}

	/** Forgets everything one frame carried into the next. For a world or dimension change. */
	public void reset() {
		this.view.reset();
		this.shift.reset();
		FrameSmoothed.forgetAll();
		this.timed = false;
		this.frameCounter = 0;
		this.frameTime = 0.0F;
		this.frameTimeCounter = 0.0F;
		// The bolt with them, and it is the tick beside it that makes this necessary rather than
		// tidy: a client that leaves a save and rejoins it comes back at the tick it left on, so a
		// counter kept across that would match, the search would be skipped, and the position of
		// the session before would be served as this one's.
		this.lightningTick = Long.MIN_VALUE;
		this.lightningBoltPosition.zero();
	}

	/**
	 * Forgets the world as well as the frame, for a client that has left one rather than moved
	 * between two.
	 * <p>
	 * What this drops is held by identity, which is what makes it worth dropping: the level of the
	 * last frame, so that the change is noticed again when one is joined. A client sitting in the
	 * menu was keeping the whole {@code ClientLevel} it had just left alive through it.
	 */
	public void leaveWorld() {
		reset();
		this.lastLevel = null;
	}

	/**
	 * Ported from Iris {@code uniforms/SystemTimeUniforms.java:37-95}.
	 * <p>
	 * The quantisation to the millisecond is not tidiness. This is the time step every smoothed
	 * value integrates over, so {@code wetness} and {@code eyeBrightnessSmooth} both inherit it,
	 * and a counter that ticks in real nanoseconds puts both of them slightly off the reference
	 * for no visible reason. {@code frameTimeCounter} accumulates those durations rather than
	 * reading a wall clock, so it stops while the game is paused, which is what a pack driving
	 * noise with it expects.
	 */
	@SuppressWarnings("NarrowCalculation")
	private void advanceClock() {
		long now = System.nanoTime();
		long elapsed = this.timed ? now - this.lastFrameNanos : 0L;
		this.lastFrameNanos = now;
		this.timed = true;

		this.frameTime = (elapsed / 1000L) / 1000L / 1000.0F;
		this.frameTimeCounter += this.frameTime;
		if (this.frameTimeCounter >= COUNTER_WRAP) {
			this.frameTimeCounter = 0.0F;
		}

		this.frameCounter = (this.frameCounter + 1) % FRAME_WRAP;
	}

	private void advanceView(Minecraft minecraft, CameraRenderState cameraState) {
		int renderDistance = minecraft.options.getEffectiveRenderDistance();
		Matrix4fc rendered = CapturedProjection.rendered(cameraState.projectionMatrix);

		// The bob goes to the model view and the projection is published clean, which is where a
		// pack expects both. Only once the two have been multiplied back together and found to be
		// the matrix the level was really drawn with: a term this engine failed to intercept would
		// otherwise be missing from one and not made up for by the other.
		boolean split = CameraBob.agrees(cameraState.projectionMatrix, rendered);

		// The render distance in blocks, which is a quarter of the plane the game actually clips
		// at: Camera sets depthFar to max(renderDistance * 4, cloudRange * 16). That quarter is
		// what Iris hands out, so every linearisation in every pack of the corpus is written
		// against it, and correcting it here would make all of them wrong at once.
		this.view.advance(cameraState.viewRotationMatrix, CameraBob.taken(),
				split ? cameraState.projectionMatrix : rendered, renderDistance * 16.0F,
				renderDistance);

		// And what Distant Horizons drew with, which is nothing at all on the sessions that have no
		// far terrain. The distance answers on its own and the two planes answer on theirs, which is
		// how Iris asks them, and advanceDistant carries what the pair being short of the distance
		// costs.
		//
		// What the planes carry is the projection DH drew the PREVIOUS frame with, and that is worth
		// saying rather than hiding. DH fills its render parameter from the opaque chunk pass, while
		// the pack's frame opens ahead of it at the first sky draw. Reading them later would not
		// mend it: the block is written once, here, so a value taken after it would reach a pack one
		// frame later still. What it costs is nothing measurable, both planes moving with DH's own
		// settings and with the player's height above the world rather than with the camera.
		//
		// And the volume beside them, which is the row rather than the pair of planes the planes
		// were worked out from: a row put back together out of two planes would be the same
		// arithmetic done twice and rounded twice. Handed a zero offset where there is no row to be
		// had, and then the volume published is the frame's own, which is what a pack reads where
		// this engine draws no far terrain at all.
		//
		// ALL THREE ARE TAKEN BEFORE ANY OF THEM IS PUBLISHED, and that is what stands in for the
		// coupling rather than the coupling itself. Each of the three reads can latch this engine's
		// whole view of that mod off on a reflective failure, and the frame that latch lands in is
		// the only frame that could hold answers from both sides of it: far terrain still on screen,
		// and the numbers that place it disagreeing. Ordering the reads mends nothing, it only
		// chooses which of them is the stale one, so the frame asks after all three instead and
		// takes the fallback whole. On a latch inside the distance or inside the planes that is what
		// this method already did while the three moved together. On a latch inside the row it is
		// new: that read used to come after the pair was published, so the frame went out with a
		// real distance, real planes and a volume that had given up.
		//
		// It does NOT cover the three partings that OUTLIVE the coupling: the ordinary one this
		// batch exists for, one that is Iris's own answer, and one that is a hole older than any of
		// this. They are named one by one in ViewMatrices.advanceDistant.
		int distance = DhDepth.renderDistanceBlocks();

		boolean planes = DhDepth.planes(this.distantPlanes);
		float near = planes ? this.distantPlanes.x : ViewMatrices.FALLBACK_PLANE;
		float far = planes ? this.distantPlanes.y : ViewMatrices.FALLBACK_PLANE;

		boolean row = DhDepth.zRow(this.distantPlanes);
		float scale = row ? this.distantPlanes.x : 0.0F;
		float offset = row ? this.distantPlanes.y : 0.0F;

		if (!DhDepth.usable()) {
			distance = -1;
			near = ViewMatrices.FALLBACK_PLANE;
			far = ViewMatrices.FALLBACK_PLANE;
			scale = 0.0F;
			offset = 0.0F;
		}

		this.view.advanceDistant(near, far, distance);
		this.view.advanceDistantVolume(scale, offset);

		this.view.advanceShadow(sunAngle(isDay()) / 360.0F, this.directives.sunPathRotation(),
				this.directives.shadowIntervalSize(), this.shift.unshifted(),
				this.directives.shadowDistance(), this.directives.shadowNearPlane(),
				this.directives.shadowFarPlane(), inEndFlash(), this.endFlashXAngle,
				this.endFlashYAngle, ShadowTerrain.takeMapState() == ShadowTerrain.MapState.SKIPPED);
	}

	/**
	 * One step of wrapping and not a modulus, exactly as Iris
	 * {@code uniforms/CelestialUniforms.java:30-42} does it. A shader that reads the value either
	 * side of the wrap has to see the discontinuity in the same place.
	 */
	private float sunAngle(boolean sun) {
		float angle = (sun ? this.sunAngleDegrees : this.moonAngleDegrees) + 90.0F;
		if (angle < 0.0F) {
			angle += 360.0F;
		} else if (angle > 360.0F) {
			angle -= 360.0F;
		}

		return angle;
	}

	private boolean isDay() {
		return sunAngle(true) < 180.0F;
	}

	/**
	 * Whether the shadow light follows the End flash rather than the sun and the moon. The pack has
	 * to have asked for it: this is a light direction the pack has to be written around, and one
	 * that never opted in gets its shadows pointed somewhere it has no idea about otherwise.
	 */
	private boolean inEndFlash() {
		return this.endFlashShadows && this.hasEndFlash && this.dimensionOrdinal == 1;
	}

	/**
	 * The sun is no longer a function of the world time on 26.2: it is an interpolated timeline a
	 * data pack can redefine, read through the camera's attribute probe. Recomputing the angle
	 * from {@code worldTime} would agree with vanilla and be wrong the moment anything touches the
	 * sky, which is a place we are shorter and more correct than Iris rather than the reverse.
	 */
	private void readWorld(ClientLevel level, Camera camera) {
		float pt = this.partialTick;
		this.sunAngleDegrees = camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, pt);
		this.moonAngleDegrees = camera.attributeProbe().getValue(EnvironmentAttributes.MOON_ANGLE, pt);
		this.moonPhase = camera.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, pt).index();
		this.skyColorPacked = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, pt);
		this.cloudHeight = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, pt);

		// Clamped because some servers send values outside the range, and a pack that trusts them
		// draws weather harder than weather.
		this.rainStrength = Mth.clamp(level.getRainLevel(pt), 0.0F, 1.0F);
		this.thunderStrength = Mth.clamp(level.getThunderLevel(pt), 0.0F, 1.0F);

		this.dimensionOrdinal = dimensionOrdinal(level);

		long clock = level.getDefaultClockTime();
		this.worldDay = clock / 24000L;
		// The Nether and the End keep the raw modulus rather than honouring hasFixedTime. That is
		// an oversight of Iris's that packs now depend on: Complementary's ender beams stop moving
		// without it.
		boolean nowhere = this.dimensionOrdinal == -1 || this.dimensionOrdinal == 1;
		this.worldTime = !nowhere && level.dimensionType().hasFixedTime() ? 0L : clock % 24000L;

		DimensionType dimension = level.dimensionType();
		this.bedrockLevel = dimension.minY();
		this.heightLimit = dimension.height();
		this.logicalHeightLimit = dimension.logicalHeight();
		this.hasCeiling = dimension.hasCeiling();
		this.hasSkylight = dimension.hasSkyLight();
		this.ambientLight = dimension.ambientLight();
		this.seaLevel = level.getSeaLevel();

		EndFlashState flash = level.endFlashState();
		this.hasEndFlash = flash != null;
		this.previousEndFlashIntensity = this.endFlashIntensity;
		this.endFlashXAngle = flash == null ? 0.0F : flash.getXAngle();
		this.endFlashYAngle = flash == null ? 0.0F : flash.getYAngle();
		this.endFlashIntensity = flash == null ? 0.0F : flash.getIntensity(pt);
	}

	// A dimension key is interned by the game, so the three names below are the instances a level
	// carries and not copies of them.
	@SuppressWarnings("ReferenceEquality")
	private static int dimensionOrdinal(ClientLevel level) {
		if (level.dimension() == Level.OVERWORLD) {
			return 0;
		} else if (level.dimension() == Level.NETHER) {
			return -1;
		} else if (level.dimension() == Level.END) {
			return 1;
		}

		return 2;
	}

	/**
	 * The fog comes from the game's own state rather than from Sodium's copy of it, which is where
	 * Iris reads it. The same numbers, one relay fewer, and it works whether or not Sodium is the
	 * one drawing.
	 */
	private void readFog(CameraRenderState cameraState) {
		this.fogColor.set(cameraState.fogData.color);
		this.fogStart = cameraState.fogData.environmentalStart;
		this.fogEnd = cameraState.fogData.environmentalEnd;
	}

	private void readPlayer(Minecraft minecraft, ClientLevel level, Camera camera) {
		float pt = this.partialTick;
		LocalPlayer player = minecraft.player;
		Entity cameraEntity = minecraft.getCameraEntity();

		this.isEyeInWater = eyeInWater(camera.getFluidInCamera(),
				player != null && player.isSpectator());
		this.heavyFog = minecraft.gui.hud.getBossOverlay().shouldCreateWorldFog();
		this.hideGui = minecraft.gui.hud.isHidden();
		this.rightHanded = minecraft.options.mainHand().get() == HumanoidArm.RIGHT;
		this.screenBrightness = minecraft.options.gamma().get().floatValue();
		this.firstPerson = minecraft.options.getCameraType().isFirstPerson();
		this.spectator = minecraft.gameMode != null
				&& minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR;

		if (cameraEntity != null) {
			this.cameraEntityTickCount = cameraEntity.tickCount;

			Vec3 eye = cameraEntity.getEyePosition(pt);
			this.eyePosition.set(eye.x, eye.y, eye.z);
			Vec3 forward = cameraEntity.getForward();
			this.playerBodyVector.set((float) forward.x, (float) forward.y, (float) forward.z);

			// At the block the eyes are in, which is not the block the feet are in and is the
			// whole reason a pack can tell that the player's head is above the water.
			BlockPos eyeBlock = BlockPos.containing(eye.x, cameraEntity.getEyeY(), eye.z);
			this.eyeBrightnessBlock = level.getBrightness(LightLayer.BLOCK, eyeBlock) * 16;
			this.eyeBrightnessSky = level.getBrightness(LightLayer.SKY, eyeBlock) * 16;
		}

		if (cameraEntity instanceof LivingEntity living) {
			Vec3 look = living.getViewVector(pt);
			this.playerLookVector.set((float) look.x, (float) look.y, (float) look.z);
			this.blindness = blindness(living);
			this.darknessFactor = living.getEffectBlendFactor(MobEffects.DARKNESS, pt);
		} else {
			this.blindness = 0.0F;
			this.darknessFactor = 0.0F;
		}

		this.nightVision = nightVision(player, cameraEntity, pt);
		this.darknessLightFactor = darknessLightFactor(minecraft, player, pt);
		this.playerMood = player == null ? 0.0F : Mth.clamp(player.getCurrentMood(), 0.0F, 1.0F);

		readFlags(player);
		readStats(minecraft, player);
		readSelection(minecraft, level, camera);
		readLightning(level, pt);
		readHeldLight(player);
	}

	/**
	 * Iris's table, which is not the ordinals of {@link FogType}: that enum reads
	 * {@code LAVA, WATER, POWDER_SNOW, ATMOSPHERIC, NONE} and a pack wants
	 * {@code 0 nothing, 1 water, 2 lava, 3 powder snow}, so the mapping is written out.
	 * <p>
	 * Lava not counting in spectator is a rule Iris lays over the game rather than a property of
	 * it, {@code Camera.getFluidInCamera} never looks at the game mode. It is here because packs
	 * are written against Iris.
	 */
	private static int eyeInWater(FogType submersion, boolean spectator) {
		if (submersion == FogType.WATER) {
			return 1;
		} else if (!spectator && submersion == FogType.LAVA) {
			return 2;
		} else if (submersion == FogType.POWDER_SNOW) {
			return 3;
		}

		return 0;
	}

	/**
	 * A fifth of a second per point of duration, and one outright while the effect is infinite.
	 * Iris {@code uniforms/CommonUniforms.java:243-261}, which guesses at what OptiFine does from
	 * how vanilla fades its own fog, and is what the packs were tuned against either way.
	 */
	private static float blindness(LivingEntity living) {
		MobEffectInstance blindness = living.getEffect(MobEffects.BLINDNESS);
		if (blindness == null) {
			return 0.0F;
		}

		return blindness.isInfiniteDuration() ? 1.0F
				: Mth.clamp(blindness.getDuration() / 20.0F, 0.0F, 1.0F);
	}

	/**
	 * {@code GameRenderer.nightVisionScale} dereferences the effect without checking for it, so
	 * calling it on an entity that does not have night vision throws, every frame. The three
	 * branch guard belongs to the caller, {@code LightmapRenderStateExtractor:77-84}, and that is
	 * the one to reproduce.
	 * <p>
	 * The conduit branch is what makes an existing pack react to conduit power underwater without
	 * ever having heard of it, and it is the player rather than the camera entity on purpose, to
	 * match the light texture.
	 */
	private static float nightVision(LocalPlayer player, Entity cameraEntity, float pt) {
		if (cameraEntity instanceof LivingEntity living
				&& living.hasEffect(MobEffects.NIGHT_VISION)) {
			return Mth.clamp(GameRenderer.nightVisionScale(living, pt), 0.0F, 1.0F);
		}

		if (player != null && player.getWaterVision() > 0.0F
				&& player.hasEffect(MobEffects.CONDUIT_POWER)) {
			return Mth.clamp(player.getWaterVision(), 0.0F, 1.0F);
		}

		return 0.0F;
	}

	/**
	 * Recomputed rather than captured. Iris takes this out of the light texture with a mixin, but
	 * what the mixin intercepts is two lines of arithmetic over values that are all public, so
	 * there is nothing here a mixin would buy. See {@code LightmapRenderStateExtractor:42-45} and
	 * {@code :73-76}: the option scale enters twice, once through the effect blend and once at the
	 * end, and dropping either occurrence halves the result.
	 */
	private static float darknessLightFactor(Minecraft minecraft, LocalPlayer player, float pt) {
		if (player == null) {
			return 0.0F;
		}

		float scale = minecraft.options.darknessEffectScale().get().floatValue();
		float gamma = player.getEffectBlendFactor(MobEffects.DARKNESS, pt) * scale;
		float darkness = Math.max(0.0F,
				Mth.cos((player.tickCount - pt) * (float) Math.PI * 0.025F) * 0.45F * gamma);

		return darkness * scale;
	}

	private void readFlags(LocalPlayer player) {
		if (player == null) {
			return;
		}

		this.sneaking = player.isCrouching();
		this.sprinting = player.isSprinting();
		// hurtTime and not isHurt. The second one answers a different question, and Iris's comment
		// on the line is emphatic about it.
		this.hurt = player.hurtTime > 0;
		this.invisible = player.isInvisible();
		this.burning = player.isOnFire();
		this.onGround = player.onGround();
		this.elytraFlying = player.isFallFlying();
		this.riding = player.isPassenger();
		this.feetInWater = player.isInShallowWater();
		this.swimming = player.isSwimming();

		Entity vehicle = player.getVehicle();
		this.vehicleInWater = vehicle != null && vehicle.isInShallowWater();
		if (vehicle == null) {
			this.vehicleLookVector.zero();
			this.relativeVehiclePosition.zero();
		} else {
			Vec3 forward = vehicle.getForward();
			this.vehicleLookVector.set(forward.x, forward.y, forward.z);
			Vec3 position = vehicle.getPosition(this.partialTick);
			this.relativeVehiclePosition.set(this.shift.unshifted())
					.sub(position.x, position.y, position.z);
		}
	}

	/**
	 * Six of these answer -1 outside survival rather than zero, and the difference matters: a pack
	 * that tests for "not applicable" by looking for a negative number sees a dying player if it
	 * is handed a zero.
	 * <p>
	 * The other trap is that Iris publishes ratios under names that read like absolutes. Health,
	 * hunger, air and armour are each divided by their maximum while the {@code max*} uniforms are
	 * the raw numbers, so writing the raw health here would give a plausible number, a wrong
	 * image, and nothing downstream that could tell.
	 */
	private void readStats(Minecraft minecraft, LocalPlayer player) {
		boolean survival = player != null && minecraft.gameMode != null
				&& minecraft.gameMode.getPlayerMode().isSurvival();
		if (!survival) {
			this.playerHealth = -1.0F;
			this.playerMaxHealth = -1.0F;
			this.playerHunger = -1.0F;
			this.playerArmor = -1.0F;
			this.playerAir = -1.0F;
			this.playerMaxAir = -1.0F;

			return;
		}

		this.playerHealth = player.getHealth() / player.getMaxHealth();
		this.playerMaxHealth = player.getMaxHealth();
		this.playerHunger = player.getFoodData().getFoodLevel() / MAX_HUNGER;
		this.playerArmor = player.getArmorValue() / MAX_ARMOR;
		this.playerAir = (float) player.getAirSupply() / (float) player.getMaxAirSupply();
		this.playerMaxAir = player.getMaxAirSupply();
	}

	/**
	 * Where the block the player is looking at is, but only while the game is drawing its outline.
	 * <p>
	 * The outline test is the whole difference between this and reading the hit result, and it is
	 * not a detail: a pack uses this to draw its own highlight, so without the test the highlight
	 * stays on with the interface hidden, which is the one moment somebody is taking a screenshot.
	 * Iris reads the game's own answer through a mixin; the two cases that answer differently from
	 * the hit result are reachable without one, so they are written out here instead.
	 */
	private void readSelection(Minecraft minecraft, ClientLevel level, Camera camera) {
		HitResult hit = minecraft.hitResult;
		if (hit == null || hit.getType() != HitResult.Type.BLOCK
				|| !(minecraft.getCameraEntity() instanceof Player player)
				|| minecraft.gui.hud.isHidden()) {
			this.selectedBlockPos.set(NOTHING_SELECTED);

			return;
		}

		BlockPos block = ((BlockHitResult) hit).getBlockPos();
		if (!player.getAbilities().mayBuild && !outlined(minecraft, level, player, block)) {
			this.selectedBlockPos.set(NOTHING_SELECTED);

			return;
		}

		Vec3 position = camera.position();
		this.selectedBlockPos.set((float) (block.getX() + 0.5 - position.x),
				(float) (block.getY() + 0.5 - position.y),
				(float) (block.getZ() + 0.5 - position.z));
	}

	/**
	 * The branch the game takes when the player may not build: a spectator only sees the outline of
	 * a block that would open something, and adventure mode only of one the held item is tagged
	 * for. One thing the game tests and this cannot is its own {@code renderBlockOutline} flag,
	 * which is private with a setter and no reader, and which nothing in the client turns off.
	 */
	private static boolean outlined(Minecraft minecraft, ClientLevel level, Player player,
			BlockPos block) {
		if (minecraft.gameMode != null
				&& minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
			return level.getBlockState(block).getMenuProvider(level, block) != null;
		}

		ItemStack held = player.getMainHandItem();
		BlockInWorld inWorld = new BlockInWorld(level, block, false);

		return !held.isEmpty() && (held.canBreakBlockInAdventureMode(inWorld)
				|| held.canPlaceOnBlockInAdventureMode(inWorld));
	}

	/**
	 * Once a tick and not once a frame, which is the frequency the reference publishes this at
	 * ({@code uniforms/IrisExclusiveUniforms.java:91}, {@code PER_TICK}) and the whole of what it
	 * buys: the search is a pass over every entity the level would render, made to find the one
	 * kind of entity that is almost never there.
	 * <p>
	 * What is held between two ticks is what the reference holds too, the camera subtracted
	 * included: it builds the whole camera relative vector inside that same per tick supplier, so
	 * a bolt stands still for a walking player on both engines rather than only on this one.
	 */
	private void readLightning(ClientLevel level, float pt) {
		long tick = level.getGameTime();
		if (tick == this.lightningTick) {
			return;
		}

		this.lightningTick = tick;
		this.lightningBoltPosition.zero();
		for (Entity entity : level.entitiesForRendering()) {
			if (entity instanceof LightningBolt) {
				Vec3 position = entity.getPosition(pt);
				Vector3dc camera = this.shift.unshifted();
				this.lightningBoltPosition.set((float) (position.x - camera.x()),
						(float) (position.y - camera.y()),
						(float) (position.z - camera.z()), 1.0F);

				return;
			}
		}
	}

	/**
	 * The {@code oldHandLight} rule, which defaults to on: the brighter of the two hands is what
	 * the main hand reports, so a torch in the off hand still lights the scene on a pack that only
	 * reads one of them.
	 */
	private void readHeldLight(LocalPlayer player) {
		if (player == null) {
			this.heldBlockLight = 0;
			this.heldBlockLight2 = 0;

			return;
		}

		int main = emission(player.getItemInHand(InteractionHand.MAIN_HAND));
		int off = emission(player.getItemInHand(InteractionHand.OFF_HAND));
		this.heldBlockLight = Math.max(main, off);
		this.heldBlockLight2 = off;
	}

	/**
	 * The block state's own emission. NeoForge offers a position aware extension of it, which is not
	 * reachable from here since this module compiles against vanilla alone, and a held item has no
	 * position to be aware of anyway.
	 */
	private static int emission(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}

		Block block = Block.byItem(stack.getItem());

		return block == null ? 0 : block.defaultBlockState().getLightEmission();
	}

	private void readBiome(Minecraft minecraft, ClientLevel level) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}

		// At the player's block and not the camera's. They are different in third person, and very
		// different when the camera has backed through a wall.
		BlockPos position = player.blockPosition();
		Holder<Biome> holder = level.getBiome(position);
		this.biomeId = BiomeClassifier.identify(holder);
		this.biomeCategory = BiomeClassifier.categoryOf(holder);
		this.biomePrecipitation =
				switch (holder.value().getPrecipitationAt(position, level.getSeaLevel())) {
					case NONE -> 0;
					case RAIN -> 1;
					case SNOW -> 2;
				};
		this.rainfall = ((BiomeHumidity) (Object) holder.value()).vitrail$downfall();
		this.temperature = holder.value().getBaseTemperature();
	}

	private void readEngine(Minecraft minecraft) {
		TextureFilteringMethod filtering = minecraft.options.textureFiltering().get();
		this.anisotropy = filtering == TextureFilteringMethod.ANISOTROPIC
				? minecraft.options.maxAnisotropyValue()
				: 0.0F;
		this.textureFilteringMode = switch (filtering) {
			case NONE -> 0;
			case RGSS -> 1;
			case ANISOTROPIC -> 2;
		};

		double fadeMillis = minecraft.options.chunkSectionFadeInTime().get() * 1000.0;
		this.chunkFadeTimeInv = fadeMillis == 0.0 ? 0.0F : (float) (1.0 / fadeMillis);

		readAtlas(minecraft);
	}

	/**
	 * The block atlas, once, and a divergence worth naming. Iris answers {@code atlasSize} with
	 * whatever texture happens to be bound to unit zero, which inside a composite is a colour
	 * target and means nothing; its own note admits it does not know how a custom uniform reading
	 * it could work. A constant is a gain here rather than a loss, until the gbuffers run and it
	 * can be per draw.
	 */
	private void readAtlas(Minecraft minecraft) {
		try {
			AbstractTexture atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
			if (atlas.getTexture() != null) {
				this.atlasWidth = atlas.getTexture().getWidth(0);
				this.atlasHeight = atlas.getTexture().getHeight(0);
			}
		} catch (RuntimeException e) {
			// Asked for before the atlases are stitched, which is a state that fixes itself. Not
			// logged, because this runs every frame and the answer arrives on its own.
		}
	}

	/**
	 * Keeps the published camera position inside the range a float can still resolve.
	 * <p>
	 * Ported from Iris {@code uniforms/CameraUniforms.java:62-143}. Both constants are observable
	 * by a pack, and so is the fact that <b>only X and Z are ever shifted</b>, which is why the
	 * altitude and the raw Y are the same number. Shifting by whole multiples of the range rather
	 * than by the overshoot is a requirement of at least one pack, not a rounding preference.
	 */
	private static final class CameraShift {

		private static final double WALK_RANGE = 30000.0;
		private static final double TP_RANGE = 1000.0;

		private final Vector3d shift = new Vector3d();
		private final Vector3d current = new Vector3d();
		private final Vector3d previous = new Vector3d();
		private final Vector3d currentUnshifted = new Vector3d();
		private final Vector3d previousUnshifted = new Vector3d();

		private boolean seeded;

		void advance(Vec3 position) {
			this.previous.set(this.current);
			this.previousUnshifted.set(this.currentUnshifted);
			this.currentUnshifted.set(position.x, position.y, position.z);
			this.current.set(this.currentUnshifted).add(this.shift);

			if (!this.seeded) {
				// The same guard the matrices give themselves, and for the same reason: the first
				// frame after a world change has no previous position, and the origin standing in
				// for it is a motion vector the width of the world. It also keeps the teleport test
				// below from reading the spawn itself as a teleport.
				this.previous.set(this.current);
				this.previousUnshifted.set(this.currentUnshifted);
				this.seeded = true;
			}

			double dX = shiftOf(this.current.x, this.previous.x);
			double dZ = shiftOf(this.current.z, this.previous.z);
			if (dX == 0.0 && dZ == 0.0) {
				return;
			}

			// This frame and the previous one move by the same amount, so that the difference
			// between them, which is all a motion vector is, survives the shift.
			this.shift.x += dX;
			this.current.x += dX;
			this.previous.x += dX;
			this.shift.z += dZ;
			this.current.z += dZ;
			this.previous.z += dZ;
		}

		void reset() {
			this.shift.zero();
			this.current.zero();
			this.previous.zero();
			this.currentUnshifted.zero();
			this.previousUnshifted.zero();
			this.seeded = false;
		}

		private static double shiftOf(double value, double previous) {
			if (Math.abs(value) > WALK_RANGE || Math.abs(value - previous) > TP_RANGE) {
				return -(value - (value % WALK_RANGE));
			}

			return 0.0;
		}

		Vector3dc shifted() {
			return this.current;
		}

		Vector3dc previousShifted() {
			return this.previous;
		}

		Vector3dc unshifted() {
			return this.currentUnshifted;
		}

		Vector3dc previousUnshifted() {
			return this.previousUnshifted;
		}
	}

	// frame and time

	@Override
	public int frameCounter() {
		return this.frameCounter;
	}

	@Override
	public float frameTime() {
		return this.frameTime;
	}

	@Override
	public float frameTimeCounter() {
		return this.frameTimeCounter;
	}

	@Override
	public float partialTick() {
		return this.partialTick;
	}

	@Override
	public long gameTime() {
		return this.gameTime;
	}

	@Override
	public float glintAlpha() {
		return this.glintAlpha;
	}

	// viewport

	@Override
	public float viewWidth() {
		return this.viewWidth;
	}

	@Override
	public float viewHeight() {
		return this.viewHeight;
	}

	// camera positions

	@Override
	public Vector3dc cameraPosition() {
		return this.shift.shifted();
	}

	@Override
	public Vector3dc previousCameraPosition() {
		return this.shift.previousShifted();
	}

	@Override
	public Vector3dc cameraPositionUnshifted() {
		return this.shift.unshifted();
	}

	@Override
	public Vector3dc previousCameraPositionUnshifted() {
		return this.shift.previousUnshifted();
	}

	// world

	@Override
	public long worldTime() {
		return this.worldTime;
	}

	@Override
	public long worldDay() {
		return this.worldDay;
	}

	@Override
	public int moonPhase() {
		return this.moonPhase;
	}

	@Override
	public float sunAngleDegrees() {
		return this.sunAngleDegrees;
	}

	@Override
	public float moonAngleDegrees() {
		return this.moonAngleDegrees;
	}

	@Override
	public float sunPathRotation() {
		return this.directives.sunPathRotation();
	}

	@Override
	public float rainStrength() {
		return this.rainStrength;
	}

	@Override
	public float thunderStrength() {
		return this.thunderStrength;
	}

	@Override
	public int skyColorPacked() {
		return this.skyColorPacked;
	}

	@Override
	public float cloudHeight() {
		return this.cloudHeight;
	}

	@Override
	public int bedrockLevel() {
		return this.bedrockLevel;
	}

	@Override
	public int heightLimit() {
		return this.heightLimit;
	}

	@Override
	public int logicalHeightLimit() {
		return this.logicalHeightLimit;
	}

	@Override
	public boolean hasCeiling() {
		return this.hasCeiling;
	}

	@Override
	public boolean hasSkylight() {
		return this.hasSkylight;
	}

	@Override
	public float ambientLight() {
		return this.ambientLight;
	}

	@Override
	public int dimensionOrdinal() {
		return this.dimensionOrdinal;
	}

	@Override
	public int seaLevel() {
		return this.seaLevel;
	}

	@Override
	public int biomeId() {
		return this.biomeId;
	}

	@Override
	public int biomeCategory() {
		return this.biomeCategory;
	}

	@Override
	public int biomePrecipitation() {
		return this.biomePrecipitation;
	}

	/**
	 * The biome's own humidity, and not the weather: {@code rainStrength} and {@code wetness} carry
	 * whether it is raining, this one is the constant a desert and a swamp differ by. Iris publishes
	 * the same field ({@code uniforms/BiomeUniforms.java:50-51}), off the same biome at the player's
	 * block, so a pack reads one number on both engines.
	 * <p>
	 * There is still no getter, and there never was one to wait for: the value comes through
	 * {@link BiomeHumidity}, which the biome answers because a mixin puts it there. That this module
	 * compiles against vanilla alone was never what blocked it, and saying so here is what kept the
	 * gap alive: a mixin onto a vanilla class is available here like any other, {@code BiomesMixin}
	 * having stood beside this one doing exactly that the whole time. What the belief cost is a
	 * nought that arrived through a registered source, which reads as measured rather than missing,
	 * and told any pack scaling puddles or fog by humidity that the whole world was a desert.
	 */
	@Override
	public float rainfall() {
		return this.rainfall;
	}

	@Override
	public float temperature() {
		return this.temperature;
	}

	// end flash

	@Override
	public boolean hasEndFlash() {
		return this.hasEndFlash;
	}

	@Override
	public boolean endFlashShadows() {
		return this.endFlashShadows;
	}

	@Override
	public float endFlashXAngleDegrees() {
		return this.endFlashXAngle;
	}

	@Override
	public float endFlashYAngleDegrees() {
		return this.endFlashYAngle;
	}

	@Override
	public float endFlashIntensity() {
		return this.endFlashIntensity;
	}

	@Override
	public float previousEndFlashIntensity() {
		return this.previousEndFlashIntensity;
	}

	// fog

	@Override
	public float fogR() {
		return this.fogColor.x;
	}

	@Override
	public float fogG() {
		return this.fogColor.y;
	}

	@Override
	public float fogB() {
		return this.fogColor.z;
	}

	@Override
	public float fogA() {
		return this.fogColor.w;
	}

	@Override
	public float fogStart() {
		return this.fogStart;
	}

	@Override
	public float fogEnd() {
		return this.fogEnd;
	}

	/**
	 * Zero, and not by omission: 26.2's {@code FogData} carries a start and an end and no density
	 * at all, so the game's environmental fog is linear by construction. A geometry pass will
	 * therefore always be told {@code GL_LINEAR}, never {@code GL_EXP2}, once there is one.
	 */
	@Override
	public float fogDensity() {
		return 0.0F;
	}

	/**
	 * Off, which is what every pass that runs today is drawn with.
	 * <p>
	 * This is a property of the pass rather than of the frame, and the only passes there are are
	 * full screen ones: nothing behind a composite fogs, so the fog the pack is told about is the
	 * fog it would apply itself, out of {@code fogColor}, {@code fogStart} and {@code fogEnd},
	 * which are answered whatever this says. Iris draws the same distinction and answers the same
	 * pair here, {@code 0} and {@code -1}, for its composite, deferred and final programs. When a
	 * gbuffers pass runs, these two stop being frame values and become arguments of the pass.
	 */
	@Override
	public int fogMode() {
		return FOG_OFF;
	}

	/** Minus one while the fog is off; otherwise Iris promises 0 spherical and 1 cylindrical. */
	@Override
	public int fogShape() {
		return FOG_SHAPE_OFF;
	}

	@Override
	public boolean heavyFog() {
		return this.heavyFog;
	}

	// player

	@Override
	public int isEyeInWater() {
		return this.isEyeInWater;
	}

	@Override
	public Vector3dc eyePosition() {
		return this.eyePosition;
	}

	@Override
	public Vector3fc playerLookVector() {
		return this.playerLookVector;
	}

	@Override
	public Vector3fc playerBodyVector() {
		return this.playerBodyVector;
	}

	@Override
	public float blindness() {
		return this.blindness;
	}

	@Override
	public float darknessFactor() {
		return this.darknessFactor;
	}

	@Override
	public float nightVision() {
		return this.nightVision;
	}

	@Override
	public float darknessLightFactor() {
		return this.darknessLightFactor;
	}

	@Override
	public int cameraEntityTickCount() {
		return this.cameraEntityTickCount;
	}

	@Override
	public float screenBrightness() {
		return this.screenBrightness;
	}

	@Override
	public float playerMood() {
		return this.playerMood;
	}

	/** Iris reads this through an interface it mixes into the player. Nothing public carries it. */
	@Override
	public float constantMood() {
		return 0.0F;
	}

	@Override
	public int eyeBrightnessBlock() {
		return this.eyeBrightnessBlock;
	}

	@Override
	public int eyeBrightnessSky() {
		return this.eyeBrightnessSky;
	}

	@Override
	public boolean sneaking() {
		return this.sneaking;
	}

	@Override
	public boolean sprinting() {
		return this.sprinting;
	}

	@Override
	public boolean hurt() {
		return this.hurt;
	}

	@Override
	public boolean invisible() {
		return this.invisible;
	}

	@Override
	public boolean burning() {
		return this.burning;
	}

	@Override
	public boolean onGround() {
		return this.onGround;
	}

	@Override
	public boolean hideGui() {
		return this.hideGui;
	}

	@Override
	public boolean rightHanded() {
		return this.rightHanded;
	}

	@Override
	public boolean spectator() {
		return this.spectator;
	}

	@Override
	public boolean firstPerson() {
		return this.firstPerson;
	}

	@Override
	public boolean elytraFlying() {
		return this.elytraFlying;
	}

	@Override
	public boolean riding() {
		return this.riding;
	}

	@Override
	public boolean feetInWater() {
		return this.feetInWater;
	}

	@Override
	public boolean swimming() {
		return this.swimming;
	}

	@Override
	public boolean vehicleInWater() {
		return this.vehicleInWater;
	}

	/** Needs the pack's entity.properties table, which nothing reads yet. */
	@Override
	public int vehicleId() {
		return 0;
	}

	@Override
	public Vector3dc vehicleLookVector() {
		return this.vehicleLookVector;
	}

	@Override
	public Vector3dc relativeVehiclePosition() {
		return this.relativeVehiclePosition;
	}

	@Override
	public float playerHealth() {
		return this.playerHealth;
	}

	@Override
	public float playerMaxHealth() {
		return this.playerMaxHealth;
	}

	@Override
	public float playerHunger() {
		return this.playerHunger;
	}

	@Override
	public float playerMaxHunger() {
		return MAX_HUNGER;
	}

	@Override
	public float playerArmor() {
		return this.playerArmor;
	}

	@Override
	public float playerMaxArmor() {
		return MAX_ARMOR;
	}

	@Override
	public float playerAir() {
		return this.playerAir;
	}

	@Override
	public float playerMaxAir() {
		return this.playerMaxAir;
	}

	@Override
	public Vector3fc selectedBlockPos() {
		return this.selectedBlockPos;
	}

	/** Needs the pack's block.properties table, which nothing reads yet. */
	@Override
	public int selectedBlockId() {
		return 0;
	}

	@Override
	public Vector4fc lightningBoltPosition() {
		return this.lightningBoltPosition;
	}

	/**
	 * Needs the item the player is holding, which nothing here has. The pack's own table is read and
	 * live, {@code PackNameIds}, and is asked about what is being DRAWN rather than about what is in
	 * a hand; the two are different questions and this is the one still owed.
	 */
	@Override
	public int heldItemId() {
		return -1;
	}

	@Override
	public int heldItemId2() {
		return -1;
	}

	@Override
	public int heldBlockLight() {
		return this.heldBlockLight;
	}

	@Override
	public int heldBlockLight2() {
		return this.heldBlockLight2;
	}

	// engine settings and atlas

	@Override
	public int atlasWidth() {
		return this.atlasWidth;
	}

	@Override
	public int atlasHeight() {
		return this.atlasHeight;
	}

	// The same number EngineDefines writes for the stage, and it is the ordinal on both sides.
	@Override
	@SuppressWarnings("EnumOrdinal")
	public int renderStage() {
		return this.stage.ordinal();
	}

	@Override
	public float anisotropy() {
		return this.anisotropy;
	}

	/** Ours to choose, and there is no settings screen to choose it from yet. */
	@Override
	public int colorSpace() {
		return 0;
	}

	@Override
	public int textureFilteringMode() {
		return this.textureFilteringMode;
	}

	@Override
	public float chunkFadeTimeInv() {
		return this.chunkFadeTimeInv;
	}

	// pack directives

	@Override
	public float wetnessHalfLife() {
		return this.directives.wetnessHalflife();
	}

	/** Always 200, whatever the pack declares. The directives layer holds the reason. */
	@Override
	public float drynessHalfLife() {
		return this.directives.drynessHalflife();
	}

	@Override
	public float eyeBrightnessHalfLife() {
		return this.directives.eyeBrightnessHalflife();
	}

	@Override
	public float centerDepthHalfLife() {
		return this.directives.centerDepthHalflife();
	}

	@Override
	public float ambientOcclusionLevel() {
		return this.directives.ambientOcclusionLevel();
	}

	@Override
	public float noiseTextureResolution() {
		return this.directives.noiseTextureResolution();
	}

	@Override
	public float shadowDistance() {
		return this.directives.shadowDistance();
	}

	@Override
	public float shadowNearPlane() {
		return this.directives.shadowNearPlane();
	}

	@Override
	public float shadowFarPlane() {
		return this.directives.shadowFarPlane();
	}

	@Override
	public float shadowIntervalSize() {
		return this.directives.shadowIntervalSize();
	}

	// view geometry, delegated

	@Override
	public Matrix4fc gbufferModelView() {
		return this.view.gbufferModelView();
	}

	@Override
	public Matrix4fc gbufferModelViewInverse() {
		return this.view.gbufferModelViewInverse();
	}

	@Override
	public Matrix4fc passModelView() {
		return this.view.passModelView();
	}

	@Override
	public Matrix4fc cameraBob() {
		return this.view.cameraBob();
	}

	@Override
	public Matrix4fc passModelViewInverse() {
		return this.view.passModelViewInverse();
	}

	@Override
	public Matrix4fc passProjection() {
		return this.view.passProjection();
	}

	@Override
	public Matrix4fc passProjectionInverse() {
		return this.view.passProjectionInverse();
	}

	@Override
	public Vector4fc passColour() {
		return this.view.passColour();
	}

	@Override
	public Matrix4fc gbufferProjection() {
		return this.view.gbufferProjection();
	}

	@Override
	public Matrix4fc gbufferProjectionInverse() {
		return this.view.gbufferProjectionInverse();
	}

	@Override
	public Matrix4fc gbufferPreviousModelView() {
		return this.view.gbufferPreviousModelView();
	}

	@Override
	public Matrix4fc gbufferPreviousProjection() {
		return this.view.gbufferPreviousProjection();
	}

	@Override
	public Matrix4fc shadowModelView() {
		return this.view.shadowModelView();
	}

	@Override
	public Matrix4fc shadowModelViewInverse() {
		return this.view.shadowModelViewInverse();
	}

	@Override
	public Matrix4fc shadowProjection() {
		return this.view.shadowProjection();
	}

	@Override
	public Matrix4fc shadowProjectionInverse() {
		return this.view.shadowProjectionInverse();
	}

	@Override
	public Matrix4fc drawnShadowModelView() {
		return this.view.drawnShadowModelView();
	}

	@Override
	public Matrix4fc drawnShadowModelViewInverse() {
		return this.view.drawnShadowModelViewInverse();
	}

	@Override
	public Matrix4fc drawnShadowProjection() {
		return this.view.drawnShadowProjection();
	}

	@Override
	public Matrix4fc drawnShadowProjectionInverse() {
		return this.view.drawnShadowProjectionInverse();
	}

	@Override
	public Matrix4fc drawnDistantProjection() {
		return this.view.drawnDistantProjection();
	}

	@Override
	public boolean distantDepthPair(Vector2f dest) {
		return this.view.distantDepthPair(dest);
	}

	@Override
	public Matrix4fc dhProjection() {
		return this.view.dhProjection();
	}

	@Override
	public Matrix4fc dhProjectionInverse() {
		return this.view.dhProjectionInverse();
	}

	@Override
	public Matrix4fc dhPreviousProjection() {
		return this.view.dhPreviousProjection();
	}

	@Override
	public float dhNearPlane() {
		return this.view.dhNearPlane();
	}

	@Override
	public float dhFarPlane() {
		return this.view.dhFarPlane();
	}

	@Override
	public int dhRenderDistance() {
		return this.view.dhRenderDistance();
	}

	@Override
	public float near() {
		return this.view.near();
	}

	@Override
	public float far() {
		return this.view.far();
	}

	@Override
	public Vector4fc depthConvention() {
		return this.view.depthConvention();
	}
}
