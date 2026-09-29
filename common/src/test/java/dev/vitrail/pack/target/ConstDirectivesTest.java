package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.target.ConstDirectives.Directive;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Holds the grammar of a {@code const} declaration to Iris's, which is arbitrary in places and is
 * what the packs are written against: the type is matched by prefix against a closed list of six,
 * the key must be a word, and the value is whatever sits between the equals sign and the first
 * semicolon, unvalidated. Widening any of it changes which declarations are found, which changes
 * formats, which changes the image, and nothing reports it.
 * <p>
 * The line is read one at a time and never with its comment stripped: every format directive of a
 * real pack sits inside a block comment, and it is the line between the comment marks that is read.
 */
class ConstDirectivesTest {

	private static Directive read(String line) {
		return ConstDirectives.readLine(line).orElseThrow();
	}

	@Test
	void aDeclarationGivesItsTypeItsNameAndItsValueAsWritten() {
		assertEquals(new Directive("int", "colortex0Format", "RGBA16F", -1),
				read("const int colortex0Format = RGBA16F;"));
		assertEquals(new Directive("bool", "colortex4Clear", "false", -1),
				read("const bool colortex4Clear = false;"));
		assertEquals(new Directive("vec4", "colortex3ClearColor", "vec4(0.0, 0.0, 0.0, 1.0)", -1),
				read("const vec4 colortex3ClearColor = vec4(0.0, 0.0, 0.0, 1.0);"));
		assertEquals(new Directive("float", "wetnessHalflife", "600.0", -1),
				read("  const   float   wetnessHalflife =  600.0 ;  // seconds"));
		assertEquals(new Directive("vec2", "v", "vec2(1.0, 2.0)", -1), read("const vec2 v = vec2(1.0, 2.0);"));
		assertEquals(new Directive("ivec3", "shadowIntervals", "ivec3(1)", -1),
				read("const ivec3 shadowIntervals = ivec3(1);"));
	}

	/** The value is not looked at: a name that is no GLSL at all stands, and so does nothing. */
	@Test
	void theValueIsWhateverLiesBetweenTheEqualsSignAndTheFirstSemicolon() {
		assertEquals("R11F_G11F_B10F", read("const int colortex0Format = R11F_G11F_B10F; // packed").value());
		assertEquals("", read("const int colortex0Format = ;").value());
		assertEquals("1", read("const int a = 1; const int b = 2;").value());
		assertEquals("SETTING == 3", read("const int a = SETTING == 3;").value());
	}

	/** Prefix, then a space: {@code intensity} is not an {@code int}. */
	@Test
	void aTypeIsTakenByPrefixFromTheClosedListAndNeedsASpaceAfterIt() {
		assertEquals(Optional.empty(), ConstDirectives.readLine("const intensity = 3;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const int64 x = 1;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const uint x = 1;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const vec3 x = vec3(1.0);"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const mat4 x = mat4(1.0);"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const highp int x = 1;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("constint x = 1;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("constant int x = 1;"));
	}

	@Test
	void theKeyHasToBeASingleWord() {
		assertEquals(Optional.empty(), ConstDirectives.readLine("const int a b = 3;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const int a-b = 3;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const int = 3;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const int a[2] = 3;"));
		assertEquals("_under_score9", read("const int _under_score9 = 3;").name());
	}

	@Test
	void aLineThatIsNotAWholeDeclarationIsNoDirective() {
		assertEquals(Optional.empty(), ConstDirectives.readLine("const int a = 3"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("const int a;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("// const int a = 3;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("/* const int a = 3; */"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("int a = 3;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine(""));
	}

	/** {@code const} has to open the line: a comment mark in front of it makes it something else. */
	@Test
	void theDeclarationHasToOpenTheLine() {
		assertTrue(ConstDirectives.readLine("\tconst int a = 3;").isPresent());
		assertEquals(Optional.empty(), ConstDirectives.readLine("uniform const int a = 3;"));
		assertEquals(Optional.empty(), ConstDirectives.readLine("/*const int a = 3;*/"));
	}
}
