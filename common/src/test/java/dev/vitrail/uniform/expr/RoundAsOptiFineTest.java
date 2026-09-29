package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Holds {@code round} to OptiFine's, which is Java's {@code Math.round} of a float: a half goes up,
 * towards positive infinity, whichever side of nought it is on.
 * <p>
 * OptiFine lists the function and Iris registers none, so a round left unregistered drops every
 * declaration using it, with nothing to stand in for it, and each call here would be refused. The
 * halves are what tell one rounding rule from another, so they are what is held here.
 */
class RoundAsOptiFineTest {

	private final ExprRig rig = new ExprRig();

	@Test
	void aHalfGoesUp() {
		assertEquals(2.0F, this.rig.floatOf("round(1.5)"));
		assertEquals(3.0F, this.rig.floatOf("round(2.5)"));
		assertEquals(1.0F, this.rig.floatOf("round(0.5)"));
	}

	@Test
	void aNegativeHalfGoesUpToo() {
		assertEquals(-1.0F, this.rig.floatOf("round(-1.5)"));
		assertEquals(-2.0F, this.rig.floatOf("round(-2.5)"));
		assertEquals(0.0F, this.rig.floatOf("round(-0.5)"));
	}

	@Test
	void anythingElseGoesToTheNearest() {
		assertEquals(0.0F, this.rig.floatOf("round(0.49)"));
		assertEquals(-3.0F, this.rig.floatOf("round(-2.51)"));
		assertEquals(4.0F, this.rig.floatOf("round(2.4) * 2.0"));
	}

	@Test
	void anIntDeclarationTakesItToo() {
		assertEquals(2, this.rig.intOf("round(1.5)"));
		assertEquals(-1, this.rig.intOf("round(-1.5)"));
	}
}
