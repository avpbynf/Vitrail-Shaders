package dev.vitrail.render.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.List;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * How many of a family's programs are the same compile over again, and the line that says so.
 * Driven by plain calls, with the two stage texts as the short strings they stand in for. What the
 * compiler answered for their modules is {@link ModuleSupplyTest}'s.
 */
class ModuleTallyTest {

	private static final Object LAYOUT = new Object();

	private final ModuleTally tally = new ModuleTally();

	private void built(String family, String vertex, String fragment, Object format) {
		built(family, vertex, fragment, 0L, format);
	}

	private void built(String family, String vertex, String fragment, long geometry, Object format) {
		this.tally.built(family, ModuleTally.hash(vertex), ModuleTally.hash(fragment), geometry, format,
				List.of());
	}

	/** What a family that asked its compiler for nothing says of its modules. */
	private static String unasked() {
		return "modules asked for 0: compiled 0 (of which repeats 0), served from the disk cache 0, "
				+ "shared within the load 0";
	}

	@Test
	void theSameTextHashesTheSameAndADifferentOneDoesNot() {
		assertEquals(ModuleTally.hash("void main() {}"), ModuleTally.hash(new String("void main() {}")));
		assertNotEquals(ModuleTally.hash("void main() {}"), ModuleTally.hash("void main() { }"));
		assertNotEquals(ModuleTally.hash(""), ModuleTally.hash("\0"));
	}

	@Test
	void programsHandingTheCompilerOneThingAreOneTriple() {
		built("entity", "v", "f", LAYOUT);
		built("entity", "v", "f", LAYOUT);
		built("entity", "v", "f", LAYOUT);

		assertEquals(1, this.tally.triples("entity"));
		assertEquals(List.of("Module census, entity: programs built 3, distinct (vertex text, fragment "
				+ "text, vertex format) triples 1, distinct vertex texts 1, distinct fragment texts 1; "
				+ unasked()), this.tally.lines());
	}

	@Test
	void aDifferentLayoutIsANewTripleWhereTheTextsAreNotNew() {
		built("particles", "v", "f", LAYOUT);
		built("particles", "v", "f", new Object());

		assertEquals(2, this.tally.triples("particles"));
		assertTrue(this.tally.lines().getFirst().contains("programs built 2, distinct (vertex text, "
				+ "fragment text, vertex format) triples 2, distinct vertex texts 1"));
	}

	@Test
	void aGeometryStageTheDeviceBindsIsANewTripleWhereTheOtherTwoTextsAreNot() {
		built("chunk", "v", "f", 0L, LAYOUT);
		built("chunk", "v", "f", ModuleTally.hash("void main() { gl_Position = vec4(0.0); }"), LAYOUT);
		built("chunk", "v", "f", ModuleTally.hash("void main() { gl_Position = vec4(1.0); }"), LAYOUT);
		built("chunk", "v", "f", ModuleTally.hash("void main() { gl_Position = vec4(1.0); }"), LAYOUT);

		assertEquals(3, this.tally.triples("chunk"));
		assertTrue(this.tally.lines().getFirst().contains("programs built 4, distinct (vertex text, "
				+ "fragment text, vertex format) triples 3, distinct vertex texts 1, distinct fragment "
				+ "texts 1"));
	}

	@Test
	void aSharedStageIsCountedOnceOnItsOwnSide() {
		built("sky", "v1", "f1", LAYOUT);
		built("sky", "v1", "f2", LAYOUT);
		built("sky", "v2", "f2", LAYOUT);

		assertEquals(3, this.tally.triples("sky"));
		assertEquals(List.of("Module census, sky: programs built 3, distinct (vertex text, fragment "
				+ "text, vertex format) triples 3, distinct vertex texts 2, distinct fragment texts 2; "
				+ unasked()), this.tally.lines());
	}

	@Test
	void aFamilyWithNoMeshLayoutIsOneTripleLikeAnyOther() {
		built("cloud", "v", "f", null);
		built("cloud", "v", "f", null);

		assertEquals(1, this.tally.triples("cloud"));
	}

	@Test
	void familiesAreSaidInTheOrderTheyArrived() {
		built("weather", "v", "f", LAYOUT);
		built("chunk", "v", "f", LAYOUT);
		built("entity", "v", "f", LAYOUT);

		List<String> lines = this.tally.lines();

		assertEquals(3, lines.size());
		assertTrue(lines.get(0).startsWith("Module census, weather:"));
		assertTrue(lines.get(1).startsWith("Module census, chunk:"));
		assertTrue(lines.get(2).startsWith("Module census, entity:"));
	}

	@Test
	void aFamilyIsSaidOnceAndAgainOnlyWhereItGrew() {
		built("chunk", "v", "f", LAYOUT);

		assertEquals(1, this.tally.lines().size());
		assertTrue(this.tally.lines().isEmpty());

		built("chunk", "v2", "f", LAYOUT);
		built("entity", "v", "f", LAYOUT);

		List<String> again = this.tally.lines();

		// The terrain's programs come after the warm-up has spoken, and its whole tally is said over.
		assertEquals(2, again.size());
		assertTrue(again.get(0).contains("chunk: programs built 2,"), again.toString());
		assertTrue(this.tally.lines().isEmpty());
	}

	@Test
	void clearingForgetsEveryFamily() {
		built("chunk", "v", "f", LAYOUT);

		this.tally.clear();

		assertTrue(this.tally.lines().isEmpty());
		assertEquals(0, this.tally.triples("chunk"));
	}

	@Test
	void theHookCountsNothingWhileTheSwitchIsOff() {
		assumeFalse(PassTimings.enabled(), "the switch is on for this run, which is what is being tested");

		Identifier stage = Identifier.fromNamespaceAndPath("vitrail", "pack/1/entity/vertex");
		ModuleCensus.built("chunk", stage, stage, "v", "f", null, LAYOUT);
		ModuleCensus.compiled("vitrail_pack_1_entity_vertex", "key");
		ModuleCensus.served("vitrail_pack_1_entity_vertex", "key");
		ModuleCensus.shared("vitrail_pack_1_entity_vertex", "key");

		assertEquals(0, ModuleCensus.tally().triples("chunk"));
		assertEquals(0, ModuleCensus.tally().compiled("chunk"));
		assertEquals(0, ModuleCensus.tally().compiled(ModuleTally.UNOWNED));
	}
}
