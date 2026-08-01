package dev.vitrail.render;

import dev.vitrail.glsl.TranslatedUnit;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Fills the uniform block a translated program declares.
 * <p>
 * The block is written by walking its members in the order the translation put them, which is
 * the whole reason that order is part of the result rather than something worked out again here.
 * Sizing and writing walk the same list through the same switch, so a member can never be
 * measured one way and written another.
 * <p>
 * A name nothing supplies yet is written as zeroes rather than skipped. Skipping would shift
 * every member after it and quietly corrupt the ones that <em>are</em> supplied; writing zeroes
 * keeps the layout and loses only that value. The names are collected so the gap can be said out
 * loud instead of being discovered as a wrong image.
 */
final class PackUniforms {

	/** Everything the engine can answer this frame. */
	record Frame(float viewWidth, float viewHeight, float frameTimeCounter, float rainStrength,
			boolean sneaking, Matrix4f quadProjection) {
	}

	private static final Matrix4f IDENTITY = new Matrix4f();

	private final List<TranslatedUnit.Uniform> members;
	private final List<String> unsupplied = new ArrayList<>();
	private final int size;

	PackUniforms(List<TranslatedUnit.Uniform> members) {
		this.members = List.copyOf(members);

		Std140SizeCalculator calculator = new Std140SizeCalculator();
		for (TranslatedUnit.Uniform member : this.members) {
			int length = arrayLength(member);
			if (length < 1) {
				throw new IllegalStateException(
						"Cannot size " + member.declaration() + ": the array length is not a literal");
			}

			for (int element = 0; element < length; element++) {
				// Every element of an array starts on a sixteen byte boundary in std140, which
				// for anything smaller than a vec4 is not what putting it back to back gives.
				if (length > 1) {
					calculator.align(16);
				}

				size(calculator, member.type(), member.declaration());
			}

			if (!supplies(member.name())) {
				this.unsupplied.add(member.name());
			}
		}

		this.size = calculator.get();
	}

	int size() {
		return this.size;
	}

	/** Names the block declares that the engine does not answer yet, in declaration order. */
	List<String> unsupplied() {
		return List.copyOf(this.unsupplied);
	}

	void write(Std140Builder builder, Frame frame) {
		for (TranslatedUnit.Uniform member : this.members) {
			int length = arrayLength(member);
			for (int element = 0; element < length; element++) {
				if (length > 1) {
					builder.align(16);
				}

				write(builder, member, frame);
			}
		}
	}

	private static void size(Std140SizeCalculator calculator, String type, String declaration) {
		switch (type) {
			case "float" -> calculator.putFloat();
			case "int", "uint", "bool" -> calculator.putInt();
			case "vec2" -> calculator.putVec2();
			case "vec3" -> calculator.putVec3();
			case "vec4" -> calculator.putVec4();
			case "ivec2", "uvec2", "bvec2" -> calculator.putIVec2();
			case "ivec3", "uvec3", "bvec3" -> calculator.putIVec3();
			case "ivec4", "uvec4", "bvec4" -> calculator.putIVec4();
			// A mat3 is three columns, each padded out to a vec4. There is no putMat3.
			case "mat3" -> calculator.putVec3().putVec3().putVec3();
			case "mat4" -> calculator.putMat4f();
			default -> throw new IllegalStateException(
					"Cannot size " + declaration + ": nothing here knows the type " + type);
		}
	}

	private void write(Std140Builder builder, TranslatedUnit.Uniform member, Frame frame) {
		switch (member.name()) {
			// For a pass drawn over a quad the model view is the identity and the projection is
			// the one that carries that quad onto the screen, so the product is the projection.
			case "of_ModelViewProjectionMatrix", "of_ProjectionMatrix" -> builder.putMat4f(frame.quadProjection());
			case "of_ModelViewMatrix", "of_ModelViewMatrixInverse", "of_TextureMatrix" -> builder.putMat4f(IDENTITY);
			case "of_NormalMatrix" -> builder.putVec3(1.0F, 0.0F, 0.0F).putVec3(0.0F, 1.0F, 0.0F).putVec3(0.0F, 0.0F, 1.0F);
			case "viewWidth" -> builder.putFloat(frame.viewWidth());
			case "viewHeight" -> builder.putFloat(frame.viewHeight());
			case "aspectRatio" -> builder.putFloat(frame.viewWidth() / Math.max(1.0F, frame.viewHeight()));
			case "frameTimeCounter" -> builder.putFloat(frame.frameTimeCounter());
			case "rainStrength", "wetness" -> builder.putFloat(frame.rainStrength());
			case "isSneaking" -> putBoolean(builder, member.type(), frame.sneaking());
			default -> zero(builder, member.type());
		}
	}

	/**
	 * Packs disagree about the type of the flags: Body Camera declares {@code isSneaking} a
	 * float, and the same name is a bool elsewhere. The declaration decides, not the name.
	 */
	private static void putBoolean(Std140Builder builder, String type, boolean value) {
		if (type.equals("float")) {
			builder.putFloat(value ? 1.0F : 0.0F);
		} else {
			builder.putInt(value ? 1 : 0);
		}
	}

	private static void zero(Std140Builder builder, String type) {
		switch (type) {
			case "float" -> builder.putFloat(0.0F);
			case "int", "uint", "bool" -> builder.putInt(0);
			case "vec2" -> builder.putVec2(0.0F, 0.0F);
			case "vec3" -> builder.putVec3(0.0F, 0.0F, 0.0F);
			case "vec4" -> builder.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
			case "ivec2", "uvec2", "bvec2" -> builder.putIVec2(0, 0);
			case "ivec3", "uvec3", "bvec3" -> builder.putIVec3(0, 0, 0);
			case "ivec4", "uvec4", "bvec4" -> builder.putIVec4(0, 0, 0, 0);
			case "mat3" -> builder.putVec3(0.0F, 0.0F, 0.0F).putVec3(0.0F, 0.0F, 0.0F).putVec3(0.0F, 0.0F, 0.0F);
			case "mat4" -> builder.putMat4f(new Matrix4f().zero());
			default -> throw new IllegalStateException("Nothing here knows the type " + type);
		}
	}

	private static boolean supplies(String name) {
		return switch (name) {
			case "of_ModelViewProjectionMatrix", "of_ProjectionMatrix", "of_ModelViewMatrix",
					"of_ModelViewMatrixInverse", "of_TextureMatrix", "of_NormalMatrix",
					"viewWidth", "viewHeight", "aspectRatio", "frameTimeCounter",
					"rainStrength", "wetness", "isSneaking" -> true;
			default -> false;
		};
	}

	/** How many elements a declaration carries, 1 when it is not an array, -1 when it cannot be read. */
	private static int arrayLength(TranslatedUnit.Uniform member) {
		String declaration = member.declaration();
		int open = declaration.indexOf('[');
		if (open < 0) {
			return 1;
		}

		int close = declaration.indexOf(']', open);
		if (close < 0) {
			return -1;
		}

		try {
			return Integer.parseInt(declaration.substring(open + 1, close).trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
