package dev.vitrail.dh;

import dev.vitrail.render.DistantDraw;
import dev.vitrail.render.DistantMesh;
import dev.vitrail.render.PassTimings;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
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
 * parameters and DH's own set of buffer containers, which is the whole of the far terrain: on the
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
 * of the session, which is the picture this engine draws without this class.
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

	/**
	 * What the containers arrive in: a set of DH's own, which is no {@code Collection}.
	 * <p>
	 * Sorted in its name alone, so nothing here may read an order into it. Its {@code add} appends
	 * and never sorts, and the comparator it is built with is used by one constructor, the one
	 * taking a {@code Collection}, which is not the one DH calls
	 * ({@code core/util/objects/SortedArraySet.java:42-61} against
	 * {@code core/render/RenderBufferHandler.java:105}). What the walk below therefore reads is the
	 * order the quad tree's own {@code enabledSections} list holds, frustum culling aside.
	 */
	private static final String SORTED_SET =
			"com.seibel.distanthorizons.core.util.objects.SortedArraySet";

	private static final String BUFFER_WRAPPER = "com.seibel.distanthorizons.common.render.blaze."
			+ "wrappers.buffer.BlazeVertexBufferWrapper";

	/** Where DH publishes what is only there once it has started, its proxy and its config alike. */
	private static final String DELAYED = "com.seibel.distanthorizons.api.DhApi$Delayed";

	/** The two DH post passes this engine holds off, and the answer of a config not published yet. */
	private static final int SWITCHES = 2;
	private static final int UNREACHED = -1;

	/** What {@link #listed} holds when it holds nothing, shared so that forgetting costs nothing. */
	private static final Object[] NOTHING = new Object[0];

	private static boolean resolved;

	/**
	 * Volatile for the one reader off the render thread, {@code DistantProgram.warmAhead}: it
	 * asks whether the far terrain family's pipelines are worth compiling ahead, and a stale
	 * answer there costs a skipped or a wasted warm-up, never an image.
	 */
	private static volatile boolean usable;

	/**
	 * Whether DH's far terrain is currently taken out of it. False before {@link #install} first
	 * resolves, and dropped for good when DH stops cooperating mid-session: once it falls it
	 * never comes back up.
	 */
	public static boolean usable() {
		return usable;
	}

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

	/** DH's own set is not a collection of the platform's, so it is walked by name. */
	private static Method setSize;

	/**
	 * The one member of that road asked for a section at a time, and a handle rather than a
	 * {@link Method} for it alone.
	 * <p>
	 * {@code Method.invoke} pays for the ask twice over every time it is made: an {@code Object[]}
	 * for its variable arity and an {@code Integer} for an index it has no way to pass as an
	 * {@code int}. {@code invokeExact} against a handle of DH's own signature passes the bare int
	 * and builds neither. What it does NOT buy is the call: a handle the compiler can fold is a
	 * {@code static final} one, this is settled into a field on the first pass because the class
	 * behind it need not exist at all, and DH's method is therefore reached through the handle
	 * rather than inlined into the walk. That is where {@code invoke} left it too, so this is two
	 * objects a section a half removed and nothing else moved.
	 */
	private static MethodHandle setGet;

	private static Method cornerX;
	private static Method cornerY;
	private static Method cornerZ;

	/** Said once a session, of the first pass that came through here carrying anything. */
	private static boolean reported;

	/** Whether DH's own stride has been measured against the format declared over it. */
	private static boolean strideChecked;

	/**
	 * DH's containers as its last listing held them, and the two halves read out of that listing.
	 * <p>
	 * <strong>The listing is what moves, and a container in it does not.</strong> DH clears and
	 * refills ONE set out of the sections its quad tree says are enabled and its frustum did not
	 * cull ({@code core/render/RenderBufferHandler} in {@code buildRenderList}, reached from
	 * {@code core/render/renderer/LodRenderer.renderTerrain} under its {@code firstPass} alone and
	 * never from the deferred half), so the set is the same object every frame and only what is in
	 * it says whether anything changed. What it is filled with is
	 * {@code LodRenderSection.renderBufferContainer}, and a section only ever holds a container
	 * whose buffers are already uploaded whole: the assignment takes the container or null on its
	 * {@code buffersUploaded}, which is set once the futures of all of its wrappers have completed,
	 * and a section whose data changed gets a NEW container while the old one is closed. So a
	 * container that is in the listing carries the same buffers, the same counts and the same corner
	 * for as long as it is in it, and the containers alone answer whether the reading below is still
	 * the right one.
	 * <p>
	 * <strong>What that refill is not is once every frame DH draws, and the one frame it misses is
	 * where a kept answer can carry a closed buffer.</strong>
	 * {@code core/api/internal/ClientApi.renderLodLayer} is entered twice a frame and can return
	 * from the first entry while the second still draws: the recovery from a renderer disabled by an
	 * exception clears its own flag and returns before {@code LodRenderer.render}, and a
	 * {@code DhApiBeforeRenderEvent} that cancels returns there too while the deferred half answers
	 * to an event of its own. On such a frame the set is the one the last drawing frame left, and
	 * {@code runRenderThreadTasks} has already run at the head of it, that being where a container
	 * replaced since really frees its buffers ({@code LodBufferContainer.close} queueing the work
	 * rather than doing it). Nothing moved, so the answer kept for that half is handed back with
	 * buffers closed under it. What makes that safe is not this class: {@code render/DistantDraw}
	 * asks {@code isClosed} of both buffers of every piece on both halves
	 * ({@code render/DistantDraw.java:712} and {@code :875}) and drops the piece, so the memo rests
	 * on that guard and is not to be read as a promise the buffers are live.
	 * <p>
	 * Compared rather than hashed: a hash of a listing this wide would trade a stale far terrain for
	 * a handful of instructions, and the walk that compares is the walk that would hash.
	 * <p>
	 * Worked out here rather than asked of DH because there is nothing to ask: 3.2.1 puts no
	 * generation on the set, on the handler that fills it or on the frame's own parameters.
	 */
	private static Object[] listed = NOTHING;

	private static int listedCount;

	private static List<Section> opaqueSections;
	private static List<Section> translucentSections;

	/**
	 * How many times {@link #forget} has let go of all three, which is read across a
	 * {@link #build} and nowhere else.
	 * <p>
	 * The walk that builds an answer can drop the very thing the answer would be kept beside:
	 * {@link #checkStride} closes the road from inside it and {@link #restore} forgets on its way
	 * out. An answer written after that would put a far view of containers, and the buffers under
	 * them, straight back into a class nothing will call again, and hold them for the session.
	 */
	private static int drops;

	/**
	 * Whether DH handed a pass over since the last {@link #install}, which is once a frame.
	 * <p>
	 * DH's renderer is a setting of its own and can be turned off mid-session, and it is turned off
	 * where nothing here can see it: {@code core/api/internal/ClientApi.renderLodLayer} returns on a
	 * {@code rendererMode} that is not DEFAULT, DISABLED first of all, before either half reaches
	 * {@code core/render/renderer/LodRenderer}, so this class is not called at all rather than
	 * called with an empty listing. What is kept would then be the containers of the last frame DH
	 * drew, and the buffers under them, held for as long as the pack is.
	 */
	private static boolean drawn;

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

		// Nothing of DH's held across a frame DH did not draw, for the reason the drawn field
		// gives. This line stands between the two halves of a frame rather than after both, that
		// being where the game calls this engine back, so a DH that goes quiet is let go of on the
		// second frame after rather than the first; what it cannot do is hold on for the session.
		if (!drawn) {
			forget();
		}
		drawn = false;

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
		// Widened to the types the walk really holds, an Object and an int, so that invokeExact
		// there matches without a cast either side of it.
		setGet = MethodHandles.publicLookup().unreflect(set.getMethod("get", int.class))
				.asType(MethodType.methodType(Object.class, Object.class, int.class));

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

		// And its own frame order with it, so a DH handed back draws exactly as it does where this
		// engine does not stand in the way. Iris clears the same switch when it stops overriding
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

		// And the reading of DH's listing, because DH is free to drop what is in it the moment it has
		// its own renderer back: a session that stops drawing a pack would otherwise hold a far
		// view's worth of containers, and the buffers under them, until it draws one again.
		forget();
	}

	/**
	 * Lets go of DH's last listing and of both answers read out of it.
	 * <p>
	 * Dropped rather than emptied, because one caller is {@link #restore} and one road to it runs
	 * through {@link #checkStride}, which closes the road in the middle of a walk of the listing:
	 * {@link #build} holds the array it walks, so a drop under it leaves that one reading whole, and
	 * {@link #drops} is what tells the caller above it not to keep the answer afterwards.
	 */
	private static void forget() {
		drops++;
		listed = NOTHING;
		listedCount = 0;
		opaqueSections = null;
		translucentSections = null;
	}

	/**
	 * One half of one pass, out of DH's objects and into this engine's, and only when DH's listing
	 * is not the one the last answer was read out of.
	 * <p>
	 * The two halves keep an answer each because they are not the same reading: one walks a
	 * container's opaque wrappers and the other its transparent ones, so a listing that did not move
	 * still owes two lists. The listing itself is asked once per half all the same, and a listing
	 * that moved drops both: DH refills its set in the frame's first half, so an answer kept for the
	 * second would otherwise outlive the set it came out of.
	 */
	private static List<Section> sections(Object set, boolean opaque)
			throws ReflectiveOperationException {
		drawn = true;

		int count = (Integer) setSize.invoke(set);
		boolean moved = moved(set, count);

		// Under the switch both halves are read again whether the listing moved or not, so that a
		// frame reading it and a frame reusing what it read come off ONE jar. What the switch cannot
		// put back is the comparison above, which is what records the listing: the two readings
		// therefore differ by the reading alone, and the comparison's own price, one member read a
		// section that allocates nothing against the dozen reads and the five objects the reading
		// costs, is on both sides.
		if (moved || PassTimings.keepRedoneWork()) {
			opaqueSections = null;
			translucentSections = null;
		}

		List<Section> kept = opaque ? opaqueSections : translucentSections;
		if (kept == null) {
			int dropped = drops;
			kept = build(count, opaque);

			// Kept only where the walk left the road as it found it, which is what the count is
			// there for: checkStride closes the road from inside build and forgets on its way out,
			// and this answer written over that would hold what the forgetting just let go of.
			if (drops == dropped) {
				if (opaque) {
					opaqueSections = kept;
				} else {
					translucentSections = kept;
				}
			}
		}

		return kept;
	}

	/**
	 * Whether DH's listing holds other containers than the kept answers were read out of, and
	 * records the ones it holds now either way.
	 * <p>
	 * Walked whole rather than left the moment a difference shows, and that is not tidiness: the
	 * record IS what {@link #build} reads, so a walk that stopped early would build one half out of
	 * this frame's listing and the rest out of the last one.
	 */
	private static boolean moved(Object set, int count) throws ReflectiveOperationException {
		boolean moved = count != listedCount;
		if (listed.length < count) {
			listed = new Object[count];
			moved = true;
		}

		try {
			for (int index = 0; index < count; index++) {
				Object one = setGet.invokeExact(set, index);
				if (listed[index] != one) {
					listed[index] = one;
					moved = true;
				}
			}
		} catch (RuntimeException | Error e) {
			throw e;
		} catch (Throwable e) {
			// A handle hands back whatever its target threw, which is anything at all, where every
			// caller up to the proxy is written around the three this class can meet. DH's own
			// member reader declares none of its own, so this is the road nothing walks.
			throw new InvocationTargetException(e);
		}

		if (moved) {
			// Nothing of DH's held past the listing that named it: a far view walked away from would
			// otherwise keep its containers, and the buffers under them, for the whole session.
			Arrays.fill(listed, count, listed.length, null);
			listedCount = count;
		}

		return moved;
	}

	/**
	 * One half of one listing, out of DH's objects and into this engine's.
	 * <p>
	 * Off the record {@link #moved} has just taken rather than out of DH a second time: it is the
	 * same listing walked one call earlier, and asking for it twice would put back half of what
	 * comparing it costs. Held here for the length of the walk, so that a {@link #checkStride} that
	 * closes the road part way through leaves this one reading whole.
	 */
	private static List<Section> build(int count, boolean opaque)
			throws ReflectiveOperationException {
		Object[] containers = listed;

		// Given the width DH just answered with rather than grown into it. A far view hands out
		// thousands of sections, and a list that starts at ten reaches that by allocating a longer
		// array and copying the old one into it a dozen times over.
		List<Section> sections = new ArrayList<>(count);

		// One list refilled section by section rather than one built per section: the record copies
		// what it is handed, so nothing downstream can be holding this one, and a far view hands out
		// thousands of sections.
		List<Piece> pieces = new ArrayList<>();

		for (int index = 0; index < count; index++) {
			Object one = containers[index];
			Object[] wrappers =
					(Object[]) (opaque ? opaqueBuffersField : translucentBuffersField).get(one);
			if (wrappers == null) {
				continue;
			}

			pieces.clear();
			for (Object wrapper : wrappers) {
				if (wrapper == null || !uploadedField.getBoolean(wrapper)) {
					continue;
				}

				// Asked once and kept: the read below it is reflective like every other here, and it
				// answered the same number twice for every buffer of every section of every frame so
				// that one check could have it on the one frame a session where it looks.
				int written = vertexCountField.getInt(wrapper);
				if (written <= 0) {
					continue;
				}

				Object vertices = vertexBufferField.get(wrapper);
				Object indices = indexBufferMethod.invoke(wrapper);
				if (vertices instanceof GpuBuffer vertexBuffer
						&& indices instanceof GpuBuffer indexBuffer
						&& !vertexBuffer.isClosed()) {
					checkStride(vertexBuffer, written);
					pieces.add(new Piece(vertexBuffer, indexBuffer, indexCountField.getInt(wrapper)));
				}
			}

			if (!pieces.isEmpty()) {
				Object corner = cornerField.get(one);
				// Handed the working list, not a copy of it: the record's own constructor copies, so
				// copying here made the same array twice.
				sections.add(new Section((Integer) cornerX.invoke(corner),
						(Integer) cornerY.invoke(corner), (Integer) cornerZ.invoke(corner), pieces));
			}
		}

		// Counted where it is really built, which is what makes the count worth reading: a frame that
		// reused an answer adds nothing here, so a still camera on a settled far view reads nought.
		PassTimings.censusFarSections(sections.size());

		// Handed over as it stands rather than copied. The copy was one more array as wide as the far
		// view, twice a frame, and it bought nothing: this list is built here and is not the reused
		// one above it. What holds it afterwards reads it and never writes it, which is what lets the
		// same list answer several frames: {@code DistantDraw} keeps it for the light's own stage at
		// the tail of the frame and puts a fresh one in its place.
		return sections;
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
