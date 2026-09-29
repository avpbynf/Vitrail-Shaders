package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds what a run of draws is told once and what it is told again: the first draw of a run sends
 * the pipeline, the scissor and the transforms, and a later draw sends one of them only where it
 * moved.
 * <p>
 * The other half is the one a wrong answer would turn into a wrong picture, so most of the cases
 * are on it: nothing is ever held over a {@link SentState#forget}, because a run that opens a pass
 * has no idea what the pass stands on, and a scissor switched off is a change from one switched on
 * however the four numbers of the two compare.
 */
class SentStateTest {

	private final SentState sent = new SentState();

	// -- the pipeline --------------------------------------------------------------------------

	@Test
	void theFirstPipelineIsSentAndTheSameOneAfterItIsNot() {
		Object entities = new Object();

		assertTrue(this.sent.pipeline(entities));
		assertFalse(this.sent.pipeline(entities));
		assertFalse(this.sent.pipeline(entities));
	}

	@Test
	void anotherPipelineIsSentAndBecomesTheOneHeld() {
		Object entities = new Object();
		Object glint = new Object();

		this.sent.pipeline(entities);

		assertTrue(this.sent.pipeline(glint));
		assertFalse(this.sent.pipeline(glint));
		assertTrue(this.sent.pipeline(entities));
	}

	@Test
	void pipelinesAreComparedByIdentityAndNotByValue() {
		// Two pipelines that compare equal are still two objects, and the pass holds the object.
		this.sent.pipeline(new String("entities"));

		assertTrue(this.sent.pipeline(new String("entities")));
	}

	@Test
	void aNullPipelineIsAlwaysSent() {
		assertTrue(this.sent.pipeline(null));
		assertTrue(this.sent.pipeline(null));
	}

	// -- the scissor ---------------------------------------------------------------------------

	@Test
	void theFirstScissorIsSentWhetherItIsOnOrOff() {
		assertTrue(this.sent.scissor(false, 0, 0, 0, 0));
		this.sent.forget();
		assertTrue(this.sent.scissor(true, 10, 20, 30, 40));
	}

	@Test
	void theSameRectangleIsNotSentAgain() {
		this.sent.scissor(true, 10, 20, 30, 40);

		assertFalse(this.sent.scissor(true, 10, 20, 30, 40));
	}

	@Test
	void eachOfTheFourNumbersIsAChange() {
		this.sent.scissor(true, 10, 20, 30, 40);

		assertTrue(this.sent.scissor(true, 11, 20, 30, 40));
		assertTrue(this.sent.scissor(true, 11, 21, 30, 40));
		assertTrue(this.sent.scissor(true, 11, 21, 31, 40));
		assertTrue(this.sent.scissor(true, 11, 21, 31, 41));
		assertFalse(this.sent.scissor(true, 11, 21, 31, 41));
	}

	@Test
	void switchingItOffAndOnAreChangesWhateverTheNumbersSay() {
		this.sent.scissor(true, 10, 20, 30, 40);

		assertTrue(this.sent.scissor(false, 10, 20, 30, 40));
		assertTrue(this.sent.scissor(true, 10, 20, 30, 40));
	}

	@Test
	void twoSwitchedOffScissorsAreOneWhateverTheirNumbersAre() {
		this.sent.scissor(false, 1, 2, 3, 4);

		assertFalse(this.sent.scissor(false, 5, 6, 7, 8));
	}

	// -- the transforms ------------------------------------------------------------------------

	@Test
	void anEqualValueOfTheTransformsIsNotSentAgain() {
		assertTrue(this.sent.transforms(new String("slice")));
		assertFalse(this.sent.transforms(new String("slice")));
	}

	@Test
	void aDifferentValueOfTheTransformsIsSent() {
		this.sent.transforms("first");

		assertTrue(this.sent.transforms("second"));
		assertFalse(this.sent.transforms("second"));
		assertTrue(this.sent.transforms("first"));
	}

	@Test
	void aNullValueIsSentEveryTimeAndNothingIsHeldForIt() {
		this.sent.transforms("slice");

		assertTrue(this.sent.transforms(null));
		assertTrue(this.sent.transforms(null));
		// The null replaced what was held, so the value that stood before it is a change again.
		assertTrue(this.sent.transforms("slice"));
	}

	// -- forgetting ----------------------------------------------------------------------------

	@Test
	void afterAForgetEverythingIsSentAgain() {
		Object entities = new Object();
		this.sent.pipeline(entities);
		this.sent.scissor(true, 10, 20, 30, 40);
		this.sent.transforms("slice");

		this.sent.forget();

		assertTrue(this.sent.pipeline(entities));
		assertTrue(this.sent.scissor(true, 10, 20, 30, 40));
		assertTrue(this.sent.transforms("slice"));
	}

	@Test
	void theThreeAreHeldApart() {
		Object entities = new Object();
		this.sent.pipeline(entities);

		// Holding a pipeline says nothing about the scissor or the transforms.
		assertTrue(this.sent.scissor(false, 0, 0, 0, 0));
		assertTrue(this.sent.transforms("slice"));
		assertFalse(this.sent.pipeline(entities));
	}
}
