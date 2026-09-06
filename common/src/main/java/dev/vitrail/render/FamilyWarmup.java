package dev.vitrail.render;

import dev.vitrail.glsl.LoadClock;
import dev.vitrail.glsl.TranslationCache;
import dev.vitrail.mixin.access.GpuDeviceAccessor;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.render.timing.PassTimings;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pack-load workers of one chain: the leftover families read off the archive while the world
 * is already being played, and their pipelines compiled on threads of this engine's own.
 * <p>
 * A chain owns one of these and nothing else touches it. What lives here is what only the warm-up
 * has a use for: how far the walk has got, whether it is over and when, how many families have
 * really been read, and the flag a release raises to stop a worker still working for a chain
 * nothing will draw again. {@link PackChain} keeps the frame's own state and reads those answers
 * off this, so none of them lives in two places and drifts.
 * <p>
 * The pool and the set of workers in flight are static, because they outlive any one chain: a
 * release only raises its chain's flag and moves on, and a pack swap detaches its chain without
 * waiting either, so what shutdown must see out is not always the active chain's.
 */
final class FamilyWarmup {

	/**
	 * Every pack-load worker still running, whichever chain started it, held for the one caller
	 * that must see them out: shutdown. A release only raises its chain's {@link #released} and
	 * moves on, the worker stopping at its next program; and a pack swap detaches its chain
	 * without waiting either, so the worker shutdown must see out is not always the active
	 * chain's. Shutdown tears the device down behind them, and a device call still in flight then
	 * is a crash at exit, so {@link PackChain#close} waits the set out, bounded.
	 */
	private static final Set<CompletableFuture<Void>> WARMUPS = ConcurrentHashMap.newKeySet();

	/**
	 * The threads the family compile tasks run on, this engine's own and never the game's
	 * shared pool: a compile parks its thread in native shaderc for half a second at a time,
	 * six families land at once at world join, and threads parked like that would starve every
	 * other user of the shared executor for the length of the warm-up. Three at most, daemons,
	 * and the pool empties itself once the warm-up is over. Being daemons, they offer shutdown
	 * no barrier of their own: {@link #awaitAll} is the one thing standing between a compile in
	 * flight and the device teardown.
	 */
	private static final ThreadPoolExecutor COMPILE_POOL = compilePool();

	/** The six families in the one order that holds everywhere, the chain's own list. */
	private final List<FamilyDraw> families;

	/** What opens the pack again on the worker: the archive, the chosen values and the profile. */
	private final Path packPath;
	private final Map<String, OptionValue> chosen;
	private final String profile;

	/**
	 * The chain's lock over the six family program maps, held here as well because the worker is
	 * one of the two threads it guards: it copies the family it has just read while the render
	 * thread may be emptying every one of them.
	 */
	private final Object familyMaps;

	private boolean started;
	private volatile int familiesReady;

	/**
	 * Raised by {@link #release()} and read by the pack-load worker on both of its stages, which is
	 * what stops a worker still working for a chain nothing will ever draw again:
	 * {@link #warmFamily} reads it between two programs and {@link #prefetchFamily} between two
	 * families. Volatile for those cross-thread reads; everything else about the release stays on
	 * the render thread.
	 */
	private volatile boolean released;

	/**
	 * Whether this chain's pack-load workers are done, whatever they managed: what moves
	 * {@link dev.vitrail.screen.CompileCard} from its pulse to its closing words. Raised on every
	 * road out of the workers, the refused and the stopped included, because a mark that can never
	 * go out is worse than one that goes out early.
	 */
	private volatile boolean familiesWarmed;

	/**
	 * When the workers finished, written just before {@link #familiesWarmed} on each of its
	 * roads and published by that volatile write: what the closing words are timed from, so a
	 * corner hidden long enough behind F3 misses the show instead of replaying it stale.
	 */
	private long warmedAt;

	/**
	 * The progress the corner's words carry: how many family programs the compile tasks have
	 * walked, out of how many the finished translations have put on their plates. The total
	 * grows family by family, the way a loading bar's does, from the one translation worker; it
	 * is atomic for the lint's peace of mind, the render thread only ever reading it. It stays
	 * nought when no task was spawned at all, which is what keeps the words bare rather than
	 * stuck at "0 of N".
	 */
	private final AtomicInteger warmWalked = new AtomicInteger();
	private final AtomicInteger warmServed = new AtomicInteger();
	private final AtomicInteger warmTotal = new AtomicInteger();

	FamilyWarmup(List<FamilyDraw> families, Path packPath, Map<String, OptionValue> chosen,
			String profile, Object familyMaps) {
		this.families = families;
		this.packPath = packPath;
		this.chosen = chosen;
		this.profile = profile;
		this.familyMaps = familyMaps;
	}

	/** How many families the one translation worker has finished, and so how many may be walked. */
	int familiesReady() {
		return this.familiesReady;
	}

	/** Whether the workers are done, whatever they managed. */
	boolean warmed() {
		return this.familiesWarmed;
	}

	/** When they finished, in the game's own milliseconds; only read once {@link #warmed} is true. */
	long warmedAt() {
		return this.warmedAt;
	}

	int walked() {
		return this.warmWalked.get();
	}

	int total() {
		return this.warmTotal.get();
	}

	/**
	 * Translates the six families on a worker, and compiles each family's pipelines on a task of
	 * its own the moment that family's translation lands, so the entities compile while the
	 * clouds are still being read. Complementary Unbound's leftovers still cost their minute of
	 * work on a cold driver cache, spread across the pool's workers instead of queued on one;
	 * what they never cost again is the render thread, which only adopts what the workers
	 * finish, and the failed shape that DID cost it is engraved above {@code PackChain.compileNext}.
	 * <p>
	 * The translations stay SEQUENTIAL on the one worker, deliberately: two readers on one zip
	 * race, which is the reason the terrain's own read finishes before this starts. Only the
	 * compiles fan out, one task and one compiler per family, on {@link #COMPILE_POOL} rather
	 * than on the game's shared pool. The sky's and the entities' tasks are spawned first:
	 * those two are on screen the moment the world is.
	 */
	void start() {
		synchronized (this) {
			if (this.started) {
				return;
			}

			this.started = true;
		}

		// Read here, before any worker exists, and carried in as a value: the arming file read
		// lazily from a worker would race the reset a pack swap does on the render thread.
		boolean keepOld = PassTimings.keepFirstDrawCompiles();
		AtomicBoolean fanned = new AtomicBoolean();
		AtomicLong start = new AtomicLong();
		CompletableFuture<Void> whole;
		try {
			whole = CompletableFuture.supplyAsync(() -> {
				start.set(System.nanoTime());
				Vitrail.logger().info("The pack-load worker starts on the six families");
				VulkanDevice device = compileDevice(keepOld);
				fanned.set(device != null);

				List<CompletableFuture<Void>> compiles = new ArrayList<>(this.families.size());
				// One opening for the six, so the plan of the place, the program tree and every
				// header they share are worked out once on this worker rather than once per family:
				// the families used to be five of every six walks a warm load made of the archive.
				// A family that reads later on its own, at a first draw, still opens for itself.
				try (OpenedPack shared = OpenedPack.open(this.packPath, this.chosen, this.profile)) {
					for (int family = 0; family < this.families.size(); family++) {
						FamilyDraw read = this.families.get(family);
						prefetchFamily(() -> read.prefetch(shared));
						spawnFamilyCompiles(compiles, family, device);
					}
				} catch (Throwable e) {
					// prefetchFamily catches the RuntimeException of one translation; anything
					// harder would otherwise take this stage down EXCEPTIONALLY, and the whole
					// would then complete while the tasks already spawned still run, out of the
					// reach of the shutdown wait. Caught here, the spawned tasks stay tracked
					// and the families never reached keep their first-draw path.
					Vitrail.logger().error("The pack-load worker died", e);
				}

				return compiles;
			}, Util.backgroundExecutor()).thenCompose(compiles ->
					CompletableFuture.allOf(compiles.toArray(new CompletableFuture<?>[0])));
		} catch (RejectedExecutionException e) {
			// The executor only refuses while the client shuts down. The families keep their
			// first-draw path, and the flag closes the mark rather than leaving one that can
			// never go out.
			this.warmedAt = Util.getMillis();
			this.familiesWarmed = true;

			return;
		}

		CompletableFuture<Void> tracked = whole.handle((unused, e) -> {
			if (e != null) {
				// handle() and not a catch: the futures swallow what their runnables throw, so
				// anything that dies unlogged reads as the workers having finished. The families
				// they did not reach fall back to the first-draw path either way, and one
				// family's failure no longer stops the five others.
				Vitrail.logger().error("The pack-load worker died", e);
			}

			this.warmedAt = Util.getMillis();
			this.familiesWarmed = true;
			if (fanned.get()) {
				Vitrail.logger().info("{} of {} leftover pipelines compiled ahead of their "
						+ "first draw, {} ms of background work, translations included",
						this.warmServed.get(), this.warmWalked.get(),
						(System.nanoTime() - start.get()) / 1_000_000L);
				// Beside the total it explains. The spans are summed per program across workers,
				// so together they can pass the wall clock of the load; they compare with each
				// other, not with it.
				Vitrail.logger().info("With the families in, flattening the chain's units cost {} "
						+ "ms over {} of them with {} more handed back, translating cost {} ms over "
						+ "{} translator calls with {} programs served from the translation cache "
						+ "and {} translated, and making modules cost {} ms over {} modules, "
						+ "shaderc and SPIRV-Cross together", LoadClock.expansionMillis(),
						LoadClock.expanded(), LoadClock.expansionsServed(),
						LoadClock.translationMillis(), LoadClock.translated(),
						TranslationCache.served(), TranslationCache.translated(),
						LoadClock.moduleMillis(), LoadClock.modules());
			}

			return (Void) null;
		});
		WARMUPS.add(tracked);
		tracked.whenComplete((unused, e) -> WARMUPS.remove(tracked));
	}

	private static ThreadPoolExecutor compilePool() {
		AtomicInteger names = new AtomicInteger();
		ThreadPoolExecutor pool = new ThreadPoolExecutor(3, 3, 30L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), runnable -> {
					Thread thread = new Thread(runnable,
							"Vitrail compile worker " + names.incrementAndGet());
					thread.setDaemon(true);
					// The game outranks the warm-up by design: a window measured during play ran
					// six times longer than the same one behind a loading screen, which is these
					// threads yielding, and the priority writes that bargain down.
					thread.setPriority(Thread.MIN_PRIORITY);

					return thread;
				});
		pool.allowCoreThreadTimeOut(true);

		return pool;
	}

	/**
	 * The Vulkan backend the compile tasks build against, or null with the reason logged: no
	 * task is spawned then, and every family keeps its first-draw path. Resolved on the worker
	 * rather than at the call, because the load's own road can run before rendering is up.
	 */
	private static VulkanDevice compileDevice(boolean keepOld) {
		GpuDevice front = RenderSystem.tryGetDevice();
		if (front != null && !keepOld
				&& ((GpuDeviceAccessor) front).vitrail$backend() instanceof VulkanDevice device) {
			return device;
		}

		Vitrail.logger().info("The workers leave the leftover families to their first draw: {}",
				front == null ? "no device"
						: keepOld ? "keep-first-draw-compiles"
								: "the backend is not the Vulkan one");

		return null;
	}

	/**
	 * One compile task for one family, started the moment its translation landed. The pool it
	 * lands on is never shut down and its queue is unbounded, so the submit cannot be refused;
	 * the game's own executor, which can refuse one at shutdown, only ever carries the
	 * translation stage.
	 */
	private void spawnFamilyCompiles(List<CompletableFuture<Void>> compiles, int family,
			VulkanDevice device) {
		if (device == null) {
			return;
		}

		// Copied here, on the thread that has just filled this family, and never walked live:
		// the maps behind it are emptied on the render thread when the pack goes, and an
		// iterator standing in one then throws under the worker even though the released flag
		// it reads every step is already up. Under the lock, because the copy itself is a read
		// of the live map and the emptying is what it races.
		List<DumpedProgram> programs;
		synchronized (this.familyMaps) {
			programs = List.copyOf(familyPrograms(family));
		}

		// The plate grows before the task that will empty it exists, so the corner's count can
		// only ever run behind the truth, never past it.
		this.warmTotal.addAndGet(programs.size());
		compiles.add(CompletableFuture.runAsync(
				() -> warmFamily(programs, device), COMPILE_POOL));
	}

	/**
	 * Compiles every program one family read, with a compiler of this task's own: the device's
	 * precompile keeps its results in maps only the render thread may touch, so each pipeline is
	 * built through the same public steps instead and {@code GeometryProgram.compile} hands the
	 * finished object to the cache on the render thread. {@code GeometryProgram.warmAhead} says
	 * why every step of that is safe off the thread, and
	 * {@code vitrail/keep-first-draw-compiles} beside the pack keeps the old first-draw path for
	 * a measurement, the way {@code keep-redone-work} does.
	 */
	private void warmFamily(List<DumpedProgram> programs, VulkanDevice device) {
		try (GlslCompiler compiler = new GlslCompiler()) {
			for (DumpedProgram program : programs) {
				if (this.released || PackChain.stopped()) {
					return;
				}

				this.warmWalked.incrementAndGet();
				if (program.warmAhead(device, compiler)) {
					this.warmServed.incrementAndGet();
				}
			}
		}
	}

	/**
	 * One family read ahead, and the translation half of what {@link #released} stops.
	 * <p>
	 * {@link #warmFamily} read that flag between two programs and this did not, so a chain nothing
	 * would ever draw again went on translating its remaining families. That is wasted work, and it
	 * is also the one thing that writes to state no chain owns: every {@code PackProgram.load}
	 * reinstalls the {@code bufferObject} lines of the pack it is reading and the translator files
	 * its storage block names away as it goes, so a worker outliving its pack can put the outgoing
	 * pack's answers back after the next load has emptied them.
	 * <p>
	 * Read once per family rather than per program, which is what the counter below can express: it
	 * is a prefix bound, and skipping the rest without raising it leaves exactly the families that
	 * were really filled walkable. So a translation already under way still finishes and can still
	 * write, and that one family is the window this narrows the race to rather than closes it.
	 */
	private void prefetchFamily(Runnable prefetch) {
		if (this.released || PackChain.stopped()) {
			return;
		}

		try {
			prefetch.run();
		} catch (RuntimeException e) {
			Vitrail.logger().error("Translating a pack family failed", e);
		}

		this.familiesReady++;
	}

	Collection<? extends DumpedProgram> familyPrograms(int index) {
		return index >= 0 && index < this.families.size()
				? this.families.get(index).programs()
				: List.of();
	}

	/**
	 * Stops the workers of this chain: they read the flag between two programs and between two
	 * families, so what is already under way finishes and nothing after it starts.
	 */
	void release() {
		this.released = true;
	}

	/**
	 * Waits out, bounded, every worker still running at shutdown, whichever chain started it.
	 * Nothing device-side can START once a chain is released; what the bound really covers is the
	 * one compile possibly in flight, about half a second, and a worker still translating holds
	 * no device work at all. The bound itself is for a worker wedged in the driver, which may not
	 * be allowed to hold the quit.
	 */
	static void awaitAll() {
		CompletableFuture<?>[] running = WARMUPS.toArray(new CompletableFuture<?>[0]);
		if (running.length == 0) {
			return;
		}

		try {
			CompletableFuture.allOf(running).get(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException ignored) {
			// The runnable logs its own failures, and a timeout leaves nothing to do but quit.
		}
	}
}
