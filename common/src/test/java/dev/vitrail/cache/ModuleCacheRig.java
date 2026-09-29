package dev.vitrail.cache;

import dev.vitrail.Vitrail;
import dev.vitrail.platform.VitrailPlatform;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * One private copy of the module cache, driven through nothing but the surface a caller has, so
 * that a test written against it holds the cache to its behaviour and not to where the class keeps
 * its fields.
 * <p>
 * The cache is all statics: the directory it opened, the ceiling it read, the once-a-run latches of
 * what it said, the running count of bytes. One copy per JVM would let every test see what the one
 * before it did, and the only ways to undo that are a reach into the fields, which breaks the day a
 * field moves, or a class that has never run. So each rig loads the classes of this package from
 * the same place the game's copy comes from through a loader of its own, which starts every static
 * afresh and leaves the JVM's own copy alone. What the copy reaches outside the package
 * ({@link Vitrail}, the engine's passes, LWJGL) is still the JVM's, and the one such thing a test
 * has to steer is the platform, which is put in place here and put back at {@link #close}.
 * <p>
 * The two games differ in what a cached module IS (26.2 keeps the reflection beside the words,
 * 26.3 keeps the words), and so in the signatures of {@code keyOf}, {@code lookup} and
 * {@code store}. The rig finds each by name and arity and hides the difference, so a test asks
 * {@link #game263()} only where a game really behaves differently.
 */
final class ModuleCacheRig implements AutoCloseable {

	static final String MOD_VERSION = "0.13.0";
	static final String MINECRAFT = "26.2";
	static final String LOADER = "testloader";
	static final String LOADER_VERSION = "1.2.3";
	static final long MIB = 1024L * 1024L;

	private static final String PROPERTY = "vitrail.moduleCache";

	private final TestPlatform platform;
	private final @Nullable Object platformBefore;
	private final Isolated loader;
	private final Class<?> cache;
	private final Logger logger;
	private final Level levelBefore;
	private final Capture capture = new Capture();
	private final Method keyOf;
	private final Method lookup;
	private final Method store;

	ModuleCacheRig(Path gameDirectory) {
		this(gameDirectory, true);
	}

	/**
	 * @param enabled false to load the copy under {@code -Dvitrail.moduleCache=false}, which the
	 *                class reads once, when it first runs
	 */
	ModuleCacheRig(Path gameDirectory, boolean enabled) {
		this.platform = new TestPlatform(gameDirectory);
		// The switch is read the first time the cache runs, which loading its class may already be,
		// so it is down before the class is reached and stays down until one call has run.
		String before = System.getProperty(PROPERTY);
		if (!enabled) {
			System.setProperty(PROPERTY, "false");
		}

		try {
			Field field = Vitrail.class.getDeclaredField("platform");
			field.setAccessible(true);
			this.platformBefore = field.get(null);
			field.set(null, this.platform);

			this.logger = (Logger) LogManager.getLogger(Vitrail.MOD_NAME);
			this.levelBefore = this.logger.getLevel();
			Configurator.setLevel(Vitrail.MOD_NAME, Level.INFO);
			this.capture.start();
			this.logger.addAppender(this.capture);

			URL where = ModuleCache.class.getProtectionDomain().getCodeSource().getLocation();
			this.loader = new Isolated(where, ModuleCache.class.getClassLoader());
			this.cache = Class.forName("dev.vitrail.cache.ModuleCache", true, this.loader);
			this.keyOf = widest("keyOf");
			this.lookup = widest("lookup");
			this.store = widest("store");
			if (!enabled) {
				keyOf("x", "y", "", true);
			}
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		} finally {
			if (!enabled) {
				if (before == null) {
					System.clearProperty(PROPERTY);
				} else {
					System.setProperty(PROPERTY, before);
				}
			}
		}
	}

	/** Puts back what the rig steered, and drops the private copy. */
	@Override
	public void close() {
		try {
			Field field = Vitrail.class.getDeclaredField("platform");
			field.setAccessible(true);
			field.set(null, this.platformBefore);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}

		this.logger.removeAppender(this.capture);
		this.capture.stop();
		Configurator.setLevel(Vitrail.MOD_NAME, this.levelBefore);
		try {
			this.loader.close();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private Method widest(String name) {
		Method found = null;
		for (Method method : this.cache.getMethods()) {
			if (method.getName().equals(name)
					&& (found == null || method.getParameterCount() > found.getParameterCount())) {
				found = method;
			}
		}

		if (found == null) {
			throw new IllegalStateException("no public " + name + " on the module cache");
		}

		return found;
	}

	/** The private copy's class, for the one caller that needs an overload the rig does not hide. */
	Class<?> cacheClass() {
		return this.cache;
	}

	// -- what differs between the games -------------------------------------------------------

	/** Whether this is the 26.3 half: {@code keyOf} then also takes the defines beside the text. */
	boolean game263() {
		return this.keyOf.getParameterCount() == 4;
	}

	/**
	 * Whether a module can be handed to {@code store}. 26.3 stores the words, which a test can
	 * make. 26.2 stores a game module through a mixin accessor that only exists inside a running
	 * game, so there a unit can only be put on disk by hand, in the layout the class documents.
	 */
	boolean canStore() {
		return game263();
	}

	/** {@code defines} is ignored by 26.2, whose key has no place for it. */
	@Nullable String keyOf(String source, String stage, String defines,
			boolean ours) {
		return (String) call(this.keyOf, game263()
				? new Object[] {source, stage, defines, ours}
				: new Object[] {source, stage, ours});
	}

	/** What the cache serves for the key: a buffer on 26.3, a game module on 26.2. */
	@Nullable Object lookup(@Nullable String key) {
		return call(this.lookup, this.lookup.getParameterCount() == 2
				? new Object[] {key, "pack/1/test"}
				: new Object[] {key});
	}

	void store(@Nullable String key, byte[] words) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(words.length);
		buffer.put(words).flip();
		store(key, buffer);
	}

	/** Hands the buffer over as it is, position and limit included. */
	void store(@Nullable String key, ByteBuffer buffer) {
		if (!canStore()) {
			throw new UnsupportedOperationException("a game module cannot be made outside the game");
		}

		call(this.store, new Object[] {key, buffer});
	}

	/** The words of a served unit, whichever shape it came back in. */
	byte[] words(Object served) {
		ByteBuffer buffer = served instanceof ByteBuffer direct ? direct : (ByteBuffer) invoke(served, "spirv");
		ByteBuffer view = buffer.duplicate();
		byte[] words = new byte[view.remaining()];
		view.get(words);

		return words;
	}

	/** Overwrites the first byte of what a hit handed over, as a pipeline's rebind would. */
	void scribble(Object served) {
		ByteBuffer buffer = served instanceof ByteBuffer direct ? direct : (ByteBuffer) invoke(served, "spirv");
		buffer.put(0, (byte) 0x55);
	}

	/** Frees what a hit handed over, the way its owner would. */
	void release(Object served) {
		if (served instanceof ByteBuffer direct) {
			MemoryUtil.memFree(direct);
		} else {
			invoke(served, "close");
		}
	}

	/** The named component of a served game module, as text; 26.2 only. */
	String component(Object module, String name) {
		return String.valueOf(invoke(module, name));
	}

	private static Object invoke(Object target, String name) {
		try {
			Method method = target.getClass().getMethod(name);
			method.setAccessible(true);

			return method.invoke(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	// -- the surface both games share ---------------------------------------------------------

	void building(String filename) {
		call(method("building"), new Object[] {filename});
	}

	void say() {
		call(method("say"), new Object[0]);
	}

	int ceilingMib() {
		return (Integer) call(method("ceilingMib"), new Object[0]);
	}

	void setCeilingMib(int mib) {
		call(method("setCeilingMib"), new Object[] {mib});
	}

	private Method method(String name) {
		for (Method method : this.cache.getMethods()) {
			if (method.getName().equals(name)) {
				return method;
			}
		}

		throw new IllegalStateException("no public " + name + " on the module cache");
	}

	private static @Nullable Object call(Method method, Object[] arguments) {
		try {
			return method.invoke(null, arguments);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException run) {
				throw run;
			}

			if (e.getCause() instanceof Error error) {
				throw error;
			}

			throw new IllegalStateException(e.getCause());
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	// -- where it keeps things ----------------------------------------------------------------

	Path gameDirectory() {
		return this.platform.gameDirectory();
	}

	/** Every edition of every build sits under this, one folder each. */
	Path modules() {
		return gameDirectory().resolve(Vitrail.MOD_ID).resolve("modules");
	}

	/** The folder of the edition the platform in place names, which is where units are kept. */
	Path edition() {
		return modules().resolve(Vitrail.cacheEdition());
	}

	Path unit(String key) {
		return edition().resolve(key + ".mod");
	}

	Path ceilingFile() {
		return gameDirectory().resolve(Vitrail.MOD_ID).resolve("module-cache-ceiling.txt");
	}

	/** The platform the cache reads its versions from, moved to say something else. */
	void platformIs(String modVersion, String minecraft, String loader, String loaderVersion) {
		this.platform.modVersion = modVersion;
		this.platform.minecraft = minecraft;
		this.platform.loader = loader;
		this.platform.loaderVersion = loaderVersion;
	}

	// -- what it said -------------------------------------------------------------------------

	/** Every line the mod's logger was asked to write since the rig began, level first. */
	List<String> log() {
		return List.copyOf(this.capture.lines);
	}

	/** The warnings among them, without the level. */
	List<String> warnings() {
		List<String> warnings = new ArrayList<>();
		for (String line : this.capture.lines) {
			if (line.startsWith("WARN ")) {
				warnings.add(line.substring("WARN ".length()));
			}
		}

		return warnings;
	}

	private static final class Capture extends AbstractAppender {

		private final List<String> lines = new ArrayList<>();

		Capture() {
			super("module-cache-capture", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			this.lines.add(event.getLevel().name() + " " + event.getMessage().getFormattedMessage());
		}
	}

	private static final class TestPlatform implements VitrailPlatform {

		private final Path gameDirectory;
		String modVersion = MOD_VERSION;
		String minecraft = MINECRAFT;
		String loader = LOADER;
		String loaderVersion = LOADER_VERSION;

		TestPlatform(Path gameDirectory) {
			this.gameDirectory = gameDirectory;
		}

		@Override
		public String loaderName() {
			return this.loader;
		}

		@Override
		public String loaderVersion() {
			return this.loaderVersion;
		}

		@Override
		public String modVersion() {
			return this.modVersion;
		}

		@Override
		public String minecraftVersion() {
			return this.minecraft;
		}

		@Override
		public Path gameDirectory() {
			return this.gameDirectory;
		}

		@Override
		public boolean isModLoaded(String modId) {
			return false;
		}
	}

	/**
	 * Loads the classes of this package from its own copy and everything else from the JVM's. The
	 * test classes that share the package are not where this looks, so they fall through to the
	 * parent as they must.
	 */
	private static final class Isolated extends URLClassLoader {

		Isolated(URL where, ClassLoader parent) {
			super(new URL[] {where}, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				if (name.startsWith("dev.vitrail.cache.")) {
					Class<?> found = findLoadedClass(name);
					if (found == null) {
						try {
							found = findClass(name);
						} catch (ClassNotFoundException e) {
							return super.loadClass(name, resolve);
						}
					}

					if (resolve) {
						resolveClass(found);
					}

					return found;
				}

				return super.loadClass(name, resolve);
			}
		}
	}
}
