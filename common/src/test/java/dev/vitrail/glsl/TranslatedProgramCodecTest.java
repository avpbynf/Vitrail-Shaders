package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.ProgramStage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link TranslatedProgramCodec} to the two things {@link TranslationCache} leans on: what is
 * written comes back as the same program, and anything else comes back as an {@link IOException}
 * and never as another kind of failure or as a program.
 * <p>
 * The layout is pinned byte for byte against a second writer in this file that spells the fields
 * out in the order the records declare them. A layout that changes without {@code FORMAT} moving
 * would serve the old blobs under the new reading, and the digest would call them whole.
 */
class TranslatedProgramCodecTest {

	private static ProgramTranslator.TranslatedProgram roundTrip(ProgramTranslator.TranslatedProgram program)
			throws IOException {
		byte[] blob = TranslatedProgramCodec.write(program);

		return TranslatedProgramCodec.read(blob, blob.length, program.inputs());
	}

	/** The pieces a blob is made of, written the way the format describes them. */
	private static final class Blob {

		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final DataOutputStream out = new DataOutputStream(this.bytes);

		Blob i(int value) throws IOException {
			this.out.writeInt(value);

			return this;
		}

		Blob text(String value) throws IOException {
			byte[] raw = value.getBytes(StandardCharsets.UTF_8);
			this.out.writeInt(raw.length);
			this.out.write(raw);

			return this;
		}

		Blob names(String... values) throws IOException {
			i(values.length);
			for (String value : values) {
				text(value);
			}

			return this;
		}

		byte[] done() {
			return this.bytes.toByteArray();
		}
	}

	@Test
	void writesTheLayoutOfTheFormatByteForByte() throws IOException {
		Blob expected = new Blob();
		expected.text("FULLSCREEN");
		expected.i(1).text("FRAGMENT");
		// The unit: entry, stage, text, then the notes, which are the record's components in order.
		expected.text("main").text("FRAGMENT").text("ab");
		for (int count = 1; count <= 18; count++) {
			expected.i(count);
		}

		expected.names("n1").names().names("h").names();
		expected.i(1).text("B").i(3);
		for (int count = 19; count <= 23; count++) {
			expected.i(count);
		}

		expected.i(2).i(0).i(1);
		expected.i(1).text("u").text("float").text("float u");
		expected.i(0);
		// The program: its uniforms, its samplers, the names sampled, the inputs synthesised.
		expected.i(0);
		expected.i(1).text("s").text("sampler2D").text("sampler2D s");
		expected.names("s");
		expected.i(1).text("k").text("v");

		byte[] written = TranslatedProgramCodec.write(SampleTranslations.program(VertexInputs.FULLSCREEN, "ab"));

		assertArrayEquals(expected.done(), written);
	}

	@Test
	void writesTheSmallestProgramInTwentyNineBytes() throws IOException {
		ProgramTranslator.TranslatedProgram nothing = new ProgramTranslator.TranslatedProgram(Map.of(),
				List.of(), List.of(), Set.of(), Map.of(), VertexInputs.WORLD);

		byte[] blob = TranslatedProgramCodec.write(nothing);

		// A length and the five letters of WORLD, then five counts of nothing.
		assertArrayEquals(new Blob().text("WORLD").i(0).i(0).i(0).i(0).i(0).done(), blob);
		assertEquals(29, blob.length);
	}

	@Test
	void theFormatTagIsPartOfTheKeyAndIsBumpedByHandWithTheLayout() {
		assertEquals("vitrail-translation-6", TranslatedProgramCodec.FORMAT);
	}

	@Test
	void readsBackTheSameProgramItWrote() throws IOException {
		ProgramTranslator.TranslatedProgram program = SampleTranslations.program(VertexInputs.TERRAIN, "void main() {}");

		assertEquals(program, roundTrip(program));
	}

	@Test
	void readsBackEveryAwkwardStringAndBothStagesAndTheEmptyOnes() throws IOException {
		ProgramTranslator.TranslatedProgram program = SampleTranslations.busy();
		ProgramTranslator.TranslatedProgram back = roundTrip(program);

		assertEquals(program.stages(), back.stages());
		assertEquals(program.uniforms(), back.uniforms());
		assertEquals(program.samplers(), back.samplers());
		assertEquals(program.sampled(), back.sampled());
		assertEquals(program.synthesized(), back.synthesized());
		assertEquals(program.inputs(), back.inputs());
	}

	@Test
	void keepsTheOrderOfTheBlockAndOfTheSamplersBecauseTheOrderIsTheLayout() throws IOException {
		ProgramTranslator.TranslatedProgram back = roundTrip(SampleTranslations.busy());

		assertEquals(List.of("a", "b"), back.uniforms().stream().map(u -> u.name()).toList());
		assertEquals(List.of("colortex0", "noSpace"), back.samplers().stream().map(u -> u.name()).toList());
		assertEquals(List.of(0, 3, Integer.MAX_VALUE, Integer.MIN_VALUE, -1),
				back.stages().get(ProgramStage.FRAGMENT).drawBuffers());
	}

	@Test
	void everyNoteComesBackInItsOwnField() throws IOException {
		ProgramTranslator.TranslatedProgram back = roundTrip(SampleTranslations.program(VertexInputs.TERRAIN, "x"));

		assertEquals(SampleTranslations.notes(), back.stages().get(ProgramStage.FRAGMENT).notes());
	}

	@Test
	void aTextPastTheLimitOfWriteUtfComesBack() throws IOException {
		// writeUTF counts in a short: this one is more than three times what it takes.
		String big = "vec4 c = texture(colortex0, uv); // \u00e9\n".repeat(6_000);
		assertTrue(big.length() > 200_000);

		ProgramTranslator.TranslatedProgram back = roundTrip(SampleTranslations.program(VertexInputs.TERRAIN, big));

		assertEquals(big, back.stages().get(ProgramStage.FRAGMENT).text());
	}

	@Test
	void writesTheSameBytesTwiceForTheSameProgram() throws IOException {
		ProgramTranslator.TranslatedProgram program = SampleTranslations.busy();

		assertArrayEquals(TranslatedProgramCodec.write(program), TranslatedProgramCodec.write(program));
	}

	/**
	 * A lone surrogate is the one string that does not come back: it is written as the question mark
	 * UTF-8 substitutes, so a program served from disk would differ from the one translated. No
	 * pack file decodes to one, since a malformed byte becomes U+FFFD, so this is a limit of the
	 * format and not a bug anyone can meet.
	 */
	@Test
	void characterizes_aLoneSurrogateComesBackAsAQuestionMark() throws IOException {
		ProgramTranslator.TranslatedProgram back = roundTrip(SampleTranslations.program(VertexInputs.TERRAIN, "a\uD83Db"));

		assertEquals("a?b", back.stages().get(ProgramStage.FRAGMENT).text());
	}

	@Test
	void readsOnlyTheLengthItIsToldAndIgnoresWhatFollowsIt() throws IOException {
		ProgramTranslator.TranslatedProgram program = SampleTranslations.program(VertexInputs.TERRAIN, "ab");
		byte[] blob = TranslatedProgramCodec.write(program);
		byte[] padded = Arrays.copyOf(blob, blob.length + 100);
		Arrays.fill(padded, blob.length, padded.length, (byte) 0x7F);

		assertEquals(program, TranslatedProgramCodec.read(padded, blob.length, VertexInputs.TERRAIN));
	}

	@Test
	void refusesABlobMadeForAnotherVertexFormat() throws IOException {
		byte[] blob = TranslatedProgramCodec.write(SampleTranslations.program(VertexInputs.TERRAIN, "ab"));

		IOException refused = assertThrows(IOException.class,
				() -> TranslatedProgramCodec.read(blob, blob.length, VertexInputs.ENTITY));

		assertTrue(refused.getMessage().contains("TERRAIN"), refused.getMessage());
	}

	@Test
	void refusesEveryProperPrefixOfABlobWithAnIoException() throws IOException {
		byte[] blob = TranslatedProgramCodec.write(SampleTranslations.busy());

		for (int length = 0; length < blob.length; length++) {
			int cut = length;
			assertThrows(IOException.class, () -> TranslatedProgramCodec.read(blob, cut, VertexInputs.TERRAIN),
					"a blob cut at " + cut + " of " + blob.length);
		}
	}

	@Test
	void refusesAStringThatClaimsMoreThanTheBlobHasLeftWithoutAllocatingIt() throws IOException {
		byte[] blob = TranslatedProgramCodec.write(SampleTranslations.program(VertexInputs.TERRAIN, "ab"));

		// The first word is the length of the vertex format's name.
		for (int claim : new int[] {Integer.MAX_VALUE, blob.length, -1, Integer.MIN_VALUE}) {
			byte[] damaged = blob.clone();
			damaged[0] = (byte) (claim >>> 24);
			damaged[1] = (byte) (claim >>> 16);
			damaged[2] = (byte) (claim >>> 8);
			damaged[3] = (byte) claim;

			IOException refused = assertThrows(IOException.class,
					() -> TranslatedProgramCodec.read(damaged, damaged.length, VertexInputs.TERRAIN));
			assertTrue(refused.getMessage().contains("claims a string of"), refused.getMessage());
		}
	}

	@Test
	void refusesAStageNamedForNothingTheEnumHasAsAnIoException() throws IOException {
		byte[] blob = TranslatedProgramCodec.write(SampleTranslations.program(VertexInputs.TERRAIN, "ab"));
		int at = indexOf(blob, "FRAGMENT".getBytes(StandardCharsets.UTF_8));
		blob[at] = 'X';

		IOException refused = assertThrows(IOException.class,
				() -> TranslatedProgramCodec.read(blob, blob.length, VertexInputs.TERRAIN));

		assertTrue(refused.getCause() instanceof IllegalArgumentException, String.valueOf(refused.getCause()));
	}

	@Test
	void aCountOfBillionsRunsOutOfBlobAndNeverOutOfMemory() throws IOException {
		byte[] blob = TranslatedProgramCodec.write(SampleTranslations.program(VertexInputs.TERRAIN, "ab"));
		int stageCount = 4 + "TERRAIN".length();
		blob[stageCount] = 0x7F;
		blob[stageCount + 1] = (byte) 0xFF;
		blob[stageCount + 2] = (byte) 0xFF;
		blob[stageCount + 3] = (byte) 0xFF;

		assertThrows(IOException.class, () -> TranslatedProgramCodec.read(blob, blob.length, VertexInputs.TERRAIN));
	}

	@Test
	void anyDamageIsAProgramOrAnIoExceptionNeverAnotherFailure() throws IOException {
		byte[] blob = TranslatedProgramCodec.write(SampleTranslations.busy());
		Random random = new Random(0xBAD);
		int programs = 0;
		int refusals = 0;

		for (int round = 0; round < 20_000; round++) {
			byte[] damaged = blob.clone();
			for (int hit = 1 + random.nextInt(4); hit > 0; hit--) {
				int at = random.nextInt(damaged.length);
				damaged[at] = switch (random.nextInt(3)) {
					case 0 -> (byte) random.nextInt(256);
					case 1 -> (byte) 0xFF;
					default -> 0;
				};
			}

			int length = random.nextInt(8) == 0 ? random.nextInt(damaged.length + 1) : damaged.length;
			try {
				TranslatedProgramCodec.read(damaged, length, VertexInputs.TERRAIN);
				programs++;
			} catch (IOException e) {
				refusals++;
			}
		}

		// Damage to the text of a stage reads as a program, and damage to a length or a count does not.
		assertTrue(programs > 0 && refusals > 0, programs + " programs, " + refusals + " refusals");
		assertNotEquals(0, programs + refusals);
	}

	@Test
	void anInputsNameThatIsNotInTheBlobIsNotBelievedFromTheKey() throws IOException {
		byte[] blob = TranslatedProgramCodec.write(SampleTranslations.program(VertexInputs.FULLSCREEN, "ab"));
		List<VertexInputs> wrong = new ArrayList<>(List.of(VertexInputs.values()));
		wrong.remove(VertexInputs.FULLSCREEN);

		for (VertexInputs other : wrong) {
			assertThrows(IOException.class, () -> TranslatedProgramCodec.read(blob, blob.length, other), other.name());
		}
	}

	private static int indexOf(byte[] whole, byte[] part) {
		outer:
		for (int at = 0; at + part.length <= whole.length; at++) {
			for (int offset = 0; offset < part.length; offset++) {
				if (whole[at + offset] != part[offset]) {
					continue outer;
				}
			}

			return at;
		}

		throw new AssertionError("not found");
	}
}
