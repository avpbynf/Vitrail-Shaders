package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds the comparison of two float vectors to one boolean at every size, and to the way the
 * scalar {@code ==} compares a component, beyond the vec3 cases in {@link ExprVectorsTest}.
 * <p>
 * The equality answers a boolean under {@code equal} and {@code equals}, and its inverse under
 * {@code notEquals}. Registered as a vector, or both under the one name {@code equal}, no call
 * would resolve, or one would find two matches, and a declaration that compared two vectors would
 * be dropped.
 */
class VectorEqualityTest {

	private final ExprRig rig = new ExprRig();

	@Test
	void equalAnswersABooleanForEverySize() {
		assertTrue(this.rig.boolOf("equal(vec2(1.0, 2.0), vec2(1.0, 2.0))"));
		assertTrue(this.rig.boolOf("equal(vec3(1.0, 2.0, 3.0), vec3(1.0, 2.0, 3.0))"));
		assertFalse(this.rig.boolOf("equal(vec3(1.0, 2.0, 3.0), vec3(1.0, 2.0, 4.0))"));
		assertFalse(this.rig.boolOf("equal(vec4(1.0, 2.0, 3.0, 4.0), vec4(0.0, 2.0, 3.0, 4.0))"));
	}

	@Test
	void theOperatorsCompareVectorsOfTheOtherSizes() {
		assertTrue(this.rig.boolOf("vec2(1.0, 2.0) == vec2(1.0, 2.0)"));
		assertFalse(this.rig.boolOf("vec2(1.0, 2.0) != vec2(1.0, 2.0)"));
		assertTrue(this.rig.boolOf("vec4(1.0, 2.0, 3.0, 4.0) != vec4(1.0, 2.0, 3.0, 0.0)"));
	}

	/** JOML's own equals compares bits, and would call these two different. */
	@Test
	void componentsCompareAsTheScalarOperatorDoes() {
		assertTrue(this.rig.boolOf("0.0 == -0.0"));
		assertTrue(this.rig.boolOf("vec2(0.0, 1.0) == vec2(-0.0, 1.0)"));
	}

	@Test
	void anEqualityDecidesAnIf() {
		assertEquals(1.0F, this.rig.floatOf("if(equal(vec2(1.0, 2.0), vec2(1.0, 2.0)), 1.0, 0.0)"));
	}
}
