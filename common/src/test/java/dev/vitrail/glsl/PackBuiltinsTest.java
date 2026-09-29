package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link PackBuiltins} to the definitions of GLSL 4.60 section 8.4 that its javadoc says the
 * helpers are: a unorm pack clamps to nought to one, a snorm pack to minus one to one, each scales
 * by 255, 65535, 127 or 32767, rounds with {@code round}, and puts the first component in the least
 * significant bits, a snorm component as its two's complement; an unpack divides by the same figure.
 * <p>
 * The helpers are one line of GLSL each, so the tests read each line for the numbers the
 * specification gives, which is the only witness there is without a compiler.
 */
class PackBuiltinsTest {

	private static final List<String> EIGHT = List.of("packUnorm4x8", "unpackUnorm4x8", "packUnorm2x16",
			"unpackUnorm2x16", "packSnorm4x8", "unpackSnorm4x8", "packSnorm2x16", "unpackSnorm2x16");

	private static String definition(String builtin) {
		return PackBuiltins.definitions(Set.of(builtin)).getFirst();
	}

	@Test
	void everyOfTheEightBuiltinsHasAHelperNamedForItAndNothingElseDoes() {
		for (String builtin : EIGHT) {
			assertEquals("of" + Character.toUpperCase(builtin.charAt(0)) + builtin.substring(1), PackBuiltins.helper(builtin));
		}

		assertNull(PackBuiltins.helper("packHalf2x16"));
		assertNull(PackBuiltins.helper("texture"));
		assertNull(PackBuiltins.helper(""));
	}

	@Test
	void theDefinitionsComeInTheFixedOrderWhateverOrderTheCallsWereMetIn() {
		Set<String> reversed = new LinkedHashSet<>(EIGHT.reversed());
		List<String> all = PackBuiltins.definitions(reversed);

		assertEquals(8, all.size());
		for (int at = 0; at < 8; at++) {
			assertTrue(all.get(at).contains(" " + PackBuiltins.helper(EIGHT.get(at)) + "("), all.get(at));
		}

		assertEquals(List.of(all.get(1), all.get(4)),
				PackBuiltins.definitions(new LinkedHashSet<>(List.of("packSnorm4x8", "unpackUnorm4x8"))));
		assertTrue(PackBuiltins.definitions(Set.of()).isEmpty());
		assertTrue(PackBuiltins.definitions(Set.of("texture", "packHalf2x16")).isEmpty());
	}

	@Test
	void aUnormPackClampsToNoughtToOneScalesRoundsAndLaysTheFirstComponentLowest() {
		String four = definition("packUnorm4x8");
		assertTrue(four.contains("round(clamp(ofV, 0.0, 1.0) * 255.0)"), four);
		assertTrue(four.contains("ofB.x | (ofB.y << 8u) | (ofB.z << 16u) | (ofB.w << 24u)"), four);
		assertTrue(four.startsWith("uint ofPackUnorm4x8(vec4 ofV) {"), four);

		String two = definition("packUnorm2x16");
		assertTrue(two.contains("round(clamp(ofV, 0.0, 1.0) * 65535.0)"), two);
		assertTrue(two.contains("ofB.x | (ofB.y << 16u)"), two);
		assertTrue(two.startsWith("uint ofPackUnorm2x16(vec2 ofV) {"), two);
	}

	@Test
	void aUnormUnpackTakesEachFieldFromItsOwnShiftAndDividesByWhatTheFieldHolds() {
		String four = definition("unpackUnorm4x8");
		assertTrue(four.contains("uvec4(ofP, ofP >> 8u, ofP >> 16u, ofP >> 24u) & 255u"), four);
		assertTrue(four.contains("/ 255.0"), four);
		assertTrue(four.startsWith("vec4 ofUnpackUnorm4x8(uint ofP) {"), four);

		String two = definition("unpackUnorm2x16");
		assertTrue(two.contains("uvec2(ofP, ofP >> 16u) & 65535u"), two);
		assertTrue(two.contains("/ 65535.0"), two);
	}

	@Test
	void aSnormPackClampsToMinusOneToOneAndKeepsTheTwosComplementOfEachField() {
		String four = definition("packSnorm4x8");
		assertTrue(four.contains("round(clamp(ofV, -1.0, 1.0) * 127.0)"), four);
		assertTrue(four.contains("& 255u"), four);
		assertTrue(four.contains("ofB.x | (ofB.y << 8u) | (ofB.z << 16u) | (ofB.w << 24u)"), four);

		String two = definition("packSnorm2x16");
		assertTrue(two.contains("round(clamp(ofV, -1.0, 1.0) * 32767.0)"), two);
		assertTrue(two.contains("& 65535u"), two);
		assertTrue(two.contains("ofB.x | (ofB.y << 16u)"), two);
	}

	@Test
	void aSnormUnpackFlipsTheTopBitAndTakesTheMidpointOffAndClampsTheQuotientBack() {
		String four = definition("unpackSnorm4x8");
		assertTrue(four.contains("^ 128u) - 128"), four);
		assertTrue(four.contains("clamp(vec4(ofB) / 127.0, -1.0, 1.0)"), four);

		String two = definition("unpackSnorm2x16");
		assertTrue(two.contains("^ 32768u) - 32768"), two);
		assertTrue(two.contains("clamp(vec2(ofB) / 32767.0, -1.0, 1.0)"), two);
	}

	@Test
	void theArgumentIsAParameterSoAnExpressionIsEvaluatedOnceAndEveryHelperIsOneLine() {
		for (String builtin : EIGHT) {
			String line = definition(builtin);

			assertEquals(-1, line.indexOf('\n'), builtin);
			assertTrue(line.endsWith("}"), builtin);
			assertTrue(builtin.startsWith("pack") ? line.contains("(vec") : line.contains("(uint ofP)"), line);
		}
	}
}
