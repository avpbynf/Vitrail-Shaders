package dev.vitrail.render.timing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How the modules a family's programs asked the compiler for were answered, and what the census calls
 * a repeat: a compiled module whose digest an earlier compile of the load had already made. Driven by
 * plain calls, the module names standing for the debug names a program's stages reach the compiler
 * under and the digests for the keys the module cache made of their text.
 */
class ModuleSupplyTest {

	private static final Object LAYOUT = new Object();

	private final ModuleTally tally = new ModuleTally();

	private void program(String family, String vertex, String fragment) {
		this.tally.built(family, ModuleTally.hash(vertex), ModuleTally.hash(fragment), 0L, LAYOUT,
				List.of(vertex + ".vertex", fragment + ".fragment"));
	}

	private void asked(String name, String unit, ModuleTally.Supply how) {
		this.tally.unit(name, unit, how);
	}

	@Test
	void aModuleIsFiledUnderTheFamilyWhoseProgramNamedIt() {
		program("entity", "cow", "cowSkin");
		program("chunk", "block", "blockSkin");

		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);
		asked("block.vertex", "k2", ModuleTally.Supply.COMPILED);
		asked("block.vertex", "k2", ModuleTally.Supply.SHARED);

		assertEquals(1, this.tally.compiled("entity"));
		assertEquals(1, this.tally.compiled("chunk"));
		assertTrue(this.tally.lines().get(1).endsWith("modules asked for 2: compiled 1 (of which "
				+ "repeats 0), served from the disk cache 0, shared within the load 1"));
	}

	@Test
	void everyWayOfBeingAnsweredIsCountedAndTheirSumIsWhatWasAsked() {
		program("entity", "cow", "cowSkin");

		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);
		asked("cow.vertex", "k1", ModuleTally.Supply.SHARED);
		asked("cow.vertex", "k1", ModuleTally.Supply.SHARED);
		asked("cowSkin.fragment", "k2", ModuleTally.Supply.SERVED);

		assertEquals(List.of("Module census, entity: programs built 1, distinct (vertex text, fragment "
				+ "text, vertex format) triples 1, distinct vertex texts 1, distinct fragment texts 1; "
				+ "modules asked for 4: compiled 1 (of which repeats 0), served from the disk cache 1, "
				+ "shared within the load 2"), this.tally.lines());
	}

	@Test
	void aTextCompiledTwiceIsARepeatAndAModuleServedTwiceIsNot() {
		program("entity", "cow", "cowSkin");

		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);
		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);
		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);
		asked("cowSkin.fragment", "k2", ModuleTally.Supply.SERVED);
		asked("cowSkin.fragment", "k2", ModuleTally.Supply.SERVED);

		assertTrue(this.tally.lines().getFirst().endsWith("modules asked for 5: compiled 3 (of which "
				+ "repeats 2), served from the disk cache 2, shared within the load 0"));
	}

	@Test
	void aModuleTheCacheCouldNotKeyIsNeverARepeat() {
		program("entity", "cow", "cowSkin");

		asked("cow.vertex", null, ModuleTally.Supply.COMPILED);
		asked("cow.vertex", null, ModuleTally.Supply.COMPILED);

		assertTrue(this.tally.lines().getFirst().endsWith("compiled 2 (of which repeats 0), served "
				+ "from the disk cache 0, shared within the load 0"));
	}

	@Test
	void aNameNoProgramRegisteredIsFiledUnderTheUnitsNoProgramOwns() {
		program("entity", "cow", "cowSkin");

		asked("vitrail_pack_1_composite1_fragment", "k1", ModuleTally.Supply.COMPILED);
		asked("minecraft_shaders_core_terrain_vertex", "k2", ModuleTally.Supply.COMPILED);

		assertEquals(2, this.tally.compiled(ModuleTally.UNOWNED));
		assertEquals(0, this.tally.compiled("entity"));
		assertEquals(List.of("Module census, " + ModuleTally.UNOWNED + ": modules asked for 2: "
				+ "compiled 2 (of which repeats 0), served from the disk cache 0, shared within the "
				+ "load 0"), this.tally.lines().stream().filter(line -> line.contains("units no program"))
				.toList());
	}

	@Test
	void theCompiledTotalOfEveryFamilyIsWhatTheModuleCacheCounts() {
		program("entity", "cow", "cowSkin");
		program("sky", "sun", "sunSkin");

		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);
		asked("cowSkin.fragment", "k2", ModuleTally.Supply.COMPILED);
		asked("sun.vertex", "k3", ModuleTally.Supply.COMPILED);
		asked("vitrail_pack_1_final_fragment", "k4", ModuleTally.Supply.COMPILED);
		asked("cow.vertex", "k1", ModuleTally.Supply.SHARED);

		int compiled = this.tally.compiled("entity") + this.tally.compiled("sky")
				+ this.tally.compiled(ModuleTally.UNOWNED);

		assertEquals(4, compiled);
	}

	@Test
	void aFamilyAskedForMoreModulesIsSaidAgainWhereItsProgramsDidNotGrow() {
		program("chunk", "block", "blockSkin");
		assertEquals(1, this.tally.lines().size());
		assertTrue(this.tally.lines().isEmpty());

		asked("block.vertex", "k1", ModuleTally.Supply.COMPILED);

		List<String> again = this.tally.lines();

		assertEquals(1, again.size());
		assertTrue(again.getFirst().contains("chunk: programs built 1,"), again.toString());
		assertTrue(this.tally.lines().isEmpty());
	}

	@Test
	void clearingForgetsWhoOwnedWhat() {
		program("entity", "cow", "cowSkin");
		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);

		this.tally.clear();
		asked("cow.vertex", "k1", ModuleTally.Supply.COMPILED);

		assertEquals(0, this.tally.compiled("entity"));
		assertEquals(1, this.tally.compiled(ModuleTally.UNOWNED));
	}
}
