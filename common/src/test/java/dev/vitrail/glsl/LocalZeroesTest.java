package dev.vitrail.glsl;

import static dev.vitrail.glsl.SpirvSupport.ACCESS_CHAIN;
import static dev.vitrail.glsl.SpirvSupport.BRANCH;
import static dev.vitrail.glsl.SpirvSupport.BRANCH_CONDITIONAL;
import static dev.vitrail.glsl.SpirvSupport.CONSTANT;
import static dev.vitrail.glsl.SpirvSupport.CONSTANT_NULL;
import static dev.vitrail.glsl.SpirvSupport.CONSTANT_TRUE;
import static dev.vitrail.glsl.SpirvSupport.ENTRY_POINT;
import static dev.vitrail.glsl.SpirvSupport.FUNCTION_CALL;
import static dev.vitrail.glsl.SpirvSupport.FUNCTION_PARAMETER;
import static dev.vitrail.glsl.SpirvSupport.FUNCTION_STORAGE;
import static dev.vitrail.glsl.SpirvSupport.KILL;
import static dev.vitrail.glsl.SpirvSupport.LOAD;
import static dev.vitrail.glsl.SpirvSupport.PRIVATE;
import static dev.vitrail.glsl.SpirvSupport.RETURN;
import static dev.vitrail.glsl.SpirvSupport.STORE;
import static dev.vitrail.glsl.SpirvSupport.SWITCH;
import static dev.vitrail.glsl.SpirvSupport.TYPE_ARRAY;
import static dev.vitrail.glsl.SpirvSupport.TYPE_BOOL;
import static dev.vitrail.glsl.SpirvSupport.TYPE_FLOAT;
import static dev.vitrail.glsl.SpirvSupport.TYPE_FUNCTION;
import static dev.vitrail.glsl.SpirvSupport.TYPE_IMAGE;
import static dev.vitrail.glsl.SpirvSupport.TYPE_INT;
import static dev.vitrail.glsl.SpirvSupport.TYPE_POINTER;
import static dev.vitrail.glsl.SpirvSupport.TYPE_SAMPLED_IMAGE;
import static dev.vitrail.glsl.SpirvSupport.TYPE_STRUCT;
import static dev.vitrail.glsl.SpirvSupport.TYPE_VOID;
import static dev.vitrail.glsl.SpirvSupport.UNDEF;
import static dev.vitrail.glsl.SpirvSupport.VARIABLE;
import static dev.vitrail.glsl.SpirvSupport.all;
import static dev.vitrail.glsl.SpirvSupport.decode;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.SpirvSupport.Instruction;
import dev.vitrail.glsl.SpirvSupport.Module;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link LocalZeroes} to what its javadoc says it does: a variable some path reads before any
 * store reaches it starts at the zero of its type, and nothing else is touched.
 * <p>
 * The modules are assembled by hand, one case each, and the expected answers are the ones the
 * definite-assignment rule gives: a variable is assigned at a point when every path from the start
 * to it stores the whole variable, a store through an access chain stores none of it, and a call
 * stores and reads what its callee does. Every claim about an entry point, a loop or a kill is a
 * claim about that rule and not about what the code happens to answer.
 */
class LocalZeroesTest {

	/** The types every module here shares, and a place to build one function's blocks. */
	private static final class Base {

		final Module m = new Module();
		final int voidType = this.m.type(TYPE_VOID);
		final int floatType = this.m.type(TYPE_FLOAT, 32);
		final int intType = this.m.type(TYPE_INT, 32, 1);
		final int boolType = this.m.type(TYPE_BOOL);
		final int fnPointer = this.m.type(TYPE_POINTER, FUNCTION_STORAGE, this.floatType);
		final int privatePointer = this.m.type(TYPE_POINTER, PRIVATE, this.floatType);
		final int fnType = this.m.type(TYPE_FUNCTION, this.voidType);
		final int fnTypeWithPointer = this.m.type(TYPE_FUNCTION, this.voidType, this.fnPointer);
		final int one = this.m.result(CONSTANT, this.floatType, 0x3f800000);
		final int cond = this.m.result(CONSTANT_TRUE, this.boolType);

		int local() {
			int id = this.m.id();
			this.m.op(VARIABLE, this.fnPointer, id, FUNCTION_STORAGE);

			return id;
		}

		int global() {
			int id = this.m.id();
			this.m.op(VARIABLE, this.privatePointer, id, PRIVATE);

			return id;
		}

		void enter(int function) {
			int[] name = SpirvSupport.string("main");
			this.m.op(ENTRY_POINT, 4, function, name[0], name[1]);
		}

		void load(int pointer) {
			this.m.result(LOAD, this.floatType, pointer);
		}

		void store(int pointer) {
			this.m.op(STORE, pointer, this.one);
		}

		/** A function with one block: whatever the caller adds, then a return. */
		int simple(Runnable body) {
			int id = this.m.id();
			this.m.function(this.fnType, this.voidType, id).label(this.m.id());
			body.run();
			this.m.op(RETURN).end();

			return id;
		}

		void call(int function, int... arguments) {
			int[] operands = new int[arguments.length + 3];
			operands[0] = this.voidType;
			operands[1] = this.m.id();
			operands[2] = function;
			System.arraycopy(arguments, 0, operands, 3, arguments.length);
			this.m.op(FUNCTION_CALL, operands);
		}

		int[] words() {
			return this.m.words();
		}
	}

	/** The variables the pass gave an initialiser, by id, with the null constant each one names. */
	private static int initialiserOf(int[] words, int variable) {
		for (Instruction instruction : all(words, VARIABLE)) {
			if (instruction.word(1) == variable) {
				return instruction.words() == 5 ? instruction.word(3) : -1;
			}
		}

		throw new AssertionError("no variable " + variable);
	}

	private static boolean zeroed(LocalZeroes.Result result, int variable) {
		return initialiserOf(result.words(), variable) >= 0;
	}

	@Test
	void aLocalReadBeforeAnyStoreStartsAtTheZeroOfItsType() {
		Base b = new Base();
		int[] variable = new int[1];
		b.simple(() -> {
			variable[0] = b.local();
			b.load(variable[0]);
		});
		int[] input = b.words();

		LocalZeroes.Result result = LocalZeroes.apply(input);

		assertTrue(result.changed());
		assertEquals(1, result.variables());
		assertEquals(0, result.undefs());
		int init = initialiserOf(result.words(), variable[0]);
		assertEquals(b.m.bound(), init, "the first constant made is numbered at the old bound");
		assertEquals(b.m.bound() + 1, result.words()[3]);

		// The constant is declared right behind the type it zeroes, ahead of every pointer to it.
		List<Instruction> module = decode(result.words());
		int floatAt = -1;
		for (int at = 0; at < module.size(); at++) {
			if (module.get(at).opcode() == TYPE_FLOAT && module.get(at).word(0) == b.floatType) {
				floatAt = at;
			}
		}

		Instruction constant = module.get(floatAt + 1);
		assertEquals(CONSTANT_NULL, constant.opcode());
		assertArrayEquals(new int[] {b.floatType, init}, constant.operands());
	}

	@Test
	void aLocalStoredBeforeItIsReadIsLeftBareAndTheSameArrayComesBack() {
		Base b = new Base();
		b.simple(() -> {
			int v = b.local();
			b.store(v);
			b.load(v);
		});
		int[] input = b.words();

		LocalZeroes.Result result = LocalZeroes.apply(input);

		assertFalse(result.changed());
		assertSame(input, result.words());
	}

	@Test
	void aLocalNobodyReadsIsLeftBare() {
		Base b = new Base();
		b.simple(b::local);
		int[] input = b.words();

		assertSame(input, LocalZeroes.apply(input).words());
	}

	/** Entry branches to two blocks that join; each may store, and the join reads. */
	private static LocalZeroes.Result diamond(boolean left, boolean right) {
		Base b = new Base();
		int entry = b.m.id();
		int l1 = b.m.id();
		int l2 = b.m.id();
		int join = b.m.id();
		int main = b.m.id();
		b.m.function(b.fnType, b.voidType, main).label(entry);
		int v = b.local();
		b.m.op(BRANCH_CONDITIONAL, b.cond, l1, l2);
		b.m.label(l1);
		if (left) {
			b.store(v);
		}

		b.m.op(BRANCH, join);
		b.m.label(l2);
		if (right) {
			b.store(v);
		}

		b.m.op(BRANCH, join);
		b.m.label(join);
		b.load(v);
		b.m.op(RETURN).end();

		return LocalZeroes.apply(b.words());
	}

	@Test
	void aLocalStoredOnOnlyOneOfTwoPathsThatJoinIsZeroed() {
		assertEquals(1, diamond(true, false).variables());
		assertEquals(1, diamond(false, true).variables());
		assertEquals(1, diamond(false, false).variables());
	}

	@Test
	void aLocalStoredOnBothPathsThatJoinIsLeftBare() {
		assertEquals(0, diamond(true, true).variables());
	}

	/** Entry, then a loop whose header reads and whose body may store; the entry may store first. */
	private static LocalZeroes.Result loop(boolean storeBefore, boolean storeInBody) {
		Base b = new Base();
		int entry = b.m.id();
		int header = b.m.id();
		int body = b.m.id();
		int exit = b.m.id();
		int main = b.m.id();
		b.m.function(b.fnType, b.voidType, main).label(entry);
		int v = b.local();
		if (storeBefore) {
			b.store(v);
		}

		b.m.op(BRANCH, header);
		b.m.label(header);
		b.load(v);
		b.m.op(BRANCH_CONDITIONAL, b.cond, body, exit);
		b.m.label(body);
		if (storeInBody) {
			b.store(v);
		}

		b.m.op(BRANCH, header);
		b.m.label(exit);
		b.m.op(RETURN).end();

		return LocalZeroes.apply(b.words());
	}

	@Test
	void aLoopHeaderThatReadsOnTheFirstTripBeforeTheBodyStoresNeedsTheZero() {
		assertEquals(1, loop(false, true).variables());
		assertEquals(1, loop(false, false).variables());
	}

	@Test
	void aLoopHeaderThatReadsAfterAStoreBeforeTheLoopIsLeftBare() {
		assertEquals(0, loop(true, false).variables());
		assertEquals(0, loop(true, true).variables());
	}

	/** A switch on a default and one case, each going to a block that may store, and a join that reads. */
	private static LocalZeroes.Result switched(boolean defaultStores, boolean caseStores) {
		Base b = new Base();
		int entry = b.m.id();
		int byDefault = b.m.id();
		int byCase = b.m.id();
		int join = b.m.id();
		int main = b.m.id();
		b.m.function(b.fnType, b.voidType, main).label(entry);
		int v = b.local();
		int selector = b.m.result(CONSTANT, b.intType, 1);
		// OpSwitch: the selector, the default, then a literal and its label for every case.
		b.m.op(SWITCH, selector, byDefault, 1, byCase);
		b.m.label(byDefault);
		if (defaultStores) {
			b.store(v);
		}

		b.m.op(BRANCH, join);
		b.m.label(byCase);
		if (caseStores) {
			b.store(v);
		}

		b.m.op(BRANCH, join);
		b.m.label(join);
		b.load(v);
		b.m.op(RETURN).end();

		return LocalZeroes.apply(b.words());
	}

	@Test
	void aSwitchArmThatSkipsTheStoreLeavesTheReadBareWhicheverArmItIs() {
		assertEquals(1, switched(false, true).variables());
		assertEquals(1, switched(true, false).variables());
		assertEquals(1, switched(false, false).variables());
	}

	@Test
	void aSwitchWhoseEveryArmStoresLeavesTheReadStored() {
		assertEquals(0, switched(true, true).variables());
	}

	// ------------------------------------------------------------------ access chains

	@Test
	void aStoreThroughAChainStoresNoneOfTheWholeAndAReadThroughOneIsAReadOfIt() {
		Base b = new Base();
		int structType = b.m.type(TYPE_STRUCT, b.floatType, b.floatType);
		int structPointer = b.m.type(TYPE_POINTER, FUNCTION_STORAGE, structType);
		int zero = b.m.result(CONSTANT, b.intType, 0);
		int structNull = b.m.result(CONSTANT_NULL, structType);
		int[] ids = new int[3];
		b.simple(() -> {
			ids[0] = b.m.id();
			b.m.op(VARIABLE, structPointer, ids[0], FUNCTION_STORAGE);
			ids[1] = b.m.id();
			b.m.op(VARIABLE, structPointer, ids[1], FUNCTION_STORAGE);
			ids[2] = b.m.id();
			b.m.op(VARIABLE, structPointer, ids[2], FUNCTION_STORAGE);
			// The first: one member stored, the whole loaded. The second: one member read, none stored.
			// The third: the whole stored, then one member read.
			int member = b.m.result(ACCESS_CHAIN, b.fnPointer, ids[0], zero);
			b.store(member);
			b.m.result(LOAD, structType, ids[0]);
			int second = b.m.result(ACCESS_CHAIN, b.fnPointer, ids[1], zero);
			b.load(second);
			b.m.op(STORE, ids[2], structNull);
			int third = b.m.result(ACCESS_CHAIN, b.fnPointer, ids[2], zero);
			b.load(third);
		});

		LocalZeroes.Result result = LocalZeroes.apply(b.words());

		assertTrue(zeroed(result, ids[0]));
		assertTrue(zeroed(result, ids[1]));
		assertFalse(zeroed(result, ids[2]));
		assertEquals(2, result.variables());
		// Both are structs, so the two share one null constant, declared behind the struct type.
		assertEquals(initialiserOf(result.words(), ids[0]), initialiserOf(result.words(), ids[1]));
		assertEquals(2, all(result.words(), CONSTANT_NULL).stream().filter(c -> c.word(0) == structType).count(),
				"the module's own null and the one made");
	}

	// ------------------------------------------------------------------------- calls

	/** A callee taking one pointer, its body the caller of this decides. */
	private static int callee(Base b, Runnable body) {
		int id = b.m.id();
		b.m.function(b.fnTypeWithPointer, b.voidType, id);
		b.m.op(FUNCTION_PARAMETER, b.fnPointer, b.m.id());
		b.m.label(b.m.id());
		body.run();
		b.m.op(RETURN).end();

		return id;
	}

	private static int parameterOf(Base b) {
		// The id handed out for the parameter just before the label of the callee.
		return b.m.bound() - 2;
	}

	@Test
	void aCalleeThatStoresItsParameterOnEveryPathDefinesWhatTheCallerPassed() {
		Base b = new Base();
		int[] parameter = new int[1];
		int writes = callee(b, () -> {
			parameter[0] = parameterOf(b);
			b.store(parameter[0]);
		});
		int[] variable = new int[1];
		b.simple(() -> {
			variable[0] = b.local();
			b.call(writes, variable[0]);
			b.load(variable[0]);
		});

		LocalZeroes.Result result = LocalZeroes.apply(b.words());

		assertEquals(0, result.variables());
	}

	@Test
	void aCalleeThatReadsItsParameterFirstNeedsTheCallersVariableZeroedAndItsOwnParameterIsNoVariable() {
		Base b = new Base();
		int reads = callee(b, () -> b.load(parameterOf(b)));
		int[] variable = new int[1];
		b.simple(() -> {
			variable[0] = b.local();
			b.call(reads, variable[0]);
		});

		LocalZeroes.Result result = LocalZeroes.apply(b.words());

		assertEquals(1, result.variables());
		assertTrue(zeroed(result, variable[0]));
	}

	@Test
	void aCalleeThatStoresOnOnePathOnlyDefinesNothingForTheCaller() {
		Base b = new Base();
		int entry = b.m.id();
		int stores = b.m.id();
		int skips = b.m.id();
		int callee = b.m.id();
		b.m.function(b.fnTypeWithPointer, b.voidType, callee);
		int parameter = b.m.id();
		b.m.op(FUNCTION_PARAMETER, b.fnPointer, parameter);
		b.m.label(entry).op(BRANCH_CONDITIONAL, b.cond, stores, skips);
		b.m.label(stores);
		b.store(parameter);
		b.m.op(RETURN);
		b.m.label(skips).op(RETURN).end();
		int[] variable = new int[1];
		b.simple(() -> {
			variable[0] = b.local();
			b.call(callee, variable[0]);
			b.load(variable[0]);
		});

		assertTrue(zeroed(LocalZeroes.apply(b.words()), variable[0]));
	}

	@Test
	void aPathThatKillsHandsNothingBackSoItDoesNotWeakenWhatTheCalleeDefines() {
		Base b = new Base();
		int entry = b.m.id();
		int stores = b.m.id();
		int kills = b.m.id();
		int callee = b.m.id();
		b.m.function(b.fnTypeWithPointer, b.voidType, callee);
		int parameter = b.m.id();
		b.m.op(FUNCTION_PARAMETER, b.fnPointer, parameter);
		b.m.label(entry).op(BRANCH_CONDITIONAL, b.cond, stores, kills);
		b.m.label(stores);
		b.store(parameter);
		b.m.op(RETURN);
		b.m.label(kills).op(KILL).end();
		int[] variable = new int[1];
		b.simple(() -> {
			variable[0] = b.local();
			b.call(callee, variable[0]);
			b.load(variable[0]);
		});

		assertFalse(zeroed(LocalZeroes.apply(b.words()), variable[0]));
	}

	@Test
	void aCalleeTheModuleDoesNotDefineReadsEverythingAndDefinesNothing() {
		Base b = new Base();
		int[] variable = new int[1];
		b.simple(() -> {
			variable[0] = b.local();
			b.call(9999, variable[0]);
			b.load(variable[0]);
		});

		assertTrue(zeroed(LocalZeroes.apply(b.words()), variable[0]));
	}

	@Test
	void theOrderTheModuleListsTheFunctionsInDoesNotChangeTheAnswer() {
		// The caller listed before its callee needs a second round for the summary to settle.
		Base b = new Base();
		int callee = b.m.id();
		int[] variable = new int[1];
		b.simple(() -> {
			variable[0] = b.local();
			b.call(callee, variable[0]);
			b.load(variable[0]);
		});
		b.m.function(b.fnTypeWithPointer, b.voidType, callee);
		int parameter = b.m.id();
		b.m.op(FUNCTION_PARAMETER, b.fnPointer, parameter);
		b.m.label(b.m.id());
		b.store(parameter);
		b.m.op(RETURN).end();

		assertFalse(zeroed(LocalZeroes.apply(b.words()), variable[0]));
	}

	// ------------------------------------------------------------------------ globals

	@Test
	void aGlobalIsJudgedFromTheEntryPointAndZeroedWhereItIsReadFirst() {
		Base b = new Base();
		int g = b.global();
		int main = b.simple(() -> b.load(g));
		b.enter(main);

		LocalZeroes.Result result = LocalZeroes.apply(b.words());

		assertEquals(1, result.variables());
		assertEquals(g, all(result.words(), VARIABLE).getFirst().word(1));
		assertTrue(zeroed(result, g));
		// A global's variable is five words long once it carries an initialiser.
		assertEquals(5, all(result.words(), VARIABLE).getFirst().words());
	}

	@Test
	void aGlobalStoredBeforeItIsReadIsLeftBare() {
		Base b = new Base();
		int g = b.global();
		int main = b.simple(() -> {
			b.store(g);
			b.load(g);
		});
		b.enter(main);

		assertEquals(0, LocalZeroes.apply(b.words()).variables());
	}

	@Test
	void aGlobalReadFirstByAFunctionTheEntryPointCallsIsZeroedAndOneItStoredFirstIsNot() {
		Base b = new Base();
		int g = b.global();
		int h = b.global();
		int reader = b.simple(() -> {
			b.load(g);
			b.load(h);
		});
		int main = b.simple(() -> {
			b.store(h);
			b.call(reader);
		});
		b.enter(main);

		LocalZeroes.Result result = LocalZeroes.apply(b.words());

		assertTrue(zeroed(result, g));
		assertFalse(zeroed(result, h));
	}

	@Test
	void aGlobalStoredByAFunctionTheEntryPointCallsBeforeItIsReadIsLeftBare() {
		Base b = new Base();
		int g = b.global();
		int writer = b.simple(() -> b.store(g));
		int main = b.simple(() -> {
			b.call(writer);
			b.load(g);
		});
		b.enter(main);

		assertEquals(0, LocalZeroes.apply(b.words()).variables());
	}

	@Test
	void aGlobalReadFirstOnlyByAFunctionNobodyEntersIsNotJudged() {
		Base b = new Base();
		int g = b.global();
		b.simple(() -> b.load(g));
		int main = b.simple(() -> {
		});
		b.enter(main);

		assertEquals(0, LocalZeroes.apply(b.words()).variables());
	}

	// ------------------------------------------------------------- what is never zeroed

	@Test
	void anOpaqueHandleAndAnythingHoldingOneIsNeverGivenAZero() {
		Base b = new Base();
		int image = b.m.type(TYPE_IMAGE, b.floatType, 1, 0, 0, 0, 1, 0);
		int sampled = b.m.type(TYPE_SAMPLED_IMAGE, image);
		int sampledPointer = b.m.type(TYPE_POINTER, FUNCTION_STORAGE, sampled);
		int holder = b.m.type(TYPE_STRUCT, b.floatType, sampled);
		int holderPointer = b.m.type(TYPE_POINTER, FUNCTION_STORAGE, holder);
		int lengthTwo = b.m.result(CONSTANT, b.intType, 2);
		int arrayOfHandles = b.m.type(TYPE_ARRAY, sampled, lengthTwo);
		int arrayPointer = b.m.type(TYPE_POINTER, FUNCTION_STORAGE, arrayOfHandles);
		b.simple(() -> {
			for (int pointer : new int[] {sampledPointer, holderPointer, arrayPointer}) {
				int id = b.m.id();
				b.m.op(VARIABLE, pointer, id, FUNCTION_STORAGE);
				b.m.result(LOAD, sampled, id);
			}
		});
		int[] input = b.words();

		LocalZeroes.Result result = LocalZeroes.apply(input);

		assertFalse(result.changed());
		assertSame(input, result.words());
	}

	@Test
	void anUndefinedValueBecomesTheNullOfItsTypeUnderItsOwnIdAndTheInstructionGoes() {
		Base b = new Base();
		int[] undef = new int[1];
		b.simple(() -> undef[0] = b.m.result(UNDEF, b.floatType));

		LocalZeroes.Result result = LocalZeroes.apply(b.words());

		assertEquals(0, result.variables());
		assertEquals(1, result.undefs());
		assertTrue(result.changed());
		assertTrue(all(result.words(), UNDEF).isEmpty());
		Instruction constant = all(result.words(), CONSTANT_NULL).getFirst();
		assertArrayEquals(new int[] {b.floatType, undef[0]}, constant.operands());
		// The id is the value's own, so nothing that used it is touched and the bound does not move.
		assertEquals(b.m.bound(), result.words()[3]);
	}

	@Test
	void anUndefinedValueOfAnOpaqueTypeIsLeftAsItWas() {
		Base b = new Base();
		int image = b.m.type(TYPE_IMAGE, b.floatType, 1, 0, 0, 0, 1, 0);
		b.simple(() -> b.m.result(UNDEF, image));
		int[] input = b.words();

		assertSame(input, LocalZeroes.apply(input).words());
	}

	// ------------------------------------------------------------------- the output

	@Test
	void oneNullIsMadePerTypeInTheOrderTheyAreFirstUsedAndTheBoundGrowsByThatMany() {
		Base b = new Base();
		int structType = b.m.type(TYPE_STRUCT, b.floatType);
		int structPointer = b.m.type(TYPE_POINTER, FUNCTION_STORAGE, structType);
		int[] ids = new int[3];
		b.simple(() -> {
			ids[0] = b.m.id();
			b.m.op(VARIABLE, structPointer, ids[0], FUNCTION_STORAGE);
			ids[1] = b.local();
			ids[2] = b.local();
			b.m.result(LOAD, structType, ids[0]);
			b.load(ids[1]);
			b.load(ids[2]);
		});
		int oldBound = b.m.bound();

		LocalZeroes.Result result = LocalZeroes.apply(b.words());

		assertEquals(3, result.variables());
		assertEquals(oldBound + 2, result.words()[3]);
		assertEquals(oldBound, initialiserOf(result.words(), ids[0]));
		assertEquals(oldBound + 1, initialiserOf(result.words(), ids[1]));
		assertEquals(oldBound + 1, initialiserOf(result.words(), ids[2]));
	}

	@Test
	void everythingButTheVariablesAndTheNullsIsCopiedInTheOrderItWasIn() {
		Base b = new Base();
		b.simple(() -> b.load(b.local()));
		int[] input = b.words();

		List<Instruction> before = decode(input);
		List<Instruction> after = decode(LocalZeroes.apply(input).words());

		List<Integer> kept = after.stream().filter(i -> i.opcode() != CONSTANT_NULL).map(Instruction::opcode).toList();
		assertEquals(before.stream().map(Instruction::opcode).toList(), kept);
		assertEquals(before.size() + 1, after.size());
	}

	@Test
	void doesNotChangeTheModuleItIsGivenAndAnswersTheSameTwiceAndNothingOnItsOwnOutput() {
		Base b = new Base();
		b.simple(() -> b.load(b.local()));
		int[] input = b.words();
		int[] copy = input.clone();

		LocalZeroes.Result first = LocalZeroes.apply(input);
		LocalZeroes.Result second = LocalZeroes.apply(input);

		assertArrayEquals(copy, input);
		assertArrayEquals(first.words(), second.words());
		// A variable that carries an initialiser is no longer a bare one.
		assertSame(first.words(), LocalZeroes.apply(first.words()).words());
	}

	// ----------------------------------------------------------------- unreadable input

	@Test
	void aModuleThatCannotBeWalkedComesBackAsTheSameArrayForTheDriverToRefuse() {
		int[][] modules = {
			new int[0],
			new int[] {SpirvSupport.MAGIC, 0, 0},
			new int[] {0xdeadbeef, 0, 0, 1, 0},
			// A zero word count would never advance, and one past the end runs off the module.
			new int[] {SpirvSupport.MAGIC, 0, 0, 1, 0, 0},
			new int[] {SpirvSupport.MAGIC, 0, 0, 1, 0, (9 << 16) | TYPE_VOID, 1},
		};

		for (int[] module : modules) {
			LocalZeroes.Result result = LocalZeroes.apply(module);
			assertSame(module, result.words());
			assertFalse(result.changed());
			assertFalse(LocalZeroes.readable(module));
		}
	}

	@Test
	void aModuleWithNothingButItsHeaderIsReadableAndUnchanged() {
		int[] header = {SpirvSupport.MAGIC, 0x00010000, 0, 1, 0};

		assertTrue(LocalZeroes.readable(header));
		assertSame(header, LocalZeroes.apply(header).words());
	}
}
