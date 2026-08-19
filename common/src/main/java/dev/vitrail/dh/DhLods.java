package dev.vitrail.dh;

import dev.vitrail.render.DistantDraw;
import dev.vitrail.render.DistantMesh;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Distant Horizons' own LOD geometry, taken where that mod hands it to its renderer.
 * <p>
 * <strong>Why the published road is not taken.</strong> The three interfaces a mod is meant to
 * substitute a shader through, {@code IDhApiShaderProgram},
 * {@code IDhApiGenericObjectShaderProgram} and {@code IDhApiFramebuffer}, are the ones Iris takes
 * ({@code compat/dh/DHCompatInternal.java:67-99}), and every one of them speaks in OpenGL names: a
 * program identifier, an attribute location, a framebuffer identifier. None of the three exists on
 * this backend, so what they publish is not a road that is hard to walk here, it is a road that
 * cannot be walked at all.
 * <p>
 * <strong>What is taken instead is one interface with one method.</strong> DH holds the pass that
 * draws its terrain behind {@code IDhTerrainRenderer}, bound into its own singleton injector by
 * {@code core/wrapperInterfaces/render/AbstractDhRenderApiDefinition.java:76} and read out of it
 * once by {@code core/render/renderer/LodRenderer.java:86}. That one method is handed the frame's
 * parameters and the sorted set of buffer containers, which is the whole of the far terrain: on the
 * Blaze backend each container holds ordinary blaze3d buffers, a {@code GpuBuffer} for its vertices
 * and another for its indices
 * ({@code common/render/blaze/wrappers/buffer/BlazeVertexBufferWrapper.java:42-51}), and the vertex
 * format DH built them with already carries the two attributes it added for Iris. Standing in that
 * one place reaches the geometry without reaching for anything DH draws it with.
 * <p>
 * Everything is by name, for the reason {@link DhDepth} gives in full: DH is not a dependency this
 * can be built against, and tying the repository to one jar on one machine is worse than a
 * reflective road that stays quiet on a DH which cannot answer it. Nothing here throws into a frame.
 * Every failure resolves once, says so once, and leaves DH drawing its own far terrain for the rest
 * of the session, which is the picture this engine had before this class existed.
 */
public final class DhLods {

	/** The interface with the one method, and the one thing this class stands in for. */
	private static final String TERRAIN_RENDERER =
			"com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer";

	/** The renderer that reads that interface out of the injector, once, and keeps it in a field. */
	private static final String LOD_RENDERER =
			"com.seibel.distanthorizons.core.render.renderer.LodRenderer";

	private static final String BUFFER_CONTAINER = "com.seibel.distanthorizons.core.dataObjects."
			+ "render.bufferBuilding.LodBufferContainer";

	/** What the containers arrive in: a sorted set of DH's own, which is no {@code Collection}. */
	private static final String SORTED_SET =
			"com.seibel.distanthorizons.core.util.objects.SortedArraySet";

	private static final String BUFFER_WRAPPER = "com.seibel.distanthorizons.common.render.blaze."
			+ "wrappers.buffer.BlazeVertexBufferWrapper";

	/** Where DH publishes what is only there once it has started, its proxy and its config alike. */
	private static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";

	/** The two DH post passes this engine holds off, and the answer of a config not published yet. */
	private static final int SWITCHES = 2;
	private static final int UNREACHED = -1;

	private static boolean resolved;
	private static boolean usable;

	/** The road to DH's own switch that moves its water half behind the deferred stage. */
	private static Field renderProxyField;
	private static Method deferTransparentMethod;

	/** Whether that switch has been thrown, which sticks until {@link #restore} lets go of it. */
	private static boolean deferred;

	/**
	 * Whether the two switches of DH's own post passes have been REACHED, which is what makes
	 * whatever took among them this engine's to hand back in {@link #restore}. Not whether both
	 * took: DH answers each one on its own, and one that refused is still one asked once and not
	 * every frame after.
	 */
	private static boolean muted;

	/** False once the road to those two has been walked and failed, which it will fail again. */
	private static boolean mutable = true;

	/** The renderer DH bound for itself, which everything this engine does not serve falls back on. */
	private static Object original;

	/** What stands in its place, kept so that a frame can tell whether it is still standing. */
	private static Object substitute;

	private static Object lodRenderer;
	private static Field rendererField;

	private static Field opaqueBuffersField;
	private static Field translucentBuffersField;
	private static Field cornerField;
	private static Field vertexBufferField;
	private static Method indexBufferMethod;
	private static Field indexCountField;
	private static Field vertexCountField;
	private static Field uploadedField;

	/** DH's own sorted set is not a collection of the platform's, so it is walked by name. */
	private static Method setSize;
	private static Method setGet;

	private static Method cornerX;
	private static Method cornerY;
	private static Method cornerZ;

	/** Said once a session, of the first pass that came through here carrying anything. */
	private static boolean reported;

	/** Whether DH's own stride has been measured against the format declared over it. */
	private static boolean strideChecked;

	private DhLods() {
	}

	/**
	 * Puts this engine in DH's place for the far terrain, and keeps it there.
	 * <p>
	 * Called every frame rather than once, and the reason is DH's own lifecycle rather than caution:
	 * it reads the interface out of its injector on the first frame it renders and keeps what it
	 * read in a field of its own, so a substitution made before that frame would be read over
	 * without a word. What this costs once it has taken is one reflective read of a field.
	 */
	public static void install() {
		if (!resolved) {
			resolve();
		}

		if (!usable) {
			return;
		}

		try {
			// Thrown BEFORE the substitution and required rather than best effort: without it DH
			// draws its water half inside its FIRST pass, at the head of the game's opaque chunk
			// group (core/render/renderer/LodRenderer.java:258-263 under a false
			// getDeferTransparentRendering), so the half this engine records for the far side of
			// the deferred stage would be recorded before that stage has run, and the depth taken
			// as dhDepthTex1 would already carry the water. Iris throws the same switch for the
			// same reason (compat/dh/LodRendererEvents.java:92). Null while DH is still starting,
			// and then nothing is substituted this frame either: the two go together or not at all.
			if (!deferred && !defer()) {
				return;
			}

			Object standing = rendererField.get(lodRenderer);
			if (standing != substitute) {
				// Null while DH has not bound its renderers yet, which is every frame before the
				// first one it draws. Keeping null would lose DH's own renderer for good, so the
				// substitution waits for something to fall back on.
				if (standing == null) {
					return;
				}

				original = standing;
				rendererField.set(lodRenderer, substitute);
			}

			// After the substitution and never instead of it: what it holds off is a cost, so a
			// road to it that cannot be walked leaves the far terrain served all the same. Says so
			// itself and throws nothing back here.
			mute();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			usable = false;
			Vitrail.logger().warn("Distant Horizons' far terrain cannot be taken over, so that mod "
					+ "keeps drawing it with its own shader for the rest of this session", e);
		}
	}

	/**
	 * Throws DH's own switch that moves its water half behind the deferred stage, where this
	 * engine's model of the frame expects it.
	 * <p>
	 * The frame it lands on pays one artefact, and it is accepted rather than mended: DH's first
	 * pass of that frame already ran with the switch off, water included, and its deferred entry
	 * then reads the switch on and draws the water again, so the far water is blended twice for
	 * that one frame. It is the same class of cost as the takeover itself landing on the next
	 * frame, once per session, and mending it would mean holding the whole takeover for a frame
	 * boundary nothing else needs.
	 *
	 * @return whether the switch is thrown, false while DH has not published its proxy yet
	 */
	private static boolean defer() throws ReflectiveOperationException {
		Object proxy = renderProxyField.get(null);
		if (proxy == null) {
			return false;
		}

		deferTransparentMethod.invoke(proxy, true);
		deferred = true;
		Vitrail.logger().info("Distant Horizons defers its water half behind the deferred stage, "
				+ "which is where the pack's own dh_water pass stands");

		return true;
	}

	/**
	 * Holds off the two passes DH draws over its own image once the terrain is down, neither of
	 * which anything downstream of here reads.
	 * <p>
	 * DH's third one, the apply, is already harmless for the reason {@code render/DistantDraw}
	 * gives: nothing of this engine lands in DH's colour or depth, so its apply shader finds the
	 * depth image as it cleared it and discards the whole screen. Its ambient occlusion and its fog
	 * are not harmless, they are paid: three full screen passes over that same image between them,
	 * the occlusion taking a second one to blur and apply itself
	 * ({@code common/render/blaze/postProcessing/BlazeDhSsaoRenderer.java:113-117}), and both are on
	 * by default ({@code core/config/Config.java:127} and {@code :447}), so an install that changed
	 * nothing pays for three screens of work a frame that are thrown away one pass later. Iris
	 * holds off the same two, through the same published config, at
	 * {@code compat/dh/LodRendererEvents.java:274-275}, and clears them again at {@code :281-282}.
	 * <p>
	 * <strong>What the three cost is the passes and not their bodies, and only one of the three
	 * even reaches its body.</strong> The image they read is DH's depth as it cleared it, which is
	 * nought under a reversed Z: the occlusion's own shader tests that correctly and leaves every
	 * pixel at once, while its apply and the fog both ask whether the depth is under one, which is
	 * true of nought, so those two run their full body over a screen of sky. That second half is
	 * DH's reversed Z defect rather than a cost of this engine, and a DH that fixes it would make
	 * these three passes cheap rather than free. Held off either way: a pass that draws nothing
	 * still binds, clears and stores its attachments.
	 * <p>
	 * Set rather than written: the API keeps its own value beside the player's, and {@link #restore}
	 * hands it back, so a session that stops drawing a pack finds its DH menu as it left it. Quiet
	 * about a road it cannot walk beyond one line, this being a cost and not a picture.
	 */
	private static void mute() {
		if (muted || !mutable) {
			return;
		}

		try {
			int held = hold(Boolean.FALSE);
			if (held == UNREACHED) {
				return;
			}

			// Latched on having reached them rather than on their having taken, so that whichever
			// DID take is handed back later. A switch forced and then forgotten would leave a
			// player's own menu answering for this engine long after it stopped drawing.
			muted = true;
			if (held == SWITCHES) {
				Vitrail.logger().info("Distant Horizons holds off its own ambient occlusion and "
						+ "fog, both of which draw over an image this engine never reads");
			} else {
				Vitrail.logger().info("Distant Horizons lets {} of its {} post passes be held off "
						+ "through its API, so it keeps drawing the rest over an image this engine "
						+ "never reads", held, SWITCHES);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// hold sets the two switches one after the other, so a throw between them has already
			// forced the first with muted still false, and nothing later would ever hand it back.
			// Clearing returns whatever DID take, and a second throw changes nothing.
			try {
				hold(null);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError swallowed) {
				e.addSuppressed(swallowed);
			}

			mutable = false;
			Vitrail.logger().info("Distant Horizons keeps drawing its own ambient occlusion and fog "
					+ "over an image this engine never reads, which costs a frame and shows "
					+ "nothing: {}", e.toString());
		}
	}

	/**
	 * Sets both switches to one value, or hands both back to the player when it is null.
	 * <p>
	 * The road is resolved on the spot rather than kept in fields: it is walked twice for a pack
	 * rather than once a frame, and one method that both callers share is worth more here than the
	 * eight fields holding it would save.
	 * <p>
	 * A switch answers whether it took: DH refuses through this road any config its own menu keeps
	 * to itself, and it says so rather than throwing. Counted rather than assumed, so that the line
	 * this engine writes about the two says what really happened to them.
	 *
	 * @return how many of the two took, or {@link #UNREACHED} while DH has published no config yet
	 */
	private static int hold(Boolean value) throws ReflectiveOperationException {
		Field field = Class.forName(DELAYED).getField("configs");
		Object configs = field.get(null);
		if (configs == null) {
			return UNREACHED;
		}

		// Every step is asked of the type DECLARED at the step above it, which is an interface of
		// DH's published API, rather than of the object in hand: the classes behind them are its
		// own and need not be public for this to reach them.
		Method graphics = field.getType().getMethod("graphics");
		Object graphicsConfig = graphics.invoke(configs);

		Method ambientOcclusion = graphics.getReturnType().getMethod("ambientOcclusion");
		Method fog = graphics.getReturnType().getMethod("fog");

		// The fog switch is the one DH points at rather than the one Iris still uses: its drawMode
		// is deprecated in favour of this since API 4.0.0 and reaches the same config entry through
		// a converter (core/api/external/methods/config/client/DhApiFogConfig.java:58-62), which is
		// the entry LodRenderer reads the API's answer out of (render/renderer/LodRenderer.java:189).
		Method enabled = ambientOcclusion.getReturnType().getMethod("enabled");
		Method dhFog = fog.getReturnType().getMethod("enableDhFog");

		// Both of DH's own setters answer with a boolean, and both are declared on the one config
		// value interface, so a null value picks the one that gives a switch back to the player.
		Class<?> configValue = enabled.getReturnType();
		Method action = value == null
				? configValue.getMethod("clearValue")
				: configValue.getMethod("setValue", Object.class);
		Object[] arguments = value == null ? new Object[0] : new Object[] { value };

		int held = 0;
		for (Object one : List.of(enabled.invoke(ambientOcclusion.invoke(graphicsConfig)),
				dhFog.invoke(fog.invoke(graphicsConfig)))) {
			if (Boolean.TRUE.equals(action.invoke(one, arguments))) {
				held++;
			}
		}

		return held;
	}

	/**
	 * One section of the far terrain: the corner it stands at, in blocks of the world, and the
	 * buffers it is drawn from.
	 * <p>
	 * The corner is the whole reason a section is a thing out here rather than a detail of DH's: the
	 * vertices under it hold block coordinates INSIDE it, three unsigned shorts wide, so what places
	 * them is this and it changes between draws of one pass.
	 */
	public record Section(int x, int y, int z, List<Piece> pieces) {

		public Section {
			pieces = List.copyOf(pieces);
		}
	}

	/**
	 * One draw of one section, in blaze3d's own objects and nothing of DH's. What crosses out of this
	 * package is this record and not a wrapper of DH's, so that nothing downstream of it is
	 * reflective.
	 */
	public record Piece(GpuBuffer vertices, GpuBuffer indices, int indexCount) {
	}

	private static void resolve() {
		resolved = true;

		try {
			Class<?> rendererType = Class.forName(TERRAIN_RENDERER);
			Class<?> lodRendererType = Class.forName(LOD_RENDERER);

			lodRenderer = lodRendererType.getField("INSTANCE").get(null);
			rendererField = lodRendererType.getDeclaredField("terrainRenderer");
			rendererField.setAccessible(true);

			// The switch is published API, so the road to it is short: the delayed holder and the
			// one setter on the proxy's interface.
			renderProxyField = Class.forName(DELAYED).getField("renderProxy");
			deferTransparentMethod = renderProxyField.getType()
					.getMethod("setDeferTransparentRendering", boolean.class);

			resolveGeometry();

			substitute = Proxy.newProxyInstance(DhLods.class.getClassLoader(),
					new Class<?>[] { rendererType }, new Handler());

			usable = true;
			// And the second half of that sentence, because standing in DH's place takes something
			// away as well: DH's own pass fires one DhApiBeforeBufferRenderEvent per buffer
			// (common/render/blaze/BlazeDhTerrainRenderer.java:313-323) and this substitute records
			// the draws itself, so the event stays quiet for a HALF the pack really draws. The
			// granularity is the half and not the frame: a half handed back to DH, no program for
			// it, a broken chain or empty sections (DistantDraw.draw answering false), goes through
			// DH's own renderer and fires the event as ever, and so does everything while install
			// has not landed, this line being logged before either of install's early returns.
			// Said rather than fired: what an outside consumer wants from it is DH's own draw
			// about to happen, and none is about to happen for a half the pack has taken.
			Vitrail.logger().info("Distant Horizons found, its far terrain will be read where that "
					+ "mod hands it to its own renderer; that mod's own per buffer render event "
					+ "stays quiet for a half a pack draws, and still fires for one handed back");
		} catch (ClassNotFoundException e) {
			// The ordinary case: DH is simply not installed. Not worth a line above debug.
			usable = false;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			usable = false;
			Vitrail.logger().info("Distant Horizons is installed but not in a shape its far terrain "
					+ "can be taken out of, so that mod keeps drawing it: {}", e.toString());
		}
	}

	/**
	 * Opens the road from one container down to the buffers under it. Every member of it is public in
	 * DH; what is not published is which wrapper the buffers arrive in, since that is the backend's
	 * answer, so the Blaze one is named here and a container holding anything else reads as nothing
	 * to draw.
	 */
	private static void resolveGeometry() throws ReflectiveOperationException {
		Class<?> container = Class.forName(BUFFER_CONTAINER);
		opaqueBuffersField = container.getField("vboOpaqueWrappers");
		translucentBuffersField = container.getField("vboTransparentWrappers");
		cornerField = container.getField("minCornerBlockPos");

		cornerX = cornerField.getType().getMethod("getX");
		cornerY = cornerField.getType().getMethod("getY");
		cornerZ = cornerField.getType().getMethod("getZ");

		Class<?> set = Class.forName(SORTED_SET);
		setSize = set.getMethod("size");
		setGet = set.getMethod("get", int.class);

		Class<?> wrapper = Class.forName(BUFFER_WRAPPER);
		vertexBufferField = wrapper.getField("vertexGpuBuffer");
		indexBufferMethod = wrapper.getMethod("getIndexGpuBuffer");
		indexCountField = wrapper.getField("indexCount");
		vertexCountField = wrapper.getField("vertexCount");
		uploadedField = wrapper.getField("uploaded");
	}

	/** Stands in DH's place, reads what the pass was handed, and hands the pass back to DH. */
	private static final class Handler implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (usable && "render".equals(method.getName()) && args != null && args.length == 4) {
				boolean opaque = (Boolean) args[1];
				try {
					List<Section> sections = sections(args[2], opaque);
					report(sections, opaque);
					// Drawn by the pack, or handed straight back to DH below. There is no third answer
					// and no half of one: a pass this engine records and then lets DH record again
					// would draw the far terrain twice, once lit by each engine.
					if (DistantDraw.draw(opaque, sections)) {
						return null;
					}
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					usable = false;
					// Handed back for good and not merely stopped: this proxy stands in front of DH's
					// own renderer, so a failure that left it standing would go on paying for a
					// reflective hop on every buffer of every frame for nothing.
					restore();
					Vitrail.logger().warn("Distant Horizons' far terrain cannot be read out of the "
							+ "pass it is drawn in, so that mod keeps drawing it with its own shader "
							+ "for the rest of this session", e);
				}
			}

			try {
				return method.invoke(original, args);
			} catch (InvocationTargetException e) {
				// Unwrapped, or the proxy hands DH's own failure back to DH wrapped in one of ours.
				throw e.getCause();
			}
		}
	}

	/**
	 * Hands DH its far terrain back because no pack is drawn any more: the renderer, and its own
	 * frame order with it. Called when the chain releases, and it has to be: {@link #install} runs
	 * only while a pack is drawn, so nothing else would ever put the deferred-water switch back,
	 * and a session whose shaders were turned off would keep DH on a frame order it only holds for
	 * a consumer, its far-clip fade among the things that order turns off. Iris keeps the same
	 * switch tied to a live condition rather than latched
	 * ({@code compat/dh/LodRendererEvents.java:92}). Quiet and idempotent: the ordinary caller is
	 * every pack unload, most of which never took the far terrain over at all.
	 */
	public static void handBack() {
		if (substitute != null && original != null) {
			restore();
		}
	}

	/**
	 * Hands DH its own renderer back, so that nothing of this engine stands in the way of a far
	 * terrain it has stopped being able to serve.
	 */
	private static void restore() {
		try {
			rendererField.set(lodRenderer, original);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// Nothing left to try: the field that was written once cannot be written back. Said at
			// debug alone, the line that brought us here having already told the reader what is lost.
			Vitrail.logger().debug("Distant Horizons' own far terrain renderer cannot be put back", e);
		}

		// And its own frame order with it, so a DH handed back draws exactly as it did before this
		// engine stood in the way. Iris clears the same switch when it stops overriding
		// (compat/dh/LodRendererEvents.java:92 setting it from a live condition).
		if (deferred) {
			deferred = false;
			try {
				Object proxy = renderProxyField.get(null);
				if (proxy != null) {
					deferTransparentMethod.invoke(proxy, false);
				}
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				Vitrail.logger().debug("Distant Horizons' deferred water switch cannot be put back", e);
			}
		}

		// And its own two post passes, which are worth having again the moment their image is the
		// one on screen. Handed back to the PLAYER rather than set true: what the menu says is his,
		// and this engine only ever stood in front of it.
		if (muted) {
			try {
				// Cleared only where the clearing really ran: a throw leaves the debt standing,
				// and so does UNREACHED, so the next restore tries again rather than forgetting
				// that a switch of the player's is still overridden.
				if (hold(null) != UNREACHED) {
					muted = false;
				}
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				Vitrail.logger().debug("Distant Horizons' own ambient occlusion and fog cannot be "
						+ "handed back", e);
			}
		}
	}

	/** One half of one pass, out of DH's objects and into this engine's. */
	private static List<Section> sections(Object set, boolean opaque)
			throws ReflectiveOperationException {
		List<Section> sections = new ArrayList<>();

		int count = (Integer) setSize.invoke(set);
		for (int index = 0; index < count; index++) {
			Object one = setGet.invoke(set, index);
			Object[] wrappers =
					(Object[]) (opaque ? opaqueBuffersField : translucentBuffersField).get(one);
			if (wrappers == null) {
				continue;
			}

			List<Piece> pieces = new ArrayList<>();
			for (Object wrapper : wrappers) {
				if (wrapper == null || !uploadedField.getBoolean(wrapper)
						|| vertexCountField.getInt(wrapper) <= 0) {
					continue;
				}

				Object vertices = vertexBufferField.get(wrapper);
				Object indices = indexBufferMethod.invoke(wrapper);
				if (vertices instanceof GpuBuffer vertexBuffer
						&& indices instanceof GpuBuffer indexBuffer
						&& !vertexBuffer.isClosed()) {
					checkStride(vertexBuffer, vertexCountField.getInt(wrapper));
					pieces.add(new Piece(vertexBuffer, indexBuffer, indexCountField.getInt(wrapper)));
				}
			}

			if (!pieces.isEmpty()) {
				Object corner = cornerField.get(one);
				sections.add(new Section((Integer) cornerX.invoke(corner),
						(Integer) cornerY.invoke(corner), (Integer) cornerZ.invoke(corner),
						List.copyOf(pieces)));
			}
		}

		return List.copyOf(sections);
	}

	/** Says once what the far terrain really holds, which is what tells a reader it is reachable. */
	private static void report(List<Section> sections, boolean opaque) {
		if (reported || sections.isEmpty()) {
			return;
		}

		reported = true;
		int pieces = sections.stream().mapToInt(one -> one.pieces().size()).sum();
		int indices = sections.stream().flatMap(one -> one.pieces().stream())
				.mapToInt(Piece::indexCount).sum();
		Vitrail.logger().info("Distant Horizons' far terrain is reachable on this backend: {} "
				+ "sections, {} buffers, {} indices in the {} half", sections.size(), pieces, indices,
				opaque ? "opaque" : "translucent");
	}

	/**
	 * Checks that DH really wrote its vertices as wide as the format this engine declares over them,
	 * once a session, and closes the road when it did not.
	 * <p>
	 * <strong>A buffer's length divided by the vertices DH says are in it IS the stride, and the
	 * division holds because DH leaves no slack for it to fall over.</strong> One call sets both:
	 * {@code uploadVertexBuffer} keeps the count it is handed and allocates exactly the bytes the
	 * builder left in front of it, {@code position} to {@code limit}
	 * ({@code common/render/blaze/wrappers/buffer/BlazeVertexBufferWrapper.java:110} and
	 * {@code :133-136}). What the quotient has to be is DH's own constant,
	 * {@code LodQuadBuilder.BYTES_PER_VERTEX} at {@code :57}, and the six vertex elements
	 * {@code putVertex} writes add up to it ({@code LodQuadBuilder.java:479-518}). So a DH that added an
	 * element or widened one shows up here as another number; read through the wrong format, the
	 * same buffer draws a far terrain out of the wrong bytes, which is a picture rather than a
	 * failure. The road is closed rather than corrected: what a wider vertex means cannot be worked
	 * out from its width.
	 */
	private static void checkStride(GpuBuffer vertices, int count) {
		if (strideChecked || count <= 0) {
			return;
		}

		strideChecked = true;
		long stride = vertices.size() / count;
		if (stride != DistantMesh.STRIDE) {
			usable = false;
			restore();
			Vitrail.logger().warn("Distant Horizons writes {} bytes a vertex where this engine's "
					+ "format declares {}, so that mod keeps drawing its far terrain with its own "
					+ "shader for the rest of this session: read through the wrong format, its "
					+ "buffers would draw a landscape out of the wrong bytes", stride,
					DistantMesh.STRIDE);
		}
	}
}
