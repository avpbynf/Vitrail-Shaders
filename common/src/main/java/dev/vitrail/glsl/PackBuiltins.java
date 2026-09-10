package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The GLSL functions that pack floats into whole numbers and back, written out in integer
 * arithmetic for a MoltenVK device, so that Apple's compiler never sees its own pack builtins.
 * <p>
 * <strong>What it costs a pack to keep them there.</strong> Noble writes its material into an
 * unsigned target as two {@code packUnorm4x8} words and a {@code packUnorm2x16}, one statement
 * each, and reads the two words back with {@code unpackUnorm4x8}. MoltenVK's SPIRV-Cross renders
 * those calls correctly, each on its own vector, and on an M4 through MoltenVK 1.4.2 the first
 * word still reached the target holding the float bits of its vector's first component, the
 * second word right. A pack changed so that one function packs a single distinct vector lands the
 * first word right, and so does packing it by shifts; the decode is hit the same way, the image
 * staying wrong with the pack fixed and the unpack left standing. The same stages draw right on
 * NVIDIA. So on MoltenVK every call to one of the eight goes to a helper of this translation's
 * own, and every other driver keeps the builtins, its text byte for byte what it was.
 * <p>
 * <strong>The helpers are the definitions of GLSL 4.60, section 8.4.</strong> A unorm pack clamps
 * to nought to one and a snorm pack to minus one to one, scales by 255, 65535, 127 or 32767,
 * rounds, and lays the first component in the least significant bits, a snorm component as its
 * two's complement; an unpack divides by the same figure, and a snorm unpack clamps the quotient
 * back to minus one to one. The rounding is {@code round}, the function the definition itself is
 * written with, which SPIRV-Cross hands Metal as its own {@code round}. Adding a half before a
 * truncation would not be the same function: it takes the last float below a half up, and a
 * negative half the other way. The two roundings can only part on an exact half, one half times
 * 255 for instance, where the language leaves the direction to the implementation. The quotient of
 * an unpack is one fp32 division, which is what the builtin computes too. The argument is a
 * parameter, so it is evaluated once whatever expression the pack wrote.
 * <p>
 * A signed component goes from a byte to an integer by flipping its top bit and taking the
 * midpoint off, which is two's complement read as arithmetic and needs neither a sign-extending
 * shift nor a comparison.
 */
final class PackBuiltins {

	/** Each builtin with the helper a call to it becomes, in the order the helpers are written. */
	private static final Map<String, String> HELPERS = helpers();

	private PackBuiltins() {
	}

	/** The helper a call to this builtin becomes, or null where the name is not one of the eight. */
	static String helper(String builtin) {
		return HELPERS.get(builtin);
	}

	/**
	 * The definitions of the helpers the named builtins became, one line each, in a fixed order
	 * whatever order the calls were met in.
	 */
	static List<String> definitions(Set<String> called) {
		List<String> lines = new ArrayList<>();
		for (String builtin : HELPERS.keySet()) {
			if (called.contains(builtin)) {
				lines.add(definition(builtin));
			}
		}

		return lines;
	}

	private static String definition(String builtin) {
		String name = HELPERS.get(builtin);
		return switch (builtin) {
			case "packUnorm4x8" -> "uint " + name + "(vec4 ofV) {"
					+ " uvec4 ofB = uvec4(round(clamp(ofV, 0.0, 1.0) * 255.0));"
					+ " return ofB.x | (ofB.y << 8u) | (ofB.z << 16u) | (ofB.w << 24u); }";
			case "unpackUnorm4x8" -> "vec4 " + name + "(uint ofP) {"
					+ " return vec4(uvec4(ofP, ofP >> 8u, ofP >> 16u, ofP >> 24u) & 255u) / 255.0; }";
			case "packUnorm2x16" -> "uint " + name + "(vec2 ofV) {"
					+ " uvec2 ofB = uvec2(round(clamp(ofV, 0.0, 1.0) * 65535.0));"
					+ " return ofB.x | (ofB.y << 16u); }";
			case "unpackUnorm2x16" -> "vec2 " + name + "(uint ofP) {"
					+ " return vec2(uvec2(ofP, ofP >> 16u) & 65535u) / 65535.0; }";
			case "packSnorm4x8" -> "uint " + name + "(vec4 ofV) {"
					+ " uvec4 ofB = uvec4(ivec4(round(clamp(ofV, -1.0, 1.0) * 127.0))) & 255u;"
					+ " return ofB.x | (ofB.y << 8u) | (ofB.z << 16u) | (ofB.w << 24u); }";
			case "unpackSnorm4x8" -> "vec4 " + name + "(uint ofP) {"
					+ " ivec4 ofB = ivec4((uvec4(ofP, ofP >> 8u, ofP >> 16u, ofP >> 24u) & 255u)"
					+ " ^ 128u) - 128;"
					+ " return clamp(vec4(ofB) / 127.0, -1.0, 1.0); }";
			case "packSnorm2x16" -> "uint " + name + "(vec2 ofV) {"
					+ " uvec2 ofB = uvec2(ivec2(round(clamp(ofV, -1.0, 1.0) * 32767.0))) & 65535u;"
					+ " return ofB.x | (ofB.y << 16u); }";
			case "unpackSnorm2x16" -> "vec2 " + name + "(uint ofP) {"
					+ " ivec2 ofB = ivec2((uvec2(ofP, ofP >> 16u) & 65535u) ^ 32768u) - 32768;"
					+ " return clamp(vec2(ofB) / 32767.0, -1.0, 1.0); }";
			default -> throw new IllegalArgumentException(builtin);
		};
	}

	private static Map<String, String> helpers() {
		Map<String, String> table = new LinkedHashMap<>();
		table.put("packUnorm4x8", "ofPackUnorm4x8");
		table.put("unpackUnorm4x8", "ofUnpackUnorm4x8");
		table.put("packUnorm2x16", "ofPackUnorm2x16");
		table.put("unpackUnorm2x16", "ofUnpackUnorm2x16");
		table.put("packSnorm4x8", "ofPackSnorm4x8");
		table.put("unpackSnorm4x8", "ofUnpackSnorm4x8");
		table.put("packSnorm2x16", "ofPackSnorm2x16");
		table.put("unpackSnorm2x16", "ofUnpackSnorm2x16");

		return Collections.unmodifiableMap(table);
	}
}
