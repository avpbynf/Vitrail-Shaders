package dev.vitrail.uniform;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.vitrail.glsl.TranslatedUnit;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Holds the fill of a uniform block to what it is measured to be: allocation free. A frame writes
 * the block once per pass and a pack has forty passes, so a source that allocated (a boxed number, a
 * temporary vector, a string key, a lambda made on the call) would allocate two hundred times per
 * pass.
 * <p>
 * The measure is the bytes the thread allocated, not the time it took: it is exact where a clock is
 * noise, and it does not depend on the machine. The bound is not nought only because an interpreter
 * that has not compiled the walk yet makes one small iterator for the member list; a source that
 * allocated per member would cost many times that.
 */
class UniformBlockAllocationTest {

	/**
	 * Measured at 32 bytes a write with the JIT switched off (the one iterator the walk makes over its
	 * member list) and none once it has compiled the walk, so this is twice the worst it can be. One
	 * boxed number or one temporary vector per member would be a hundred and forty five of them, four
	 * thousand bytes and up.
	 */
	private static final int BYTES_PER_WRITE = 64;

	private static final int WRITES = 2000;

	/** Every name the tables answer, at its natural shape, but the three that read the wall clock. */
	private static UniformBlock blockOf(UniformCatalog catalog) {
		List<String> names = new ArrayList<>(catalog.names());
		Collections.sort(names);
		names.removeAll(List.of("currentDate", "currentTime", "currentYearTime"));

		List<TranslatedUnit.Uniform> members = new ArrayList<>();
		for (String name : names) {
			String declaration = BlockCheck.type(catalog.natural(name)) + " " + name
					+ (name.equals("of_TextureMatrix") ? "[8]" : "");
			members.add(TranslatedUnit.Uniform.of(name, declaration));
		}

		return new UniformBlock(members, catalog);
	}

	private static long allocated() {
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

		return bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
	}

	private static void assumeMeasurable() {
		assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
				&& bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled());
	}

	@Test
	void everyPassOfAFrameWritesTheBlockWithoutAllocating() {
		assumeMeasurable();
		UniformBlock block = blockOf(UniformCatalog.geometry());
		FakeWorld world = FakeWorld.distinct();
		BytesSink sink = new BytesSink(block.size());

		for (int i = 0; i < 3000; i++) {
			sink.rewind();
			block.write(sink, world);
		}

		long before = allocated();
		for (int i = 0; i < WRITES; i++) {
			sink.rewind();
			block.write(sink, world);
		}
		long bytes = allocated() - before;

		assertTrue(bytes <= (long) WRITES * BYTES_PER_WRITE,
				bytes + " bytes for " + WRITES + " writes of " + block.size() + " bytes each");
	}

	@Test
	void aFrameThatMovesEverythingTheSkyAndTheSmoothersDependOnAllocatesNothingEither() {
		assumeMeasurable();
		UniformBlock block = blockOf(UniformCatalog.shadowGeometry());
		FakeWorld world = FakeWorld.distinct();
		BytesSink sink = new BytesSink(block.size());

		for (int i = 0; i < 3000; i++) {
			step(world);
			sink.rewind();
			block.write(sink, world);
		}

		long before = allocated();
		for (int i = 0; i < WRITES; i++) {
			step(world);
			sink.rewind();
			block.write(sink, world);
		}
		long bytes = allocated() - before;

		assertTrue(bytes <= (long) WRITES * BYTES_PER_WRITE, bytes + " bytes for " + WRITES + " moving frames");
	}

	/** The next frame: a new number, a turned head and a sun that has moved, so the caches rebuild. */
	private static void step(FakeWorld world) {
		world.frameCounter++;
		world.sunAngleDegrees += 0.01F;
		world.gbufferModelView.rotateY(0.001F);
		world.passModelView.rotateY(0.001F);
		world.rainStrength = (world.frameCounter % 200) / 200.0F;
	}
}
