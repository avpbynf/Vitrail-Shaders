package dev.vitrail.dh;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * One private universe for a test of the Distant Horizons bridge: its own copy of {@code DhLods} and
 * {@code DhDepth} with all their static state, a Distant Horizons that exists only here, and
 * recording stand-ins for the engine classes the bridge calls.
 * <p>
 * <strong>Why a class loader and not a reset.</strong> The bridge finds Distant Horizons with
 * {@code Class.forName(name)} from its own loader, and keeps everything it found, and every latch
 * it has thrown, in static fields. A fake DH on the test class path would be seen by every other
 * test of the JVM, and those fields would carry one test's answer into the next. This loader defines
 * the bridge's classes again from the very bytes the game runs (child first), defines the fake DH
 * from source compiled at run time, and hands everything else to the test's own loader. Nothing of
 * it is reachable from the class path, and dropping the world drops the state.
 * <p>
 * The three engine stand-ins ({@code Vitrail}, {@code DistantDraw}, {@code PassTimings}) are shadowed
 * the same way so that the log, the far terrain draw and the pass census are observable and
 * scripted. What is shared with the test on purpose: the JDK, {@code org.joml}, and the game's
 * buffer and exception types, which the bridge really has to recognise.
 */
final class DhWorld extends ClassLoader {

	static final String LODS = "dev.vitrail.dh.DhLods";
	static final String DEPTH = "dev.vitrail.dh.DhDepth";

	/** One change to the text of one fake source, applied before it is compiled. */
	record Edit(String type, String from, String to) {
	}

	/** What the two games call the class the bridge lets a lost device through as. */
	private static final List<String> DEVICE_LOSS = List.of("com.mojang.blaze3d.GpuDeviceLossException",
			"com.mojang.renderpearl.api.device.GpuDeviceLossException");

	private static final Map<String, byte[]> MIRRORED = new ConcurrentHashMap<>();
	private static final Map<List<Edit>, Map<String, byte[]>> COMPILED = new HashMap<>();
	private static Map<String, byte[]> buffers;

	private final Map<String, byte[]> fakes;
	private final Set<String> blocked;

	private DhWorld(Map<String, byte[]> fakes, Set<String> blocked) {
		super(DhWorld.class.getClassLoader());
		this.fakes = fakes;
		this.blocked = blocked;
	}

	/** A complete fake DH. */
	static DhWorld standard() {
		return new DhWorld(fakes(List.of()), Set.of());
	}

	/** A DH that lacks the class of that name, as the class loader answers for a class it has not got. */
	static DhWorld missing(String... classNames) {
		return new DhWorld(fakes(List.of()), Set.of(classNames));
	}

	/** A DH whose sources were changed before they were compiled. */
	static DhWorld edited(Edit... edits) {
		return new DhWorld(fakes(List.of(edits)), Set.of());
	}

	private static synchronized Map<String, byte[]> fakes(List<Edit> edits) {
		Map<String, byte[]> compiled = COMPILED.get(edits);
		if (compiled == null) {
			Map<String, String> sources = DhFakes.sources();
			for (Edit edit : edits) {
				String text = sources.get(edit.type());
				if (text == null || !text.contains(edit.from())) {
					throw new AssertionError("edit does not apply to " + edit.type() + ": " + edit.from());
				}
				sources.put(edit.type(), text.replace(edit.from(), edit.to()));
			}

			compiled = new HashMap<>(compile(sources));
			compiled.putAll(buffers());
			COMPILED.put(List.copyOf(edits), compiled);
		}

		return compiled;
	}

	/** The buffer class, compiled once against whichever game type this build has. */
	private static synchronized Map<String, byte[]> buffers() {
		if (buffers == null) {
			buffers = compile(Map.of(DhFakes.BUFFER, DhFakes.bufferSource(bufferType())));
		}

		return buffers;
	}

	/** The game's buffer type, read off the record the bridge hands to the pack. */
	static Class<?> bufferType() {
		return DhLods.Piece.class.getRecordComponents()[0].getType();
	}

	private static String codeSource(Class<?> type) {
		try {
			return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
		} catch (java.net.URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	private static Map<String, byte[]> compile(Map<String, String> sources) {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalStateException("these tests compile a fake Distant Horizons and need a JDK");
		}

		Map<String, ByteArrayOutputStream> classes = new TreeMap<>();
		DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
		StandardJavaFileManager standard =
				compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);

		List<JavaFileObject> units = new ArrayList<>();
		sources.forEach((name, text) -> units.add(new SimpleJavaFileObject(
				URI.create("mem:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return text;
			}
		}));

		List<String> options = List.of("-proc:none", "-nowarn", "-Xlint:none", "-classpath",
				codeSource(org.slf4j.Logger.class) + File.pathSeparator + codeSource(bufferType()));

		boolean compiled;
		try (JavaFileManager memory = new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
			@Override
			public JavaFileObject getJavaFileForOutput(Location location, String className,
					JavaFileObject.Kind kind, FileObject sibling) {
				return new SimpleJavaFileObject(
						URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind) {
					@Override
					public OutputStream openOutputStream() {
						ByteArrayOutputStream bytes = new ByteArrayOutputStream();
						classes.put(className, bytes);
						return bytes;
					}
				};
			}
		}) {
			compiled = compiler.getTask(null, memory, diagnostics, options, null, units).call();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}

		if (!compiled) {
			StringBuilder report = new StringBuilder("the fake Distant Horizons does not compile:");
			for (Diagnostic<? extends JavaFileObject> one : diagnostics.getDiagnostics()) {
				report.append("\n  ").append(one.getSource() == null ? "" : one.getSource().getName())
						.append(':').append(one.getLineNumber()).append(' ').append(one.getMessage(Locale.ROOT));
			}
			throw new AssertionError(report.toString());
		}

		Map<String, byte[]> result = new HashMap<>();
		classes.forEach((name, bytes) -> result.put(name, bytes.toByteArray()));

		return result;
	}

	private static boolean mirrored(String name) {
		return name.equals(LODS) || name.equals(DEPTH) || name.startsWith(LODS + "$");
	}

	private static byte[] bytesOfBridge(String name) {
		return MIRRORED.computeIfAbsent(name, key -> {
			String path = key.replace('.', '/') + ".class";
			try (InputStream in = DhWorld.class.getClassLoader().getResourceAsStream(path)) {
				if (in == null) {
					throw new IllegalStateException("no class file for " + key);
				}
				return in.readAllBytes();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
		});
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> loaded = findLoadedClass(name);
			if (loaded == null) {
				if (blocked.contains(name)) {
					throw new ClassNotFoundException(name);
				}

				byte[] bytes = fakes.get(name);
				if (bytes == null && mirrored(name)) {
					bytes = bytesOfBridge(name);
				}

				loaded = bytes == null ? super.loadClass(name, false) : defineClass(name, bytes, 0, bytes.length);
			}

			if (resolve) {
				resolveClass(loaded);
			}

			return loaded;
		}
	}

	/** The class of that name as this world sees it. */
	Class<?> type(String name) {
		try {
			return loadClass(name);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("not in this world: " + name, e);
		}
	}

	/**
	 * Calls a public static method by name and argument count. An unchecked failure comes out as
	 * itself, so a test can say which one it expects.
	 */
	Object call(String type, String method, Object... arguments) {
		Method found = null;
		for (Method one : type(type).getMethods()) {
			if (one.getName().equals(method) && one.getParameterCount() == arguments.length) {
				found = one;
			}
		}

		if (found == null) {
			throw new AssertionError("no public " + method + "/" + arguments.length + " on " + type);
		}

		return invoke(found, null, arguments);
	}

	/** Calls a method of an object of this world, found by name and argument count. */
	Object callOn(Object target, String method, Object... arguments) {
		return invoke(interfaceMethod(target, method, arguments.length), target, arguments);
	}

	/** What a method of an object of this world throws, or null when it returns. */
	Throwable thrownBy(Object target, String method, Object... arguments) {
		try {
			interfaceMethod(target, method, arguments.length).invoke(target, arguments);
			return null;
		} catch (InvocationTargetException e) {
			return e.getCause();
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	private Method interfaceMethod(Object target, String method, int count) {
		for (Class<?> type : target.getClass().getInterfaces()) {
			for (Method one : type.getMethods()) {
				if (one.getName().equals(method) && one.getParameterCount() == count) {
					return one;
				}
			}
		}

		throw new AssertionError("no " + method + "/" + count + " on " + target.getClass());
	}

	private static Object invoke(Method method, Object target, Object[] arguments) {
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (e.getCause() instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(e.getCause());
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	/** A static field of this world, whatever its visibility. */
	Object peek(String type, String field) {
		try {
			Field found = type(type).getDeclaredField(field);
			found.setAccessible(true);
			return found.get(null);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	void poke(String type, String field, Object value) {
		try {
			Field found = type(type).getDeclaredField(field);
			found.setAccessible(true);
			found.set(null, value);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	// The bridge, through its public door.

	void install() {
		call(LODS, "install");
	}

	void handBack() {
		call(LODS, "handBack");
	}

	boolean lodsUsable() {
		return (Boolean) call(LODS, "usable");
	}

	// The fake Distant Horizons, and what it saw.

	/** DH up and running: its render proxy and configuration published and its renderer bound. */
	void dhStarted() {
		stage("publishProxy");
		stage("publishConfigs");
		stage("bindRenderer");
	}

	Object stage(String method, Object... arguments) {
		return call(DhFakes.STAGE, method, arguments);
	}

	List<String> events() {
		return strings(peek(DhFakes.STAGE, "EVENTS"));
	}

	void clearEvents() {
		((List<?>) peek(DhFakes.STAGE, "EVENTS")).clear();
	}

	/** The lines the bridge logged, each as {@code LEVEL message <throwable / cause>}. */
	List<String> log() {
		return strings(peek(DhFakes.VITRAIL, "LOG"));
	}

	void clearLog() {
		((List<?>) peek(DhFakes.VITRAIL, "LOG")).clear();
	}

	/** The section counts the bridge reported, one per far terrain half it really built. */
	List<Integer> census() {
		List<Integer> counts = new ArrayList<>();
		for (Object one : (List<?>) peek(DhFakes.PASS_TIMINGS, "CENSUS")) {
			counts.add((Integer) one);
		}

		return counts;
	}

	private static List<String> strings(Object list) {
		List<String> lines = new ArrayList<>();
		for (Object one : (List<?>) list) {
			lines.add(String.valueOf(one));
		}

		return lines;
	}

	/** What DH's renderer field holds now. */
	Object standing() {
		return stage("standing");
	}

	boolean standingIsProxy() {
		Object standing = standing();
		return standing != null && java.lang.reflect.Proxy.isProxyClass(standing.getClass());
	}

	/** Runs one half of DH's frame: its renderer is handed the listing. */
	void frame(boolean opaque) {
		call(DhFakes.GEOMETRY, "frame", opaque);
	}

	void list(Object... containers) {
		call(DhFakes.GEOMETRY, "list", new Object[] { containers });
	}

	Object container(int x, int y, int z, Object[] opaque, Object[] translucent) {
		return call(DhFakes.GEOMETRY, "container", x, y, z, opaque, translucent);
	}

	Object wrapper(Object vertices, Object indices, int indexCount, int vertexCount) {
		return call(DhFakes.GEOMETRY, "wrapper", vertices, indices, indexCount, vertexCount, true);
	}

	Object wrapper(Object vertices, Object indices, int indexCount, int vertexCount, boolean uploaded) {
		return call(DhFakes.GEOMETRY, "wrapper", vertices, indices, indexCount, vertexCount, uploaded);
	}

	/** A buffer of the game's type that prints as {@code name} and is {@code bytes} long. */
	Object buffer(String name, long bytes) {
		try {
			return type(DhFakes.BUFFER).getConstructor(String.class, long.class).newInstance(name, bytes);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	void closeBuffer(Object buffer) {
		try {
			buffer.getClass().getMethod("close").invoke(buffer);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	/** Scripts what the engine's far terrain draw answers, and sees every list it is handed. */
	void onDraw(BiFunction<Boolean, List<?>, Boolean> answer) {
		poke(DhFakes.DISTANT_DRAW, "answer", answer);
	}

	/** Makes the engine's redo-the-work switch answer this. */
	void keepRedoneWork(boolean on) {
		poke(DhFakes.PASS_TIMINGS, "keep", on);
	}

	/** An exception of the game's own lost-device type, which the bridge lets through untouched. */
	static RuntimeException deviceLoss(String message) {
		for (String name : DEVICE_LOSS) {
			try {
				return (RuntimeException) Class.forName(name).getConstructor(String.class).newInstance(message);
			} catch (ClassNotFoundException e) {
				continue;
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}

		throw new AssertionError("this game has none of " + DEVICE_LOSS);
	}
}
