package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Holds the vector half of the expression language: the constructors, the component accessors, the
 * arithmetic and the functions that take a vector, and the places where a vector and a scalar do
 * not meet.
 * <p>
 * The engine values the expressions read are {@code va} (1, 2, 3), {@code vb} (4, 5), {@code vc}
 * (1, 2, 3, 4), {@code iv} (6, 9) and the matrix {@code m4} whose sixteen numbers count from one
 * down its columns, all set in {@link ExprRig}.
 */
class ExprVectorsTest {

	private final ExprRig rig = new ExprRig();

	private static void assertVec(float[] expected, float[] actual, String what) {
		assertArrayEquals(expected, actual, what + " " + Arrays.toString(actual));
	}

	// constructors

	@Test
	void buildsVectorsFromTwoThreeAndFourScalars() {
		assertVec(new float[] {1, 2}, this.rig.vecOf(2, "vec2(1, 2)"), "vec2");
		assertVec(new float[] {1, 2, 3}, this.rig.vecOf(3, "vec3(1, 2, 3)"), "vec3");
		assertVec(new float[] {1, 2, 3, 4}, this.rig.vecOf(4, "vec4(1, 2, 3, 4)"), "vec4");
		assertVec(new float[] {0.5F, -1.5F, 7}, this.rig.vecOf(3, "vec3(0.5, -1.5, ia)"),
				"floats, a negated float and an engine int in one call");
		assertVec(new float[] {7, 2.5F, 1}, this.rig.vecOf(3, "vec3(ia, fa, 1)"), "engine values");
		assertVec(new float[] {3, 2}, this.rig.vecOf(2, "vec2(va.z, vb.x - 2)"), "components and arithmetic");
	}

	@Test
	void aVectorTakesInfinityAndNaNPerComponent() {
		float[] out = this.rig.vecOf(3, "vec3(0 / 0, 1 / 0, -1 / 0)");
		assertTrue(Float.isNaN(out[0]));
		assertEquals(Float.POSITIVE_INFINITY, out[1]);
		assertEquals(Float.NEGATIVE_INFINITY, out[2]);
	}

	@Test
	void gapAConstructorTakesScalarsOnly() {
		// The TODO in ExprFunctions: vec3(vec2(0), 0). Refused cleanly, at build time.
		assertTrue(this.rig.refusal("vec3", "vec3(vec2(1, 2), 3)").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("vec4", "vec4(va, 1)").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("vec4", "vec4(vb, vb)").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("vec3", "vec3(1)").contains("Couldn't resolve"), "no splat, GLSL's vec3(1)");
		assertTrue(this.rig.refusal("vec3", "vec3(1, 2)").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("vec2", "vec2(1, 2, 3)").contains("Couldn't resolve"));
	}

	@Test
	void aDeclaredVectorMustHaveTheDeclaredSize() {
		assertTrue(this.rig.refusal("vec3", "vb").contains("Couldn't resolve"), "a vec2 is not a vec3");
		assertTrue(this.rig.refusal("vec2", "va").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("vec3", "vec4(1, 2, 3, 4)").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("vec3", "fa").contains("Couldn't resolve"), "a float is not a vec3");
		assertTrue(this.rig.refusal("float", "va").contains("Couldn't resolve"));
	}

	// accessors

	@Test
	void readsAComponentUnderFourFamiliesOfNames() {
		String[][] spellings = {
				{"x", "y", "z", "w"}, {"r", "g", "b", "a"}, {"s", "t", "p", "q"}, {"0", "1", "2", "3"}};
		float[] vc = {1, 2, 3, 4};
		for (String[] family : spellings) {
			for (int k = 0; k < 4; k++) {
				assertEquals(vc[k], this.rig.floatOf("vc." + family[k]), "vc." + family[k]);
			}
			for (int k = 0; k < 3; k++) {
				assertEquals(vc[k], this.rig.floatOf("va." + family[k]), "va." + family[k]);
			}
			for (int k = 0; k < 2; k++) {
				assertEquals(k == 0 ? 4.0F : 5.0F, this.rig.floatOf("vb." + family[k]), "vb." + family[k]);
			}
		}
	}

	@Test
	void anAccessorIsRefusedPastTheSizeOfTheVector() {
		assertTrue(this.rig.refusal("float", "vb.z").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("float", "va.w").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("float", "vb.3").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("float", "fa.x").contains("Couldn't resolve"), "a float has no components");
		assertTrue(this.rig.refusal("float", "ia.x").contains("Couldn't resolve"));
	}

	@Test
	void gapASwizzleOfMoreThanOneComponentIsNoAccessor() {
		assertEquals("r: No such function: <access$xy>", this.rig.refusal("vec2", "va.xy"));
		assertEquals("r: No such function: <access$yx>", this.rig.refusal("vec2", "vb.yx"));
		assertEquals("r: No such function: <access$rgb>", this.rig.refusal("vec3", "va.rgb"));
		assertEquals("r: No such function: <access$xx>", this.rig.refusal("float", "va.xx"));
	}

	@Test
	void anAccessorIsReadOffANameAndNothingElse() {
		// It parses only straight after an identifier or another accessor; after a call or a bracket
		// the dot starts a number and the expression dies as two of them.
		assertTrue(this.rig.refusal("float", "vec3(1, 2, 3).x").contains("The stack of tokens isn't empty"));
		assertTrue(this.rig.refusal("float", "(va + va).x").contains("The stack of tokens isn't empty"));
		assertEquals(1.0F, this.rig.floatOf("(va).x"), "a bracket around a bare name hands the name back");
		assertEquals(2.0F, this.rig.floatOf("va . y"), "spaces around the dot are fine");
	}

	@Test
	void anIntVectorGivesIntComponents() {
		assertEquals(6, this.rig.intOf("iv.x"));
		assertEquals(9, this.rig.intOf("iv.y"));
		assertEquals(9, this.rig.intOf("iv.1"));
		assertEquals(3.0F, this.rig.floatOf("iv.y / 3.0"), "and they divide as any int does");
		assertEquals(15, this.rig.intOf("iv.x + iv.y"));
		assertTrue(this.rig.refusal("float", "iv.z").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("vec2", "iv").contains("Couldn't resolve"), "an ivec2 is not a vec2");
	}

	@Test
	void aMatrixGivesItsColumnsAsVec4() {
		// The constructor of the rig's matrix lists the numbers down the columns: 1 2 3 4, then 5 6 7 8.
		assertVec(new float[] {1, 2, 3, 4}, this.rig.vecOf(4, "m4.x"), "column 0");
		assertVec(new float[] {5, 6, 7, 8}, this.rig.vecOf(4, "m4.y"), "column 1");
		assertVec(new float[] {9, 10, 11, 12}, this.rig.vecOf(4, "m4.z"), "column 2");
		assertVec(new float[] {13, 14, 15, 16}, this.rig.vecOf(4, "m4.w"), "column 3");
		assertVec(new float[] {5, 6, 7, 8}, this.rig.vecOf(4, "m4.g"), "and by colour names");
		assertEquals(13.0F, this.rig.floatOf("m4.w.x"), "a component of a column: two accessors in a row");
		assertEquals(7.0F, this.rig.floatOf("m4.y.z"));
		assertTrue(this.rig.refusal("vec4", "m4").contains("Couldn't resolve"), "a matrix is not a vec4");
		assertTrue(this.rig.refusal("float", "m4.x").contains("Couldn't resolve"), "a column is not a float");
	}

	// arithmetic

	@Test
	void addsSubtractsMultipliesAndDividesComponentWise() {
		assertVec(new float[] {2, 4, 6}, this.rig.vecOf(3, "va + va"), "add");
		assertVec(new float[] {0, 0, 0}, this.rig.vecOf(3, "va - va"), "subtract");
		assertVec(new float[] {1, 4, 9}, this.rig.vecOf(3, "va * va"), "multiply");
		assertVec(new float[] {1, 1, 1}, this.rig.vecOf(3, "va / va"), "divide");
		assertVec(new float[] {8, 10}, this.rig.vecOf(2, "vb + vb"), "vec2 add");
		assertVec(new float[] {16, 25}, this.rig.vecOf(2, "vb * vb"), "vec2 multiply");
		assertVec(new float[] {1, 1}, this.rig.vecOf(2, "vb / vb"), "vec2 divide");
		assertVec(new float[] {2, 4, 6, 8}, this.rig.vecOf(4, "vc + vc"), "vec4 add");
		assertVec(new float[] {-1, 0, 1, 2}, this.rig.vecOf(4, "vc - vec4(2, 2, 2, 2)"), "vec4 subtract");
		assertVec(new float[] {2, 4, 6, 8}, this.rig.vecOf(4, "vc * vec4(2, 2, 2, 2)"), "vec4 multiply");
		assertVec(new float[] {0.5F, 1, 1.5F, 2}, this.rig.vecOf(4, "vc / vec4(2, 2, 2, 2)"), "vec4 divide");
		assertVec(new float[] {-1, -2, -3}, this.rig.vecOf(3, "-va"), "negate");
		assertVec(new float[] {-4, -5}, this.rig.vecOf(2, "-vb"), "vec2 negate");
		assertVec(new float[] {-1, -2, -3, -4}, this.rig.vecOf(4, "-vc"), "vec4 negate");
	}

	@Test
	void vectorArithmeticFollowsTheSamePrecedenceAsScalars() {
		// 1 + (2 * 3) per component, and (1 + 2) * 3 with the brackets.
		assertVec(new float[] {5, 12, 21}, this.rig.vecOf(3, "va + va * vec3(3, 3, 3) + va * va - va + va"),
				"a + a*b + a*a - a + a with b = 3, which is 4a + a*a: (4+1, 8+4, 12+9)");
		assertVec(new float[] {6, 12, 18}, this.rig.vecOf(3, "(va + va) * vec3(3, 3, 3)"), "brackets");
	}

	@Test
	void vectorDivisionByZeroIsIeeePerComponent() {
		float[] out = this.rig.vecOf(3, "va / vec3(0, 0 - 0.0, 1)");
		assertEquals(Float.POSITIVE_INFINITY, out[0]);
		assertEquals(Float.POSITIVE_INFINITY, out[1], "0 - 0.0 is plus zero");
		assertEquals(3.0F, out[2]);
		assertTrue(Float.isNaN(this.rig.vecOf(3, "vec3(0, 0, 0) / vec3(0, 0, 0)")[0]));
	}

	@Test
	void gapAVectorAndAScalarDoNotMeet() {
		for (String expression : new String[] {"va * 2", "2 * va", "va + 1", "va / 2", "va - fa", "fa * va",
				"min(va, 1.0)", "max(va, 1.0)", "clamp(va, 0.0, 1.0)", "mix(va, va, 0.5)", "va % 2",
				"va % va", "frac(va)", "sign(va)", "edge(1, va)", "edge(va, va)", "pow(va, va)",
				"sqrt(va)", "exp(va)", "sin(va)", "mix(va, vec3(0, 0, 0), 0.5)", "smooth(va)",
				"normalize(va)"}) {
			String reason = this.rig.refusal("vec3", expression);
			assertTrue(reason.contains("Couldn't resolve") || reason.contains("No such function"),
					expression + " was refused as: " + reason);
		}
	}

	@Test
	void gapVectorsHaveNoOrdering() {
		for (String expression : new String[] {"va < va", "va <= va", "va > va", "va >= va",
				"lessThan(va, va)"}) {
			assertTrue(this.rig.refusal("bool", expression).contains("Couldn't resolve"), expression);
		}
	}

	@Test
	void vectorsCompareWithTheOperatorsAndWithEqual() {
		// The operators look for "equals" and "notEquals", and each answers a bool for two vectors.
		// A vector equality registered only as "equal", or as a vector type, would be reached by no
		// way of writing it, and every line here would be refused.
		assertTrue(this.rig.boolOf("va == va"));
		assertFalse(this.rig.boolOf("va != va"));
		assertTrue(this.rig.boolOf("equals(va, va)"));
		assertTrue(this.rig.boolOf("equal(va, va)"));
		assertFalse(this.rig.boolOf("notEquals(va, va)"));
		assertFalse(this.rig.boolOf("va == vec3(1, 2, 4)"), "the last component differs");
		assertTrue(this.rig.boolOf("va != vec3(1, 2, 4)"));
	}

	@Test
	void theVectorEqualFunctionAnswersABoolAndNotAVector() {
		// "equal" answers a bool, and a vector is simply not what it gives: asked for one, it is
		// refused. Registered twice with one signature, once as equality and once as its inverse,
		// and with the vector type as its return type, it would throw "Ambiguity" instead.
		String reason = this.rig.refusal("vec3", "equal(va, va)");
		assertTrue(reason.contains("Couldn't resolve"), reason);
	}

	// functions on vectors

	@Test
	void absFloorAndCeilWorkPerComponent() {
		assertVec(new float[] {1, 2, 3}, this.rig.vecOf(3, "abs(-va)"), "abs vec3");
		assertVec(new float[] {4, 5}, this.rig.vecOf(2, "abs(-vb)"), "abs vec2");
		assertVec(new float[] {1, 2, 3, 4}, this.rig.vecOf(4, "abs(-vc)"), "abs vec4");
		assertVec(new float[] {-1, 1, 2}, this.rig.vecOf(3, "floor(vec3(-0.5, 1.5, 2))"), "floor vec3");
		assertVec(new float[] {1, 2}, this.rig.vecOf(2, "floor(vec2(1.9, 2.1))"), "floor vec2");
		assertVec(new float[] {1, 2, 3, 4}, this.rig.vecOf(4, "floor(vec4(1.5, 2.5, 3.5, 4.5))"), "floor vec4");
		assertVec(new float[] {1, 2, 2}, this.rig.vecOf(3, "ceil(vec3(0.2, 1.5, 2))"), "ceil vec3");
		assertVec(new float[] {2, 3}, this.rig.vecOf(2, "ceil(vec2(1.1, 2.9))"), "ceil vec2");
		assertVec(new float[] {2, 3, 4, 5}, this.rig.vecOf(4, "ceil(vec4(1.5, 2.5, 3.5, 4.5))"), "ceil vec4");
	}

	@Test
	void minMaxAndClampWorkPerComponent() {
		assertVec(new float[] {1, 2, 2}, this.rig.vecOf(3, "min(va, vec3(2, 2, 2))"), "min");
		assertVec(new float[] {2, 2, 3}, this.rig.vecOf(3, "max(va, vec3(2, 2, 2))"), "max");
		assertVec(new float[] {1, 2}, this.rig.vecOf(2, "min(vec2(1, 5), vec2(3, 2))"), "vec2 min");
		assertVec(new float[] {3, 5}, this.rig.vecOf(2, "max(vec2(1, 5), vec2(3, 2))"), "vec2 max");
		assertVec(new float[] {1, 2, 3, 1}, this.rig.vecOf(4, "min(vc, vec4(9, 9, 9, 1))"), "vec4 min");
		assertVec(new float[] {9, 9, 9, 4}, this.rig.vecOf(4, "max(vc, vec4(9, 9, 9, 1))"), "vec4 max");
		assertVec(new float[] {1.5F, 2, 2.5F},
				this.rig.vecOf(3, "clamp(va, vec3(1.5, 1.5, 1.5), vec3(2.5, 2.5, 2.5))"), "clamp");
		assertVec(new float[] {0, 0.5F, 1}, this.rig.vecOf(3, "clamp(vec3(-1, 0.5, 2), vec3(0, 0, 0), vec3(1, 1, 1))"),
				"clamp into the unit cube");
		assertVec(new float[] {0, 1}, this.rig.vecOf(2, "clamp(vec2(-1, 2), vec2(0, 0), vec2(1, 1))"), "vec2 clamp");
		assertVec(new float[] {0, 1, 1, 0.25F},
				this.rig.vecOf(4, "clamp(vec4(-1, 1, 2, 0.25), vec4(0, 0, 0, 0), vec4(1, 1, 1, 1))"), "vec4 clamp");
		assertVec(new float[] {1, 1, 1}, this.rig.vecOf(3, "clamp(vec3(5, 5, 5), vec3(1, 1, 1), vec3(0, 0, 0))"),
				"bounds the wrong way round: the lower bound wins, as for scalars");
	}

	@Test
	void ifPicksAWholeVector() {
		assertVec(new float[] {1, 2, 3}, this.rig.vecOf(3, "if(fa > 1, va, vec3(0, 0, 0))"), "then");
		assertVec(new float[] {0, 0, 0}, this.rig.vecOf(3, "if(fa < 1, va, vec3(0, 0, 0))"), "else");
		assertVec(new float[] {4, 5}, this.rig.vecOf(2, "if(ia > 1, vb, vec2(0, 0))"), "vec2");
		assertVec(new float[] {1, 2, 3, 4}, this.rig.vecOf(4, "if(ia > 1, vc, -vc)"), "vec4");
		assertVec(new float[] {-1, -2, -3}, this.rig.vecOf(3, "if(va.x > 5, va, -va)"), "a component in the test");
		assertTrue(this.rig.refusal("vec3", "if(fa > 1, va, vb)").contains("Couldn't resolve"),
				"the two branches must be one type");
		assertTrue(this.rig.refusal("vec3", "if(fa > 1, va, vec3(0, 0, 0), va, vec3(1, 1, 1))")
				.contains("Couldn't resolve"), "and the many-branch form is for scalars only");
	}

	// ivec and bvec

	@Test
	void anIntVectorBuiltInAnExpressionCannotBeDeclaredButCanBeCompared() {
		// ivec2(..) is a plain int array under the hood, not the ivec2 type the engine's values
		// have, so it can neither be declared nor mixed with them. What it can do is pass through
		// the vectorised functions, of which equals is the one that returns a bool.
		assertTrue(this.rig.refusal("vec2", "ivec2(1, 2)").contains("Couldn't resolve"));
		assertTrue(this.rig.boolOf("equals(ivec2(1, 2), ivec2(1, 2))"));
		assertFalse(this.rig.boolOf("equals(ivec2(1, 2), ivec2(1, 3))"));
		assertTrue(this.rig.boolOf("equals(ivec2(1, 2) + ivec2(3, 4), ivec2(4, 6))"), "add is vectorised");
		assertTrue(this.rig.boolOf("equals(ivec3(1, 2, 3) * ivec3(2, 2, 2), ivec3(2, 4, 6))"));
		assertTrue(this.rig.boolOf("equals(min(ivec2(3, 1), ivec2(2, 2)), ivec2(2, 1))"));
		assertTrue(this.rig.boolOf("equals(abs(ivec2(-1, 2)), ivec2(1, 2))"));
	}

	@Test
	void aVectorisedComparisonIsTrueOnlyWhenEveryComponentPasses() {
		// Not the same as "not equals": notEquals is true only when every component differs.
		assertFalse(this.rig.boolOf("notEquals(ivec2(1, 2), ivec2(1, 3))"), "the first component is equal");
		assertTrue(this.rig.boolOf("notEquals(ivec2(1, 2), ivec2(2, 3))"));
		assertFalse(this.rig.boolOf("equals(ivec3(1, 2, 3), ivec3(1, 2, 4))"), "only the last differs");
	}

	// calls that share no buffer

	@Test
	void twoCallsOfOneVectorOperationInOneExpressionKeepTheirOwnResults() {
		// Each call of a vector function has an object and a buffer of its own. Were there ONE
		// object per name, as in Iris, then nested twice in one expression the second call would
		// overwrite the first one's result before the outer call had read it.
		//
		// va + va = (2, 4, 6) and va + va + va = (3, 6, 9).
		assertVec(new float[] {5, 10, 15}, this.rig.vecOf(3, "(va + va) + (va + va + va)"),
				"was 6, 12, 18: both sides read the buffer the right hand add left behind");
		assertVec(new float[] {6, 24, 54}, this.rig.vecOf(3, "(va + va) * (va + va + va)"),
				"(2,4,6)*(3,6,9)");
		// va * va = (1, 4, 9); va * (2,2,2) = (2, 4, 6): sum (3, 8, 15).
		assertVec(new float[] {3, 8, 15}, this.rig.vecOf(3, "(va * va) + (va * vec3(2, 2, 2))"),
				"(1,4,9) + (2,4,6)");
		// min(va, 2) = (1, 2, 2), min(va, 1) = (1, 1, 1): the max of the two is (1, 2, 2).
		assertVec(new float[] {1, 2, 2},
				this.rig.vecOf(3, "max(min(va, vec3(2, 2, 2)), min(va, vec3(1, 1, 1)))"), "max of two mins");
		// abs(-va) = (1,2,3); va - 2 = (-1,0,1), abs = (1,0,1); difference (0,2,2), abs (0,2,2).
		assertVec(new float[] {0, 2, 2}, this.rig.vecOf(3, "abs(abs(-va) - abs(va - vec3(2, 2, 2)))"),
				"abs of a difference of two abs");
	}

	@Test
	void twoDifferentVectorOperationsInOneExpressionDoNotShare() {
		// The same nesting with two different operations: add, subtract and multiply each keep a
		// buffer of their own, so a regression that pooled one buffer across operations would fail
		// here.
		assertVec(new float[] {2, 8, 18}, this.rig.vecOf(3, "(va + va) * va - vec3(0, 0, 0)"),
				"(2,4,6) * (1,2,3)");
		// va * 2 = (2,4,6) and 3 - va = (2,1,0): a multiply and a subtract feeding one add.
		assertVec(new float[] {4, 5, 6}, this.rig.vecOf(3, "(va * vec3(2, 2, 2)) + (vec3(3, 3, 3) - va)"),
				"(2,4,6) + (2,1,0)");
		// A chain that leans left: each inner result is the FIRST operand, which the outer call
		// writes over in place, so it has to be that call's own buffer and nobody else's.
		assertVec(new float[] {4, 8, 12}, this.rig.vecOf(3, "va + va + va + va"), "((a + a) + a) + a");
	}
}
