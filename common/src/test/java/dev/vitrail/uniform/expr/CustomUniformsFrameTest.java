package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.UniformSource;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.values.FrameSmoothed;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds what happens from one frame to the next: the values follow the engine, an engine value is
 * read once however many declarations use it, a {@code smooth()} moves once a frame, and an
 * expression that throws leaves the graph without taking the frame with it.
 * <p>
 * The smoothing numbers are worked out by hand. A half life is in tenths of a second, so a fade
 * time of one is a tenth of a second, and a frame of exactly a tenth moves the value half way to
 * where it is going; two is a fifth of a second, and a frame of a tenth then moves it
 * {@code 1 - 2^(-1/2)} of the way. The first value is taken as it comes, with no smoothing.
 */
class CustomUniformsFrameTest {

	private final ExprRig rig = new ExprRig();

	@BeforeEach
	void forgetWhatTheLastTestSmoothed() {
		FrameSmoothed.forgetAll();
	}

	/** How far one frame of {@code dt} moves an accumulator, by the half life formula written out. */
	private static double factor(double halfLifeDeciseconds, double dt) {
		return 1.0 - Math.pow(2.0, -dt / (halfLifeDeciseconds * 0.1));
	}

	// following the engine

	@Test
	void everyFrameBringsEveryValueUpToDate() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.a = fa * 2.0",
				"uniform.int.n = ia + 1",
				"uniform.vec3.v = va + va");

		this.rig.frame(uniforms);
		assertEquals(5.0F, this.rig.f(uniforms, "a"));
		assertEquals(8, this.rig.i(uniforms, "n"));
		assertEquals(4.0F, this.rig.vec(uniforms, "v", 3)[1]);

		this.rig.fa = 10.0F;
		this.rig.ia = 100;
		this.rig.va = new float[] {10, 20, 30};
		this.rig.frame(uniforms);
		assertEquals(20.0F, this.rig.f(uniforms, "a"));
		assertEquals(101, this.rig.i(uniforms, "n"));
		assertEquals(40.0F, this.rig.vec(uniforms, "v", 3)[1]);
	}

	@Test
	void nothingIsReadBeforeTheFirstFrameAndAValueIsZeroUntilThen() {
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.a = fa * 2.0");

		assertTrue(this.rig.reads.isEmpty(), "building asks the table for a type, not for a value");
		assertEquals(0.0F, this.rig.f(uniforms, "a"), "and the value before the first frame is zero");
	}

	@Test
	void anEngineValueIsReadOncePerFrameHoweverManyDeclarationsUseIt() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.a = fa + 1.0",
				"uniform.float.b = fa * 2.0",
				"uniform.float.c = fa - va.x",
				"variable.float.d = fa * fa * fa",
				"uniform.float.e = d + fa");
		for (int frame = 0; frame < 3; frame++) {
			this.rig.frame(uniforms);
		}

		assertEquals(3, this.rig.reads.get("fa"), "one read a frame for five readers");
		assertEquals(3, this.rig.reads.get("va"));
		assertEquals(2, this.rig.reads.size(), "and no other name is read: " + this.rig.reads);
	}

	@Test
	void aDerivedValueIsWorkedOutOncePerFrameHoweverManyDeclarationsReadIt() {
		// random() is a new number every time it is evaluated, so two readers that agree read one
		// evaluation.
		CustomUniforms uniforms = this.rig.buildClean(
				"variable.float.r = random()",
				"uniform.float.a = r * 1.0",
				"uniform.float.b = r + 0.0");
		for (int frame = 0; frame < 20; frame++) {
			this.rig.frame(uniforms);
			assertEquals(this.rig.f(uniforms, "a"), this.rig.f(uniforms, "b"), "frame " + frame);
			assertEquals(this.rig.f(uniforms, "a"), this.rig.f(uniforms, "r"), "frame " + frame);
		}
	}

	@Test
	void twoUsesOfRandomInOneFrameAreTwoEvaluations() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.a = random()",
				"uniform.float.b = random()");
		int differing = 0;
		for (int frame = 0; frame < 50; frame++) {
			this.rig.frame(uniforms);
			if (this.rig.f(uniforms, "a") != this.rig.f(uniforms, "b")) {
				differing++;
			}
		}

		assertTrue(differing > 45, "the two sites draw separately: " + differing + " of 50 differ");
	}

	@Test
	void theFrameClockIsTheDurationHandedToUpdate() {
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.a = fa");
		assertEquals(0.0F, uniforms.deltaSeconds(), "nothing has run yet");

		this.rig.frame(uniforms, 0.25F);
		assertEquals(0.25F, uniforms.deltaSeconds());
	}

	// smoothing

	@Test
	void smoothStartsAtTheValueItIsFirstGivenAndThenFollowsIt() {
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.s = smooth(fa)");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		assertEquals(4.0F, this.rig.f(uniforms, "s"), "the first value is not smoothed");

		// Default fade one: a frame of 0.1 s is exactly one half life, so each frame halves the gap.
		this.rig.fa = 0.0F;
		this.rig.frame(uniforms);
		assertEquals(2.0F, this.rig.f(uniforms, "s"), 1.0E-5F);
		this.rig.frame(uniforms);
		assertEquals(1.0F, this.rig.f(uniforms, "s"), 1.0E-5F);
		this.rig.frame(uniforms);
		assertEquals(0.5F, this.rig.f(uniforms, "s"), 1.0E-5F);

		this.rig.fa = 8.0F;
		this.rig.frame(uniforms);
		assertEquals(4.25F, this.rig.f(uniforms, "s"), 1.0E-5F, "0.5 + (8 - 0.5) / 2, on the way up");
	}

	@Test
	void smoothTakesOneFadeTimeForBothWaysOrOneForEach() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.same = smooth(fa, 2)",
				"uniform.float.split = smooth(fa, 1, 3)");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);

		this.rig.fa = 0.0F;
		this.rig.frame(uniforms);
		assertEquals(4.0F * (1.0F - (float) factor(2, 0.1)), this.rig.f(uniforms, "same"), 1.0E-4F);
		assertEquals(2.8284271F, this.rig.f(uniforms, "same"), 1.0E-4F, "4 * 2^(-1/2)");
		assertEquals(3.1748021F, this.rig.f(uniforms, "split"), 1.0E-4F, "falling uses the third: 4 * 2^(-1/3)");

		this.rig.fa = 8.0F;
		this.rig.frame(uniforms);
		assertEquals(2.8284271F + (8.0F - 2.8284271F) * (1.0F - (float) Math.pow(2.0, -0.5)),
				this.rig.f(uniforms, "same"), 1.0E-4F, "both ways alike");
		assertEquals(3.1748021F + (8.0F - 3.1748021F) * 0.5F, this.rig.f(uniforms, "split"), 1.0E-4F,
				"rising uses the second of two: a half life of one, so half the gap");
	}

	@Test
	void smoothTakesAnOptionalIdentifierThatIsNeverRead() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.plain = smooth(fa)",
				"uniform.float.tagged = smooth(7, fa)",
				"uniform.float.tagged2 = smooth(9, fa, 2)",
				"uniform.float.tagged3 = smooth(11, fa, 1, 3)");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		this.rig.fa = 0.0F;
		this.rig.frame(uniforms);

		assertEquals(this.rig.f(uniforms, "plain"), this.rig.f(uniforms, "tagged"), 1.0E-6F,
				"smooth(7, fa) is the id form and fades as smooth(fa) does, not as smooth(fa, 7) would");
		assertEquals(2.0F, this.rig.f(uniforms, "tagged"), 1.0E-5F);
		assertEquals(2.8284271F, this.rig.f(uniforms, "tagged2"), 1.0E-4F);
		assertEquals(3.1748021F, this.rig.f(uniforms, "tagged3"), 1.0E-4F);
	}

	@Test
	void smoothRefusesAnIdentifierThatIsNotALiteral() {
		assertTrue(this.rig.refusal("float", "smooth(fa, fa, 2, 3)").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("float", "smooth()").contains("Couldn't resolve"));
		assertTrue(this.rig.refusal("float", "smooth(1, 2, 3, 4, 5)").contains("Couldn't resolve"));
	}

	@Test
	void eachCallSiteOfSmoothHasAnAccumulatorOfItsOwn() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.a = smooth(fa)",
				"uniform.float.b = smooth(fa)",
				"uniform.float.c = smooth(fa, 5)");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		this.rig.fa = 0.0F;
		this.rig.frame(uniforms);

		assertEquals(2.0F, this.rig.f(uniforms, "a"), 1.0E-5F);
		assertEquals(2.0F, this.rig.f(uniforms, "b"), 1.0E-5F, "one step, not one step per site");
		assertEquals(4.0F * (float) Math.pow(2.0, -0.2), this.rig.f(uniforms, "c"), 1.0E-4F,
				"a fade of five is half a second: 4 * 2^(-0.1/0.5)");
	}

	@Test
	void aSmoothedValueReadByTwoDeclarationsMovesOnceAFrame() {
		CustomUniforms uniforms = this.rig.buildClean(
				"variable.float.sm = smooth(fa)",
				"uniform.float.a = sm * 1.0",
				"uniform.float.b = sm + 0.0");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		this.rig.fa = 0.0F;
		this.rig.frame(uniforms);

		assertEquals(2.0F, this.rig.f(uniforms, "a"), 1.0E-5F, "twice would be 1.0");
		assertEquals(2.0F, this.rig.f(uniforms, "b"), 1.0E-5F);
	}

	@Test
	void aFrameOfNoLengthHoldsASmoothedValueStill() {
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.s = smooth(fa)");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		this.rig.fa = 0.0F;

		this.rig.frame(uniforms, 0.0F);
		assertEquals(4.0F, this.rig.f(uniforms, "s"), "no time passed, so no movement");
		this.rig.frame(uniforms, -0.1F);
		assertEquals(4.0F, this.rig.f(uniforms, "s"), "and a negative one is no time either");
		this.rig.frame(uniforms, 0.1F);
		assertEquals(2.0F, this.rig.f(uniforms, "s"), 1.0E-5F);
	}

	@Test
	void updatingTwiceInOneFrameSmoothsTwiceBecauseThatIsTheCallersToPrevent() {
		// The class says update is called once a frame and never once per program. Nothing here
		// checks the frame counter: two calls are two steps.
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.s = smooth(fa)");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		this.rig.fa = 0.0F;
		uniforms.update(this.rig.world, 0.1F);
		uniforms.update(this.rig.world, 0.1F);

		assertEquals(1.0F, this.rig.f(uniforms, "s"), 1.0E-5F, "4 -> 2 -> 1");
	}

	@Test
	void aWorldChangeMakesSmoothRestartFromWhereTheValueIsThen() {
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.s = smooth(fa)");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		this.rig.fa = 0.0F;
		this.rig.frame(uniforms);
		assertEquals(2.0F, this.rig.f(uniforms, "s"), 1.0E-5F);

		FrameSmoothed.forgetAll();
		this.rig.fa = 8.0F;
		this.rig.frame(uniforms);

		assertEquals(8.0F, this.rig.f(uniforms, "s"), "taken as it comes, as on the very first frame");
	}

	@Test
	void smoothInsideAnExpressionSmoothsItsOwnArgument() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.on = smooth(if(ia > 5, 1.0, 0.0), 1, 1) * 10.0");
		this.rig.frame(uniforms);
		assertEquals(10.0F, this.rig.f(uniforms, "on"));

		this.rig.ia = 0;
		this.rig.frame(uniforms);
		assertEquals(5.0F, this.rig.f(uniforms, "on"), 1.0E-4F, "the switch fades over the half life");
		this.rig.frame(uniforms);
		assertEquals(2.5F, this.rig.f(uniforms, "on"), 1.0E-4F);
	}

	// the many-branch if

	@Test
	void ifWithManyBranchesTakesTheFirstTrueOne() {
		assertEquals(20.0F, this.rig.floatOf("if(ia < 0, 10.0, ia < 10, 20.0, ia < 100, 30.0, 40.0)"));
		assertEquals(30.0F, this.rig.floatOf("if(ia < 0, 10.0, ia < 5, 20.0, ia < 100, 30.0, 40.0)"));
		assertEquals(40.0F, this.rig.floatOf("if(ia < 0, 10.0, ia < 5, 20.0, ia < 6, 30.0, 40.0)"));
		assertEquals(10.0F, this.rig.floatOf("if(ia > 0, 10.0, ia > 0, 20.0, 40.0)"), "the first, not the last");
		assertEquals(3, this.rig.intOf("if(ia < 0, 1, ia < 5, 2, 3)"));
		assertTrue(this.rig.boolOf("if(ia < 0, 1 > 2, ia < 5, 1 > 2, 2 > 1)"), "of bools");
	}

	@Test
	void ifTakesTwoToSixteenBranchesAndNotSeventeen() {
		// Only the sixteenth test is true: ia is 7 and the fifteen before it look for 101 to 115.
		StringBuilder sixteen = new StringBuilder("if(");
		for (int branch = 1; branch <= 16; branch++) {
			sixteen.append(branch == 16 ? "ia == 7" : "ia == " + (100 + branch)).append(", ")
					.append(branch).append(".0, ");
		}
		sixteen.append("99.0)");
		StringBuilder seventeen = new StringBuilder("if(");
		for (int branch = 1; branch <= 17; branch++) {
			seventeen.append("ia == ").append(branch).append(", ").append(branch).append(".0, ");
		}
		seventeen.append("99.0)");

		assertEquals(16.0F, this.rig.floatOf(sixteen.toString()), "ia is 7 and the sixteenth test says so");
		assertTrue(this.rig.refusal("float", seventeen.toString()).contains("Couldn't resolve"));
	}

	@Test
	void ifWithThreeArgumentsEvaluatesOnlyTheBranchItTakes() {
		// The untaken branch divides an int by zero, which would throw if it ran.
		assertEquals(2, this.rig.intOf("if(ia > 0, 2, 1 % (ia - 7))"));
		assertEquals(2, this.rig.intOf("if(ia < 0, 1 % (ia - 7), 2)"));
	}

	@Test
	void ifWithManyBranchesDoesNotReadTheElseWhenALaterTestIsTrue() {
		// The else throws if it runs. It is read only when every test has failed, so the second
		// test being true gives 2. A loop that evaluated it after each failed test, before reading
		// the next one, as Iris's does, would throw on the first test and drop the declaration.
		assertEquals(2, this.rig.intOf("if(ia < 0, 1, ia > 0, 2, 1 % (ia - 7))"));
	}

	@Test
	void ifWithManyBranchesDoesNotReadTheElseWhenTheFirstTestIsTrue() {
		// A first test that is true returns before the else is read, so the throwing else is never
		// reached; a walk that read the else ahead of the tests would throw here.
		assertEquals(1, this.rig.intOf("if(ia > 0, 1, ia > 0, 2, 1 % (ia - 7))"));
	}

	@Test
	void theElseOfAManyBranchIfSmoothsOnceAFrame() {
		// smooth() in the else advances every time it is evaluated, and it is evaluated once a
		// frame whatever the number of tests before it: half the way to its target in a frame,
		// here from 4 to 2. A loop that evaluated it once per failed test would advance it twice
		// for two tests, to 1, and three times for three, to 0.5.
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.two = if(ia < 0, 1.0, ia < 1, 2.0, smooth(fa))",
				"uniform.float.three = if(ia < 0, 1.0, ia < 1, 2.0, ia < 2, 3.0, smooth(fa))",
				"uniform.float.one = if(ia < 0, 1.0, smooth(fa))");
		this.rig.fa = 4.0F;
		this.rig.frame(uniforms);
		this.rig.fa = 0.0F;
		this.rig.frame(uniforms);

		assertEquals(2.0F, this.rig.f(uniforms, "one"), 1.0E-5F, "the three-argument form");
		assertEquals(2.0F, this.rig.f(uniforms, "two"), 1.0E-5F, "two tests fail, one step");
		assertEquals(2.0F, this.rig.f(uniforms, "three"), 1.0E-5F, "three tests fail, one step");
	}

	// failing at run time

	@Test
	void anExpressionThatThrowsIsDroppedWithEverythingThatReadsIt() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.int.q = 100 % ia",
				"uniform.int.d = q + 1",
				"uniform.int.e = d * 2",
				"uniform.float.ok = fa * 2.0");
		UniformSource q = uniforms.source("q");
		UniformSource d = uniforms.source("d");
		UniformSource e = uniforms.source("e");
		assertNotNull(q);

		this.rig.frame(uniforms);
		assertEquals(2, this.rig.i(uniforms, "q"), "100 % 7");
		assertEquals(3, this.rig.i(uniforms, "d"));
		assertEquals(6, this.rig.i(uniforms, "e"));
		assertEquals(List.of(), uniforms.drainProblems());

		this.rig.ia = 0;
		this.rig.fa = 10.0F;
		this.rig.frame(uniforms);

		List<String> problems = uniforms.drainProblems();
		assertEquals(3, problems.size(), problems.toString());
		assertEquals("q: no longer evaluated, it threw on frame 1, / by zero", problems.get(0));
		assertTrue(problems.contains("d: no longer evaluated either, it reads q"), problems.toString());
		assertTrue(problems.contains("e: no longer evaluated either, it reads d"), problems.toString());
		assertEquals(List.of(), uniforms.drainProblems(), "said once");

		assertNull(uniforms.source("q"), "the name is gone from the graph");
		assertNull(uniforms.source("d"));
		assertNull(uniforms.source("e"));
		assertEquals(List.of("ok"), List.copyOf(uniforms.exposed()));
		assertEquals(20.0F, this.rig.f(uniforms, "ok"), "and the rest of that very frame ran");

		// The sources handed out before keep answering what the values last stood at.
		Val out = new Val();
		q.read(this.rig.world, out);
		assertEquals(2, out.i(0));
		d.read(this.rig.world, out);
		assertEquals(3, out.i(0), "d was worked out on the way to being dropped, from the stale q");
		e.read(this.rig.world, out);
		assertEquals(6, out.i(0));

		// Still the same zero divisor: a value that stayed in the walk would throw and be named again.
		this.rig.frame(uniforms);
		assertEquals(List.of(), uniforms.drainProblems(), "and it is not said again on the next frame");

		// And once the input is fine it does not come back: 100 % 9 is 1, which q never takes.
		this.rig.ia = 9;
		this.rig.fa = 30.0F;
		this.rig.frame(uniforms);
		q.read(this.rig.world, out);
		assertEquals(2, out.i(0), "a dropped value is never worked out again, even once it could be");
		d.read(this.rig.world, out);
		assertEquals(3, out.i(0));
		assertEquals(60.0F, this.rig.f(uniforms, "ok"));
		assertEquals(List.of(), uniforms.drainProblems());
	}

	@Test
	void anExceptionWithoutAMessageIsNamedByItsClass() {
		// An engine value whose source throws with no message: the input is what fails, and the
		// declaration that reads it goes with it.
		UniformCatalog engine = UniformCatalog.builder()
				.add("probe", UniformShape.FLOAT, (world, out) -> {
					throw new IllegalStateException();
				})
				.build();
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(engine, problems, "uniform.float.a = probe * 2.0");
		assertEquals(List.of(), problems);

		this.rig.answers.put("frameCounter", 4);
		uniforms.update(this.rig.world, 0.1F);

		assertEquals(List.of(
				"probe: no longer evaluated, it threw on frame 4, IllegalStateException",
				"a: no longer evaluated either, it reads probe"), uniforms.drainProblems());
		assertNull(uniforms.source("a"));
	}
}
