package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.OpenedPack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds {@link PackProgram} to what it reads out of a pack's text: the compute directives that say
 * how many groups to dispatch, and the program a directory pack serves.
 * <p>
 * The packs are written into a JUnit temp directory as a directory pack would be laid out on a
 * player's disk, a handful of lines each, so that the include expander, the option index and the
 * translator all run for real and no game does. The directives are Iris's and the expected numbers
 * are worked out from the rules its javadoc states: only a line that begins with the declaration
 * counts, the last live one wins, a negative count clamps to nought and nought itself is kept.
 */
class PackProgramTest {

	@TempDir
	Path root;

	private int serial;

	private Path pack(String file, String source) throws IOException {
		Path pack = this.root.resolve("pack" + this.serial++);
		Path target = pack.resolve("shaders").resolve(file);
		Files.createDirectories(target.getParent());
		Files.writeString(target, source);

		return pack;
	}

	private PackProgram.Compute compute(String source) throws IOException {
		Path pack = pack("main.csh", source);
		try (OpenedPack opened = OpenedPack.open(pack, Map.of(), "")) {
			return PackProgram.loadCompute(opened, "main").orElseThrow();
		}
	}

	/** A compute shader of these lines, under a version line and ahead of an empty main. */
	private static String source(String... lines) {
		return "#version 430\n" + String.join("\n", lines) + "\nvoid main() { }\n";
	}

	// ------------------------------------------------------------------------ work groups

	@Test
	void aCountOfItsOwnIsDispatchedWhateverTheSizeOfThePass() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 16, local_size_y = 8) in;",
				"const ivec3 workGroups = ivec3(4, 5, 1);"));

		assertTrue(compute.fixed());
		assertFalse(compute.relative());
		assertEquals(List.of(4, 5, 1), List.of(compute.groupsX(), compute.groupsY(), compute.groupsZ()));
		assertArrayEquals(new int[] {4, 5, 1}, compute.groupsAt(1920, 1080));
		assertArrayEquals(new int[] {4, 5, 1}, compute.groupsAt(1, 1));
	}

	@Test
	void withNoCountOfItsOwnThePassIsCoveredByGroupsOfTheShadersLocalSize() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 16, local_size_y = 8) in;"));

		assertFalse(compute.fixed());
		assertTrue(compute.sized());
		assertEquals(-1, compute.groupsX());
		assertEquals(16, compute.localX());
		assertEquals(8, compute.localY());
		// ceil(1920 / 16) and ceil(1080 / 8): the last group of a row is part used, never left out.
		assertArrayEquals(new int[] {120, 135, 1}, compute.groupsAt(1920, 1080));
		assertArrayEquals(new int[] {1, 1, 1}, compute.groupsAt(1, 1));
		assertArrayEquals(new int[] {0, 0, 1}, compute.groupsAt(0, 0));
	}

	@Test
	void aFractionOfThePassIsTheRoadAPackTakesForAVolumetricPass() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 16, local_size_y = 8) in;",
				"const vec2 workGroupsRender = vec2(0.5, 0.5);"));

		assertTrue(compute.relative());
		assertFalse(compute.fixed());
		assertEquals(0.5F, compute.renderX());
		assertEquals(0.5F, compute.renderY());
		// ceil(1920 * 0.5) = 960 over sixteen; ceil(1080 * 0.5) = 540 over eight is sixty-seven and a half.
		assertArrayEquals(new int[] {60, 68, 1}, compute.groupsAt(1920, 1080));
		// A half of an odd size is rounded up too: 961 over sixteen is sixty-one groups and not sixty.
		assertArrayEquals(new int[] {61, 68, 1}, compute.groupsAt(1921, 1081));
	}

	@Test
	void aMultiplierIsReadWithOrWithoutADecimalPointAndASuffix() throws IOException {
		assertEquals(1.0F, compute(source("layout(local_size_x = 8) in;", "const vec2 workGroupsRender = vec2(1, 1);"))
				.renderX());
		assertEquals(0.25F, compute(source("layout(local_size_x = 8) in;", "const vec2 workGroupsRender = vec2(.25, 0.5f);"))
				.renderX());
		assertEquals(0.5F, compute(source("layout(local_size_x = 8) in;", "const vec2 workGroupsRender = vec2(.25, 0.5f);"))
				.renderY());
	}

	@Test
	void aLocalSizeWrittenOnlyInXLeavesYAtOne() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 64) in;"));

		assertEquals(64, compute.localX());
		assertEquals(1, compute.localY());
		assertArrayEquals(new int[] {2, 50, 1}, compute.groupsAt(100, 50));
	}

	@Test
	void aLocalSizeOfNoughtIsOneBecauseTheLanguageCountsInWholeGroups() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 0, local_size_y = 0) in;"));

		assertEquals(1, compute.localX());
		assertEquals(1, compute.localY());
	}

	/**
	 * The record says minus one in both where the size cannot be read, and here, where the shader
	 * writes none at all, the answer is minus one and one. {@link PackProgram.Compute#sized} is false
	 * either way, and it is what a caller asks, so nothing is drawn wrong; pinned as it is.
	 */
	@Test
	void characterizes_aShaderThatWritesNoLocalSizeAtAllIsUnsizedWithYAtOneNotMinusOne() throws IOException {
		PackProgram.Compute compute = compute(source());

		assertEquals(-1, compute.localX());
		assertEquals(1, compute.localY());
		assertFalse(compute.sized());
		assertEquals(List.of(), compute.unresolved());
	}

	@Test
	void aCountOfItsOwnIsSizedEvenWithNoLocalSize() throws IOException {
		PackProgram.Compute compute = compute(source("const ivec3 workGroups = ivec3(2, 2, 2);"));

		assertTrue(compute.sized());
		assertEquals(-1, compute.localX());
	}

	// ------------------------------------------------------------- which line is a directive

	@Test
	void onlyTheLiveBranchOfAConditionalIsRead() throws IOException {
		String source = source("layout(local_size_x = 8) in;", "#ifdef BIG", "const ivec3 workGroups = ivec3(8, 8, 8);",
				"#else", "const ivec3 workGroups = ivec3(2, 2, 2);", "#endif");

		assertEquals(2, compute(source).groupsX());
		assertEquals(8, compute(source.replace("#ifdef BIG", "#define BIG\n#ifdef BIG")).groupsX());

		// And with the live branch first: the dead one after it does not get the last word.
		String live = source("layout(local_size_x = 8) in;", "#ifndef BIG", "const ivec3 workGroups = ivec3(2, 2, 2);",
				"#else", "const ivec3 workGroups = ivec3(8, 8, 8);", "#endif");
		assertEquals(2, compute(live).groupsX());
	}

	@Test
	void theLastLiveDirectiveWins() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 4) in;", "layout(local_size_x = 8) in;",
				"const ivec3 workGroups = ivec3(1, 1, 1);", "const ivec3 workGroups = ivec3(3, 3, 3);"));

		assertEquals(3, compute.groupsX());
		assertEquals(8, compute.localX());
	}

	@Test
	void aDirectiveACommentCrossedOutIsNotOneAndOneAfterACommentOnItsLineIs() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 8) in;",
				"// const ivec3 workGroups = ivec3(9, 9, 9);",
				"/* const ivec3 workGroups = ivec3(8, 8, 8);",
				"const ivec3 workGroups = ivec3(6, 6, 6);",
				"*/",
				"/* a note */ const ivec3 workGroups = ivec3(7, 7, 7);"));

		assertEquals(7, compute.groupsX());
	}

	@Test
	void aDeclarationThatIsNotTheFirstThingOnItsLineIsNotADirective() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 8) in;",
				"int other; const ivec3 workGroups = ivec3(9, 9, 9);"));

		assertFalse(compute.fixed());
		assertEquals(-1, compute.groupsX());
	}

	// ----------------------------------------------------------------------- the values

	@Test
	void aNegativeCountIsClampedToNoughtAndNoughtItselfIsKeptForAPassThePackTurnedOff() throws IOException {
		PackProgram.Compute negative = compute(source("layout(local_size_x = 8) in;",
				"const ivec3 workGroups = ivec3(-1, 5, 2);"));
		assertEquals(List.of(0, 5, 2), List.of(negative.groupsX(), negative.groupsY(), negative.groupsZ()));

		PackProgram.Compute off = compute(source("layout(local_size_x = 8) in;",
				"const ivec3 workGroups = ivec3(0, 0, 0);"));
		assertTrue(off.fixed());
		assertArrayEquals(new int[] {0, 0, 0}, off.groupsAt(1920, 1080));
	}

	@Test
	void aNameIsStoodInForOutOfTheUnitsDefinesAndSoIsAChainOfThem() throws IOException {
		PackProgram.Compute compute = compute(source("#define TILE 6", "#define ALIAS TILE", "#define OUTER ALIAS",
				"layout(local_size_x = TILE, local_size_y = ALIAS) in;", "const ivec3 workGroups = ivec3(OUTER, TILE, 1);"));

		assertEquals(List.of(6, 6, 1), List.of(compute.groupsX(), compute.groupsY(), compute.groupsZ()));
		assertEquals(6, compute.localX());
		assertEquals(6, compute.localY());
		assertEquals(List.of(), compute.unresolved());
	}

	@Test
	void aNameThatStandsForNothingLeavesTheDirectiveAbsentAndEveryWordItCouldNotReadIsSaid() throws IOException {
		PackProgram.Compute compute = compute(source("#define LOOP_A LOOP_B", "#define LOOP_B LOOP_A",
				"layout(local_size_x = 8) in;", "const ivec3 workGroups = ivec3(LOOP_A, MISSING, 1);"));

		assertFalse(compute.fixed());
		assertEquals(-1, compute.groupsX());
		// Both are said, not only the first: the log is the one place a word is ever reported.
		assertEquals(List.of("LOOP_A in workGroups", "MISSING in workGroups"), compute.unresolved());
	}

	@Test
	void anExpressionOrANumberTooWideIsRefusedLikeAName() throws IOException {
		PackProgram.Compute expression = compute(source("layout(local_size_x = 8) in;",
				"const ivec3 workGroups = ivec3(2 * 3, 1, 1);"));
		assertEquals(-1, expression.groupsX());
		assertEquals(List.of("2 * 3 in workGroups"), expression.unresolved());

		PackProgram.Compute wide = compute(source("layout(local_size_x = 8) in;",
				"const ivec3 workGroups = ivec3(99999999999, 1, 1);"));
		assertEquals(-1, wide.groupsX());
		assertEquals(List.of("99999999999 in workGroups"), wide.unresolved());
	}

	@Test
	void aLocalSizeThatIsACallIsTakenWholeToItsClosingBracketAndRefusesTheAnswer() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = min(gl_MaxComputeWorkGroupSize.x, 64),"
				+ " local_size_y = 4) in;"));

		// The comma inside the call closes nothing, so the whole call is the word said in the log.
		assertEquals(List.of("min(gl_MaxComputeWorkGroupSize.x, 64) in local_size_x"), compute.unresolved());
		assertEquals(-1, compute.localX());
		assertEquals(-1, compute.localY());
		assertFalse(compute.sized());
	}

	@Test
	void aRefusedAxisRefusesBothAndTheWalkGoesOnPastItForTheSakeOfTheWords() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = 8, local_size_y = SIZE_Y) in;"));

		assertEquals(-1, compute.localX());
		assertEquals(-1, compute.localY());
		assertEquals(List.of("SIZE_Y in local_size_y"), compute.unresolved());
	}

	@Test
	void theWordsOfAnUnresolvedDirectiveCannotBeAddedTo() throws IOException {
		PackProgram.Compute compute = compute(source("layout(local_size_x = SIZE) in;"));

		assertThrows(UnsupportedOperationException.class, () -> compute.unresolved().add("x"));
	}

	@Test
	void aComputeThePackDoesNotShipIsEmpty() throws IOException {
		Path pack = pack("main.csh", source());

		try (OpenedPack opened = OpenedPack.open(pack, Map.of(), "")) {
			assertEquals(Optional.empty(), PackProgram.loadCompute(opened, "missing"));
		}
	}

	@Test
	void theCountsOfAComputeThatWasBuiltByHandFollowTheSameRules() {
		PackProgram.Compute derived = new PackProgram.Compute(null, -1, -1, -1, 0.25F, 0.25F, 8, 8, List.of());

		assertTrue(derived.relative());
		assertArrayEquals(new int[] {25, 13, 1}, derived.groupsAt(800, 400));
		assertArrayEquals(new int[] {0, 0, 1}, derived.groupsAt(-5, 0));
		List<String> words = new ArrayList<>(List.of("a"));
		PackProgram.Compute said = new PackProgram.Compute(null, 1, 1, 1, -1F, -1F, 1, 1, words);
		words.add("b");
		assertEquals(List.of("a"), said.unresolved());
	}

	// ------------------------------------------------------------------------- a program

	private static final String VERTEX = """
			#version 120
			varying vec2 texcoord;
			void main() {
				gl_Position = ftransform();
				texcoord = gl_MultiTexCoord0.st;
			}
			""";

	private static final String FRAGMENT = """
			#version 120
			varying vec2 texcoord;
			uniform sampler2D colortex0;
			/* DRAWBUFFERS:0 */
			void main() {
				gl_FragData[0] = texture2D(colortex0, texcoord);
			}
			""";

	private Path program(String directory, String vertex, String fragment, String... more) throws IOException {
		Path pack = this.root.resolve("pack" + this.serial++);
		Path shaders = Files.createDirectories(pack.resolve("shaders").resolve(directory));
		Files.writeString(shaders.resolve("composite.vsh"), vertex);
		Files.writeString(shaders.resolve("composite.fsh"), fragment);
		for (int at = 0; at + 1 < more.length; at += 2) {
			Files.writeString(shaders.resolve(more[at]), more[at + 1]);
		}

		return pack;
	}

	@Test
	void aProgramBothHalvesOfWhichThePackShipsIsReadAndTranslatedWithTheDrawBuffersItNames() throws IOException {
		Path pack = program("", VERTEX, FRAGMENT);

		PackProgram.Loaded loaded = PackProgram.load(pack, "composite", true).orElseThrow();

		assertEquals(pack.getFileName().toString(), loaded.packName());
		assertEquals("composite", loaded.path());
		assertEquals(List.of(ProgramStage.VERTEX, ProgramStage.FRAGMENT), List.copyOf(loaded.program().stages().keySet())
				.stream().sorted().toList());
		assertEquals(List.of(0), loaded.program().stages().get(ProgramStage.FRAGMENT).drawBuffers());
		assertTrue(loaded.program().stages().get(ProgramStage.FRAGMENT).text().startsWith("#version 460 core\n"));
		assertEquals(List.of("colortex0"), loaded.program().samplers().stream().map(s -> s.name()).toList());
		assertEquals(VertexInputs.FULLSCREEN, loaded.program().inputs());
	}

	@Test
	void aProgramInADimensionFolderIsFoundUnderItsFullPath() throws IOException {
		Path pack = program("world0", VERTEX, FRAGMENT);

		assertTrue(PackProgram.load(pack, "world0/composite", true).isPresent());
		assertEquals("world0/composite", PackProgram.load(pack, "world0/composite", true).orElseThrow().path());
		assertTrue(PackProgram.load(pack, "composite", true).isEmpty());
	}

	@Test
	void aProgramWithOnlyOneHalfIsNotServed() throws IOException {
		Path pack = program("", VERTEX, FRAGMENT);
		Files.delete(pack.resolve("shaders").resolve("composite.vsh"));

		assertTrue(PackProgram.load(pack, "composite", true).isEmpty());
		assertTrue(PackProgram.load(pack, "missing", true).isEmpty());
	}

	@Test
	void aProgramDeclaringNothingItCannotBindIsNotRefusedAndOneWithAVolumeNobodyShipsIs() throws IOException {
		PackProgram.Loaded plain = PackProgram.load(program("", VERTEX, FRAGMENT), "composite", true).orElseThrow();
		assertTrue(plain.unbindable().isEmpty());
		assertTrue(plain.storageBlocks().isEmpty());
		assertFalse(plain.voxelises());
		assertFalse(plain.readsGameTransforms());
		assertFalse(plain.fullbright());

		String volume = FRAGMENT.replace("uniform sampler2D colortex0;", "uniform sampler2D colortex0;\nuniform sampler3D lut;");
		PackProgram.Loaded refused = PackProgram.load(program("", VERTEX, volume), "composite", true).orElseThrow();

		assertEquals(List.of("lut"), refused.unbindable().stream().map(s -> s.name()).toList());
		assertEquals("sampler3D", refused.unbindable().getFirst().type());
	}

	@Test
	void aProgramWithAGeometryStageVoxelises() throws IOException {
		String geometry = """
				#version 150 compatibility
				layout(triangles) in;
				layout(triangle_strip, max_vertices = 3) out;
				void main() {
					for (int i = 0; i < 3; i++) {
						gl_Position = gl_in[i].gl_Position;
						EmitVertex();
					}
					EndPrimitive();
				}
				""";

		PackProgram.Loaded loaded = PackProgram.load(program("", VERTEX, FRAGMENT, "composite.gsh", geometry), "composite", true)
				.orElseThrow();

		assertTrue(loaded.program().stages().containsKey(ProgramStage.GEOMETRY));
		assertTrue(loaded.voxelises());
	}

	// ------------------------------------------------------------------------------- refusal

	@Test
	void aRefusalNamesEachKindOfProblemInItsOwnClauseAndCopiesWhatItIsGiven() {
		TranslatedUnit.Uniform volume = new TranslatedUnit.Uniform("colortex6", "sampler3D", "sampler3D colortex6");
		List<TranslatedUnit.Uniform> samplers = new ArrayList<>(List.of(volume));
		List<String> blocks = new ArrayList<>(List.of("blockDataBuffer"));
		PackProgram.Refusal refusal = new PackProgram.Refusal(samplers, blocks);
		samplers.clear();
		blocks.clear();

		assertTrue(refusal.any());
		assertEquals("declares colortex6 as sampler3D, a shape this backend cannot bind, with nothing behind that name "
				+ "this engine knows how to serve, and declares the storage block blockDataBuffer, which nothing binds: "
				+ "the game lists a module's uniform buffers and its sampled images and neither includes one, so its "
				+ "descriptor stays on the binding the pack wrote", refusal.reason());
		assertThrows(UnsupportedOperationException.class, () -> refusal.storage().add("x"));
	}

	@Test
	void aRefusalWithOnlyOneKindSaysOnlyThatAndOneWithNothingIsNotARefusal() {
		PackProgram.Refusal blocksOnly = new PackProgram.Refusal(List.of(), List.of("a", "b"));
		assertTrue(blocksOnly.reason().startsWith("declares the storage block a, b, which nothing binds"), blocksOnly.reason());
		assertFalse(blocksOnly.reason().contains(", and "), blocksOnly.reason());

		PackProgram.Refusal samplersOnly = new PackProgram.Refusal(List.of(
				new TranslatedUnit.Uniform("a", "sampler3D", "sampler3D a"), new TranslatedUnit.Uniform("b", "sampler3D", "sampler3D b")),
				List.of());
		assertTrue(samplersOnly.reason().startsWith("declares a as sampler3D, b as sampler3D, a shape"), samplersOnly.reason());

		PackProgram.Refusal none = new PackProgram.Refusal(List.of(), List.of());
		assertFalse(none.any());
		assertEquals("", none.reason());
	}
}
