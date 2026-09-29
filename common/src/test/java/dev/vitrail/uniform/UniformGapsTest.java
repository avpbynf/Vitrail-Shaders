package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Holds the two lists of names {@link UniformGaps} keeps for the log to the one thing that makes
 * them worth reading: each is true of the table it talks about.
 * <p>
 * A stand-in is a name the tables do answer, with something that is not the value, and a name that
 * no engine answers is one the tables do not. If either list drifts from the tables, the log says
 * that a value is a debt when it is settled, or that a name is nobody's when this engine serves it,
 * and sends whoever reads it looking for work that is not there. The lists are private and the
 * accessors take a name, so the lists themselves are read by reflection.
 */
class UniformGapsTest {

	private static Map<String, String> list(String field) throws ReflectiveOperationException {
		Field declared = UniformGaps.class.getDeclaredField(field);
		declared.setAccessible(true);

		@SuppressWarnings("unchecked")
		Map<String, String> reasons = (Map<String, String>) declared.get(null);

		return reasons;
	}

	private static List<UniformCatalog> tables() {
		return List.of(UniformCatalog.engine(), UniformCatalog.geometry(), UniformCatalog.shadowGeometry());
	}

	@Test
	void everyStandInIsANameTheEngineReallyAnswers() throws ReflectiveOperationException {
		Set<String> standIns = new TreeSet<>(list("STAND_INS").keySet());
		assertFalse(standIns.isEmpty());

		for (String name : standIns) {
			assertNotNull(UniformCatalog.engine().source(name),
					name + " is listed as a stand-in and nothing answers it");
		}
	}

	@Test
	void noNameNobodyAnswersIsOneTheTablesServe() throws ReflectiveOperationException {
		Set<String> unanswerable = new TreeSet<>(list("UNANSWERABLE").keySet());
		assertFalse(unanswerable.isEmpty());

		List<String> served = new ArrayList<>();
		for (String name : unanswerable) {
			for (UniformCatalog table : tables()) {
				if (table.source(name) != null) {
					served.add(name);
				}
			}
		}

		assertEquals(List.of(), served, "listed as answered by no engine, and answered here");
	}

	@Test
	void aNameIsNeverBothAStandInAndUnanswerable() throws ReflectiveOperationException {
		Set<String> both = new TreeSet<>(list("STAND_INS").keySet());
		both.retainAll(list("UNANSWERABLE").keySet());

		assertEquals(Set.of(), both);
	}

	@Test
	void everyReasonSaysSomething() throws ReflectiveOperationException {
		for (String field : List.of("STAND_INS", "UNANSWERABLE")) {
			list(field).forEach((name, reason) -> {
				assertNotNull(reason, name);
				assertTrue(reason.strip().length() > 20, name + ": '" + reason + "'");
				assertFalse(reason.contains("\n"), name);
			});
		}
	}

	@Test
	void theAccessorsAnswerFromTheListsAndNullForEverythingElse() throws ReflectiveOperationException {
		list("STAND_INS").forEach((name, reason) -> {
			assertEquals(reason, UniformGaps.standIn(name));
			assertNull(UniformGaps.unanswerable(name), name);
		});
		list("UNANSWERABLE").forEach((name, reason) -> {
			assertEquals(reason, UniformGaps.unanswerable(name));
			assertNull(UniformGaps.standIn(name), name);
		});

		for (String ordinary : List.of("near", "sunPosition", "gbufferModelView", "noSuchUniform", "")) {
			assertNull(UniformGaps.standIn(ordinary), ordinary);
			assertNull(UniformGaps.unanswerable(ordinary), ordinary);
		}
	}

	@Test
	void keepsTheNamesTheLogMostOftenSaysUnderTheirExactSpelling() {
		// The two stand-ins, and some of the names no engine answers, spelled as packs spell them.
		assertNotNull(UniformGaps.standIn("currentColorSpace"));
		assertNotNull(UniformGaps.standIn("constantMood"));
		assertNotNull(UniformGaps.unanswerable("farPlane"));
		assertNotNull(UniformGaps.unanswerable("velocity"));
		assertNotNull(UniformGaps.unanswerable("vxProj"));
		assertNotNull(UniformGaps.unanswerable("previouscameraPositionFract"));
		assertNull(UniformGaps.unanswerable("previousCameraPositionFract"),
				"the correctly spelt name is answered, so it is not on the list");
	}
}
