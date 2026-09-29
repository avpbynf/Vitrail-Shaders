package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Holds {@code &&} binding tighter than {@code ||} where {@link ExprOperatorsTest} does not look:
 * against brackets, which must still decide, and inside the condition of an {@code if}, where a
 * pack writes most of its boolean logic.
 * <p>
 * Taken left to right on one level, {@code a || b && c} would be {@code (a || b) && c}, and the
 * two readings part exactly when the left of the {@code ||} holds and the right of the {@code &&}
 * does not. That is the case held here: a grammar that put the two on one level answers false
 * where {@code &&} binds tighter and the answer is true.
 */
class AndOrPrecedenceTest {

	private static final String TRUE = "1 > 0";
	private static final String FALSE = "0 > 1";

	private final ExprRig rig = new ExprRig();

	@Test
	void bracketsStillDecide() {
		assertFalse(this.rig.boolOf("(" + TRUE + " || " + TRUE + ") && " + FALSE));
	}

	@Test
	void theConditionOfAnIfReadsTheSameWay() {
		assertEquals(1.0F, this.rig.floatOf("if(" + TRUE + " || " + TRUE + " && " + FALSE + ", 1.0, 0.0)"));
	}
}
