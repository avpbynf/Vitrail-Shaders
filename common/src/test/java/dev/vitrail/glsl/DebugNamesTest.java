package dev.vitrail.glsl;

import static dev.vitrail.glsl.SpirvSupport.MEMBER_NAME;
import static dev.vitrail.glsl.SpirvSupport.NAME;
import static dev.vitrail.glsl.SpirvSupport.TYPE_ARRAY;
import static dev.vitrail.glsl.SpirvSupport.TYPE_FLOAT;
import static dev.vitrail.glsl.SpirvSupport.TYPE_INT;
import static dev.vitrail.glsl.SpirvSupport.TYPE_POINTER;
import static dev.vitrail.glsl.SpirvSupport.TYPE_STRUCT;
import static dev.vitrail.glsl.SpirvSupport.VARIABLE;
import static dev.vitrail.glsl.SpirvSupport.all;
import static dev.vitrail.glsl.SpirvSupport.decode;
import static dev.vitrail.glsl.SpirvSupport.string;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.SpirvSupport.Instruction;
import dev.vitrail.glsl.SpirvSupport.Module;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link DebugNames} to its javadoc: every name a module carries is dropped except the ones
 * the outside reads back, which are the variables of seven storage classes, plus the type behind each
 * with its arrays unwrapped, and the names of that type's members.
 * <p>
 * The modules are assembled by hand with the names first, ahead of the ids they name, which is
 * where SPIR-V puts its debug section and the reason the pass has to walk the module twice before
 * it can drop anything.
 */
class DebugNamesTest {

	/** A name for an id: the target, then the characters. */
	private static void name(Module m, int target, String text) {
		int[] chars = string(text);
		int[] operands = new int[chars.length + 1];
		operands[0] = target;
		System.arraycopy(chars, 0, operands, 1, chars.length);
		m.op(NAME, operands);
	}

	private static void memberName(Module m, int type, int member, String text) {
		int[] chars = string(text);
		int[] operands = new int[chars.length + 2];
		operands[0] = type;
		operands[1] = member;
		System.arraycopy(chars, 0, operands, 2, chars.length);
		m.op(MEMBER_NAME, operands);
	}

	/** The targets of every OpName that stands in the module, sorted. */
	private static Set<Integer> named(int[] words) {
		Set<Integer> targets = new TreeSet<>();
		for (Instruction instruction : all(words, NAME)) {
			targets.add(instruction.word(0));
		}

		return targets;
	}

	private static Set<String> memberNamed(int[] words) {
		Set<String> found = new TreeSet<>();
		for (Instruction instruction : all(words, MEMBER_NAME)) {
			found.add(instruction.word(0) + "." + instruction.word(1));
		}

		return found;
	}

	@Test
	void everyStorageClassTheOutsideReadsKeepsItsVariablesNameAndTheOthersLoseIt() {
		int[] kept = {0, 1, 2, 3, 9, 11, 12};
		int[] dropped = {4, 5, 6, 7, 8, 10};
		Module m = new Module();
		int[] variables = new int[13];
		for (int storage = 0; storage <= 12; storage++) {
			variables[storage] = m.id();
			name(m, variables[storage], "v" + storage);
		}

		int floatType = m.type(TYPE_FLOAT, 32);
		for (int storage = 0; storage <= 12; storage++) {
			int pointer = m.type(TYPE_POINTER, storage, floatType);
			m.op(VARIABLE, pointer, variables[storage], storage);
		}

		DebugNames.Result result = DebugNames.strip(m.words());

		Set<Integer> remaining = named(result.words());
		for (int storage : kept) {
			assertTrue(remaining.contains(variables[storage]), "class " + storage + " is read from outside");
		}

		for (int storage : dropped) {
			assertFalse(remaining.contains(variables[storage]), "class " + storage + " is nobody's business");
		}

		assertEquals(dropped.length, result.dropped());
		assertTrue(result.changed());
	}

	@Test
	void theNameOfTheTypeBehindAKeptVariableAndOfItsMembersSurvivesWhereAnyOtherTypesDoNot() {
		Module m = new Module();
		int block = m.id();
		int plain = m.id();
		int variable = m.id();
		int local = m.id();
		name(m, block, "Globals");
		memberName(m, block, 0, "matrix");
		memberName(m, block, 1, "tint");
		name(m, plain, "Helper");
		memberName(m, plain, 0, "value");
		name(m, variable, "globals");
		name(m, local, "tmp");
		int floatType = m.type(TYPE_FLOAT, 32);
		m.op(TYPE_STRUCT, block, floatType, floatType);
		m.op(TYPE_STRUCT, plain, floatType);
		int blockPointer = m.type(TYPE_POINTER, 2, block);
		int plainPointer = m.type(TYPE_POINTER, 7, plain);
		m.op(VARIABLE, blockPointer, variable, 2);
		m.op(VARIABLE, plainPointer, local, 7);

		DebugNames.Result result = DebugNames.strip(m.words());

		assertEquals(Set.of(block, variable), named(result.words()));
		assertEquals(Set.of(block + ".0", block + ".1"), memberNamed(result.words()));
		assertEquals(3, result.dropped());
	}

	@Test
	void arraysAreUnwrappedSoABlockArrayKeepsTheNameOfItsElementToo() {
		Module m = new Module();
		int block = m.id();
		int inner = m.id();
		int outer = m.id();
		int variable = m.id();
		name(m, block, "Element");
		name(m, inner, "Inner");
		name(m, outer, "Outer");
		memberName(m, block, 0, "member");
		name(m, variable, "instances");
		int floatType = m.type(TYPE_FLOAT, 32);
		int length = m.type(TYPE_INT, 32, 0);
		m.op(TYPE_STRUCT, block, floatType);
		m.op(TYPE_ARRAY, inner, block, length);
		m.op(TYPE_ARRAY, outer, inner, length);
		int pointer = m.type(TYPE_POINTER, 2, outer);
		m.op(VARIABLE, pointer, variable, 2);

		DebugNames.Result result = DebugNames.strip(m.words());

		assertEquals(Set.of(block, inner, outer, variable), named(result.words()));
		assertEquals(Set.of(block + ".0"), memberNamed(result.words()));
		assertFalse(result.changed());
		assertEquals(m.words().length, result.words().length);
	}

	@Test
	void aModuleWithNothingToDropComesBackAsTheSameArray() {
		Module m = new Module();
		int variable = m.id();
		name(m, variable, "kept");
		int floatType = m.type(TYPE_FLOAT, 32);
		int pointer = m.type(TYPE_POINTER, 3, floatType);
		m.op(VARIABLE, pointer, variable, 3);
		int[] input = m.words();

		DebugNames.Result result = DebugNames.strip(input);

		assertSame(input, result.words());
		assertEquals(0, result.dropped());
		assertFalse(result.changed());
	}

	@Test
	void everythingButTheDroppedNamesIsCopiedInTheOrderItWasInAndTheHeaderIsKept() {
		Module m = new Module();
		int local = m.id();
		int variable = m.id();
		name(m, local, "tmp");
		name(m, variable, "kept");
		int floatType = m.type(TYPE_FLOAT, 32);
		int pointer = m.type(TYPE_POINTER, 3, floatType);
		int localPointer = m.type(TYPE_POINTER, 7, floatType);
		m.op(VARIABLE, pointer, variable, 3);
		m.op(VARIABLE, localPointer, local, 7);
		int[] input = m.words();

		DebugNames.Result result = DebugNames.strip(input);

		List<Instruction> before = decode(input);
		List<Instruction> after = decode(result.words());
		List<Integer> expected = new ArrayList<>(before.stream().map(Instruction::opcode).toList());
		expected.remove(expected.indexOf(NAME));
		assertEquals(expected, after.stream().map(Instruction::opcode).toList());
		for (int word = 0; word < 5; word++) {
			assertEquals(input[word], result.words()[word], "header word " + word);
		}

		// Stripping what it has already stripped changes nothing.
		assertSame(result.words(), DebugNames.strip(result.words()).words());
	}

	@Test
	void aNameShorterThanThreeWordsIsNotOneAndIsKept() {
		Module m = new Module();
		int local = m.id();
		m.op(NAME, local);
		int floatType = m.type(TYPE_FLOAT, 32);
		int localPointer = m.type(TYPE_POINTER, 7, floatType);
		m.op(VARIABLE, localPointer, local, 7);
		int[] input = m.words();

		assertSame(input, DebugNames.strip(input).words());
	}

	@Test
	void aBoundThatIsNoBoundSizesNothingAndDropsOnlyWhatItUnderstood() {
		Module m = new Module();
		int local = m.id();
		int variable = m.id();
		name(m, local, "tmp");
		name(m, variable, "kept");
		int floatType = m.type(TYPE_FLOAT, 32);
		int pointer = m.type(TYPE_POINTER, 3, floatType);
		int localPointer = m.type(TYPE_POINTER, 7, floatType);
		m.op(VARIABLE, pointer, variable, 3);
		m.op(VARIABLE, localPointer, local, 7);

		for (int bound : new int[] {Integer.MAX_VALUE, -1, 0}) {
			int[] words = m.words();
			words[3] = bound;

			DebugNames.Result result = DebugNames.strip(words);

			// The variable's own id is kept whatever the tables hold; only a type name can be lost to a bound.
			assertEquals(Set.of(variable), named(result.words()), "bound " + bound);
			assertEquals(1, result.dropped(), "bound " + bound);
		}
	}

	@Test
	void aModuleThatCannotBeWalkedComesBackAsTheSameArray() {
		int[][] modules = {
			new int[0],
			new int[] {SpirvSupport.MAGIC, 0, 0, 1},
			new int[] {0, 0, 0, 1, 0, (3 << 16) | NAME, 1, 0},
			new int[] {SpirvSupport.MAGIC, 0, 0, 1, 0, (9 << 16) | NAME, 1},
			new int[] {SpirvSupport.MAGIC, 0, 0, 1, 0, 0},
		};

		for (int[] module : modules) {
			assertSame(module, DebugNames.strip(module).words());
		}
	}
}
