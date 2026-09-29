package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link Std140Counter} to the std140 rules as the specification words them: what each kind of
 * put aligns on and what it consumes, from every offset a previous member can leave the cursor at.
 * <p>
 * The expected numbers are the table below and a round up, never the counter's own output. The
 * counter is the one place besides the game's builder where the layout is written down, so it is
 * checked against a third writer, {@link BytesSink}, over seeded random sequences as well.
 */
class Std140CounterTest {

	/** One kind of put, and what std140 says about it: alignment and bytes consumed. */
	private record Put(String name, Consumer<UniformSink> call, int alignment, int size) {

		@Override
		public String toString() {
			return this.name;
		}
	}

	private static final List<Put> PUTS = List.of(
			new Put("float", s -> s.putFloat(1.0F), 4, 4),
			new Put("int", s -> s.putInt(1), 4, 4),
			new Put("vec2", s -> s.putVec2(1.0F, 2.0F), 8, 8),
			new Put("ivec2", s -> s.putIVec2(1, 2), 8, 8),
			// Sixteen to start on and TWELVE consumed: the member after it starts at twelve if it can.
			new Put("vec3", s -> s.putVec3(1.0F, 2.0F, 3.0F), 16, 12),
			new Put("ivec3", s -> s.putIVec3(1, 2, 3), 16, 12),
			new Put("vec4", s -> s.putVec4(1.0F, 2.0F, 3.0F, 4.0F), 16, 16),
			new Put("ivec4", s -> s.putIVec4(1, 2, 3, 4), 16, 16),
			new Put("mat3", s -> s.putMat3(new Matrix3f()), 16, 48),
			new Put("mat4", s -> s.putMat4(new Matrix4f()), 16, 64));

	/** Every offset a member of size four, eight or twelve can leave the cursor at, and a few more. */
	private static final int[] STARTS = { 0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 44, 60 };

	@Test
	void startsAtZeroAndCountsNothingUntilWritten() {
		assertEquals(0, new Std140Counter().size());
	}

	@Test
	void everyPutAlignsThenConsumesFromEveryOffset() {
		for (Put put : PUTS) {
			for (int start : STARTS) {
				Std140Counter counter = at(start);
				put.call().accept(counter);

				assertEquals(roundUp(start, put.alignment()) + put.size(), counter.size(),
						put + " written from offset " + start);
			}
		}
	}

	@Test
	void theMemberAfterAVec3StartsAtTwelveWhenItCan() {
		// vec3 a; float b;  b is at twelve, so the pair is sixteen long and not twenty.
		Std140Counter counter = new Std140Counter();
		counter.putVec3(1, 2, 3).putFloat(4);
		assertEquals(16, counter.size());

		// ivec3 a; int b; the same, for the integer three vector.
		counter = new Std140Counter();
		counter.putIVec3(1, 2, 3).putInt(4);
		assertEquals(16, counter.size());

		// vec3 a; vec2 b;  b needs eight and twelve is not, so it is at sixteen and ends at twenty four.
		counter = new Std140Counter();
		counter.putVec3(1, 2, 3).putVec2(4, 5);
		assertEquals(24, counter.size());

		// vec3 a; vec3 b;  b aligns up to sixteen, ends at twenty eight.
		counter = new Std140Counter();
		counter.putVec3(1, 2, 3).putVec3(4, 5, 6);
		assertEquals(28, counter.size());

		// float a; vec3 b;  b at sixteen, ends at twenty eight.
		counter = new Std140Counter();
		counter.putFloat(1).putVec3(2, 3, 4);
		assertEquals(28, counter.size());
	}

	@Test
	void aMat3IsThreeColumnsOfSixteenAndTheNextMemberStartsBehindThem() {
		Std140Counter counter = new Std140Counter();
		counter.putMat3(new Matrix3f()).putFloat(1.0F);

		assertEquals(52, counter.size(), "forty eight for the matrix, then the float at forty eight");

		counter = new Std140Counter();
		counter.putFloat(1.0F).putMat3(new Matrix3f());
		assertEquals(64, counter.size(), "the matrix starts at sixteen");
	}

	@Test
	void alignRoundsUpAndLeavesAnAlignedCursorAlone() {
		Std140Counter counter = new Std140Counter();
		counter.align(16);
		assertEquals(0, counter.size());

		counter.putFloat(1.0F).align(4);
		assertEquals(4, counter.size());

		counter.align(16);
		assertEquals(16, counter.size());

		counter.putVec3(1, 2, 3).align(16);
		assertEquals(32, counter.size(), "a three vector padded out to its stride, as a matrix column is");

		counter.putFloat(1.0F).align(8);
		assertEquals(40, counter.size());
	}

	@Test
	void theValuesAndTheNamesDoNotChangeTheCount() {
		Std140Counter plain = new Std140Counter();
		Std140Counter odd = new Std140Counter();
		odd.member("anything", 3, false);

		plain.putFloat(1.0F).putVec3(1, 2, 3).putMat4(new Matrix4f());
		odd.putFloat(Float.NaN).putVec3(Float.POSITIVE_INFINITY, -0.0F, 1e30F).putMat4(new Matrix4f().zero());

		assertEquals(plain.size(), odd.size());
	}

	@Test
	void everyPutHandsBackTheSameSinkSoCallsChain() {
		Std140Counter counter = new Std140Counter();
		for (Put put : PUTS) {
			put.call().accept(counter);
		}

		assertSame(counter, counter.putFloat(1.0F));
		assertSame(counter, counter.putVec2(1.0F, 2.0F));
		assertSame(counter, counter.putVec3(1.0F, 2.0F, 3.0F));
		assertSame(counter, counter.align(16));
		assertSame(counter, counter.member("x", 1, true));
	}

	@Test
	void agreesWithAnIndependentWriterOverRandomSequences() {
		Random random = new Random(140);
		for (int round = 0; round < 500; round++) {
			Std140Counter counter = new Std140Counter();
			BytesSink bytes = new BytesSink(4096);

			int length = 1 + random.nextInt(40);
			for (int i = 0; i < length; i++) {
				int pick = random.nextInt(PUTS.size() + 1);
				if (pick == PUTS.size()) {
					int alignment = 1 << random.nextInt(5);
					counter.align(alignment);
					bytes.align(alignment);
				} else {
					PUTS.get(pick).call().accept(counter);
					PUTS.get(pick).call().accept(bytes);
				}

				assertEquals(bytes.position(), counter.size(), "round " + round + " step " + i);
			}
		}
	}

	private static Std140Counter at(int offset) {
		Std140Counter counter = new Std140Counter();
		for (int i = 0; i < offset; i += 4) {
			counter.putFloat(0.0F);
		}

		return counter;
	}

	private static int roundUp(int value, int multiple) {
		return (value + multiple - 1) / multiple * multiple;
	}
}
