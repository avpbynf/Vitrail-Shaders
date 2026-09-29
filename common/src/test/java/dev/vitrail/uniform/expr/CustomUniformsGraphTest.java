package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.UniformSource;
import dev.vitrail.uniform.Val;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds the graph a pack's declarations become: which order the values are worked out in, which
 * names may be read by which, what is dropped and how it is said, and what a program is finally
 * offered.
 * <p>
 * The wording of a problem line is not pinned whole; the name at the start of it and the words
 * that say why are, since those are what a person reading a log looks for.
 */
class CustomUniformsGraphTest {

	private final ExprRig rig = new ExprRig();

	/** The one line about {@code name}, which must exist and contain {@code words}. */
	private static String problem(List<String> problems, String name, String words) {
		List<String> about = problems.stream().filter(line -> line.startsWith(name + ": ")).toList();
		assertFalse(about.isEmpty(), "no line about " + name + " in " + problems);
		assertTrue(about.stream().anyMatch(line -> line.contains(words)),
				name + " should say '" + words + "' in " + about);

		return about.getFirst();
	}

	// order

	@Test
	void worksAValueOutAfterEverythingItReads() {
		// Declared against the grain: each line reads the one below it.
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.a = b + 1.0",
				"variable.float.b = c * 2.0",
				"variable.float.c = fa");
		this.rig.frame(uniforms);

		assertEquals(6.0F, this.rig.f(uniforms, "a"), "(2.5 * 2) + 1, on the very first frame");
		assertEquals(5.0F, this.rig.f(uniforms, "b"));
		assertEquals(2.5F, this.rig.f(uniforms, "c"));
	}

	@Test
	void worksADiamondOut() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.d = b + c",
				"variable.float.b = a * 2.0",
				"variable.float.c = a * 3.0",
				"variable.float.a = fa");
		this.rig.frame(uniforms);

		assertEquals(12.5F, this.rig.f(uniforms, "d"), "2.5 * 2 + 2.5 * 3");
	}

	@Test
	void worksALongChainOutDeclaredBackwards() {
		// 400 links, each one plus one, the last one read first. Nothing recurses, so the depth
		// costs a walk and not a stack.
		String[] lines = new String[401];
		lines[0] = "uniform.float.n400 = n399 + 1";
		for (int n = 1; n < 400; n++) {
			lines[n] = "variable.float.n" + (400 - n) + " = n" + (399 - n) + " + 1";
		}
		lines[400] = "variable.float.n0 = fa";

		CustomUniforms uniforms = this.rig.buildClean(lines);
		this.rig.frame(uniforms);

		assertEquals(402.5F, this.rig.f(uniforms, "n400"), "2.5 + 400");
		assertEquals(202.5F, this.rig.f(uniforms, "n200"));
	}

	@Test
	void aNameReadTwiceInOneExpressionIsStillOneDependency() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.a = b * b + b",
				"variable.float.b = fa");
		this.rig.frame(uniforms);

		assertEquals(8.75F, this.rig.f(uniforms, "a"), "2.5 * 2.5 + 2.5");
	}

	@Test
	void bothKeywordsShareOneNamespaceAndReadEachOther() {
		CustomUniforms uniforms = this.rig.buildClean(
				"variable.float.v = fa + 1.0",
				"uniform.float.u = v * 2.0",
				"variable.float.w = u + v");
		// w is a variable nobody reads and u a uniform something reads: the walk starts from u.
		this.rig.frame(uniforms);

		assertEquals(7.0F, this.rig.f(uniforms, "u"), "(2.5 + 1) * 2");
		assertEquals(3.5F, this.rig.f(uniforms, "v"), "a variable read by a uniform stays");
		assertNull(uniforms.source("w"), "a variable nothing reaches is dropped");
	}

	// what stays

	@Test
	void aVariableNothingReadsIsPrunedAndItsInputsAreNotRead() {
		CustomUniforms uniforms = this.rig.buildClean(
				"variable.float.stale = fa * 2.0",
				"uniform.float.u = ia + 1");
		this.rig.frame(uniforms);
		this.rig.frame(uniforms);

		assertNull(uniforms.source("stale"));
		assertEquals(List.of("u"), List.copyOf(uniforms.exposed()));
		assertEquals(2, this.rig.reads.get("ia"), "read once each frame");
		assertNull(this.rig.reads.get("fa"), "read by nothing that is evaluated");
	}

	@Test
	void everythingAUniformReachesStaysWhateverItIsCalled() {
		CustomUniforms uniforms = this.rig.buildClean(
				"variable.float.a = fa",
				"variable.float.b = a + 1.0",
				"uniform.float.c = b + 1.0");

		assertEquals(Set.of("a", "b", "c"), uniforms.exposed(), "a variable that is reached is offered");
		assertEquals(List.of("a", "b", "c"), List.copyOf(uniforms.exposed()), "in the order declared");
	}

	@Test
	void exposedFollowsDeclarationOrderNotDependencyOrder() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.z = m + 1.0",
				"uniform.float.m = a + 1.0",
				"uniform.float.a = fa");

		assertEquals(List.of("z", "m", "a"), List.copyOf(uniforms.exposed()));
	}

	@Test
	void exposedCannotBeChangedByTheCaller() {
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.a = fa");

		assertThrows(UnsupportedOperationException.class, () -> uniforms.exposed().add("b"));
		assertEquals(List.of("a"), List.copyOf(uniforms.exposed()));
	}

	@Test
	void saysWhatShapeEachTypeTakesInTheBlock() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.f = fa",
				"uniform.int.i = ia",
				"uniform.bool.b = ia > 3",
				"uniform.vec2.v2 = vb",
				"uniform.vec3.v3 = va",
				"uniform.vec4.v4 = vc");

		assertEquals(UniformShape.FLOAT, uniforms.shape("f"));
		assertEquals(UniformShape.INT, uniforms.shape("i"));
		assertEquals(UniformShape.INT, uniforms.shape("b"), "a bool is an int in the block");
		assertEquals(UniformShape.VEC2, uniforms.shape("v2"));
		assertEquals(UniformShape.VEC3, uniforms.shape("v3"));
		assertEquals(UniformShape.VEC4, uniforms.shape("v4"));
		assertNull(uniforms.shape("nothing"));
		assertNull(uniforms.source("nothing"));
	}

	@Test
	void layersTheSurvivorsOnTopOfTheEngineTable() {
		UniformCatalog engine = this.rig.engine();
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(engine, problems,
				"uniform.float.a = fa + 1.0",
				"uniform.vec3.b = va",
				"uniform.bool.c = ia > 3",
				"variable.int.d = ia * 2",
				"uniform.int.e = d + 1",
				"variable.float.unused = fa");
		assertEquals(List.of(), problems);

		UniformCatalog layered = uniforms.layerOn(engine);
		this.rig.frame(uniforms);

		assertEquals(UniformShape.FLOAT, layered.natural("a"));
		assertEquals(UniformShape.VEC3, layered.natural("b"));
		assertEquals(UniformShape.INT, layered.natural("c"));
		assertEquals(UniformShape.INT, layered.natural("d"), "a variable that is reached is offered too");
		assertNull(layered.source("unused"), "a variable that is not is not");
		assertEquals(UniformShape.FLOAT, layered.natural("fa"), "the engine's names are all still there");
		assertNull(engine.source("a"), "and the engine's own table is untouched");
		assertEquals(engine.names().size() + 5, layered.names().size());

		Val out = new Val();
		layered.source("a").read(this.rig.world, out);
		assertEquals(3.5F, out.f(0));
		layered.source("e").read(this.rig.world, out);
		assertEquals(15, out.i(0), "7 * 2 + 1");
	}

	@Test
	void aSourceHandedOutEarlyReadsTheLatestValue() {
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.a = fa * 2.0");
		UniformSource early = uniforms.source("a");
		assertNotNull(early);
		Val out = new Val();

		this.rig.frame(uniforms);
		early.read(this.rig.world, out);
		assertEquals(5.0F, out.f(0));

		this.rig.fa = 10.0F;
		this.rig.frame(uniforms);
		early.read(this.rig.world, out);
		assertEquals(20.0F, out.f(0), "the same source object, the new frame's number");
	}

	// what is refused

	@Test
	void aCycleIsDroppedAndNamedWhereIrisWouldThrow() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.float.a = b + 1.0",
				"uniform.float.b = a + 1.0",
				"uniform.float.ok = fa * 2.0");

		problem(problems, "a", "circular");
		problem(problems, "b", "circular");
		assertEquals(2, problems.size(), "and nothing is said about ok: " + problems);
		assertNull(uniforms.source("a"));
		assertNull(uniforms.source("b"));
		assertEquals(List.of("ok"), List.copyOf(uniforms.exposed()));

		this.rig.frame(uniforms);
		assertEquals(5.0F, this.rig.f(uniforms, "ok"), "the rest of the graph is untouched");
	}

	@Test
	void aSelfReferenceIsACycle() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems, "uniform.float.a = a + 1.0");

		problem(problems, "a", "circular");
		assertNull(uniforms.source("a"));
	}

	@Test
	void aLongerCycleAndWhatHangsOffItAreAllDropped() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"variable.float.a = c + 1.0",
				"variable.float.b = a + 1.0",
				"variable.float.c = b + 1.0",
				"uniform.float.hangs = c * 2.0",
				"uniform.float.fine = fa");

		for (String name : new String[] {"a", "b", "c", "hangs"}) {
			problem(problems, name, "circular");
			assertNull(uniforms.source(name), name);
		}
		assertEquals(4, problems.size(), problems.toString());
		assertEquals(List.of("fine"), List.copyOf(uniforms.exposed()));
	}

	@Test
	void anUnknownNameIsRefusedByNameAndTakesItsReadersWithIt() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.float.a = nope + 1.0",
				"uniform.float.b = a * 2.0",
				"uniform.float.c = b + 1.0",
				"uniform.float.ok = fa");

		problem(problems, "a", "Unknown variable: nope");
		problem(problems, "b", "could not be resolved");
		problem(problems, "c", "could not be resolved");
		assertEquals(3, problems.size(), problems.toString());
		assertEquals(List.of("ok"), List.copyOf(uniforms.exposed()));
		this.rig.frame(uniforms);
		assertEquals(2.5F, this.rig.f(uniforms, "ok"));
	}

	@Test
	void anUnknownFunctionIsRefusedByName() {
		List<String> problems = new ArrayList<>();
		this.rig.build(problems, "uniform.float.a = frobnicate(1.0)");

		assertEquals("a: No such function: frobnicate", problems.getFirst());
	}

	@Test
	void aTypeErrorIsRefusedAsUnresolvable() {
		List<String> problems = new ArrayList<>();
		this.rig.build(problems,
				"uniform.int.a = 1.5",
				"uniform.float.b = va",
				"uniform.vec3.c = fa",
				"uniform.float.d = if(fa, 1.0, 2.0)",
				"uniform.bool.e = fa",
				"uniform.float.f = abs(va)");

		for (String name : new String[] {"a", "b", "c", "d", "e", "f"}) {
			problem(problems, name, "Couldn't resolve");
		}
		assertEquals(6, problems.size());
	}

	@Test
	void aNameNothingCanCarryIsUnknownToo() {
		// The engine answers "fog" but with the struct shape, which no expression can hold.
		List<String> problems = new ArrayList<>();
		this.rig.build(problems, "uniform.float.a = fog");

		problem(problems, "a", "Unknown variable: fog");
	}

	@Test
	void refusesTheTypesAPackMayNotDeclare() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.mat4.m = m4",
				"uniform.mat3.n = m4",
				"uniform.ivec2.i = iv",
				"uniform.double.d = 1.0",
				"uniform.uint.u = 1",
				"uniform.string.s = 1");

		for (String name : new String[] {"m", "n", "i", "d", "u", "s"}) {
			problem(problems, name, "is not a type a custom uniform may take");
			assertNull(uniforms.source(name));
		}
	}

	@Test
	void theFirstOfTwoDeclarationsOfANameWins() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.float.a = 1.0",
				"uniform.float.a = 2.0",
				"variable.int.a = 3");
		this.rig.frame(uniforms);

		assertEquals(1.0F, this.rig.f(uniforms, "a"));
		assertEquals(2, problems.size());
		assertTrue(problems.stream().allMatch(line -> line.startsWith("a: declared more than once")), problems.toString());
	}

	@Test
	void aDeclarationThatShadowsAnEngineNameIsRefused() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.float.fa = 99.0",
				"variable.int.ia = 99",
				"uniform.float.reader = fa + ia");
		this.rig.frame(uniforms);

		problem(problems, "fa", "shadows a value the engine already answers");
		problem(problems, "ia", "shadows a value the engine already answers");
		assertEquals(2, problems.size());
		assertEquals(9.5F, this.rig.f(uniforms, "reader"), "and the engine's values are what is read: 2.5 + 7");
	}

	@Test
	void aParseFailureIsNamedWithItsExpressionAndDoesNotStopTheRest() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(problems,
				"uniform.float.bad = 1 +",
				"uniform.float.good = fa");
		this.rig.frame(uniforms);

		String line = problem(problems, "bad", "right side of the operator");
		assertTrue(line.endsWith("(= 1 +)"), line);
		assertEquals(1, problems.size());
		assertEquals(2.5F, this.rig.f(uniforms, "good"));
	}

	@Test
	void problemsAreAddedToTheListHandedIn() {
		List<String> problems = new ArrayList<>(List.of("something from before"));
		this.rig.build(problems, "uniform.float.a = nope");

		assertEquals("something from before", problems.getFirst());
		assertEquals(2, problems.size());
	}

	@Test
	void anExpressionThousandsOfTermsLongIsRefusedAtBuild() {
		// The parser walks it with a list, the resolver and the evaluator with the call stack, so
		// with no depth limit a very long or very deeply bracketed expression would throw an Error
		// out of build(). It is measured after it parses and refused, as a line in the problems.
		String longSum = "fa" + "+fa".repeat(100_000);
		List<String> problems = new ArrayList<>();

		CustomUniforms uniforms = assertDoesNotThrow(
				() -> this.rig.build(problems, "uniform.float.r = " + longSum));

		assertNull(uniforms.source("r"));
		assertEquals(List.of("r: nests more than " + CustomUniforms.MAX_DEPTH
				+ " levels deep, which is refused rather than read"), problems);
	}

	@Test
	void anExpressionAsLongAsTheDepthLimitBuildsAndEvaluates() {
		// A sum nests on the left, one level a term, so this is exactly as deep as a declaration
		// may be. It has to build and evaluate: a limit that counted one level too many would
		// refuse it.
		int terms = CustomUniforms.MAX_DEPTH;
		CustomUniforms uniforms = this.rig.buildClean("uniform.float.r = fa" + "+fa".repeat(terms - 1));
		this.rig.frame(uniforms);

		assertEquals(terms * 2.5F, this.rig.f(uniforms, "r"), "128 * 2.5");
	}

	@Test
	void anEmptyGraphIsFine() {
		CustomUniforms uniforms = this.rig.buildClean();
		this.rig.frame(uniforms);

		assertEquals(Set.of(), uniforms.exposed());
		assertEquals(List.of(), uniforms.drainProblems());
	}

	@Test
	void hasVariableAnswersForTheEngineNamesTheExpressionsMentionAndTheDeclaredOnes() {
		CustomUniforms uniforms = this.rig.buildClean(
				"uniform.float.a = fa + 1.0");

		assertTrue(uniforms.hasVariable("a"));
		assertTrue(uniforms.hasVariable("fa"), "an engine name, created when the expression asked");
		assertFalse(uniforms.hasVariable("ia"), "an engine name no expression mentioned is not an input");
		assertFalse(uniforms.hasVariable("zz"));
		assertEquals("Unknown variable: zz",
				assertThrows(IllegalStateException.class, () -> uniforms.getVariable("zz")).getMessage());
	}
}
