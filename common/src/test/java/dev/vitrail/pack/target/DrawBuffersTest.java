package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds the rule that turns a program's draw buffer directive into the shadow colour targets the
 * light draws into, and the ceiling that rule is applied against. What {@link DrawBuffers#parse}
 * reads from a pack's text is pinned through the plan, in {@link TargetPlanAllocationTest}.
 */
class DrawBuffersTest {

	/** The shadow ceiling is the pack's own declaration and never the engine's: two, or eight. */
	@Test
	void theShadowColourCeilingIsTwoUnlessThePackDeclaresTheEight() {
		assertEquals(2, PackDirectives.shadowColours(false));
		assertEquals(8, PackDirectives.shadowColours(true));
		assertEquals(8, PackDirectives.MAX_SHADOW_COLOURS);
		assertEquals(2, PackDirectives.SHADOW_COLOURS);
	}

	/**
	 * A declaration naming a shadow colour past the ceiling is thrown away whole and rebuilt as the
	 * pair, which is Iris's rule and not a reading of the directive; a program that declares nothing
	 * gets the pair too, and the pair is the pair whatever the ceiling.
	 */
	@Test
	void aShadowDeclarationPastTheCeilingIsRebuiltAsThePair() {
		assertEquals(List.of(0, 1), DrawBuffers.shadowColours(List.of(), 2));
		assertEquals(List.of(0, 1), DrawBuffers.shadowColours(List.of(), 8));
		assertEquals(List.of(0), DrawBuffers.shadowColours(List.of(0), 2));
		assertEquals(List.of(1, 0), DrawBuffers.shadowColours(List.of(1, 0), 2));
		assertEquals(List.of(0, 1), DrawBuffers.shadowColours(List.of(0, 2, 1), 2));
		assertEquals(List.of(0, 2, 1), DrawBuffers.shadowColours(List.of(0, 2, 1), 8), "Reverie");
		assertEquals(List.of(0, 1), DrawBuffers.shadowColours(List.of(0, 8), 8));
		assertEquals(List.of(7), DrawBuffers.shadowColours(List.of(7), 8));
	}
}
