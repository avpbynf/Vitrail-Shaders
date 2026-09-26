package dev.vitrail.glsl;

import java.util.List;

/**
 * The game's transforms block, declared exactly as the game fills it.
 * <p>
 * All four members and not the two that are read, because std140 matches by OFFSET. The 26.3 half,
 * and the order is not 26.2's: {@code DynamicGpuData.Transform.write} at
 * {@code renderer/DynamicGpuData.java:150} puts the texture matrix second, at sixty four bytes
 * behind the model view, and the modulator and the offset after it, which is also the order of the
 * game's own {@code shaders/include/dynamictransforms.glsl}. A block declared in the 26.2 order
 * reads the texture matrix ninety six bytes in, which here is the back half of the real one followed
 * by the modulator and the offset: every texture coordinate of the entity door is then sent
 * somewhere else on its sheet, and the mobs and the bare arm were drawn transparent and a held item
 * as one stretched texel.
 * <p>
 * The other two are named rather than padded so that a reader meets the reason they are here.
 * Nothing reads them and nothing should: for a draw the game prepares from a render type the
 * modulator is always white and the offset always nought
 * ({@code rendertype/RenderType.java:88} reaching the two argument
 * {@code DynamicGpuData.writeTransform}, {@code renderer/DynamicGpuData.java:70}).
 */
public final class GameTransforms {

	/** The declaration, one line to an entry, as {@link Emitter} writes it into a stage. */
	public static final List<String> BLOCK = List.of(
			"layout(std140) uniform " + LegacyGlsl.GAME_TRANSFORMS + " {",
			"\tmat4 " + LegacyGlsl.GAME_MODEL_VIEW + ";",
			"\tmat4 " + LegacyGlsl.GAME_TEXTURE_MATRIX + ";",
			"\tvec4 of_GameColorModulator;",
			"\tvec3 of_GameModelOffset;",
			"};");

	private GameTransforms() {
	}
}
