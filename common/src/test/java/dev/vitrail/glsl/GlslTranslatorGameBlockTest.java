package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.GlslTranslatorCases.Single;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds the one part of a translation that is the game's and not the translator's: the block of per
 * draw transforms the header declares wherever a rewrite read the game's texture matrix or model
 * view.
 * <p>
 * Its members and their order differ between the games this tree builds for, 26.3 having moved the
 * texture matrix to the front, because std140 matches by offset and the block has to be the game's
 * own. So the goldens do not carry it: a case that declares the block has it folded to a marker
 * by {@link GlslTranslatorCases}, which keeps every golden the same under either game, and the block
 * is recorded here instead, one file to a game under {@code translator/game/}, next to the check that
 * a translation writes the block of the game the tree is built against, whole, once, and only where
 * something read it. A game that is neither recorded fails the first of these and says what to
 * record, and {@link GameTransformsTest} is what holds the block to the game's own declaration.
 */
class GlslTranslatorGameBlockTest {

	/** The games with a recorded block. Adding a game is a file under {@code translator/game/} and a name here. */
	private static final List<String> GAMES = List.of("26.2", "26.3");

	private static String block() {
		return String.join("\n", GameTransforms.BLOCK) + "\n";
	}

	@Test
	void theBlockIsTheOneRecordedForTheGameTheTreeIsBuiltFor() throws IOException {
		int matching = 0;
		for (String game : GAMES) {
			if (GlslTranslatorGoldenTest.golden("game/" + game).equals(block())) {
				matching++;
			}
		}

		assertEquals(1, matching, "GameTransforms.BLOCK is recorded under exactly one game of " + GAMES
				+ "; if the game changed it, write it under translator/game/ for the game it is:\n" + block());
	}

	@Test
	void theRecordedBlocksOfTheGamesAreTwoBlocks() throws IOException {
		assertFalse(GlslTranslatorGoldenTest.golden("game/26.2").equals(GlslTranslatorGoldenTest.golden("game/26.3")),
				"the games are recorded because they differ");
	}

	@Test
	void aTranslationWritesTheBlockWholeAndOnceWhereSomethingReadIt() {
		for (Single one : GlslTranslatorCases.everySingle()) {
			String text = GlslTranslatorCases.translate(one).text();
			boolean readsIt = one.name().equals("entity-vertex") || one.name().equals("game-transforms-glint")
					|| one.name().equals("game-transforms-entities");
			if (readsIt) {
				assertTrue(text.contains(block()), one.name() + " carries the block whole");
				assertEquals(text.indexOf(block()), text.lastIndexOf(block()), one.name() + " carries it once");
			} else {
				assertFalse(text.contains(LegacyGlsl.GAME_TRANSFORMS), one.name() + " reads nothing of the game's");
			}
		}
	}
}
