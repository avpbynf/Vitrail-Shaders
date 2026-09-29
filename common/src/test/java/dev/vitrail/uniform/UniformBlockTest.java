package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.TranslatedUnit;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link UniformBlock} to the std140 layout the shader reads it at: every kind of member a
 * pack can declare, arrays and their padding, and a name nothing answers.
 * <p>
 * The expected offsets come from {@link Std140Reference}, which works them out from the declaration
 * text and the specification's rules, and a few blocks are added up by hand as well so that the
 * reference itself is held to something. The bytes are written through {@link BytesSink} into a
 * block whose sources answer numbers that name their own place, so a value at the wrong offset, or
 * one member's number in another's slot, or a padding byte that was written, all fail.
 */
class UniformBlockTest {

	private static TranslatedUnit.Uniform member(String declaration) {
		int space = declaration.indexOf(' ');
		String rest = declaration.substring(space + 1);
		int bracket = rest.indexOf('[');

		return TranslatedUnit.Uniform.of(bracket < 0 ? rest : rest.substring(0, bracket), declaration);
	}

	private static List<TranslatedUnit.Uniform> members(String... declarations) {
		List<TranslatedUnit.Uniform> members = new ArrayList<>();
		for (String declaration : declarations) {
			members.add(member(declaration));
		}

		return members;
	}

	/** A source that fills the carrier from a seed that names the member, the element and the component. */
	private static UniformSource probe(UniformShape shape, int member) {
		return new UniformSource() {

			@Override
			public void read(WorldState world, Val out) {
				fill(out, shape, member * 1000);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				fill(out, shape, member * 1000 + element * 100);
			}
		};
	}

	/** Component {@code c} is {@code seed + c + 1}: float where the shape is, integer where it is not. */
	private static void fill(Val out, UniformShape shape, int seed) {
		switch (shape) {
			case FLOAT -> out.set(seed + 1.0F);
			case INT -> out.set(seed + 1);
			case VEC2 -> out.set(seed + 1.0F, seed + 2.0F);
			case VEC3 -> out.set(seed + 1.0F, seed + 2.0F, seed + 3.0F);
			case VEC4 -> out.set(seed + 1.0F, seed + 2.0F, seed + 3.0F, seed + 4.0F);
			case IVEC2 -> out.set(seed + 1, seed + 2);
			case IVEC3 -> out.set(seed + 1, seed + 2, seed + 3);
			case IVEC4 -> out.set(seed + 1, seed + 2, seed + 3, seed + 4);
			case MAT3 -> out.set(new Matrix3f().set(numbers(seed, 9)));
			case MAT4 -> out.set(new Matrix4f().set(numbers(seed, 16)));
			case FOG -> out.setFog(seed + 1.0F, seed + 2.0F, seed + 3.0F, seed + 4.0F,
					seed + 5.0F, seed + 6.0F, seed + 7.0F, seed + 8.0F);
		}
	}

	private static float[] numbers(int seed, int count) {
		float[] values = new float[count];
		for (int i = 0; i < count; i++) {
			values[i] = seed + i + 1.0F;
		}

		return values;
	}

	/** The catalogue of a block: each declaration answered by a probe, but the names left out. */
	private static UniformCatalog catalogFor(List<TranslatedUnit.Uniform> members, List<String> unanswered) {
		UniformCatalog.Builder builder = UniformCatalog.builder();
		int index = 1;
		for (TranslatedUnit.Uniform member : members) {
			UniformShape shape = UniformShape.of(member.type());
			if (!unanswered.contains(member.name())) {
				builder.add(member.name(), shape, probe(shape, index));
			}

			index++;
		}

		return builder.build();
	}

	private static final String[] KITCHEN_SINK = {
			"float scalarA", "vec3 vectorA", "float scalarB", "vec2 pairA", "mat3 normalA", "int countA",
			"ivec3 tripleA", "int countB", "mat4 matrixA", "bool flagA", "uint countC", "ivec2 gridA",
			"bvec3 flagsA", "uvec4 quadA", "vec4 colourA", "OfFog fogA", "float scalarC",
			"int counts[2]", "vec3 points[2]", "float singles[1]", "float grid[2][3]", "mat3 normals[2]",
			"mat4 matrices[3]", "vec2 pairs[3]", "ivec3 triples[2]", "vec4 colours[2]", "mat3x3 wide3",
			"mat4x4 wide4", "float tail" };

	@Test
	void placesEveryKindOfMemberWhereTheSpecificationPutsIt() {
		List<TranslatedUnit.Uniform> declared = members(KITCHEN_SINK);
		UniformCatalog catalog = catalogFor(declared, List.of());

		BlockCheck.verify(declared, catalog, new FakeWorld());
	}

	@Test
	void keepsTheLayoutWhenSomeMembersAreNotAnswered() {
		List<TranslatedUnit.Uniform> declared = members(KITCHEN_SINK);
		List<String> unanswered = List.of("vectorA", "normalA", "matrixA", "points", "matrices", "fogA", "tail");
		UniformCatalog catalog = catalogFor(declared, unanswered);

		BlockCheck.verify(declared, catalog, new FakeWorld());
	}

	@Test
	void placesTheFixedFunctionBlockTheTranslationBuilds() {
		// The members LegacyGlsl.FIXED_FUNCTION_MEMBERS declares, in that order, then a pack's own.
		List<TranslatedUnit.Uniform> declared = members(
				"mat4 of_ModelViewMatrix", "mat4 of_ModelViewProjectionMatrix", "mat4 of_ProjectionMatrix",
				"mat4 of_ModelViewMatrixInverse", "mat4 of_ProjectionMatrixInverse", "mat3 of_NormalMatrix",
				"mat4 of_TextureMatrix[8]", "OfFog of_Fog", "float frameTimeCounter", "vec3 sunPosition");
		UniformCatalog catalog = catalogFor(declared, List.of());

		UniformBlock block = new UniformBlock(declared, catalog);

		// 5 * 64, then 48 for the mat3, then 512 for the eight texture matrices, then 32 for the fog:
		// 320 + 48 + 512 + 32 = 912, the float at 912 and the vec3 at 928 to 940.
		assertEquals(940, block.size());
		BlockCheck.verify(declared, catalog, new FakeWorld());

		// And three of the numbers by hand. Each probe answers 1000 * its place in the list plus 100 *
		// the element plus the component and one: the fourth texture matrix starts at 368 + 3 * 64.
		BytesSink sink = new BytesSink(1024);
		block.write(sink, new FakeWorld());
		assertEquals(7301.0F, sink.floatAt(560), "of_TextureMatrix[3], first component");
		assertEquals(7316.0F, sink.floatAt(620), "of_TextureMatrix[3], last component");
		assertEquals(9001.0F, sink.floatAt(912), "frameTimeCounter");
		assertEquals(10003.0F, sink.floatAt(936), "the third component of sunPosition");
	}

	@Test
	void addsUpTheHandWorkedBlockToTwoHundredAndFortyFourBytes() {
		String[] declarations = { "float a", "vec3 b", "float c", "vec2 d", "mat3 e", "float f", "mat4 g",
				"int h[2]", "vec3 i[2]", "float j" };

		// a 0..4; b aligns to 16, 16..28; c 28..32 (the vec3 leaves room); d aligns to 8, 32..40;
		// e aligns to 16, 48..96 (three columns of sixteen); f 96..100; g aligns to 16, 112..176;
		// h is two elements of sixteen, 176..208; i the same, 208..240; j 240..244.
		int[] offsets = { 0, 16, 28, 32, 48, 96, 112, 176, 208, 240 };

		Std140Reference.Layout layout = Std140Reference.of(List.of(declarations));
		for (int i = 0; i < offsets.length; i++) {
			assertEquals(offsets[i], layout.slots().get(i).offset(), declarations[i]);
		}

		assertEquals(244, layout.size());
		assertEquals(16, layout.slot("h").stride());
		assertEquals(192, layout.slot("h").elementOffset(1));
		assertEquals(224, layout.slot("i").elementOffset(1));

		List<TranslatedUnit.Uniform> declared = members(declarations);
		assertEquals(244, new UniformBlock(declared, catalogFor(declared, List.of())).size());
	}

	@Test
	void aBareMemberPaysItsOwnSizeAndAnArrayPaysTheStrideEvenAtOneElement() {
		String[][] blocks = {
				{ "float x" }, { "float x[1]" }, { "float x", "float y" }, { "float x[1]", "float y" },
				{ "vec3 v[2]", "float y" }, { "vec3 v[1]", "float y" }, { "vec3 v", "float y" },
				{ "vec2 v[3]" }, { "vec4 v[3]" }, { "ivec3 v[1]", "int y" }, { "float m[2][3]", "float y" },
				{ "mat3 m[2]", "int y" }, { "mat3 m", "int y" }, { "mat4 t[8]", "float y" },
				{ "mat4 t[1]" }, { "OfFog f[2]", "float y" }, { "OfFog f", "float y" } };
		int[] sizes = { 4, 16, 8, 20, 36, 20, 16, 48, 48, 20, 100, 100, 52, 516, 64, 68, 36 };

		for (int i = 0; i < blocks.length; i++) {
			List<TranslatedUnit.Uniform> declared = members(blocks[i]);
			UniformBlock block = new UniformBlock(declared, catalogFor(declared, List.of()));

			assertEquals(sizes[i], block.size(), String.join("; ", blocks[i]));
			assertEquals(sizes[i], Std140Reference.of(List.of(blocks[i])).size(), "the reference: "
					+ String.join("; ", blocks[i]));
		}
	}

	@Test
	void anEmptyBlockIsZeroBytesAndWritesNothing() {
		UniformBlock block = new UniformBlock(List.of(), UniformCatalog.builder().build());
		BytesSink sink = new BytesSink(16);
		block.write(sink, new FakeWorld());

		assertEquals(0, block.size());
		assertEquals(0, sink.position());
		assertEquals(List.of(), block.unanswered());
	}

	@Test
	void theGamesTextureMatrixArrayIsFiveHundredAndTwelveBytesAndTheMemberBehindItMoves() {
		List<TranslatedUnit.Uniform> declared = members("mat4 of_TextureMatrix[8]", "float behind");
		UniformBlock block = new UniformBlock(declared, catalogFor(declared, List.of()));

		assertEquals(516, block.size(), "read as a single matrix the float would sit 448 bytes early");
		assertEquals(512, Std140Reference.of(List.of("mat4 of_TextureMatrix[8]")).size());
	}

	@Test
	void reportsTheNamesNothingAnswersInDeclarationOrder() {
		List<TranslatedUnit.Uniform> declared = members("float a", "float b", "float c", "float d");
		UniformCatalog catalog = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F))
				.add("c", UniformShape.FLOAT, (world, out) -> out.set(3.0F))
				.build();

		assertEquals(List.of("b", "d"), new UniformBlock(declared, catalog).unanswered());
	}

	@Test
	void writesZeroesForANameNothingAnswersAndTheMembersAfterItStayInPlace() {
		List<TranslatedUnit.Uniform> declared = members("float a", "vec3 lost", "float c");
		UniformCatalog catalog = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F))
				.add("c", UniformShape.FLOAT, (world, out) -> out.set(3.0F))
				.build();

		BytesSink sink = new BytesSink(64);
		new UniformBlock(declared, catalog).write(sink, new FakeWorld());

		assertEquals(1.0F, sink.floatAt(0));
		assertEquals(0.0F, sink.floatAt(16));
		assertEquals(0.0F, sink.floatAt(20));
		assertEquals(0.0F, sink.floatAt(24));
		assertEquals(3.0F, sink.floatAt(28), "not shifted by the gap");
	}

	@Test
	void aSourceThatHoldsTheFogStructAnswersNoOtherShapeAndTheOtherWayRound() {
		UniformCatalog catalog = UniformCatalog.builder()
				.add("of_Fog", UniformShape.FOG, (world, out) -> out.setFog(1, 2, 3, 4, 5, 6, 7, 8))
				.add("fogColour", UniformShape.VEC4, (world, out) -> out.set(1.0F, 2.0F, 3.0F, 4.0F))
				.build();

		// A struct is not eight floats to the compiler: neither direction is coerced, both are gaps.
		assertEquals(List.of("of_Fog", "fogColour"),
				new UniformBlock(members("vec4 of_Fog", "OfFog fogColour"), catalog).unanswered());
		assertEquals(List.of("missing"),
				new UniformBlock(members("OfFog of_Fog", "vec4 fogColour", "OfFog missing"), catalog).unanswered());
	}

	@Test
	void aMismatchedFogIsWrittenAsZeroesAtTheStructsSize() {
		UniformCatalog catalog = UniformCatalog.builder()
				.add("fogColour", UniformShape.VEC4, (world, out) -> out.set(1.0F, 2.0F, 3.0F, 4.0F))
				.build();

		BytesSink sink = new BytesSink(64);
		UniformBlock block = new UniformBlock(members("OfFog fogColour", "float after"), catalog);
		block.write(sink, new FakeWorld());

		assertEquals(36, block.size());
		assertEquals(0.0F, sink.floatAt(0));
		assertEquals(0.0F, sink.floatAt(16));
	}

	@Test
	void refusesATypeNothingCanSizeRatherThanGuess() {
		for (String type : List.of("double", "dvec3", "mat2", "mat3x2", "sampler2D", "Vec3")) {
			IllegalStateException refused = assertThrows(IllegalStateException.class,
					() -> new UniformBlock(members(type + " x"), UniformCatalog.builder().build()), type);

			assertTrue(refused.getMessage().contains("Cannot size " + type + " x"), refused.getMessage());
			assertTrue(refused.getMessage().contains("nothing here knows the type " + type),
					refused.getMessage());
		}
	}

	@Test
	void refusesAnArrayWhoseLengthIsNotALiteralOrIsNotPositive() {
		for (String declaration : List.of("float x[N]", "float x[]", "float x[0]", "float x[-1]",
				"float x[2", "float x[2][]", "float x[2][N]", "float x[0x10]", "float x[1.5]",
				"float x[2][0]")) {
			IllegalStateException refused = assertThrows(IllegalStateException.class,
					() -> new UniformBlock(members(declaration), UniformCatalog.builder().build()),
					declaration);

			assertTrue(refused.getMessage().contains("the array length is not a literal"),
					declaration + ": " + refused.getMessage());
		}
	}

	@Test
	void countsEveryPairOfBracketsAndTrimsTheSpaceInsideThem() {
		assertEquals(0, UniformBlock.arrayLength("float x"));
		assertEquals(1, UniformBlock.arrayLength("float x[1]"));
		assertEquals(8, UniformBlock.arrayLength("mat4 of_TextureMatrix[8]"));
		assertEquals(6, UniformBlock.arrayLength("float x[2][3]"));
		assertEquals(24, UniformBlock.arrayLength("float x[2][3][4]"));
		assertEquals(3, UniformBlock.arrayLength("float x[ 3 ]"));
		assertEquals(12, UniformBlock.arrayLength("vec3 x[ 3 ] [4]"));
		assertEquals(-1, UniformBlock.arrayLength("float x[3"));
		assertEquals(-1, UniformBlock.arrayLength("float x[3][]"));
		assertEquals(-1, UniformBlock.arrayLength("float x[03x]"));
	}

	@Test
	void anArrayOfSixElementsWrittenAsTwoByThreeCostsSixStrides() {
		// Reading only the first pair of brackets would write two elements and leave the rest of
		// the block four elements early.
		List<TranslatedUnit.Uniform> declared = members("float grid[2][3]", "float after");
		BytesSink sink = new BytesSink(256);
		UniformCatalog catalog = UniformCatalog.builder()
				.add("grid", UniformShape.FLOAT, probe(UniformShape.FLOAT, 1))
				.add("after", UniformShape.FLOAT, probe(UniformShape.FLOAT, 2))
				.build();

		new UniformBlock(declared, catalog).write(sink, new FakeWorld());

		for (int element = 0; element < 6; element++) {
			assertEquals(1001.0F + element * 100, sink.floatAt(element * 16), "element " + element);
		}

		assertEquals(2001.0F, sink.floatAt(96));
	}

	@Test
	void asksTheSourceOncePerElementInOrderAndNeverForANameNothingAnswers() {
		List<String> asked = new ArrayList<>();
		UniformCatalog catalog = UniformCatalog.builder()
				.add("bare", UniformShape.FLOAT, recording("bare", asked))
				.add("single", UniformShape.FLOAT, recording("single", asked))
				.add("many", UniformShape.MAT4, recording("many", asked))
				.build();

		new UniformBlock(members("float bare", "float single[1]", "float lost", "mat4 many[3]"), catalog)
				.write(new Std140Counter(), new FakeWorld());

		assertEquals(List.of("bare 0", "single 0", "many 0", "many 1", "many 2"), asked);
	}

	private static UniformSource recording(String name, List<String> asked) {
		return new UniformSource() {

			@Override
			public void read(WorldState world, Val out) {
				asked.add(name + " short");
				out.set(1.0F);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				asked.add(name + " " + element);
				out.set(1.0F);
			}
		};
	}

	@Test
	void tellsTheSinkWhichMemberIsComingWithItsElementsAndWhetherItIsAnswered() {
		List<String> told = new ArrayList<>();
		UniformSink sink = new BytesSink(1024) {

			@Override
			public UniformSink member(String name, int elements, boolean supplied) {
				told.add(name + " " + elements + " " + supplied);

				return this;
			}
		};
		UniformCatalog catalog = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F))
				.add("t", UniformShape.MAT4, (world, out) -> out.set(new Matrix4f()))
				.build();

		new UniformBlock(members("float a", "float lost", "mat4 t[8]", "float x[1]"), catalog)
				.write(sink, new FakeWorld());

		assertEquals(List.of("a 1 true", "lost 1 false", "t 8 true", "x 1 false"), told);
	}

	@Test
	void writesTheSameBytesEveryTimeItIsAsked() {
		List<TranslatedUnit.Uniform> declared = members(KITCHEN_SINK);
		UniformBlock block = new UniformBlock(declared, catalogFor(declared, List.of("vectorA", "points")));

		BytesSink first = new BytesSink(block.size());
		BytesSink second = new BytesSink(block.size());
		block.write(first, new FakeWorld());
		block.write(second, new FakeWorld());

		assertArrayEquals(first.array(), second.array());
	}

	@Test
	void aValueLeftInTheCarrierByTheMemberBeforeNeverReachesTheNextOne() {
		// A mat4 fills the carrier's sixteen numbers, then a scalar source answers a vec4 member:
		// the last three components are zeroes and not the matrix's.
		UniformCatalog catalog = UniformCatalog.builder()
				.add("matrix", UniformShape.MAT4, probe(UniformShape.MAT4, 1))
				.add("scalar", UniformShape.FLOAT, (world, out) -> out.set(5.0F))
				.build();

		BytesSink sink = new BytesSink(256);
		new UniformBlock(members("mat4 matrix", "vec4 scalar"), catalog).write(sink, new FakeWorld());

		assertEquals(5.0F, sink.floatAt(64));
		assertEquals(0.0F, sink.floatAt(68));
		assertEquals(0.0F, sink.floatAt(72));
		assertEquals(0.0F, sink.floatAt(76));
	}

	@Test
	void theTypeThePackDeclaredDecidesWhatIsWrittenAndNotTheName() {
		// Registered once as an integer flag, declared by three packs as a bool, an int and a float.
		UniformCatalog catalog = UniformCatalog.builder()
				.add("hideGUI", UniformShape.INT, (world, out) -> out.set(true))
				.build();

		for (String declaration : List.of("bool hideGUI", "int hideGUI", "uint hideGUI")) {
			BytesSink sink = new BytesSink(16);
			new UniformBlock(members(declaration), catalog).write(sink, new FakeWorld());
			assertEquals(1, sink.intAt(0), declaration);
		}

		BytesSink asFloat = new BytesSink(16);
		new UniformBlock(members("float hideGUI"), catalog).write(asFloat, new FakeWorld());
		assertEquals(1.0F, asFloat.floatAt(0), "float hideGUI");

		BytesSink asVector = new BytesSink(16);
		new UniformBlock(members("vec2 hideGUI"), catalog).write(asVector, new FakeWorld());
		assertEquals(1.0F, asVector.floatAt(0));
		assertEquals(0.0F, asVector.floatAt(4), "zero filled");
	}

	@Test
	void measuringDoesNotAskAnySourceAnything() {
		List<String> asked = new ArrayList<>();
		UniformCatalog catalog = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, recording("a", asked))
				.build();

		new UniformBlock(members("float a", "float a"), catalog);

		assertEquals(List.of(), asked, "the size is what a write of zeroes costs, no world involved");
	}

	@Test
	void aBlockWrittenWithNoWorldIsAllZeroesAndAllUnanswered() {
		List<TranslatedUnit.Uniform> declared = members("float a", "vec3 b[2]");
		UniformCatalog catalog = UniformCatalog.builder()
				.add("a", UniformShape.FLOAT, (world, out) -> out.set(1.0F))
				.add("b", UniformShape.VEC3, (world, out) -> out.set(1.0F, 2.0F, 3.0F))
				.build();

		TextSink text = new TextSink();
		new UniformBlock(declared, catalog).write(text, null);

		assertEquals("""
				a = 0.00000   <- nothing supplies this
				b[0] = (0.00000, 0.00000, 0.00000)   <- nothing supplies this
				b[1] = (0.00000, 0.00000, 0.00000)   <- nothing supplies this
				""", text.text());
	}
}
