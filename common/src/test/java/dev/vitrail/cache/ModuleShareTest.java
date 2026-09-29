package dev.vitrail.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The table a pack load shares its units through: what it holds, who may make a unit while nobody
 * else is, and that nothing it does can leave a waiter waiting. Driven with plain byte arrays for
 * the units and real threads for the askers, every wait bounded so that a hang is a failure and not
 * a stuck build.
 */
class ModuleShareTest {

	private static final Duration LIMIT = Duration.ofSeconds(20);

	private final ModuleShare table = new ModuleShare(true);

	private static ModuleShare.Blob unit(int... bytes) {
		byte[] raw = new byte[bytes.length + 32];
		for (int at = 0; at < bytes.length; at++) {
			raw[at] = (byte) bytes[at];
		}

		return new ModuleShare.Blob(raw, bytes.length);
	}

	/** Runs the body on a thread of its own, started at once, and hands the thread back. */
	private static Thread thread(Runnable body) {
		Thread thread = new Thread(body, "module share test");
		thread.setDaemon(true);
		thread.start();

		return thread;
	}

	/** Waits, bounded, for a thread to be parked in {@code wait}, which is how a waiting asker looks. */
	private static void awaitWaiting(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + LIMIT.toNanos();
		while (thread.getState() != Thread.State.WAITING) {
			assertTrue(System.nanoTime() < deadline, "the thread never waited");
			Thread.sleep(1);
		}
	}

	private static void join(Thread thread) throws InterruptedException {
		thread.join(LIMIT.toMillis());
		assertFalse(thread.isAlive(), "the thread did not finish");
	}

	// -- what the table holds -----------------------------------------------------------------

	@Test
	void aUnitOfferedIsFoundAndOneNobodyMadeIsNot() {
		ModuleShare.Blob made = unit(1, 2, 3);

		this.table.offer("a", made);

		assertSame(made, this.table.find("a"));
		assertNull(this.table.find("b"));
		assertEquals(1, this.table.size());
	}

	@Test
	void aUnitOfferedTwiceKeepsTheFirstMakersWords() {
		ModuleShare.Blob first = unit(1);
		ModuleShare.Blob second = unit(2);

		this.table.offer("a", first);
		this.table.offer("a", second);

		assertSame(first, this.table.find("a"));
		assertEquals(1, this.table.size());
	}

	@Test
	void clearingForgetsEveryUnit() {
		this.table.offer("a", unit(1));
		this.table.offer("b", unit(2));

		this.table.clear();

		assertNull(this.table.find("a"));
		assertNull(this.table.find("b"));
		assertEquals(0, this.table.size());
	}

	@Test
	void aUnitWithNoKeyIsKeptNowhereAndFoundNowhere() {
		this.table.offer(null, unit(1));

		assertNull(this.table.find(null));
		assertEquals(0, this.table.size());
	}

	@Test
	void aTableThatIsOffKeepsNothingAndMakesEveryAskerItsOwnMaker() throws InterruptedException {
		ModuleShare off = new ModuleShare(false);

		off.offer("a", unit(1));
		ModuleShare.Claim first = off.claim("a");
		// A second claim of the same unit does not wait: a table that is off holds nothing to wait for.
		AtomicBoolean second = new AtomicBoolean();
		Thread other = thread(() -> {
			off.claim("a").release();
			second.set(true);
		});
		join(other);
		first.release();

		assertNull(off.find("a"));
		assertTrue(second.get());
	}

	// -- who makes a unit ---------------------------------------------------------------------

	@Test
	void aClaimOfNoUnitHoldsNothingAndNothingWaitsForIt() {
		assertTimeoutPreemptively(LIMIT, () -> {
			ModuleShare.Claim none = this.table.claim(null);
			this.table.claim(null).release();
			none.release();
		});
	}

	@Test
	void aSecondAskerWaitsForTheFirstAndFindsTheUnitMade() throws InterruptedException {
		ModuleShare.Claim first = this.table.claim("a");
		AtomicReference<ModuleShare.Blob> found = new AtomicReference<>();
		AtomicBoolean through = new AtomicBoolean();
		Thread second = thread(() -> {
			ModuleShare.Claim claim = this.table.claim("a");
			try {
				found.set(this.table.find("a"));
				through.set(true);
			} finally {
				claim.release();
			}
		});

		awaitWaiting(second);
		assertFalse(through.get(), "the second asker went ahead of the first");

		ModuleShare.Blob made = unit(7, 8, 9);
		this.table.offer("a", made);
		first.release();
		join(second);

		assertSame(made, found.get());
	}

	@Test
	void aUnitAskedForByManyAtOnceIsMadeOnce() throws InterruptedException {
		int askers = 8;
		AtomicInteger makes = new AtomicInteger();
		CountDownLatch go = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();
		List<ModuleShare.Blob> got = Collections.synchronizedList(new ArrayList<>());
		for (int asker = 0; asker < askers; asker++) {
			threads.add(thread(() -> {
				try {
					go.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();

					return;
				}

				ModuleShare.Claim claim = this.table.claim("a");
				try {
					ModuleShare.Blob held = this.table.find("a");
					if (held == null) {
						makes.incrementAndGet();
						pause();
						held = unit(1, 2, 3);
						this.table.offer("a", held);
					}

					got.add(held);
				} finally {
					claim.release();
				}
			}));
		}

		go.countDown();
		for (Thread thread : threads) {
			join(thread);
		}

		assertEquals(1, makes.get(), "a unit asked for at once was made more than once");
		assertEquals(askers, got.size());
		for (ModuleShare.Blob blob : got) {
			assertSame(got.getFirst(), blob);
		}
	}

	private static void pause() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void aMakerThatFailedLeavesTheNextAskerToMakeItAndNobodyHanging() throws InterruptedException {
		ModuleShare.Claim failing = this.table.claim("a");
		AtomicReference<ModuleShare.Blob> found = new AtomicReference<>(unit(0));
		Thread next = thread(() -> {
			ModuleShare.Claim claim = this.table.claim("a");
			try {
				found.set(this.table.find("a"));
			} finally {
				claim.release();
			}
		});

		awaitWaiting(next);
		// The pack's text was refused: the compile threw and the finally released the claim with
		// nothing offered.
		failing.release();
		join(next);

		assertNull(found.get(), "the next asker was handed a unit nobody made");
	}

	@Test
	void unitsThatAreNotTheSameNeverWaitForEachOther() throws InterruptedException {
		ModuleShare.Claim held = this.table.claim("a");
		AtomicBoolean through = new AtomicBoolean();
		Thread other = thread(() -> {
			this.table.claim("b").release();
			through.set(true);
		});

		join(other);
		held.release();

		assertTrue(through.get());
	}

	@Test
	void aClaimReleasedTwiceLetsOneAskerInAndNotTwo() throws InterruptedException {
		ModuleShare.Claim first = this.table.claim("a");
		first.release();
		first.release();

		// The second claim is this thread's, and the second release of the first must not be able
		// to take it away: a third asker still waits for it.
		ModuleShare.Claim second = this.table.claim("a");
		AtomicBoolean through = new AtomicBoolean();
		Thread third = thread(() -> {
			this.table.claim("a").release();
			through.set(true);
		});

		awaitWaiting(third);
		first.release();
		assertFalse(through.get(), "a claim released twice let a third asker in early");

		second.release();
		join(third);
		assertTrue(through.get());
	}

	@Test
	void aWaitThatIsInterruptedGoesOnUnclaimedWithTheInterruptKept() throws InterruptedException {
		ModuleShare.Claim held = this.table.claim("a");
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicReference<ModuleShare.Claim> taken = new AtomicReference<>();
		Thread waiter = thread(() -> {
			taken.set(this.table.claim("a"));
			interrupted.set(Thread.currentThread().isInterrupted());
		});

		awaitWaiting(waiter);
		waiter.interrupt();
		join(waiter);

		assertNotNull(taken.get());
		assertTrue(interrupted.get(), "the interrupt was swallowed");
		// Releasing what it never held takes nothing from the asker that does.
		taken.get().release();
		AtomicBoolean through = new AtomicBoolean();
		Thread after = thread(() -> {
			this.table.claim("a").release();
			through.set(true);
		});

		awaitWaiting(after);
		assertFalse(through.get());
		held.release();
		join(after);
		assertTrue(through.get());
	}

	@Test
	void clearingTheTableTakesNoClaimAway() throws InterruptedException {
		ModuleShare.Claim held = this.table.claim("a");
		AtomicBoolean through = new AtomicBoolean();
		Thread other = thread(() -> {
			this.table.claim("a").release();
			through.set(true);
		});

		awaitWaiting(other);
		this.table.clear();
		assertFalse(through.get(), "clearing let a second maker in beside the first");

		held.release();
		join(other);
		assertTrue(through.get());
	}
}
