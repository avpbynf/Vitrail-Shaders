package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link UniformShape} to the GLSL types a block member can be declared as, the components
 * each carries, and the bytes each costs whichever way it is written.
 * <p>
 * The costs are the std140 table, not the counter's answer, and the types that are refused are
 * pinned as firmly as those that are accepted: a type nobody can size is a block that must refuse
 * to be built, because every member behind it would land at the wrong offset.
 */
class UniformShapeTest {

	/** The GLSL type names a pack can declare, and the shape each takes in the block. */
	private static final Map<String, UniformShape> ACCEPTED = Map.ofEntries(
			Map.entry("float", UniformShape.FLOAT),
			Map.entry("int", UniformShape.INT),
			Map.entry("uint", UniformShape.INT),
			Map.entry("bool", UniformShape.INT),
			Map.entry("vec2", UniformShape.VEC2),
			Map.entry("vec3", UniformShape.VEC3),
			Map.entry("vec4", UniformShape.VEC4),
			Map.entry("ivec2", UniformShape.IVEC2),
			Map.entry("uvec2", UniformShape.IVEC2),
			Map.entry("bvec2", UniformShape.IVEC2),
			Map.entry("ivec3", UniformShape.IVEC3),
			Map.entry("uvec3", UniformShape.IVEC3),
			Map.entry("bvec3", UniformShape.IVEC3),
			Map.entry("ivec4", UniformShape.IVEC4),
			Map.entry("uvec4", UniformShape.IVEC4),
			Map.entry("bvec4", UniformShape.IVEC4),
			Map.entry("mat3", UniformShape.MAT3),
			Map.entry("mat3x3", UniformShape.MAT3),
			Map.entry("mat4", UniformShape.MAT4),
			Map.entry("mat4x4", UniformShape.MAT4),
			Map.entry("OfFog", UniformShape.FOG));

	/**
	 * What a shape consumes in std140 from an offset of nought, in bytes. A fog struct is a vec4
	 * and four floats, aligned on sixteen and padded to it: thirty two.
	 */
	private static final Map<UniformShape, Integer> BYTES = Map.ofEntries(
			Map.entry(UniformShape.FLOAT, 4),
			Map.entry(UniformShape.INT, 4),
			Map.entry(UniformShape.VEC2, 8),
			Map.entry(UniformShape.VEC3, 12),
			Map.entry(UniformShape.VEC4, 16),
			Map.entry(UniformShape.IVEC2, 8),
			Map.entry(UniformShape.IVEC3, 12),
			Map.entry(UniformShape.IVEC4, 16),
			Map.entry(UniformShape.MAT3, 48),
			Map.entry(UniformShape.MAT4, 64),
			Map.entry(UniformShape.FOG, 32));

	@Test
	void mapsEveryTypeAPackCanDeclareToItsShape() {
		ACCEPTED.forEach((glsl, shape) -> assertEquals(shape, UniformShape.of(glsl), glsl));
	}

	@Test
	void refusesEveryTypeNothingHereCanSize() {
		// The double family is eight bytes aligned on eight (a dvec3 thirty two on thirty two), so
		// treating it as its single precision namesake would write half a value and move everything
		// behind it. mat2 and the non-square matrices have no shape at all. A sampler is not a member.
		List<String> refused = List.of("double", "dvec2", "dvec3", "dvec4", "dmat2", "dmat3", "dmat4",
				"mat2", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x4", "mat4x2", "mat4x3",
				"sampler2D", "usampler2D", "image2D", "void", "", "Vec3", "FLOAT", "float[2]", "vec4 ",
				"OfFog[2]", "struct");

		for (String type : refused) {
			assertNull(UniformShape.of(type), "'" + type + "'");
		}
	}

	@Test
	void everyShapeIsCoveredByTheTypeTable() {
		assertEquals(List.of(UniformShape.values()).size(), BYTES.size());
		assertEquals(List.of(UniformShape.values()).size(),
				ACCEPTED.values().stream().distinct().count());
	}

	@Test
	void carriesTheComponentsTheNameSays() {
		Map<UniformShape, Integer> ranks = Map.ofEntries(
				Map.entry(UniformShape.FLOAT, 1),
				Map.entry(UniformShape.INT, 1),
				Map.entry(UniformShape.VEC2, 2),
				Map.entry(UniformShape.VEC3, 3),
				Map.entry(UniformShape.VEC4, 4),
				Map.entry(UniformShape.IVEC2, 2),
				Map.entry(UniformShape.IVEC3, 3),
				Map.entry(UniformShape.IVEC4, 4),
				Map.entry(UniformShape.MAT3, 9),
				Map.entry(UniformShape.MAT4, 16),
				Map.entry(UniformShape.FOG, 8));

		for (UniformShape shape : UniformShape.values()) {
			assertEquals(ranks.get(shape), shape.rank(), shape.name());
		}
	}

	@Test
	void zeroingCostsWhatTheShapeCosts() {
		for (UniformShape shape : UniformShape.values()) {
			Std140Counter counter = new Std140Counter();
			shape.zero(counter);

			assertEquals(BYTES.get(shape), counter.size(), shape.name());
		}
	}

	@Test
	void writingCostsWhatZeroingDoesForAValueOfAnyRank() {
		Val[] values = {
				new Val().set(1.5F),
				new Val().set(1.5F, 2.5F),
				new Val().set(1.5F, 2.5F, 3.5F),
				new Val().set(1.5F, 2.5F, 3.5F, 4.5F),
				new Val().set(7),
				new Val().set(7, 8, 9, 10),
				new Val().set(new Matrix3f()),
				new Val().set(new Matrix4f()),
				new Val().setFog(1, 2, 3, 4, 5, 6, 7, 8) };

		for (UniformShape shape : UniformShape.values()) {
			for (int start : new int[] { 0, 4, 8, 12 }) {
				for (Val value : values) {
					Std140Counter zeroed = new Std140Counter();
					Std140Counter written = new Std140Counter();
					for (int i = 0; i < start; i += 4) {
						zeroed.putFloat(0.0F);
						written.putFloat(0.0F);
					}

					shape.zero(zeroed);
					shape.write(written, value);

					assertEquals(zeroed.size(), written.size(),
							shape + " over a rank " + value.rank() + " value from offset " + start);
				}
			}
		}
	}

	@Test
	void aFogStructStartsAndEndsOnSixteen() {
		// float a; OfFog f; float b;  the struct at sixteen, thirty two long, b right behind it.
		Std140Counter counter = new Std140Counter();
		counter.putFloat(1.0F);
		UniformShape.FOG.zero(counter);
		counter.putFloat(2.0F);

		assertEquals(52, counter.size());
	}
}
