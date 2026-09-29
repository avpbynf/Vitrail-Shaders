package dev.vitrail.glsl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A SPIR-V module assembled by hand for the tests of the two passes that read one, {@link
 * LocalZeroes} and {@link DebugNames}, which have to be shown a module with exactly the shape a
 * case is about and no other.
 * <p>
 * Nothing here validates. An instruction is its opcode and its operands, framed by a word count in
 * the top half of its first word, and the opcodes and operand orders are the specification's.
 */
final class SpirvSupport {

	static final int MAGIC = 0x07230203;

	static final int UNDEF = 1;
	static final int NAME = 5;
	static final int MEMBER_NAME = 6;
	static final int ENTRY_POINT = 15;
	static final int TYPE_VOID = 19;
	static final int TYPE_BOOL = 20;
	static final int TYPE_INT = 21;
	static final int TYPE_FLOAT = 22;
	static final int TYPE_IMAGE = 25;
	static final int TYPE_SAMPLED_IMAGE = 27;
	static final int TYPE_ARRAY = 28;
	static final int TYPE_STRUCT = 30;
	static final int TYPE_POINTER = 32;
	static final int TYPE_FUNCTION = 33;
	static final int CONSTANT_TRUE = 41;
	static final int CONSTANT = 43;
	static final int CONSTANT_NULL = 46;
	static final int FUNCTION = 54;
	static final int FUNCTION_PARAMETER = 55;
	static final int FUNCTION_END = 56;
	static final int FUNCTION_CALL = 57;
	static final int VARIABLE = 59;
	static final int LOAD = 61;
	static final int STORE = 62;
	static final int ACCESS_CHAIN = 65;
	static final int LABEL = 248;
	static final int BRANCH = 249;
	static final int BRANCH_CONDITIONAL = 250;
	static final int SWITCH = 251;
	static final int KILL = 252;
	static final int RETURN = 253;

	static final int UNIFORM_CONSTANT = 0;
	static final int UNIFORM = 2;
	static final int PRIVATE = 6;
	static final int FUNCTION_STORAGE = 7;
	static final int WORKGROUP = 4;
	static final int OUTPUT = 3;

	/** One decoded instruction: its opcode, and every word after the first. */
	record Instruction(int opcode, int[] operands) {

		int word(int index) {
			return this.operands[index];
		}

		int words() {
			return this.operands.length + 1;
		}
	}

	private SpirvSupport() {
	}

	/** The module as instructions, header left out. */
	static List<Instruction> decode(int[] words) {
		List<Instruction> found = new ArrayList<>();
		for (int at = 5; at < words.length; ) {
			int count = words[at] >>> 16;
			found.add(new Instruction(words[at] & 0xFFFF, Arrays.copyOfRange(words, at + 1, at + count)));
			at += count;
		}

		return found;
	}

	static List<Instruction> all(int[] words, int opcode) {
		return decode(words).stream().filter(i -> i.opcode() == opcode).toList();
	}

	/** The words of a literal string: its bytes, little endian, then a zero, padded to a whole word. */
	static int[] string(String text) {
		byte[] raw = text.getBytes(StandardCharsets.UTF_8);
		int[] words = new int[raw.length / 4 + 1];
		for (int at = 0; at < raw.length; at++) {
			words[at / 4] |= (raw[at] & 0xFF) << (8 * (at % 4));
		}

		return words;
	}

	/** A module under construction: ids are handed out in order and the bound is what was handed out. */
	static final class Module {

		private final List<int[]> instructions = new ArrayList<>();
		private int next = 1;

		int id() {
			return this.next++;
		}

		int bound() {
			return this.next;
		}

		/** An instruction with no result id, or one whose ids the caller has already placed. */
		Module op(int opcode, int... operands) {
			int[] words = new int[operands.length + 1];
			words[0] = (words.length << 16) | opcode;
			System.arraycopy(operands, 0, words, 1, operands.length);
			this.instructions.add(words);

			return this;
		}

		/** A type declaration: the result id comes first, then the operands. */
		int type(int opcode, int... operands) {
			int id = id();
			int[] all = new int[operands.length + 1];
			all[0] = id;
			System.arraycopy(operands, 0, all, 1, operands.length);
			op(opcode, all);

			return id;
		}

		/** An instruction with a result type and a result id, in that order, then the operands. */
		int result(int opcode, int type, int... operands) {
			int id = id();
			int[] all = new int[operands.length + 2];
			all[0] = type;
			all[1] = id;
			System.arraycopy(operands, 0, all, 2, operands.length);
			op(opcode, all);

			return id;
		}

		/** Opens a function under an id the caller chose, so that an entry point can name it first. */
		Module function(int functionType, int returnType, int id) {
			return op(FUNCTION, returnType, id, 0, functionType);
		}

		Module end() {
			return op(FUNCTION_END);
		}

		Module label(int id) {
			return op(LABEL, id);
		}

		int[] words() {
			int total = 5;
			for (int[] instruction : this.instructions) {
				total += instruction.length;
			}

			int[] words = new int[total];
			words[0] = MAGIC;
			words[1] = 0x00010000;
			words[3] = this.next;
			int at = 5;
			for (int[] instruction : this.instructions) {
				System.arraycopy(instruction, 0, words, at, instruction.length);
				at += instruction.length;
			}

			return words;
		}
	}
}
