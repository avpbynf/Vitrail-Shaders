package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Holds every scalar function a pack may call to the value a reader of OptiFine's list would
 * expect, at one ordinary input and at the edge where the arithmetic has to decide something: a
 * NaN, an infinity, a zero divisor, an argument past the range.
 * <p>
 * The values are worked out by hand or from a fact about the function (the sine of ninety degrees
 * is one), not read back from the code. Where the code does something a reader would not expect
 * the test says so in its name: {@code knownBug_} for a defect being fixed elsewhere, {@code gap}
 * for a name or a form that is not there. Those tests pin what is true today so that a fix shows
 * up as a failing test with the old value in it, and none of them is a statement that the value is
 * right.
 */
class ExprFunctionsTest {

	private static final float PI = 3.1415927F;
	private static final float HALF_PI = 1.5707964F;

	private final ExprRig rig = new ExprRig();

	private static void near(float expected, float actual, String what) {
		assertEquals(expected, actual, 1.0E-6F, what);
	}

	// trigonometry

	@Test
	void sineCosineAndTangentAtTheFamiliarAngles() {
		near(0.0F, this.rig.floatOf("sin(0)"), "sin 0");
		near(0.5F, this.rig.floatOf("sin(torad(30))"), "sin 30");
		near(1.0F, this.rig.floatOf("sin(torad(90))"), "sin 90");
		near(-1.0F, this.rig.floatOf("sin(torad(-90))"), "sin -90");
		near(1.0F, this.rig.floatOf("cos(0)"), "cos 0");
		near(0.5F, this.rig.floatOf("cos(torad(60))"), "cos 60");
		near(-1.0F, this.rig.floatOf("cos(torad(180))"), "cos 180");
		near(0.0F, this.rig.floatOf("tan(0)"), "tan 0");
		near(1.0F, this.rig.floatOf("tan(torad(45))"), "tan 45");
	}

	@Test
	void inverseTrigonometryAndItsDomain() {
		near(HALF_PI, this.rig.floatOf("asin(1)"), "asin 1");
		near(0.0F, this.rig.floatOf("asin(0)"), "asin 0");
		near(0.0F, this.rig.floatOf("acos(1)"), "acos 1");
		near(HALF_PI, this.rig.floatOf("acos(0)"), "acos 0");
		near(PI, this.rig.floatOf("acos(-1)"), "acos -1");
		near(PI / 4.0F, this.rig.floatOf("atan(1)"), "atan 1");
		near(0.0F, this.rig.floatOf("atan(0)"), "atan 0");
		assertEquals(Float.NaN, this.rig.floatOf("asin(2)"), "outside [-1, 1]");
		assertEquals(Float.NaN, this.rig.floatOf("acos(-2)"));
	}

	@Test
	void atanWithTwoArgumentsTakesTheOrdinateFirstUnderBothNames() {
		for (String name : new String[] {"atan", "atan2"}) {
			near(PI / 4.0F, this.rig.floatOf(name + "(1, 1)"), name + " (1, 1)");
			near(HALF_PI, this.rig.floatOf(name + "(1, 0)"), name + " (1, 0): straight up");
			near(3.0F * PI / 4.0F, this.rig.floatOf(name + "(1, -1)"), name + " (1, -1)");
			near(PI, this.rig.floatOf(name + "(0, -1)"), name + " (0, -1): straight left");
			near(0.0F, this.rig.floatOf(name + "(0, 0)"), name + " (0, 0)");
			near(-HALF_PI, this.rig.floatOf(name + "(-1, 0)"), name + " (-1, 0): straight down");
		}
	}

	@Test
	void convertsBetweenDegreesAndRadiansUnderBothNames() {
		near(PI, this.rig.floatOf("torad(180)"), "torad");
		near(PI, this.rig.floatOf("radians(180)"), "radians");
		near(HALF_PI, this.rig.floatOf("torad(90)"), "torad 90");
		assertEquals(180.0F, this.rig.floatOf("todeg(3.1415927)"), 1.0E-4F, "todeg");
		assertEquals(180.0F, this.rig.floatOf("degrees(3.1415927)"), 1.0E-4F, "degrees");
		near(0.0F, this.rig.floatOf("torad(0)"), "torad 0");
	}

	// exponentials and logarithms

	@Test
	void powersAndExponentials() {
		assertEquals(1024.0F, this.rig.floatOf("pow(2, 10)"));
		assertEquals(0.5F, this.rig.floatOf("pow(2, -1)"));
		assertEquals(4.0F, this.rig.floatOf("pow(-2, 2)"), "a negative base with a whole exponent");
		assertEquals(1.0F, this.rig.floatOf("pow(0, 0)"));
		assertEquals(Float.NaN, this.rig.floatOf("pow(-8, 1 / 3)"), "a negative base with a fraction");
		assertEquals(Float.POSITIVE_INFINITY, this.rig.floatOf("pow(0, -1)"));
		assertEquals(1.0F, this.rig.floatOf("exp(0)"));
		assertEquals(2.7182817F, this.rig.floatOf("exp(1)"), "e as a float");
		assertEquals(8.0F, this.rig.floatOf("exp2(3)"));
		assertEquals(0.5F, this.rig.floatOf("exp2(-1)"));
		assertEquals(100.0F, this.rig.floatOf("exp10(2)"));
		assertEquals(0.0F, this.rig.floatOf("exp(-1000)"), "underflows to zero");
		assertEquals(Float.POSITIVE_INFINITY, this.rig.floatOf("exp(1000)"));
	}

	@Test
	void logOfOneArgumentIsNaturalAndOfTwoTakesTheBaseFirst() {
		assertEquals(0.0F, this.rig.floatOf("log(1)"));
		near(1.0F, this.rig.floatOf("log(2.7182817)"), "ln e");
		assertEquals(3.0F, this.rig.floatOf("log(2, 8)"), "log base 2 of 8: base first, value second");
		assertEquals(3.0F, this.rig.floatOf("log(10, 1000)"));
		assertEquals(0.5F, this.rig.floatOf("log(4, 2)"), "log base 4 of 2, not log base 2 of 4");
		assertEquals(3.0F, this.rig.floatOf("log2(8)"));
		assertEquals(3.0F, this.rig.floatOf("log10(1000)"));
		near(-3.0F, this.rig.floatOf("log10(0.001)"), "log10 of a fraction");
	}

	@Test
	void logarithmsAtTheEdgeOfTheirDomain() {
		assertEquals(Float.NEGATIVE_INFINITY, this.rig.floatOf("log(0)"));
		assertEquals(Float.NaN, this.rig.floatOf("log(-1)"));
		assertEquals(Float.NEGATIVE_INFINITY, this.rig.floatOf("log2(0)"));
		assertEquals(Float.NEGATIVE_INFINITY, this.rig.floatOf("log10(0)"));
		assertEquals(Float.NaN, this.rig.floatOf("log2(-1)"));
		// log(base, value) is ln(value) / ln(base): a base of one divides by zero and a base of
		// zero divides by minus infinity.
		assertEquals(Float.POSITIVE_INFINITY, this.rig.floatOf("log(1, 8)"));
		assertEquals(-0.0F, this.rig.floatOf("log(0, 8)"));
	}

	@Test
	void squareRoot() {
		assertEquals(4.0F, this.rig.floatOf("sqrt(16)"));
		assertEquals(0.0F, this.rig.floatOf("sqrt(0)"));
		assertEquals(0.5F, this.rig.floatOf("sqrt(0.25)"));
		assertEquals(Float.NaN, this.rig.floatOf("sqrt(-1)"));
	}

	// common functions

	@Test
	void absoluteValueOfFloatsAndInts() {
		assertEquals(2.5F, this.rig.floatOf("abs(-2.5)"));
		assertEquals(2.5F, this.rig.floatOf("abs(2.5)"));
		assertEquals(3, this.rig.intOf("abs(-3)"));
		assertEquals(0.0F, this.rig.floatOf("abs(0 - 0.0)"));
		assertEquals(Integer.MIN_VALUE, this.rig.intOf("abs(0 - 2147483647 - 1)"),
				"the one int with no positive twin stays negative, as in Java");
	}

	@Test
	void signUnderBothNames() {
		for (String name : new String[] {"sign", "signum"}) {
			assertEquals(-1.0F, this.rig.floatOf(name + "(-3)"), name);
			assertEquals(1.0F, this.rig.floatOf(name + "(2.5)"), name);
			assertEquals(0.0F, this.rig.floatOf(name + "(0)"), name + " of zero is zero");
			assertEquals(Float.NaN, this.rig.floatOf(name + "(0 / 0)"), name + " of NaN");
		}
	}

	@Test
	void floorAndCeilRoundTowardsTheirInfinity() {
		assertEquals(2.0F, this.rig.floatOf("floor(2.5)"));
		assertEquals(-1.0F, this.rig.floatOf("floor(-0.5)"));
		assertEquals(-3.0F, this.rig.floatOf("floor(-2.5)"));
		assertEquals(2.0F, this.rig.floatOf("floor(2)"));
		assertEquals(3.0F, this.rig.floatOf("ceil(2.5)"));
		assertEquals(-2.0F, this.rig.floatOf("ceil(-2.5)"));
		assertEquals(-0.0F, this.rig.floatOf("ceil(-0.5)"), "minus zero, as Math.ceil gives it");
	}

	@Test
	void floorAndCeilAlsoAnswerAnIntWhichSaturates() {
		// The float form is chosen for a float target, so a big float survives; the int form is
		// chosen for an int target and clamps the way a Java cast does.
		assertEquals(1.0E10F, this.rig.floatOf("floor(1e10)"));
		assertEquals(Integer.MAX_VALUE, this.rig.intOf("floor(1e10)"));
		assertEquals(Integer.MIN_VALUE, this.rig.intOf("ceil(-1e10)"));
		assertEquals(2, this.rig.intOf("floor(2.9)"));
		assertEquals(3, this.rig.intOf("ceil(2.1)"));
		assertEquals(-3, this.rig.intOf("floor(-2.1)"));
		assertEquals(0, this.rig.intOf("floor(0 / 0)"), "NaN is zero as an int");
	}

	@Test
	void fracIsTheDistanceAboveTheFloor() {
		assertEquals(0.75F, this.rig.floatOf("frac(2.75)"));
		assertEquals(0.75F, this.rig.floatOf("frac(-0.25)"), "above the floor, not the part after the point");
		assertEquals(0.0F, this.rig.floatOf("frac(3)"));
		assertEquals(0.0F, this.rig.floatOf("frac(0)"));
		assertEquals(0.5F, this.rig.floatOf("frac(-1.5)"));
		assertEquals(Float.NaN, this.rig.floatOf("frac(0 / 0)"));
		assertEquals(1.0F, this.rig.floatOf("frac(-0.00000001)"),
				"a tiny negative rounds up to one, so frac is not always below one");
	}

	@Test
	void minAndMaxOfTwo() {
		assertEquals(1.0F, this.rig.floatOf("min(1, 2)"));
		assertEquals(1.5F, this.rig.floatOf("min(2.5, 1.5)"));
		assertEquals(2.0F, this.rig.floatOf("max(1, 2)"));
		assertEquals(2.5F, this.rig.floatOf("max(2.5, 1.5)"));
		assertEquals(1.0F, this.rig.floatOf("min(fa, 1.0)"), "engine float 2.5");
		assertEquals(1.0F, this.rig.floatOf("min(1, 2.5)"), "an int and a float take the float form");
		assertEquals(7.0F, this.rig.floatOf("max(ia, 3.5)"), "engine int 7");
		assertEquals(3, this.rig.intOf("min(3, 4)"));
		assertEquals(4, this.rig.intOf("max(3, 4)"));
		assertEquals(-4, this.rig.intOf("min(-3, -4)"));
		assertEquals(Float.NaN, this.rig.floatOf("min(0 / 0, 1)"), "NaN wins, as Math.min has it");
		assertEquals(Float.NaN, this.rig.floatOf("max(1, 0 / 0)"));
	}

	@Test
	void minAndMaxOfThreeOrMoreReadEveryArgument() {
		// OptiFine's min(x, y, ...) is the least of all of them. A loop that read params[1] on
		// every turn, as Iris's does, would never count the third argument onward, and each of
		// these would give the second argument.
		assertEquals(1.0F, this.rig.floatOf("min(3, 2, 1)"));
		assertEquals(3.0F, this.rig.floatOf("max(1, 2, 3)"));
		assertEquals(9.0F, this.rig.floatOf("max(1, 2, 9, 3)"));
		assertEquals(0.0F, this.rig.floatOf("min(9, 8, 1, 0)"));
		assertEquals(0.5F, this.rig.floatOf("min(1.5, 2.5, 0.5)"));
		assertEquals(9.5F, this.rig.floatOf("max(1.5, 2.5, 9.5)"));
		assertEquals(1, this.rig.intOf("min(3, 2, 1)"), "the int form has a loop of its own");
		assertEquals(3, this.rig.intOf("max(1, 2, 3)"));
		assertEquals(16.0F, this.rig.floatOf("max(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)"),
				"sixteen arguments, all of them read");
	}

	@Test
	void minAndMaxOfThreeAreRightWhenTheAnswerIsAmongTheFirstTwo() {
		// The answer is among the first two, so a loop that read only params[1] would be right here
		// too: what these hold is that reading every argument does not disturb them.
		assertEquals(1.0F, this.rig.floatOf("min(3, 1, 2)"));
		assertEquals(5.0F, this.rig.floatOf("max(1, 5, 3)"));
		assertEquals(1, this.rig.intOf("min(1, 2, 3)"));
		assertEquals(3, this.rig.intOf("max(3, 2, 1)"));
	}

	@Test
	void minAndMaxTakeTwoToSixteenArguments() {
		String sixteen = IntStream.rangeClosed(1, 16).mapToObj(Integer::toString)
				.collect(Collectors.joining(", "));
		String seventeen = sixteen + ", 17";

		assertEquals(1.0F, this.rig.floatOf("min(" + sixteen + ")"), "sixteen are taken, ascending");
		assertEquals(1, this.rig.intOf("min(" + sixteen + ")"));
		assertTrue(this.rig.refusal("float", "max(" + seventeen + ")").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("float", "min(1)").contains("Couldn't resolve"), "one argument");
		assertTrue(this.rig.refusal("float", "min()").contains("Couldn't resolve"), "none");
	}

	@Test
	void clampLimitsToTheRange() {
		assertEquals(1.0F, this.rig.floatOf("clamp(5, 0, 1)"));
		assertEquals(0.0F, this.rig.floatOf("clamp(-5, 0, 1)"));
		assertEquals(0.5F, this.rig.floatOf("clamp(0.5, 0, 1)"));
		assertEquals(0.0F, this.rig.floatOf("clamp(0, 0, 1)"), "on the lower bound");
		assertEquals(1.0F, this.rig.floatOf("clamp(1, 0, 1)"), "on the upper bound");
		assertEquals(5, this.rig.intOf("clamp(9, 0, 5)"));
		assertEquals(0, this.rig.intOf("clamp(-9, 0, 5)"));
		assertEquals(Float.NaN, this.rig.floatOf("clamp(0 / 0, 0, 1)"), "NaN passes through");
		assertEquals(1.0F, this.rig.floatOf("clamp(1 / 0, 0, 1)"), "infinity is clamped");
	}

	@Test
	void clampWithTheBoundsTheWrongWayRoundLetsTheLowerBoundWin() {
		// max(lo, min(hi, x)) with lo 1 and hi 0: min(0, 5) = 0, max(1, 0) = 1. GLSL calls this
		// undefined, and the source says "if max < min => undefined behaviour".
		assertEquals(1.0F, this.rig.floatOf("clamp(5, 1, 0)"));
		assertEquals(1.5F, this.rig.floatOf("clamp(5.5, 1.5, 0.5)"));
		assertEquals(1, this.rig.intOf("clamp(5, 1, 0)"));
		assertEquals(1.0F, this.rig.floatOf("clamp(-5, 1, 0)"));
	}

	@Test
	void mixInterpolatesAndExtrapolatesLinearly() {
		assertEquals(2.5F, this.rig.floatOf("mix(0, 10, 0.25)"));
		assertEquals(1.0F, this.rig.floatOf("mix(1, 3, 0)"));
		assertEquals(3.0F, this.rig.floatOf("mix(1, 3, 1)"));
		assertEquals(2.0F, this.rig.floatOf("mix(1, 3, 0.5)"));
		assertEquals(5.0F, this.rig.floatOf("mix(1, 3, 2)"), "no clamp on the weight");
		assertEquals(-1.0F, this.rig.floatOf("mix(1, 3, -1)"));
		assertEquals(Float.NaN, this.rig.floatOf("mix(1, 1 / 0, 0)"), "0 * infinity is NaN, even at weight zero");
	}

	@Test
	void edgeIsGlslStepUnderAnotherName() {
		// edge(edge, x): zero below the edge, one on it and above.
		assertEquals(0.0F, this.rig.floatOf("edge(2, 1)"));
		assertEquals(1.0F, this.rig.floatOf("edge(1, 2)"));
		assertEquals(1.0F, this.rig.floatOf("edge(1, 1)"), "on the edge is one");
		assertEquals(1.0F, this.rig.floatOf("edge(1, 0 / 0)"), "NaN is not below the edge");
		assertEquals(1, this.rig.intOf("edge(1, 2)"), "and an int form");
		assertEquals(0, this.rig.intOf("edge(2, 1)"));
		assertEquals(1, this.rig.intOf("edge(1, 1)"));
		// Float literals, which take the float form and not the int one behind a cast.
		assertEquals(0.0F, this.rig.floatOf("edge(1.5, 1.25)"));
		assertEquals(1.0F, this.rig.floatOf("edge(1.5, 1.5)"), "on the edge");
		assertEquals(1.0F, this.rig.floatOf("edge(0.5, 1.5)"));
	}

	@Test
	void fmodTakesTheSignOfTheDivisor() {
		assertEquals(2.0F, this.rig.floatOf("fmod(-1, 3)"), "unlike the % operator, which gives -1");
		assertEquals(1.0F, this.rig.floatOf("fmod(4, 3)"));
		assertEquals(1.5F, this.rig.floatOf("fmod(5.5, 2)"));
		assertEquals(0.5F, this.rig.floatOf("fmod(-1.5, 2)"));
		assertEquals(-2.0F, this.rig.floatOf("fmod(1, -3)"), "a negative divisor gives a negative result");
		assertEquals(Float.NaN, this.rig.floatOf("fmod(1, 0.0)"));
		assertEquals(2, this.rig.intOf("fmod(-7, 3)"), "the int form is Math.floorMod");
		assertEquals(1, this.rig.intOf("fmod(7, 3)"));
		assertEquals(-2, this.rig.intOf("fmod(1, -3)"));
	}

	// booleans

	@Test
	void betweenIsInclusiveAtBothEnds() {
		assertTrue(this.rig.boolOf("between(2, 1, 3)"));
		assertTrue(this.rig.boolOf("between(1, 1, 3)"));
		assertTrue(this.rig.boolOf("between(3, 1, 3)"));
		assertFalse(this.rig.boolOf("between(4, 1, 3)"));
		assertFalse(this.rig.boolOf("between(0, 1, 3)"));
		assertTrue(this.rig.boolOf("between(1.5, 1, 2)"), "floats");
		assertTrue(this.rig.boolOf("between(1.0, 1.0, 3.5)"), "the float form, on the lower bound");
		assertTrue(this.rig.boolOf("between(3.5, 1.0, 3.5)"), "and on the upper");
		assertFalse(this.rig.boolOf("between(3.75, 1.0, 3.5)"));
		assertFalse(this.rig.boolOf("between(2.5, 1, 2)"));
		assertFalse(this.rig.boolOf("between(3, 3, 1)"), "the bounds the wrong way round admit nothing");
		assertFalse(this.rig.boolOf("between(0 / 0, 0, 1)"), "NaN is between nothing");
	}

	@Test
	void equalsWithAnEpsilon() {
		assertTrue(this.rig.boolOf("equals(1.0, 1.05, 0.1)"));
		assertFalse(this.rig.boolOf("equals(1.0, 1.2, 0.1)"));
		assertTrue(this.rig.boolOf("equals(1, 3, 2)"), "on the margin, inclusive");
		assertTrue(this.rig.boolOf("equals(1, 1, 0)"));
		assertFalse(this.rig.boolOf("equals(0 / 0, 0 / 0, 1)"));
		assertFalse(this.rig.boolOf("equals(1 / 0, 0 - 1 / 0, 1)"), "opposite infinities are far apart");
	}

	@Test
	void equalsWithTwoArgumentsIsTheOperator() {
		assertTrue(this.rig.boolOf("equals(1.5, 1.5)"));
		assertFalse(this.rig.boolOf("equals(1.5, 1.6)"));
		assertTrue(this.rig.boolOf("equals(2, 2)"));
		assertFalse(this.rig.boolOf("equals(0 / 0, 0 / 0)"));
	}

	@Test
	void inIsTrueWhenTheFirstEqualsAnyOfTheRest() {
		assertTrue(this.rig.boolOf("in(3, 1, 2, 3)"));
		assertFalse(this.rig.boolOf("in(3, 1, 2)"));
		assertTrue(this.rig.boolOf("in(1.5, 1.5, 2.5)"));
		assertTrue(this.rig.boolOf("in(1, 1)"), "two arguments is the smallest");
		assertFalse(this.rig.boolOf("in(1, 2)"));
		assertFalse(this.rig.boolOf("in(0 / 0, 0 / 0)"), "NaN is in nothing");
		assertTrue(this.rig.boolOf("in(ia, 5, 6, 7)"), "engine int 7");
	}

	@Test
	void inTakesTwoToThirtyTwoArguments() {
		String thirtyOne = IntStream.rangeClosed(1, 31).mapToObj(Integer::toString)
				.collect(Collectors.joining(", "));

		assertTrue(this.rig.boolOf("in(31, " + thirtyOne + ")"), "thirty two arguments");
		assertFalse(this.rig.boolOf("in(99, " + thirtyOne + ")"));
		assertTrue(this.rig.refusal("bool", "in(31, " + thirtyOne + ", 32)").contains("Couldn't resolve"),
				"thirty three");
		assertTrue(this.rig.refusal("bool", "in(1)").contains("Couldn't resolve"), "one");
		assertTrue(this.rig.refusal("float", "in(1, 1)").contains("Couldn't resolve"),
				"and it is a bool, not a number");
	}

	@Test
	void namedBooleanOperators() {
		assertFalse(this.rig.boolOf("and(1 > 0, 1 < 0)"));
		assertTrue(this.rig.boolOf("and(1 > 0, 1 < 2)"));
		assertTrue(this.rig.boolOf("or(1 > 0, 1 < 0)"));
		assertFalse(this.rig.boolOf("or(1 < 0, 1 < 0)"));
		assertTrue(this.rig.boolOf("not(1 < 0)"));
		assertFalse(this.rig.boolOf("not(ia)"), "an int is read as a bool");
		assertTrue(this.rig.boolOf("notEquals(1, 2)"));
		assertTrue(this.rig.boolOf("equals(1 > 0, 2 > 1)"), "equality of two bools");
	}

	@Test
	void everyOperatorIsAlsoAFunctionByItsName() {
		assertEquals(3.0F, this.rig.floatOf("add(1, 2)"));
		assertEquals(3.0F, this.rig.floatOf("subtract(5, 2)"));
		assertEquals(6.0F, this.rig.floatOf("multiply(2, 3)"));
		assertEquals(0.25F, this.rig.floatOf("divide(1, 4)"));
		assertEquals(2.0F, this.rig.floatOf("remainder(5, 3)"));
		assertEquals(-2.0F, this.rig.floatOf("negate(2)"));
		assertEquals(3, this.rig.intOf("add(1, 2)"));
		assertTrue(this.rig.boolOf("equals(1, 1)"));
	}

	// random

	@Test
	void randomStaysInItsRangeAndMovesEveryFrame() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.r = random()",
				"uniform.float.s = random(2, 4)",
				"uniform.float.t = random(4, 2)",
				"uniform.int.n = randomInt(5)",
				"uniform.int.m = randomInt(3, 7)",
				"uniform.int.any = randomInt()");
		Set<Float> seen = new HashSet<>();
		Set<Integer> seenInts = new HashSet<>();
		for (int frame = 0; frame < 200; frame++) {
			this.rig.frame(uniforms);
			float r = this.rig.f(uniforms, "r");
			float s = this.rig.f(uniforms, "s");
			float t = this.rig.f(uniforms, "t");
			int n = this.rig.i(uniforms, "n");
			int m = this.rig.i(uniforms, "m");
			assertTrue(r >= 0.0F && r < 1.0F, "random() " + r);
			assertTrue(s >= 2.0F && s <= 4.0F, "random(2, 4) " + s);
			assertTrue(t >= 2.0F && t <= 4.0F, "random(4, 2) reads min + r * (max - min): " + t);
			assertTrue(n >= 0 && n < 5, "randomInt(5) " + n);
			assertTrue(m >= 3 && m < 7, "randomInt(3, 7) " + m);
			seen.add(r);
			seenInts.add(this.rig.i(uniforms, "any"));
		}

		assertTrue(seen.size() > 100, "a constant expression is evaluated again every frame, not folded: "
				+ seen.size() + " distinct in 200");
		assertTrue(seenInts.size() > 100, "randomInt() over the whole int range: " + seenInts.size());
	}

	@Test
	void randomIntWithAnEmptyRangeThrowsAndTheDeclarationIsDropped() {
		assertTrue(this.rig.throwing("int", "randomInt(0)").getFirst().contains("bound must be positive"));
		assertTrue(this.rig.throwing("int", "randomInt(3, 3)").getFirst().contains("bound must be positive"));
		assertTrue(this.rig.throwing("int", "randomInt(5, 3)").getFirst().contains("bound must be positive"));
	}

	@Test
	void integerRemainderAndFmodByZeroThrowAndTheDeclarationIsDropped() {
		assertTrue(this.rig.throwing("int", "5 % 0").getFirst().contains("/ by zero"));
		assertTrue(this.rig.throwing("int", "fmod(5, 0)").getFirst().contains("/ by zero"));
		assertTrue(this.rig.throwing("int", "remainder(5, 0)").getFirst().contains("/ by zero"));
	}

	// the gaps, one per TODO in ExprFunctions

	@Test
	void roundIsRegisteredAsOptiFineEvaluatesIt() {
		// round(x) is in the header list and in OptiFine, and Iris registers nothing for it, so
		// left unregistered both of these would be refused with "No such function: round". The
		// halves either side of nought are in RoundAsOptiFineTest.
		assertEquals(3.0F, this.rig.floatOf("round(2.5)"), "a half goes up");
		assertEquals(2, this.rig.intOf("round(2)"), "an int declaration takes the int form");
		assertTrue(ExprFunctions.functions.names().contains("round"));
	}

	@Test
	void gapStepSmoothstepAndLerpAreNotRegistered() {
		assertEquals("r: No such function: step", this.rig.refusal("float", "step(1, 2)"));
		assertEquals("r: No such function: smoothstep", this.rig.refusal("float", "smoothstep(0, 1, 0.5)"));
		assertEquals("r: No such function: lerp", this.rig.refusal("float", "lerp(0, 10, 0.5)"));
	}

	@Test
	void gapTheGeometricAndMatrixFunctionsAreNotRegistered() {
		for (String name : new String[] {"normalize", "length", "dot", "cross", "distance", "reflect",
				"mod", "inversesqrt", "trunc", "saturate", "isnan"}) {
			String expression = name + "(va)";
			String reason = this.rig.refusal("float", expression);
			assertEquals("r: No such function: " + name, reason, expression);
		}
	}

	@Test
	void gapAFunctionNameOnItsOwnIsAVariableLookup() {
		assertEquals("r: Unknown variable: if", this.rig.refusal("float", "if"));
		assertEquals("r: No such function: fa", this.rig.refusal("float", "fa(1)"), "and a variable is no function");
		assertEquals("r: No such function: frobnicate", this.rig.refusal("float", "frobnicate(1)"));
	}

	@Test
	void wrongArityIsRefusedAsUnresolvable() {
		for (String expression : new String[] {"pow(2)", "pow(1, 2, 3)", "sin()", "sin(1, 2)", "clamp(1, 2)",
				"mix(1, 2)", "abs(1, 2)", "sqrt()", "floor(1, 2)", "if(1)", "if(1 > 0, 2)",
				"between(1, 2)", "fmod(1)", "edge(1)"}) {
			String reason = this.rig.refusal("float", expression);
			assertTrue(reason.contains("Couldn't resolve"), expression + " was refused as: " + reason);
		}
	}

	@Test
	void theRegistryHoldsExactlyTheseNames() {
		// A list, so that a name added or lost has to be added here on purpose. The accessors are
		// the eight spellings of a component plus the four indices. Three names are left out of
		// the list and held in tests of their own: round and equal, which are registered on purpose,
		// and notEqual, which is not, so that a change to one of them changes only that test.
		List<String> expected = new ArrayList<>(List.of(
				"<access$0>", "<access$1>", "<access$2>", "<access$3>", "<access$a>", "<access$b>",
				"<access$g>", "<access$p>", "<access$q>", "<access$r>", "<access$s>", "<access$t>",
				"<access$w>", "<access$x>", "<access$y>", "<access$z>", "<cast>",
				"abs", "acos", "add", "and", "asin", "atan", "atan2", "between", "bvec2", "bvec3", "bvec4",
				"ceil", "clamp", "cos", "degrees", "divide", "edge", "equals", "exp", "exp10",
				"exp2", "floor", "fmod", "frac", "if", "in", "ivec2", "ivec3", "ivec4", "lessThan",
				"lessThanOrEquals", "log", "log10", "log2", "max", "min", "mix", "moreThan",
				"moreThanOrEquals", "multiply", "negate", "not", "notEquals", "or", "pow", "radians",
				"random", "randomInt", "remainder", "sign", "signum", "sin", "smooth", "sqrt", "subtract",
				"tan", "toBoolean", "toFloat", "toInt", "todeg", "torad", "vec2", "vec3", "vec4"));
		List<String> registered = new ArrayList<>(ExprFunctions.functions.names());
		registered.removeAll(List.of("round", "equal", "notEqual"));

		assertEquals(expected, registered);
	}

	@Test
	void theVectorEqualityIsNamedEqualAndItsInverseNotEquals() {
		// The inverse of "equal" goes by the name of the != operator, notEquals, beside the other
		// types, and no "notEqual" is registered for it. The name notEquals is taken by the scalar
		// forms as well, so its being listed says nothing about the vector one: that is asked by
		// calling it on two vectors, and an inverse registered under another name resolves nothing.
		List<String> names = ExprFunctions.functions.names();
		assertTrue(names.contains("equal"));
		assertFalse(names.contains("notEqual"));
		assertTrue(this.rig.boolOf("notEquals(vec3(1.0, 2.0, 3.0), vec3(1.0, 2.0, 4.0))"));
		assertFalse(this.rig.boolOf("notEquals(vec3(1.0, 2.0, 3.0), vec3(1.0, 2.0, 3.0))"));
	}
}
