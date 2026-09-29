package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds a declaration nested past {@link CustomUniforms#MAX_DEPTH} to a refusal named among the
 * problems, and not a {@code StackOverflowError} out of the pack load.
 * <p>
 * Five thousand levels is well past where the stack gives out, in every shape the grammar nests
 * in: calls, the minus sign, a chain of sums, which nests on the left, and a stray token beside a
 * deep call, whose parser error message would overflow before any tree was built if the depth
 * were not measured first.
 */
class NestingDepthTest {

	private static final int DEEP = 5000;

	private static final List<String> SHAPES = List.of(
			"abs(".repeat(DEEP) + "1.0" + ")".repeat(DEEP),
			"- ".repeat(DEEP) + "1.0",
			"1.0" + " + 1.0".repeat(DEEP),
			"(1.0 + ".repeat(DEEP) + "1.0" + ")".repeat(DEEP),
			"1.0 " + "abs(".repeat(DEEP) + "1.0" + ")".repeat(DEEP));

	private final ExprRig rig = new ExprRig();

	@Test
	void aDeepNestIsRefusedAndNamed() {
		for (String expression : SHAPES) {
			List<String> problems = new ArrayList<>();
			CustomUniforms uniforms = assertDoesNotThrow(
					() -> this.rig.build(problems, "uniform.float.deep = " + expression), () -> shape(expression));

			assertDoesNotThrow(() -> this.rig.frame(uniforms), () -> shape(expression));
			assertNull(uniforms.source("deep"), () -> shape(expression) + " was not refused");
			assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("deep: ")),
					() -> shape(expression) + " was dropped without a word: " + problems);
		}
	}

	@Test
	void whatReadsARefusedNestIsDroppedWithIt() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = assertDoesNotThrow(() -> this.rig.build(problems,
				"uniform.float.deep = " + SHAPES.getFirst(),
				"uniform.float.after = deep + 1.0"));

		assertDoesNotThrow(() -> this.rig.frame(uniforms));
		assertNull(uniforms.source("after"));
		assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("after: ")), problems::toString);
	}

	@Test
	void theLimitItselfIsStillRead() {
		// Each minus is one level and the number under them one more.
		int minuses = CustomUniforms.MAX_DEPTH - 1;
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.float.limit = " + "- ".repeat(minuses) + "1.0",
				"uniform.float.over = " + "- ".repeat(minuses + 1) + "1.0");
		this.rig.frame(uniforms);

		assertEquals(minuses % 2 == 0 ? 1.0F : -1.0F, this.rig.f(uniforms, "limit"), problems::toString);
		assertNull(uniforms.source("over"));
	}

	/**
	 * A sum nests on the left, one level a term, and a hundred thousand of them would throw out of
	 * the build at some three thousand if they reached anything that recurses. It has to be refused
	 * before that: the resolver, the listing of what a declaration reads and the sort of the graph
	 * all recurse. The name it reads is a declared one, so that it is the depth that refuses it and
	 * not an unknown name, and the declaration of that name goes on being read.
	 */
	@Test
	void aHundredThousandTermSumIsRefusedBeforeAnythingRecurses() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = assertDoesNotThrow(() -> this.rig.build(problems,
				"uniform.float.a = 1.0",
				"uniform.float.sum = a" + " + a".repeat(99_999)));

		assertDoesNotThrow(() -> this.rig.frame(uniforms));
		assertNull(uniforms.source("sum"));
		assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("sum: nests more than")),
				problems::toString);
		assertEquals(1.0F, this.rig.f(uniforms, "a"), "the declaration beside it goes on");
	}

	/** The same shape at the edge: every addition is one level, and the first term one more. */
	@Test
	void aLeftDeepChainIsReadUpToTheLimit() {
		int additions = CustomUniforms.MAX_DEPTH - 1;
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.float.limit = 1.0" + " + 1.0".repeat(additions),
				"uniform.float.over = 1.0" + " + 1.0".repeat(additions + 1));
		this.rig.frame(uniforms);

		assertEquals(CustomUniforms.MAX_DEPTH, this.rig.f(uniforms, "limit"), problems::toString);
		assertNull(uniforms.source("over"));
	}

	private static String shape(String expression) {
		return expression.substring(0, 24) + "... (" + expression.length() + " characters)";
	}
}
