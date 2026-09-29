package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link TranslatedUnit.Uniform#of} to the one rule it has: the type is what comes before the
 * first space of the declaration, and the declaration is kept whole because it is the only place an
 * array size shows.
 */
class TranslatedUnitTest {

	@Test
	void theTypeIsTheWordBeforeTheFirstSpaceAndTheDeclarationIsKeptAsWritten() {
		TranslatedUnit.Uniform uniform = TranslatedUnit.Uniform.of("sunPosition", "vec3 sunPosition");

		assertEquals(new TranslatedUnit.Uniform("sunPosition", "vec3", "vec3 sunPosition"), uniform);
	}

	@Test
	void anArraySizeIsOnlyInTheDeclarationAndNotInTheTypeOrTheName() {
		TranslatedUnit.Uniform uniform = TranslatedUnit.Uniform.of("of_TextureMatrix", "mat4 of_TextureMatrix[8]");

		assertEquals("mat4", uniform.type());
		assertEquals("of_TextureMatrix", uniform.name());
		assertEquals("mat4 of_TextureMatrix[8]", uniform.declaration());
	}

	@Test
	void aDeclarationWithNoSpaceIsItsOwnTypeAndAnEmptyOneIsEmpty() {
		assertEquals("noSpace", TranslatedUnit.Uniform.of("x", "noSpace").type());
		assertEquals("", TranslatedUnit.Uniform.of("x", "").type());
		assertEquals("", TranslatedUnit.Uniform.of("x", " leading").type());
		assertEquals("a", TranslatedUnit.Uniform.of("x", "a b c").type());
	}
}
