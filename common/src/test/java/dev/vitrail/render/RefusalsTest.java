package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds the two halves of a refusal said once: a call site is let through to compose its key the
 * first time it is reached about something and never again, and the line under a key is said once
 * whichever site composed it.
 * <p>
 * The first half is what a lasting refusal costs on every draw after its first, and the second is
 * what the log says, which the first must never change: a pair let through twice would compose a
 * key twice and still say one line, but a pair held back that had a new key to say would lose it.
 */
class RefusalsTest {

	@Test
	void letsAPairThroughOnce() {
		Refusals refusals = new Refusals();

		assertTrue(refusals.first("missing", "solid"));
		assertFalse(refusals.first("missing", "solid"));
		assertTrue(refusals.reached("missing", "solid"));
	}

	@Test
	void keepsTwoSitesAskingAboutOneObjectApart() {
		Refusals refusals = new Refusals();

		assertTrue(refusals.first("missing", "solid"));
		assertFalse(refusals.reached("prepare", "solid"));
		assertTrue(refusals.first("prepare", "solid"));
	}

	@Test
	void holdsEqualObjectsAsOne() {
		Refusals refusals = new Refusals();

		assertTrue(refusals.first("elsewhere:entity", new String("item_entity_target")));
		assertFalse(refusals.first("elsewhere:entity", "item_entity_target"));
	}

	@Test
	void saysAKeyOnceWhicheverPairComposedIt() {
		Refusals refusals = new Refusals();

		assertTrue(refusals.first("prepare", "cutout"));
		assertTrue(refusals.add("prepare:gbuffers_entities"));
		assertTrue(refusals.first("prepare", "cutout_cull"));
		assertFalse(refusals.add("prepare:gbuffers_entities"));
	}

	@Test
	void forgetsBothWithTheLoad() {
		Refusals refusals = new Refusals();
		refusals.first("missing", "solid");
		refusals.add("missing:solid");

		refusals.clear();

		assertFalse(refusals.reached("missing", "solid"));
		assertTrue(refusals.first("missing", "solid"));
		assertTrue(refusals.add("missing:solid"));
	}
}
