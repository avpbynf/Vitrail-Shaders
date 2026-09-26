package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link GameTransforms#BLOCK} to the block the game it is built against really declares.
 * <p>
 * std140 matches a block by offset, so a declaration whose members are in another order compiles,
 * links and reads the wrong bytes without a word: that is how 26.3 moving the texture matrix reached
 * the screen, as mobs drawn transparent. The game ships the block's declaration as an include inside
 * its own jar, which is on this module's classpath whichever game is built, so the check needs no
 * game running and no pack.
 */
class GameTransformsTest {

	private static final String INCLUDE = "/assets/minecraft/shaders/include/dynamictransforms.glsl";

	/** A member line, its type and its name, which is all either declaration holds. */
	private static final Pattern MEMBER = Pattern.compile("^\\s*(\\w+)\\s+\\w+\\s*;");

	@Test
	void declaresTheGamesMembersInTheGamesOrder() throws IOException {
		assertEquals(types(gameBlock()), types(GameTransforms.BLOCK),
				"the member types of " + LegacyGlsl.GAME_TRANSFORMS + ", in order");
	}

	@Test
	void namesTheGamesBlock() {
		assertTrue(GameTransforms.BLOCK.getFirst().contains("uniform " + LegacyGlsl.GAME_TRANSFORMS + " {"),
				GameTransforms.BLOCK.getFirst());
	}

	private static List<String> gameBlock() throws IOException {
		try (InputStream in = GameTransformsTest.class.getResourceAsStream(INCLUDE)) {
			assertNotNull(in, INCLUDE + " is not on the test classpath");
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			int named = text.indexOf("uniform " + LegacyGlsl.GAME_TRANSFORMS);
			assertTrue(named >= 0, "the include declares no " + LegacyGlsl.GAME_TRANSFORMS);
			int open = text.indexOf('{', named);
			int close = text.indexOf('}', open);

			return text.substring(open + 1, close).lines().toList();
		}
	}

	private static List<String> types(List<String> lines) {
		List<String> types = new ArrayList<>();
		for (String line : lines) {
			Matcher member = MEMBER.matcher(line);
			if (member.find()) {
				types.add(member.group(1));
			}
		}

		return types;
	}
}
