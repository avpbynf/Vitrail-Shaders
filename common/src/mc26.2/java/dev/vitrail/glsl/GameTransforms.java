package dev.vitrail.glsl;

import java.util.List;

/**
 * The game's transforms block, declared exactly as the game fills it.
 * <p>
 * All four members and not the two that are read, because std140 matches by OFFSET: the texture
 * matrix sits at ninety six bytes, behind a {@code mat4}, a {@code vec4} and a {@code vec3}, and a
 * block declaring only the last of the four would read the model view instead. The order is
 * {@code DynamicUniforms.Transform.write} at {@code renderer/DynamicUniforms.java:84}, and the
 * same four in the same order are what Iris declares at
 * {@code transform/transformer/VanillaTransformer.java:52-57}.
 * <p>
 * The other two are named rather than padded so that a reader meets the reason they are here.
 * Nothing reads them and nothing should: for a draw the game prepares from a render type the
 * modulator is always white and the offset always nought
 * ({@code rendertype/RenderType.java:76} reaching the two argument
 * {@code DynamicUniforms.writeTransform}, {@code renderer/DynamicUniforms.java:48-50}).
 * <p>
 * The 26.2 half. The order is the game's and not this engine's, which is why the block is declared
 * once per game: 26.3 moved the texture matrix to the front.
 */
public final class GameTransforms {

	/** The declaration, one line to an entry, as {@link Emitter} writes it into a stage. */
	public static final List<String> BLOCK = List.of(
			"layout(std140) uniform " + LegacyGlsl.GAME_TRANSFORMS + " {",
			"\tmat4 " + LegacyGlsl.GAME_MODEL_VIEW + ";",
			"\tvec4 of_GameColorModulator;",
			"\tvec3 of_GameModelOffset;",
			"\tmat4 " + LegacyGlsl.GAME_TEXTURE_MATRIX + ";",
			"};");

	private GameTransforms() {
	}
}
