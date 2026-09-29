package dev.vitrail.uniform;

import java.util.ArrayList;
import java.util.List;

/**
 * The std140 layout of a block worked out from the text of its declarations and the rules of the
 * OpenGL specification (section 7.6.2.2) alone, as the second reading the tests hold
 * {@link UniformBlock} to.
 * <p>
 * It shares no code with {@link Std140Counter}, {@link UniformShape} or {@link UniformBlock}, and
 * it answers by the rules as the specification words them: a scalar aligns on four, a two vector on
 * eight, a three or four vector on sixteen, a matrix is an array of its columns, an array element
 * starts on a multiple of sixteen and the array is its stride times its length, and a structure
 * aligns on its widest member rounded up to sixteen and is padded to a multiple of that.
 */
final class Std140Reference {

	/** Where one member starts, and what one of its elements costs. */
	record Slot(String name, int offset, int stride, int elements) {

		/** Where element {@code element} starts; a bare member has the one. */
		int elementOffset(int element) {
			return this.offset + element * this.stride;
		}
	}

	/** Every member's slot in declaration order, and the offset one past the last byte written. */
	record Layout(List<Slot> slots, int size) {

		Slot slot(String name) {
			return this.slots.stream().filter(slot -> slot.name().equals(name)).findFirst().orElseThrow();
		}
	}

	/** Base alignment and size of a non-array type, both in bytes. */
	private record Type(int alignment, int size) {
	}

	private Std140Reference() {
	}

	/**
	 * @param declarations {@code "<type> <name>"} with any number of {@code [n]} after the name, the
	 *                     shapes packs declare
	 */
	static Layout of(List<String> declarations) {
		List<Slot> slots = new ArrayList<>();
		int cursor = 0;

		for (String declaration : declarations) {
			int space = declaration.indexOf(' ');
			Type element = type(declaration.substring(0, space));
			String rest = declaration.substring(space + 1).trim();
			int bracket = rest.indexOf('[');
			String name = bracket < 0 ? rest : rest.substring(0, bracket);

			int count = 1;
			boolean array = bracket >= 0;
			for (int at = bracket; at >= 0; at = rest.indexOf('[', at + 1)) {
				count *= Integer.parseInt(rest.substring(at + 1, rest.indexOf(']', at)).trim());
			}

			int alignment = element.alignment();
			int stride = element.size();
			if (array) {
				// Rule 4: the element of an array is aligned and strided as a vec4 at least.
				alignment = roundUp(alignment, 16);
				stride = roundUp(stride, 16);
			}

			int offset = roundUp(cursor, alignment);
			int size = array ? count * stride : element.size();
			slots.add(new Slot(name, offset, stride, count));
			cursor = offset + size;
		}

		return new Layout(List.copyOf(slots), cursor);
	}

	private static Type type(String glsl) {
		return switch (glsl) {
			case "float", "int", "uint", "bool" -> new Type(4, 4);
			case "vec2", "ivec2", "uvec2", "bvec2" -> new Type(8, 8);
			// The three vector aligns as a four vector and is twelve long: the member behind it
			// starts at twelve when it can, which is the whole trap.
			case "vec3", "ivec3", "uvec3", "bvec3" -> new Type(16, 12);
			case "vec4", "ivec4", "uvec4", "bvec4" -> new Type(16, 16);
			// Rules 5 and 7: a matrix is an array of its columns, and an array of vec3 strides sixteen.
			case "mat3", "mat3x3" -> new Type(16, 3 * 16);
			case "mat4", "mat4x4" -> new Type(16, 4 * 16);
			case "OfFog" -> struct("vec4", "float", "float", "float", "float");
			default -> throw new IllegalArgumentException(glsl);
		};
	}

	/** Rule 9: aligned on the widest member rounded up to sixteen, and padded to a multiple of it. */
	private static Type struct(String... members) {
		int cursor = 0;
		int widest = 0;
		for (String member : members) {
			Type type = type(member);
			cursor = roundUp(cursor, type.alignment()) + type.size();
			widest = Math.max(widest, type.alignment());
		}

		int alignment = roundUp(widest, 16);

		return new Type(alignment, roundUp(cursor, alignment));
	}

	private static int roundUp(int value, int multiple) {
		return (value + multiple - 1) / multiple * multiple;
	}
}
