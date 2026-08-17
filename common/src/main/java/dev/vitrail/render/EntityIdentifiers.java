package dev.vitrail.render;

/**
 * The three numbers a pack tells one entity, block entity or held item apart by, at whichever moment
 * of the frame each of them is knowable.
 * <p>
 * <strong>The same three moments {@link BlockEntityGeometry} walks, and for the same reason</strong>:
 * a mob is only recognisable while it is being SUBMITTED, during the level walk, and what reaches a
 * draw an hour later is one batch of nodes from every renderer at once. So the answer is taken where
 * it is true, put on the submission node, put back on while that node is turned into vertices, and
 * written on each vertex from there. The difference with the block entity mark is what happens at
 * the far end: that one decides which program a draw is served with, and these three ride on the
 * geometry itself, so a single draw may carry a different answer on every vertex of it.
 * <p>
 * <strong>Which is the whole reason they are on the mesh and not in the uniform block.</strong> A
 * draw batches submissions: {@code RenderTypeFeatureRenderer$Group.getVertexBuilder} hands back the
 * last draw whenever the render type is the same instance and consolidates, which every quad type
 * does. A uniform would therefore have to break the batch at every change of identifier, one draw
 * per mob. Iris settles it the same way, on an element of its own
 * ({@code vertices/IrisVertexFormats.java:30}).
 * <p>
 * <strong>Nought is not "unknown" and -1 is.</strong> Nought is what the three are worth outside any
 * entity, block entity or item, which is Iris's own value there
 * ({@code mixin/entity_render_context/MixinEntityRenderDispatcher.java:89}); -1 is what a table
 * answers for a name the pack never mapped, and the element carries it unsigned, so a pack reads
 * 65535. Both are Iris's numbers and the packs are written against them.
 * <p>
 * Read and written on the render thread alone, and {@code volatile} for the reason
 * {@link BlockEntityGeometry}'s are: the keyword costs less than the arbitration over a field this
 * many mixins touch.
 */
public final class EntityIdentifiers {

	/** What all three are worth where nothing has named one, which is Iris's value there. */
	private static final int NONE = 0;

	/** How wide one lane is, the element holding four shorts of which three are read. */
	private static final int LANE = 16;

	private static final long LANE_MASK = 0xFFFFL;

	private static volatile int entity = NONE;

	private static volatile int blockEntity = NONE;

	private static volatile int item = NONE;

	private EntityIdentifiers() {
	}

	public static void entity(int id) {
		entity = id;
	}

	public static void blockEntity(int id) {
		blockEntity = id;
	}

	public static void item(int id) {
		item = id;
	}

	public static int entity() {
		return entity;
	}

	public static int blockEntity() {
		return blockEntity;
	}

	public static int item() {
		return item;
	}

	/**
	 * The three as one number, so that a submission carries one field rather than three and puts
	 * them back in one call.
	 * <p>
	 * A packed long and not a record, because this is taken in the constructor of every model
	 * submission of the frame and given back at every one of them: an object each would be a
	 * per submission allocation for three numbers that fit in one. The lanes are the element's own
	 * order, and only the low sixteen bits of each are kept, which is exactly what the short written
	 * on the vertex keeps.
	 */
	public static long packed() {
		return (entity & LANE_MASK)
				| ((blockEntity & LANE_MASK) << LANE)
				| ((item & LANE_MASK) << (2 * LANE));
	}

	/** Puts back what {@link #packed} took, while a submission is turned into vertices. */
	public static void restore(long packed) {
		// Sign extended back out of the lane, so that the -1 a table answers for an unmapped name is
		// still -1 here and is turned into 0xFFFF once, by the short that is written on the vertex.
		// Keeping it as 65535 from here on would be the same bytes and a different number in the log.
		entity = (short) packed;
		blockEntity = (short) (packed >>> LANE);
		item = (short) (packed >>> (2 * LANE));
	}

	/** Drops all three, at the end of every submission and at the frame boundary. */
	public static void clear() {
		entity = NONE;
		blockEntity = NONE;
		item = NONE;
	}
}
