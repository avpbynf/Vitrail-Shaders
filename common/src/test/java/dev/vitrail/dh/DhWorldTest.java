package dev.vitrail.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Holds what every other test of the Distant Horizons bridge stands on: that a fake DH cannot be
 * seen from anywhere but its own world, that two worlds share no state, and that the engine
 * stand-ins have the signatures of the engine classes they stand in for.
 */
class DhWorldTest {

	private static final List<String> FAKE_DH = List.of(DhFakes.TERRAIN_RENDERER, DhFakes.LOD_RENDERER,
			DhFakes.BUFFER_CONTAINER, DhFakes.SORTED_SET, DhFakes.BUFFER_WRAPPER, DhFakes.DELAYED,
			DhFakes.CLIENT_API);

	@Test
	void theFakeDhIsNotOnTheTestClassPath() {
		DhWorld world = DhWorld.standard();
		world.dhStarted();
		world.install();

		assertTrue(world.lodsUsable());
		for (String name : FAKE_DH) {
			assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
			assertEquals(name, world.type(name).getName());
		}
	}

	@Test
	void aWorldHasItsOwnCopyOfTheBridge() {
		DhWorld world = DhWorld.standard();

		assertNotSame(DhLods.class, world.type(DhWorld.LODS));
		assertNotSame(DhLods.Piece.class, world.type(DhWorld.LODS + "$Piece"));
		assertNotSame(DhDepth.class, world.type(DhWorld.DEPTH));
		assertEquals(world, world.type(DhWorld.LODS).getClassLoader());
	}

	@Test
	void twoWorldsShareNoState() {
		DhWorld first = DhWorld.standard();
		first.dhStarted();
		first.install();

		DhWorld second = DhWorld.standard();

		assertTrue(first.lodsUsable());
		assertFalse(second.lodsUsable());
		assertEquals(List.of(), second.events());
		assertEquals(List.of(), second.log());
		assertFalse(first.events().isEmpty());
	}

	@Test
	void theStandInsHaveTheSignaturesOfTheEngineClassesTheyReplace() throws ReflectiveOperationException {
		DhWorld world = DhWorld.standard();

		assertSameMethod(world, DhFakes.DISTANT_DRAW, "dev.vitrail.render.DistantDraw", "draw", boolean.class,
				List.class);
		assertSameMethod(world, DhFakes.PASS_TIMINGS, "dev.vitrail.render.timing.PassTimings", "keepRedoneWork");
		assertSameMethod(world, DhFakes.PASS_TIMINGS, "dev.vitrail.render.timing.PassTimings",
				"censusFarSections", int.class);
		assertSameMethod(world, DhFakes.VITRAIL, "dev.vitrail.Vitrail", "logger");
	}

	private static void assertSameMethod(DhWorld world, String standIn, String real, String name,
			Class<?>... parameters) throws ReflectiveOperationException {
		Method fake = world.type(standIn).getMethod(name, parameters);
		Method actual = Class.forName(real, false, DhWorldTest.class.getClassLoader()).getMethod(name, parameters);

		assertEquals(actual.getReturnType(), fake.getReturnType(), real + "." + name);
		assertEquals(Modifier.isStatic(actual.getModifiers()), Modifier.isStatic(fake.getModifiers()),
				real + "." + name);
	}
}
