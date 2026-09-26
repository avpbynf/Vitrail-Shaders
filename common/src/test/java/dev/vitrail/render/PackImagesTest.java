package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * Holds the name a pack writes for a texture of the game to the identifier it is looked up under.
 * <p>
 * The same reading decides whether a resource pack is asked for a file and which texture the game
 * is asked for at every bind, so an atlas named by a pack is found under the identifier the game
 * registered it with.
 */
class PackImagesTest {

	@Test
	void readsTheBlockAtlasName() {
		assertEquals(Identifier.withDefaultNamespace("textures/atlas/blocks.png"),
				PackImages.location("minecraft:textures/atlas/blocks.png"));
	}

	@Test
	void keepsTheFirstTwoPartsOfALongerName() {
		assertEquals(Identifier.fromNamespaceAndPath("mod", "textures/a.png"),
				PackImages.location("mod:textures/a.png:extra"));
	}

	@Test
	void namesNothingWithoutAPath() {
		assertNull(PackImages.location("minecraft:"));
		assertNull(PackImages.location("textures/atlas/blocks.png"));
	}
}
