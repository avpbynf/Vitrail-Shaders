package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.glsl.TranslatedUnit;

import java.util.List;

/**
 * Writes a block and holds every byte of it to {@link Std140Reference}: each component of each
 * element where the specification puts it, the value the source answers for that element, zeroes
 * where a name is not answered, and nothing at all in the padding between.
 * <p>
 * The expected value of a member is what its own source answers when it is asked directly, so the
 * check is about where {@link UniformBlock} puts things and not about what the sources compute.
 * Members must be declared under the shape their source holds: coercion is not this check's
 * business.
 */
final class BlockCheck {

	private BlockCheck() {
	}

	static void verify(List<TranslatedUnit.Uniform> declared, UniformCatalog catalog, WorldState world) {
		List<String> declarations = declared.stream().map(TranslatedUnit.Uniform::declaration).toList();
		Std140Reference.Layout layout = Std140Reference.of(declarations);

		UniformBlock block = new UniformBlock(declared, catalog);
		assertEquals(layout.size(), block.size(), "the size is where the last member ends");
		assertEquals(declared.stream().map(TranslatedUnit.Uniform::name)
				.filter(name -> catalog.source(name) == null).toList(), block.unanswered(),
				"the names nothing answers, in declaration order");

		BytesSink sink = new BytesSink(layout.size() + 64);
		block.write(sink, world);
		assertEquals(layout.size(), sink.position(), "the write ends where the size says");

		boolean[] covered = new boolean[sink.array().length];
		Val expected = new Val();
		for (TranslatedUnit.Uniform member : declared) {
			UniformShape shape = UniformShape.of(member.type());
			Std140Reference.Slot slot = layout.slot(member.name());
			UniformSource source = catalog.source(member.name());

			for (int element = 0; element < slot.elements(); element++) {
				if (source != null) {
					source.read(world, expected, element);
				}

				for (int c = 0; c < shape.rank(); c++) {
					int at = slot.elementOffset(element) + offset(shape, c);
					String what = member.declaration() + " element " + element + " component " + c
							+ " at " + at;
					if (integral(shape)) {
						assertEquals(source == null ? 0 : expected.i(c), sink.intAt(at), what);
					} else {
						assertEquals(source == null ? 0.0F : expected.f(c), sink.floatAt(at), what);
					}

					for (int b = 0; b < 4; b++) {
						covered[at + b] = true;
					}
				}
			}
		}

		for (int at = 0; at < covered.length; at++) {
			if (!covered[at]) {
				assertEquals(0, sink.array()[at], "padding byte " + at + " was written");
			}
		}
	}

	/** Where component {@code c} of one element sits, relative to the element: the shape's own layout. */
	private static int offset(UniformShape shape, int c) {
		// A mat3 is three columns of three at a stride of sixteen; everything else is contiguous.
		return shape == UniformShape.MAT3 ? c / 3 * 16 + c % 3 * 4 : c * 4;
	}

	private static boolean integral(UniformShape shape) {
		return shape == UniformShape.INT || shape == UniformShape.IVEC2 || shape == UniformShape.IVEC3
				|| shape == UniformShape.IVEC4;
	}

	/** The GLSL type a shape is declared as, for a block made of the names a catalogue answers. */
	static String type(UniformShape shape) {
		return switch (shape) {
			case FLOAT -> "float";
			case INT -> "int";
			case VEC2 -> "vec2";
			case VEC3 -> "vec3";
			case VEC4 -> "vec4";
			case IVEC2 -> "ivec2";
			case IVEC3 -> "ivec3";
			case IVEC4 -> "ivec4";
			case MAT3 -> "mat3";
			case MAT4 -> "mat4";
			case FOG -> "OfFog";
		};
	}
}
