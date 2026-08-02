package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

/**
 * The player: where they are looking, what is happening to them, and the flags a pack branches on.
 * <p>
 * The flags are the clearest case of the rule that runs through this package: the type the pack
 * declared decides what is written. Mellow declares {@code hideGUI} a bool and Bliss declares the
 * same name an int, and both are served, because the value is registered once under the shape the
 * engine holds it in and converted at the member.
 * <p>
 * Two things here look like mistakes and are not. Health, hunger, air and armour are <b>ratios</b>
 * while their {@code max*} partners are absolutes, which is what Iris publishes and therefore what
 * a pack divides by; and six of them answer -1 rather than 0 outside survival, because a pack that
 * tests for "not applicable" by looking for a negative number would otherwise see a dying player.
 */
public final class PlayerValues {

	private static final FrameSmoothed EYE_BLOCK = new FrameSmoothed();
	private static final FrameSmoothed EYE_SKY = new FrameSmoothed();

	private PlayerValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("isEyeInWater", UniformShape.INT, (world, out) -> out.set(world.isEyeInWater()));
		builder.add("blindness", UniformShape.FLOAT, (world, out) -> out.set(world.blindness()));
		builder.add("darknessFactor", UniformShape.FLOAT,
				(world, out) -> out.set(world.darknessFactor()));
		builder.add("darknessLightFactor", UniformShape.FLOAT,
				(world, out) -> out.set(world.darknessLightFactor()));
		builder.add("nightVision", UniformShape.FLOAT, (world, out) -> out.set(world.nightVision()));
		builder.add("screenBrightness", UniformShape.FLOAT,
				(world, out) -> out.set(world.screenBrightness()));
		builder.add("playerMood", UniformShape.FLOAT, (world, out) -> out.set(world.playerMood()));
		builder.add("constantMood", UniformShape.FLOAT,
				(world, out) -> out.set(world.constantMood()));

		builder.add("eyeBrightness", UniformShape.IVEC2,
				(world, out) -> out.set(world.eyeBrightnessBlock(), world.eyeBrightnessSky()));
		// Truncated to an integer after the smoothing and not before, which is what makes the
		// value climb one step at a time on the way out of a cave instead of snapping.
		builder.add("eyeBrightnessSmooth", UniformShape.IVEC2, (world, out) -> {
			float halfLife = world.eyeBrightnessHalfLife();
			out.set((int) EYE_BLOCK.get(world, world.eyeBrightnessBlock(), halfLife, halfLife),
					(int) EYE_SKY.get(world, world.eyeBrightnessSky(), halfLife, halfLife));
		});

		// is_sneaking and nothing else. isSneaking is not an engine value: it is Body Camera's one
		// custom uniform, if(is_sneaking, 1.0, 0.0), and a pack's declaration that shadows an
		// engine name is refused. Answering it here would take the pack's only declaration away
		// from it to hand back the same number under a shape it did not ask for.
		builder.add("is_sneaking", UniformShape.INT, (world, out) -> out.set(world.sneaking()));
		builder.add("is_sprinting", UniformShape.INT, (world, out) -> out.set(world.sprinting()));
		builder.add("is_hurt", UniformShape.INT, (world, out) -> out.set(world.hurt()));
		builder.add("is_invisible", UniformShape.INT, (world, out) -> out.set(world.invisible()));
		builder.add("is_burning", UniformShape.INT, (world, out) -> out.set(world.burning()));
		builder.add("is_on_ground", UniformShape.INT, (world, out) -> out.set(world.onGround()));
		builder.add("hideGUI", UniformShape.INT, (world, out) -> out.set(world.hideGui()));
		builder.add("isRightHanded", UniformShape.INT, (world, out) -> out.set(world.rightHanded()));
		builder.add("isSpectator", UniformShape.INT, (world, out) -> out.set(world.spectator()));
		builder.add("firstPersonCamera", UniformShape.INT,
				(world, out) -> out.set(world.firstPerson()));
		builder.add("isElytraFlying", UniformShape.INT,
				(world, out) -> out.set(world.elytraFlying()));
		builder.add("isRiding", UniformShape.INT, (world, out) -> out.set(world.riding()));
		builder.add("feetInWater", UniformShape.INT, (world, out) -> out.set(world.feetInWater()));
		builder.add("inSwimmingAnimation", UniformShape.INT,
				(world, out) -> out.set(world.swimming()));
		builder.add("vehicleInWater", UniformShape.INT,
				(world, out) -> out.set(world.vehicleInWater()));
		builder.add("heavyFog", UniformShape.INT, (world, out) -> out.set(world.heavyFog()));

		builder.add("playerLookVector", UniformShape.VEC3,
				(world, out) -> out.set(world.playerLookVector()));
		builder.add("playerBodyVector", UniformShape.VEC3,
				(world, out) -> out.set(world.playerBodyVector()));

		builder.add("vehicleId", UniformShape.INT, (world, out) -> out.set(world.vehicleId()));
		builder.add("vehicleLookVector", UniformShape.VEC3,
				(world, out) -> out.set(world.vehicleLookVector()));
		builder.add("relativeVehiclePosition", UniformShape.VEC3,
				(world, out) -> out.set(world.relativeVehiclePosition()));

		builder.add("currentPlayerHealth", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerHealth()));
		builder.add("maxPlayerHealth", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerMaxHealth()));
		builder.add("currentPlayerHunger", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerHunger()));
		builder.add("maxPlayerHunger", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerMaxHunger()));
		builder.add("currentPlayerArmor", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerArmor()));
		builder.add("maxPlayerArmor", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerMaxArmor()));
		builder.add("currentPlayerAir", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerAir()));
		builder.add("maxPlayerAir", UniformShape.FLOAT,
				(world, out) -> out.set(world.playerMaxAir()));

		builder.add("currentSelectedBlockPos", UniformShape.VEC3,
				(world, out) -> out.set(world.selectedBlockPos()));
		builder.add("currentSelectedBlockId", UniformShape.INT,
				(world, out) -> out.set(world.selectedBlockId()));
		builder.add("lightningBoltPosition", UniformShape.VEC4, (world, out) -> out.set(
				world.lightningBoltPosition().x(), world.lightningBoltPosition().y(),
				world.lightningBoltPosition().z(), world.lightningBoltPosition().w()));

		builder.add("heldItemId", UniformShape.INT, (world, out) -> out.set(world.heldItemId()));
		builder.add("heldItemId2", UniformShape.INT, (world, out) -> out.set(world.heldItemId2()));
		builder.add("heldBlockLightValue", UniformShape.INT,
				(world, out) -> out.set(world.heldBlockLight()));
		builder.add("heldBlockLightValue2", UniformShape.INT,
				(world, out) -> out.set(world.heldBlockLight2()));
	}
}
