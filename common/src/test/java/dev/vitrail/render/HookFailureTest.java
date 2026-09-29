package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

/**
 * Holds what a hook of the chain does with an exception that got out of it: which steps run, in
 * which order, and what a step that throws does to the ones behind it.
 * <p>
 * The steps are fakes that write into one list, so the order is what the list says. The sites this
 * class serves are inline catches on a game that cannot be started here, and
 * {@link #matchesTheCatchItReplaced} holds the helper against that catch written out once more,
 * over every combination of failing steps.
 */
class HookFailureTest {

	/** The device loss of the game being built, which has no name that is the same on both. */
	private static final List<String> DEVICE_LOSS = List.of(
			"com.mojang.blaze3d.GpuDeviceLossException",
			"com.mojang.renderpearl.api.device.GpuDeviceLossException");

	private final List<String> events = new ArrayList<>();
	private final List<Throwable> logged = new ArrayList<>();
	private final List<String> texts = new ArrayList<>();

	private final BiConsumer<String, Throwable> log = (text, error) -> {
		this.events.add("log");
		this.texts.add(text);
		this.logged.add(error);
	};

	private HookFailure failure() {
		return new HookFailure(() -> this.events.add("stop"), this.log);
	}

	private Runnable step(String name) {
		return () -> this.events.add(name);
	}

	/** A real one, made by name because the tests are compiled against 26.2's spelling on both games. */
	private static RuntimeException deviceLoss(String message) {
		for (String name : DEVICE_LOSS) {
			try {
				return (RuntimeException) Class.forName(name).getConstructor(String.class)
						.newInstance(message);
			} catch (ReflectiveOperationException absent) {
				// The other game's name.
			}
		}

		throw new AssertionError("no GpuDeviceLossException under either known name");
	}

	@Test
	void runsTheUndoTheStopTheLogAndTheReleaseInThatOrder() {
		this.failure().abandon(new IllegalStateException("boom"), step("undo"), step("release"));

		assertEquals(List.of("undo", "stop", "log", "release"), this.events);
	}

	@Test
	void runsTheStopTheLogAndTheReleaseWhenThereIsNothingToUndo() {
		this.failure().abandon(new IllegalStateException("boom"), step("release"));

		assertEquals(List.of("stop", "log", "release"), this.events);
	}

	@Test
	void leavesTheReleaseToTheHookWhenHandedNothing() {
		this.failure().abandon(new IllegalStateException("boom"), HookFailure.NOTHING);

		assertEquals(List.of("stop", "log"), this.events, "leaveWorld released inside its own try");
	}

	@Test
	void logsTheExactTextAndTheVeryExceptionItWasHanded() {
		RuntimeException thrown = new IllegalStateException("boom");

		this.failure().abandon(thrown, step("release"));

		assertEquals(List.of("Vitrail stopped drawing this pack after an error"), this.texts);
		assertEquals(1, this.logged.size());
		assertSame(thrown, this.logged.get(0));
	}

	@Test
	void handlesEveryRuntimeExceptionThatIsNotADeviceLoss() {
		List<RuntimeException> kinds = List.of(new IllegalStateException(), new NullPointerException(),
				new UnsupportedOperationException(), new IndexOutOfBoundsException(),
				new RuntimeException("a subclass of nothing"), new RuntimeException() { });
		for (RuntimeException kind : kinds) {
			this.events.clear();

			this.failure().abandon(kind, step("undo"), step("release"));

			assertEquals(List.of("undo", "stop", "log", "release"), this.events, kind.toString());
		}
	}

	@Test
	void rethrowsADeviceLossAsItCameAndRunsNothing() {
		RuntimeException lost = deviceLoss("the device is gone");

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> this.failure().abandon(lost, step("undo"), step("release")));

		assertSame(lost, thrown);
		assertEquals(0, thrown.getSuppressed().length);
		assertEquals(List.of(), this.events, "not the undo, the stop, the log or the release");
	}

	@Test
	void rethrowsADeviceLossOnTheRoadWithoutAnUndoToo() {
		RuntimeException lost = deviceLoss("the device is gone");

		assertSame(lost, assertThrows(RuntimeException.class,
				() -> this.failure().abandon(lost, step("release"))));
		assertSame(lost, assertThrows(RuntimeException.class,
				() -> this.failure().abandon(lost, HookFailure.NOTHING)));
		assertEquals(List.of(), this.events);
	}

	@Test
	void aFailingUndoEndsTheSequenceAndIsTheOneThatPropagates() {
		RuntimeException second = new IllegalArgumentException("undo failed");
		HookFailure failure = new HookFailure(step("stop"), this.log);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> failure.abandon(
				new IllegalStateException("first"), () -> {
					this.events.add("undo");
					throw second;
				}, step("release")));

		assertSame(second, thrown);
		assertEquals(List.of("undo"), this.events, "neither the stop, the log nor the release");
		assertEquals(0, thrown.getSuppressed().length, "the first exception is not attached");
	}

	@Test
	void aFailingStopSkipsTheLogAndTheRelease() {
		RuntimeException second = new IllegalArgumentException("stop failed");
		HookFailure failure = new HookFailure(() -> {
			this.events.add("stop");
			throw second;
		}, this.log);

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> failure.abandon(new IllegalStateException("first"), step("undo"), step("release")));

		assertSame(second, thrown);
		assertEquals(List.of("undo", "stop"), this.events);
		assertEquals(0, thrown.getSuppressed().length);
	}

	@Test
	void aFailingLogSkipsTheRelease() {
		RuntimeException second = new IllegalArgumentException("log failed");
		HookFailure failure = new HookFailure(step("stop"), (text, error) -> {
			this.events.add("log");
			throw second;
		});

		RuntimeException thrown = assertThrows(RuntimeException.class,
				() -> failure.abandon(new IllegalStateException("first"), step("release")));

		assertSame(second, thrown);
		assertEquals(List.of("stop", "log"), this.events);
	}

	@Test
	void aFailingReleasePropagatesAfterTheReasonIsInTheLog() {
		RuntimeException second = new IllegalArgumentException("release failed");
		RuntimeException first = new IllegalStateException("first");

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> this.failure().abandon(first,
				() -> {
					this.events.add("release");
					throw second;
				}));

		assertSame(second, thrown);
		assertEquals(List.of("stop", "log", "release"), this.events);
		assertSame(first, this.logged.get(0), "the reason is in the log by then");
		assertEquals(0, thrown.getSuppressed().length, "the first exception is not attached");
	}

	@Test
	void matchesTheCatchItReplaced() {
		RuntimeException first = new IllegalStateException("first");
		for (int failing = 0; failing < 16; failing++) {
			for (int kind = 0; kind < 2; kind++) {
				RuntimeException handed = kind == 0 ? first : deviceLoss("lost");
				boolean undo = (failing & 1) != 0;
				boolean stop = (failing & 2) != 0;
				boolean log = (failing & 4) != 0;
				boolean release = (failing & 8) != 0;
				String label = "failing " + failing + " kind " + kind;

				List<String> expected = new ArrayList<>();
				RuntimeException expectedThrown = legacy(handed, expected, undo, stop, log, release);

				List<String> got = new ArrayList<>();
				RuntimeException gotThrown = null;
				try {
					new HookFailure(() -> {
						got.add("stop");
						if (stop) {
							throw new IllegalArgumentException("stop");
						}
					}, (text, error) -> {
						got.add("log");
						if (log) {
							throw new IllegalArgumentException("log");
						}
					}).abandon(handed, () -> {
						got.add("undo");
						if (undo) {
							throw new IllegalArgumentException("undo");
						}
					}, () -> {
						got.add("release");
						if (release) {
							throw new IllegalArgumentException("release");
						}
					});
				} catch (RuntimeException escaped) {
					gotThrown = escaped;
				}

				assertEquals(expected, got, label);
				assertEquals(expectedThrown == null, gotThrown == null, label);
				if (expectedThrown != null) {
					assertEquals(expectedThrown.getClass(), gotThrown.getClass(), label);
					assertEquals(expectedThrown.getMessage(), gotThrown.getMessage(), label);
				}
			}
		}
	}

	/**
	 * The catch the sites carried before, written out again: the device loss first and rethrown, then
	 * the undo, {@code stop()}, the log line and {@code chain.release()} in that order, none of them
	 * guarded. Returns what escaped it, or null.
	 */
	private static RuntimeException legacy(RuntimeException handed, List<String> trace, boolean undo,
			boolean stop, boolean log, boolean release) {
		try {
			try {
				throw handed;
			} catch (RuntimeException e) {
				if (DEVICE_LOSS.contains(e.getClass().getName())) {
					throw e;
				}

				trace.add("undo");
				if (undo) {
					throw new IllegalArgumentException("undo");
				}

				trace.add("stop");
				if (stop) {
					throw new IllegalArgumentException("stop");
				}

				trace.add("log");
				if (log) {
					throw new IllegalArgumentException("log");
				}

				trace.add("release");
				if (release) {
					throw new IllegalArgumentException("release");
				}
			}
		} catch (RuntimeException escaped) {
			return escaped;
		}

		return null;
	}
}
