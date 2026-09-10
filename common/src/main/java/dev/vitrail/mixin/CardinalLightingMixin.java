package dev.vitrail.mixin;

import dev.vitrail.render.FaceShading;

import net.minecraft.core.Direction;
import net.minecraft.world.level.CardinalLighting;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Hands the game's per face brightness the top face whatever face it was asked about, so what it
 * gives back stops depending on which face it is, while a pack's own terrain program is drawing the
 * chunks. Not a one: in the nether the top face is 0.9 like the bottom, and that stays the answer.
 * <p>
 * Why the shading has to go at all is in {@link FaceShading}: the pack shades from the normal it is
 * handed, and the game's factor multiplied in beneath it shades the same surface twice. This is the
 * reference's own gesture, {@code mixin/vertices/block_rendering/MixinClientLevel.java:16-23},
 * against the same method.
 * <p>
 * <strong>The argument and not the return value</strong>, which is what keeps a pack's own
 * {@code CardinalLighting} table honest: the record carries six numbers and the nether's differ from
 * the overworld's, so forcing the answer to one would also throw away the dimension's own choice of
 * what a top face is worth. Asking about the top face instead reads that table where it stands.
 * <p>
 * On the chunk build threads, several at a time, and it reads one volatile field. Nothing here
 * orders the field against those builds, and nothing needs to: a section meshed on either answer is
 * covered by the world being asked for again whenever the answer really moved, which is what
 * {@link FaceShading} does. The one reader that is not a mesh is a block in flight, lit per frame,
 * and it simply follows the field.
 */
@Mixin(CardinalLighting.class)
public class CardinalLightingMixin {

	@ModifyVariable(method = "byFace", at = @At("HEAD"), argsOnly = true, require = 1)
	private Direction vitrail$topFaceWhileAPackShades(Direction face) {
		return FaceShading.dropped() ? Direction.UP : face;
	}
}
