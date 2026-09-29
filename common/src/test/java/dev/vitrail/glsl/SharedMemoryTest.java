package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Holds {@link SharedMemory} to the two rules it is written from: Metal refuses a kernel holding
 * more than 32768 bytes of threadgroup memory, and SPIRV-Cross lays every element of an array out at
 * the stride of its std430 type, which is sixteen bytes for a {@code vec3} and for the {@code
 * vec4} and eight for a {@code vec2}.
 * <p>
 * The sizes are worked out by hand from those two rules. The text it hands back is what the compute
 * stage is compiled from when the shared variables do not fit, so what has to survive is every
 * other byte of the stage, in place: a span blanked keeps its length, and the line breaks in it.
 */
class SharedMemoryTest {

	private static final String BARRIER = "#define barrier() (memoryBarrierBuffer(), barrier())";

	private static SharedMemory.Reading read(String source) {
		return SharedMemory.read(source);
	}

	@Test
	void photonsSkyLightIsThirtySixThousandEightHundredAndSixtyFourBytesAndMoves() {
		// 256 * 9 elements of sixteen bytes: a vec3 takes a whole vec4 as an element, in Metal and in std430.
		SharedMemory.Reading reading = read("""
				#version 450
				layout(local_size_x = 256) in;
				shared vec3 shared_memory[256][9];
				void main() { barrier(); }
				""");

		assertEquals(36864L, reading.threadgroupBytes());
		assertEquals(36864L, reading.bufferBytes());
		assertTrue(reading.over());
		assertNull(reading.unread());
		String moved = reading.moved();
		assertNotNull(moved);
		assertTrue(moved.contains("layout(std430) coherent buffer OfSharedMemory { vec3 shared_memory [ 256 ] [ 9 ]; };"), moved);
		assertFalse(moved.contains("shared vec3"), moved);
		// The barrier is redefined in the line after the version, ahead of every use.
		assertTrue(moved.contains("#version 450\n" + BARRIER + "\n"), moved);
		assertTrue(moved.contains("layout(local_size_x = 256) in;"), moved);
		assertTrue(moved.contains("void main() { barrier(); }"), moved);
	}

	@Test
	void whatFitsIsLeftAloneAndOnlyMeasured() {
		SharedMemory.Reading reading = read("#version 450\nshared float a[64];\nvoid main() { }\n");

		assertEquals(256L, reading.threadgroupBytes());
		assertFalse(reading.over());
		assertNull(reading.moved());
		assertNull(reading.unread());
	}

	@Test
	void theLimitIsTheLastByteMetalAllowsAndNotOneOver() {
		assertNull(read("shared float a[8192];").moved());
		assertEquals(32768L, read("shared float a[8192];").threadgroupBytes());
		assertFalse(read("shared float a[8192];").over());

		assertNotNull(read("shared float a[8193];").moved());
		assertTrue(read("shared float a[8193];").over());
		assertEquals(32772L, read("shared float a[8193];").threadgroupBytes());
	}

	@Test
	void theStrideOfAnElementIsTheAlignmentOfItsTypeWithAVec3RoundedUpToAVec4() {
		assertEquals(4L * 100, read("shared float a[100];").threadgroupBytes());
		assertEquals(8L * 100, read("shared vec2 a[100];").threadgroupBytes());
		assertEquals(16L * 100, read("shared vec3 a[100];").threadgroupBytes());
		assertEquals(16L * 100, read("shared vec4 a[100];").threadgroupBytes());
		assertEquals(8L * 100, read("shared ivec2 a[100];").threadgroupBytes());
		assertEquals(16L * 100, read("shared uvec3 a[100];").threadgroupBytes());
		assertEquals(4L * 100, read("shared uint a[100];").threadgroupBytes());
		assertEquals(4L * 100, read("shared int a[100];").threadgroupBytes());
	}

	@Test
	void aMatrixIsItsColumnsAndTheStrideOfAnArrayOfThemIsTheWholeMatrix() {
		// A column of two floats is eight bytes, a column of three or four is sixteen.
		assertEquals(2 * 8L * 10, read("shared mat2 m[10];").threadgroupBytes());
		assertEquals(3 * 16L * 10, read("shared mat3 m[10];").threadgroupBytes());
		assertEquals(4 * 16L * 10, read("shared mat4 m[10];").threadgroupBytes());
		assertEquals(2 * 16L * 10, read("shared mat2x3 m[10];").threadgroupBytes());
		assertEquals(3 * 8L * 10, read("shared mat3x2 m[10];").threadgroupBytes());
		assertEquals(4 * 16L * 10, read("shared mat4x3 m[10];").threadgroupBytes());
	}

	@Test
	void everyDeclaratorOfAStatementCountsAndAProductOfLiteralsIsACount() {
		assertEquals(4L * (10 + 20), read("shared float a[10], b[20];").threadgroupBytes());
		assertEquals(4L * 1000, read("shared float cache[10 * 10 * 10];").threadgroupBytes());
		assertEquals(4L * 6, read("shared float a[2][3];").threadgroupBytes());
		assertEquals(16L * 6, read("shared vec4[2] a[3];").threadgroupBytes());
		assertEquals(4L * 5, read("shared float a[5u];").threadgroupBytes());
	}

	@Test
	void aPlainVariableIsOneElementOfThreadgroupMemoryAndItsOwnSizeInTheBuffer() {
		SharedMemory.Reading reading = read("shared vec3 v;");

		// Metal gives it a float3 of sixteen bytes; std430 gives the vec3 twelve, and the block a multiple of sixteen.
		assertEquals(16L, reading.threadgroupBytes());
		assertEquals(16L, reading.bufferBytes());
		// And a float right behind it sits in the vec3's own padding, at twelve, as std430 has it.
		assertEquals(16L, read("shared vec3 v;\nshared float f;\n").bufferBytes());
		assertEquals(16L + 4L, read("shared vec3 v;\nshared float f;\n").threadgroupBytes());
	}

	@Test
	void theBufferIsLaidOutUnderStd430WithEachMemberAtItsAlignment() {
		// A float array of three ends at twelve, a vec4 array starts at the next sixteen and takes thirty two.
		SharedMemory.Reading reading = read("shared float a[3];\nshared vec4 b[2];\n");

		assertEquals(12L + 32L, reading.threadgroupBytes());
		assertEquals(48L, reading.bufferBytes());
	}

	@Test
	void qualifiersThatDoNotChangeTheLayoutAreStepOverAndKeptInTheBlock() {
		SharedMemory.Reading reading = read("shared highp vec4 a[3000];\nprecise shared float b[4];\n");

		assertEquals(16L * 3000 + 16, reading.threadgroupBytes());
		String moved = reading.moved();
		assertNotNull(moved);
		assertTrue(moved.contains("highp vec4 a [ 3000 ];"), moved);
		assertTrue(moved.contains("precise float b [ 4 ];"), moved);
	}

	@Test
	void theFirstDeclarationBecomesTheBlockAndTheOthersAreBlankedWhereTheyStoodLineBreaksKept() {
		String source = "#version 450\nshared vec4 a[1500];\nint between;\nshared vec4 b[1000];\nvoid main() { }\n";

		String moved = read(source).moved();

		assertNotNull(moved);
		assertTrue(moved.contains("buffer OfSharedMemory { vec4 a [ 1500 ]; vec4 b [ 1000 ]; };"), moved);
		assertTrue(moved.contains("int between;"), moved);
		assertFalse(moved.contains("shared"), moved);
		// The second declaration is spaces now, so every line after it keeps its number.
		assertEquals(source.lines().count() + 1, moved.lines().count());
		assertTrue(moved.contains("\n" + " ".repeat("shared vec4 b[1000];".length()) + "\nvoid main() { }"), moved);
	}

	@Test
	void aDeclarationSpreadOverSeveralLinesIsBlankedLineForLine() {
		String source = "shared vec4 a[1500];\nshared vec4 b[\n1000\n];\nvoid main() { }\n";

		String moved = read(source).moved();

		assertNotNull(moved);
		// One line more for the barrier, and the function is on the line it was on, shifted by that one.
		assertEquals(source.lines().count() + 1, moved.lines().count());
		assertEquals(moved.lines().toList().size() - 1, moved.lines().toList().indexOf("void main() { }"));
	}

	@Test
	void withNoVersionLineTheBarrierIsWrittenAtTheTop() {
		String moved = read("shared float a[9000];\nvoid main() { }\n").moved();

		assertNotNull(moved);
		assertTrue(moved.startsWith(BARRIER + "\n"), moved);
	}

	@Test
	void aTypeItCannotSizeIsNamedAndTheStageIsLeftAsItStands() {
		for (String declaration : new String[] {"shared Foo data[4]", "shared bool flags[4]", "shared float a[N]",
			"shared float a[0]", "shared float a[]", "shared float a[10 + 1]", "shared double d[4]",
			"shared float a[99999999999 * 99999999999]", "shared float a[99999999999999999999]", "shared float"}) {
			SharedMemory.Reading reading = read("void main() { }\n" + declaration + ";\nvoid other() { }\n");

			assertEquals(-1L, reading.threadgroupBytes(), declaration);
			assertEquals(declaration, reading.unread(), declaration);
			assertNull(reading.moved(), declaration);
			assertFalse(reading.over(), "an unread stage is not over: " + declaration);
		}
	}

	@Test
	void aDirectiveInsideAStatementLosesTheSpanItSitsInSoTheStatementIsNamedUnread() {
		SharedMemory.Reading reading = read("shared float a[\n#define N 4\n4];\n");

		assertEquals(-1L, reading.threadgroupBytes());
		assertNotNull(reading.unread());
	}

	@Test
	void aDirectiveLineOnItsOwnIsNotReadAsTextAndAShadedWordIsNotADeclaration() {
		SharedMemory.Reading reading = read("#define shared_thing 4\nlayout(shared) uniform Foo { float x; };\n"
				+ "layout(shared) uniform float y;\nint shared_count;\n");

		assertEquals(0L, reading.threadgroupBytes());
		assertNull(reading.moved());
		assertNull(reading.unread());
	}

	@Test
	void aDeclarationAfterAFunctionBodyIsStillReadAndOneInsideItIsNot() {
		assertEquals(4L * 10, read("void f() { int x; }\nshared float a[10];\n").threadgroupBytes());
		assertEquals(0L, read("void f() { shared float a[10]; }\n").threadgroupBytes());
	}

	@Test
	void anEmptyOrHugeTextIsMeasuredAsNothingAndNothingMoves() {
		assertEquals(0L, read("").threadgroupBytes());
		assertNull(read("").moved());

		String many = "shared float a[1];\n".repeat(5_000);
		SharedMemory.Reading reading = read(many);
		assertEquals(4L * 5_000, reading.threadgroupBytes());
		assertNull(reading.moved());
	}

	@Test
	void mentionedAsksForTheWholeWordAnywhereEvenInAComment() {
		assertTrue(SharedMemory.mentioned("shared float a;"));
		assertTrue(SharedMemory.mentioned("// shared"));
		assertTrue(SharedMemory.mentioned("x;shared\tfloat"));
		assertFalse(SharedMemory.mentioned("sharedMemory"));
		assertFalse(SharedMemory.mentioned("unshared"));
		assertFalse(SharedMemory.mentioned("shared_thing"));
		assertFalse(SharedMemory.mentioned(""));
	}

	@Test
	void theLimitAndTheBlockNameAreTheOnesTheDispatchAndMetalUse() {
		assertEquals(32768L, SharedMemory.THREADGROUP_BYTES);
		assertEquals("OfSharedMemory", SharedMemory.BLOCK);
	}
}
