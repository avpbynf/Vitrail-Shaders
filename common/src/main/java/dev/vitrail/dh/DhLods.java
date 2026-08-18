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

	private static boolean resolved;
	private static boolean usable;

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
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			usable = false;
			Vitrail.logger().warn("Distant Horizons' far terrain cannot be taken over, so that mod "
					+ "keeps drawing it with its own shader for the rest of this session", e);
		}
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

			resolveGeometry();

			substitute = Proxy.newProxyInstance(DhLods.class.getClassLoader(),
					new Class<?>[] { rendererType }, new Handler());

			usable = true;
			Vitrail.logger().info("Distant Horizons found, its far terrain will be read where that "
					+ "mod hands it to its own renderer");
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
	 * <strong>It is the one assumption in this package nothing else would catch.</strong> A buffer's
	 * own length divided by the vertices DH says are in it IS the stride, so a DH that added an
	 * element or widened one shows up here as a number; read through the wrong format, the same
	 * buffer draws a far terrain out of the wrong bytes, which is a picture rather than a failure.
	 * The road is closed rather than corrected: what a wider vertex means cannot be worked out from
	 * its width.
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
