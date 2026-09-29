package dev.vitrail.dh;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The Java text of everything {@link DhWorld} defines next to a private copy of the bridge: a
 * Distant Horizons that has exactly the classes, fields and methods the bridge looks up by name, and
 * three stand-ins for the engine classes the bridge calls.
 * <p>
 * Text and not compiled classes, because a test that wants a DH with one field renamed needs a
 * different class and not a different value: {@link DhWorld} edits the text and compiles it at run
 * time. It is also what keeps these names off the test class path. Every member the bridge looks up
 * is declared and used inside one source only (a static factory or accessor beside it), so an edit
 * of one source never breaks the compile of another.
 * <p>
 * {@code Stage}, which every test reads its events from, names the fake DH types only through
 * {@code Object}, so that the verifier has no reason to load them: a world that lacks one of those
 * types can still link it and report that nothing happened, which is what a test of a missing class
 * needs.
 * <p>
 * What the fake DH does is recorded in {@code Stage.EVENTS}, one line per call the bridge makes into
 * it, and what it is told to do is set through the static methods of {@code Stage} and
 * {@code Geometry}. Both live in the world they are compiled into, so no state is shared between two
 * tests.
 */
final class DhFakes {

	static final String STAGE = "com.seibel.distanthorizons.fake.Stage";
	static final String GEOMETRY = "com.seibel.distanthorizons.fake.Geometry";
	static final String BUFFER = "com.seibel.distanthorizons.fake.Buf";

	static final String TERRAIN_RENDERER = "com.seibel.distanthorizons.core.wrapperInterfaces."
			+ "render.renderPass.IDhTerrainRenderer";
	static final String LOD_RENDERER = "com.seibel.distanthorizons.core.render.renderer.LodRenderer";
	static final String BUFFER_CONTAINER = "com.seibel.distanthorizons.core.dataObjects."
			+ "render.bufferBuilding.LodBufferContainer";
	static final String SORTED_SET = "com.seibel.distanthorizons.core.util.objects.SortedArraySet";
	static final String BUFFER_WRAPPER = "com.seibel.distanthorizons.common.render.blaze."
			+ "wrappers.buffer.BlazeVertexBufferWrapper";
	static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";
	static final String CLIENT_API = "com.seibel.distanthorizons.core.api.internal.ClientApi";
	static final String CONFIG = "com.seibel.distanthorizons.api.config.IDhApiConfig";
	static final String RENDER_PROXY = "com.seibel.distanthorizons.api.render.IDhApiRenderProxy";
	static final String DH_API = "com.seibel.distanthorizons.api.DhApi";

	static final String VITRAIL = "dev.vitrail.Vitrail";
	static final String DISTANT_DRAW = "dev.vitrail.render.DistantDraw";
	static final String PASS_TIMINGS = "dev.vitrail.render.timing.PassTimings";

	private DhFakes() {
	}

	/** Every source of the fake DH and of the three engine stand-ins, by class name. */
	static Map<String, String> sources() {
		Map<String, String> sources = new LinkedHashMap<>();

		sources.put(DH_API, """
				package com.seibel.distanthorizons.api;

				import com.seibel.distanthorizons.api.config.IDhApiConfig;
				import com.seibel.distanthorizons.api.render.IDhApiRenderProxy;

				public class DhApi {

					public static void publishProxy(IDhApiRenderProxy proxy) {
						Delayed.renderProxy = proxy;
					}

					public static void publishConfigs(IDhApiConfig configs) {
						Delayed.configs = configs;
					}

					public static class Delayed {

						public static IDhApiRenderProxy renderProxy;

						public static IDhApiConfig configs;
					}
				}
				""");

		sources.put(RENDER_PROXY, """
				package com.seibel.distanthorizons.api.render;

				public interface IDhApiRenderProxy {

					void setDeferTransparentRendering(boolean deferred);
				}
				""");

		sources.put(CONFIG, """
				package com.seibel.distanthorizons.api.config;

				public interface IDhApiConfig {

					Graphics graphics();

					interface Graphics {

						Ao ambientOcclusion();

						Fog fog();

						Value renderingEnabled();

						Value chunkRenderDistance();
					}

					interface Ao {

						Value enabled();
					}

					interface Fog {

						Value enableDhFog();
					}

					interface Value {

						Object getValue();

						boolean setValue(Object value);

						boolean clearValue();
					}
				}
				""");

		sources.put(TERRAIN_RENDERER, """
				package com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass;

				import com.seibel.distanthorizons.core.util.objects.SortedArraySet;

				public interface IDhTerrainRenderer {

					void render(Object params, boolean opaque, SortedArraySet containers, boolean firstPass);

					void render(Object single);

					String name();

					void fail();

					void failChecked() throws java.io.IOException;
				}
				""");

		sources.put(LOD_RENDERER, """
				package com.seibel.distanthorizons.core.render.renderer;

				import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
				import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
				import com.seibel.distanthorizons.fake.Stage;

				public class LodRenderer {

					static {
						Stage.hit("LodRenderer.init");
					}

					public static final LodRenderer INSTANCE = new LodRenderer();

					private IDhTerrainRenderer terrainRenderer;

					public static void bind(Object renderer) {
						INSTANCE.terrainRenderer = (IDhTerrainRenderer) renderer;
					}

					public static Object standing() {
						return INSTANCE.terrainRenderer;
					}

					public static void frame(boolean opaque, SortedArraySet containers) {
						INSTANCE.terrainRenderer.render("params", opaque, containers, true);
					}
				}
				""");

		sources.put(BUFFER_CONTAINER, """
				package com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding;

				public class LodBufferContainer {

					public Object[] vboOpaqueWrappers;

					public Object[] vboTransparentWrappers;

					public Pos minCornerBlockPos;

					public static Object make(int x, int y, int z, Object[] opaque, Object[] translucent) {
						LodBufferContainer container = new LodBufferContainer();
						container.vboOpaqueWrappers = opaque;
						container.vboTransparentWrappers = translucent;
						container.minCornerBlockPos = new Pos(x, y, z);
						return container;
					}

					public static class Pos {

						private final int x;
						private final int y;
						private final int z;

						public Pos(int x, int y, int z) {
							this.x = x;
							this.y = y;
							this.z = z;
						}

						public int getX() {
							return x;
						}

						public int getY() {
							return y;
						}

						public int getZ() {
							return z;
						}
					}
				}
				""");

		sources.put(SORTED_SET, """
				package com.seibel.distanthorizons.core.util.objects;

				import com.seibel.distanthorizons.fake.Stage;

				public class SortedArraySet {

					private Object[] items = new Object[0];

					public void fill(Object[] containers) {
						items = containers.clone();
					}

					public int size() {
						return items.length;
					}

					public Object get(int index) throws Exception {
						Stage.pull();
						return items[index];
					}

					public String toString() {
						return "set:" + items.length;
					}
				}
				""");

		sources.put(BUFFER_WRAPPER, """
				package com.seibel.distanthorizons.common.render.blaze.wrappers.buffer;

				public class BlazeVertexBufferWrapper {

					public Object vertexGpuBuffer;

					public Object indexGpuBuffer;

					public int indexCount;

					public int vertexCount;

					public boolean uploaded;

					public static Object make(Object vertices, Object indices, int indexCount, int vertexCount,
							boolean uploaded) {
						BlazeVertexBufferWrapper wrapper = new BlazeVertexBufferWrapper();
						wrapper.vertexGpuBuffer = vertices;
						wrapper.indexGpuBuffer = indices;
						wrapper.indexCount = indexCount;
						wrapper.vertexCount = vertexCount;
						wrapper.uploaded = uploaded;
						return wrapper;
					}

					public Object getIndexGpuBuffer() {
						return indexGpuBuffer;
					}
				}
				""");

		sources.put(CLIENT_API, """
				package com.seibel.distanthorizons.core.api.internal;

				import com.seibel.distanthorizons.fake.Stage;

				public class ClientApi {

					static {
						Stage.hit("ClientApi.init");
					}

					private static Params RENDER_PARAMS;

					public static void publish(float m22, float m23) {
						Mat4 matrix = new Mat4();
						matrix.m22 = m22;
						matrix.m23 = m23;
						Params params = new Params();
						params.dhProjectionMatrix = matrix;
						RENDER_PARAMS = params;
					}

					public static void publishWithoutMatrix() {
						RENDER_PARAMS = new Params();
					}

					public static void publishNothing() {
						RENDER_PARAMS = null;
					}

					public static class Params {

						public Mat4 dhProjectionMatrix;
					}

					public static class Mat4 {

						public float m22;

						public float m23;
					}
				}
				""");

		sources.put("com.seibel.distanthorizons.fake.OriginalRenderer", """
				package com.seibel.distanthorizons.fake;

				import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
				import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
				import java.io.IOException;

				public final class OriginalRenderer implements IDhTerrainRenderer {

					public void render(Object params, boolean opaque, SortedArraySet containers, boolean firstPass) {
						Stage.hit("original.render(" + opaque + "," + containers + "," + firstPass + ")");
					}

					public void render(Object single) {
						Stage.hit("original.render(" + single + ")");
					}

					public String name() {
						return "original";
					}

					public void fail() {
						IllegalStateException failure = new IllegalStateException("dh failed");
						Stage.failed(failure);
						throw failure;
					}

					public void failChecked() throws IOException {
						IOException failure = new IOException("dh io failed");
						Stage.failed(failure);
						throw failure;
					}
				}
				""");

		sources.put(GEOMETRY, """
				package com.seibel.distanthorizons.fake;

				import com.seibel.distanthorizons.common.render.blaze.wrappers.buffer.BlazeVertexBufferWrapper;
				import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
				import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
				import com.seibel.distanthorizons.core.util.objects.SortedArraySet;

				public final class Geometry {

					private static final SortedArraySet SET = new SortedArraySet();

					public static Object wrapper(Object vertices, Object indices, int indexCount, int vertexCount,
							boolean uploaded) {
						return BlazeVertexBufferWrapper.make(vertices, indices, indexCount, vertexCount, uploaded);
					}

					public static Object container(int x, int y, int z, Object[] opaque, Object[] translucent) {
						return LodBufferContainer.make(x, y, z, opaque, translucent);
					}

					public static void list(Object[] containers) {
						SET.fill(containers);
					}

					public static void frame(boolean opaque) {
						LodRenderer.frame(opaque, SET);
					}
				}
				""");

		sources.put(STAGE, """
				package com.seibel.distanthorizons.fake;

				import com.seibel.distanthorizons.api.DhApi;
				import com.seibel.distanthorizons.api.config.IDhApiConfig;
				import com.seibel.distanthorizons.core.api.internal.ClientApi;
				import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
				import java.io.IOException;
				import java.lang.reflect.InvocationHandler;
				import java.lang.reflect.Proxy;
				import java.util.ArrayList;
				import java.util.HashMap;
				import java.util.HashSet;
				import java.util.List;
				import java.util.Map;
				import java.util.Set;

				public final class Stage {

					public static final List<String> EVENTS = new ArrayList<>();

					private static final Set<String> FAILING = new HashSet<>();

					private static final Map<String, Value> VALUES = new HashMap<>();

					private static final Map<String, String> VALUE_OF = Map.of("enabled", "ao", "enableDhFog", "fog",
							"renderingEnabled", "rendering", "chunkRenderDistance", "distance");

					private static Object original;

					private static Throwable lastFailure;

					private static int pulls;

					static {
						VALUES.put("ao", new Value(Boolean.TRUE));
						VALUES.put("fog", new Value(Boolean.TRUE));
						VALUES.put("rendering", new Value(Boolean.TRUE));
						VALUES.put("distance", new Value(Integer.valueOf(12)));
					}

					private static final class Value {

						private Object player;
						private Object api;
						private boolean takes = true;

						private Value(Object player) {
							this.player = player;
						}
					}

					public static void hit(String event) {
						EVENTS.add(event);
						if (FAILING.contains(event)) {
							throw new IllegalStateException("fake DH failed at " + event);
						}
					}

					public static void failOn(String event) {
						FAILING.add(event);
					}

					public static void heal(String event) {
						FAILING.remove(event);
					}

					public static void failed(Throwable failure) {
						lastFailure = failure;
					}

					public static Throwable lastFailure() {
						return lastFailure;
					}

					public static void pull() throws IOException {
						pulls++;
						if (FAILING.contains("set.get")) {
							throw new IllegalStateException("fake DH failed at set.get");
						}
						if (FAILING.contains("set.get.checked")) {
							throw new IOException("fake DH failed at set.get.checked");
						}
					}

					public static int pulls() {
						return pulls;
					}

					public static void publishProxy() {
						DhApi.publishProxy(deferred -> hit("proxy.defer(" + deferred + ")"));
					}

					public static void unpublishProxy() {
						DhApi.publishProxy(null);
					}

					public static void publishConfigs() {
						DhApi.publishConfigs((IDhApiConfig) node(IDhApiConfig.class, null));
					}

					public static void unpublishConfigs() {
						DhApi.publishConfigs(null);
					}

					public static void player(String name, Object value) {
						VALUES.get(name).player = value;
					}

					public static void refuse(String name) {
						VALUES.get(name).takes = false;
					}

					public static Object effective(String name) {
						Value value = VALUES.get(name);
						return value.api != null ? value.api : value.player;
					}

					public static void bindRenderer() {
						OriginalRenderer renderer = new OriginalRenderer();
						original = renderer;
						LodRenderer.bind(renderer);
					}

					public static Object standing() {
						return LodRenderer.standing();
					}

					public static Object original() {
						return original;
					}

					public static void matrix(float m22, float m23) {
						ClientApi.publish(m22, m23);
					}

					public static void paramsWithoutMatrix() {
						ClientApi.publishWithoutMatrix();
					}

					public static void noParams() {
						ClientApi.publishNothing();
					}

					private static Object node(Class<?> type, String valueName) {
						return Proxy.newProxyInstance(Stage.class.getClassLoader(), new Class<?>[] { type },
								handler(valueName));
					}

					private static InvocationHandler handler(String valueName) {
						return (proxy, method, args) -> {
							switch (method.getName()) {
								case "hashCode":
									return System.identityHashCode(proxy);
								case "equals":
									return proxy == args[0];
								case "toString":
									return "fake config node";
								default:
									break;
							}

							if (valueName == null) {
								return node(method.getReturnType(), VALUE_OF.get(method.getName()));
							}

							Value value = VALUES.get(valueName);
							switch (method.getName()) {
								case "getValue":
									hit(valueName + ".get");
									return value.api != null ? value.api : value.player;
								case "setValue":
									hit(valueName + ".set(" + args[0] + ")");
									if (!value.takes) {
										return Boolean.FALSE;
									}
									value.api = args[0];
									return Boolean.TRUE;
								case "clearValue":
									hit(valueName + ".clear");
									if (!value.takes) {
										return Boolean.FALSE;
									}
									value.api = null;
									return Boolean.TRUE;
								default:
									throw new IllegalStateException(method.getName());
							}
						};
					}
				}
				""");

		sources.put(VITRAIL, """
				package dev.vitrail;

				import java.lang.reflect.Proxy;
				import java.util.ArrayList;
				import java.util.Arrays;
				import java.util.List;
				import java.util.Locale;
				import org.slf4j.Logger;
				import org.slf4j.helpers.FormattingTuple;
				import org.slf4j.helpers.MessageFormatter;

				public final class Vitrail {

					public static final List<String> LOG = new ArrayList<>();

					private static final Logger LOGGER = (Logger) Proxy.newProxyInstance(Vitrail.class.getClassLoader(),
							new Class<?>[] { Logger.class }, (proxy, method, args) -> {
								switch (method.getName()) {
									case "hashCode":
										return System.identityHashCode(proxy);
									case "equals":
										return proxy == args[0];
									case "toString":
										return "recording logger";
									default:
										break;
								}

								if (args == null || args.length == 0 || !(args[0] instanceof String pattern)) {
									return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
								}

								Object[] rest = Arrays.copyOfRange(args, 1, args.length);
								if (rest.length == 1 && rest[0] instanceof Object[] spread) {
									rest = spread;
								}

								FormattingTuple tuple = MessageFormatter.arrayFormat(pattern, rest);
								Throwable thrown = tuple.getThrowable();
								String cause = thrown == null || thrown.getCause() == null ? ""
										: " / " + thrown.getCause();
								LOG.add(method.getName().toUpperCase(Locale.ROOT) + " " + tuple.getMessage()
										+ (thrown == null ? "" : " <" + thrown + cause + ">"));
								return null;
							});

					public static Logger logger() {
						return LOGGER;
					}
				}
				""");

		sources.put(DISTANT_DRAW, """
				package dev.vitrail.render;

				import java.util.List;
				import java.util.function.BiFunction;

				public final class DistantDraw {

					public static BiFunction<Boolean, List<?>, Boolean> answer = (opaque, sections) -> Boolean.FALSE;

					public static boolean draw(boolean opaque, List<?> sections) {
						return answer.apply(opaque, sections);
					}
				}
				""");

		sources.put(PASS_TIMINGS, """
				package dev.vitrail.render.timing;

				import java.util.ArrayList;
				import java.util.List;

				public final class PassTimings {

					public static boolean keep;

					public static final List<Integer> CENSUS = new ArrayList<>();

					public static boolean keepRedoneWork() {
						return keep;
					}

					public static void censusFarSections(int count) {
						CENSUS.add(count);
					}
				}
				""");

		return sources;
	}

	/**
	 * The source of a buffer the bridge accepts as a {@code GpuBuffer}, written from the game's own
	 * type.
	 * <p>
	 * Generated because the type is not the same thing in the two games this tree builds for: an
	 * abstract class with a constructor in 26.2, an interface in 26.3 (which also moved it, see
	 * {@code versions/26.3.remap}). Every abstract member of whichever it is gets a body, so the same
	 * code serves both. The buffer says a name where a reader prints it, has the size it is given and
	 * closes when asked.
	 *
	 * @param type the game's buffer type
	 */
	static String bufferSource(Class<?> type) {
		StringBuilder source = new StringBuilder(1024);
		source.append("package com.seibel.distanthorizons.fake;\n\npublic final class Buf ")
				.append(type.isInterface() ? "implements " : "extends ")
				.append(type.getCanonicalName()).append(" {\n")
				.append("\tprivate final String name;\n\tprivate final long bytes;\n\tprivate boolean closed;\n")
				.append("\tpublic Buf(String name, long bytes) {\n")
				.append(type.isInterface() ? "" : "\t\tsuper(0, bytes);\n")
				.append("\t\tthis.name = name;\n\t\tthis.bytes = bytes;\n\t}\n")
				.append("\tpublic String toString() {\n\t\treturn name;\n\t}\n");

		Map<String, Method> abstracts = new TreeMap<>();
		for (Method method : type.getMethods()) {
			if (Modifier.isAbstract(method.getModifiers())) {
				StringBuilder key = new StringBuilder(method.getName());
				for (Class<?> parameter : method.getParameterTypes()) {
					key.append(',').append(parameter.getName());
				}
				abstracts.putIfAbsent(key.toString(), method);
			}
		}

		for (Method method : abstracts.values()) {
			source.append("\tpublic ").append(method.getReturnType().getCanonicalName()).append(' ')
					.append(method.getName()).append('(');
			Class<?>[] parameters = method.getParameterTypes();
			for (int index = 0; index < parameters.length; index++) {
				source.append(index == 0 ? "" : ", ").append(parameters[index].getCanonicalName())
						.append(" p").append(index);
			}

			source.append(") {\n\t\t").append(switch (method.getName()) {
				case "isClosed" -> "return closed;";
				case "close" -> "closed = true;";
				case "size" -> "return bytes;";
				case "usage" -> "return 0;";
				default -> "throw new UnsupportedOperationException();";
			}).append("\n\t}\n");
		}

		return source.append("}\n").toString();
	}
}
