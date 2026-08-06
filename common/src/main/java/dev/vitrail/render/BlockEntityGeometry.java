package dev.vitrail.render;

/**
 * Whether the geometry going through the entity door was handed in by a block entity renderer, at
 * each of the three moments that have to agree about it.
 * <p>
 * <strong>Three moments and not one, because the answer is only knowable at the first and only
 * needed at the last.</strong> A chest is a block entity while it is being SUBMITTED, during the
 * level walk, and there is nothing in a draw an hour later that says so: the features of a frame are
 * all submitted first and executed afterwards, in one batch, through the same renderers and the same
 * pipelines as every mob. So the answer is taken where it is true, put on the submission node, put
 * back on while that node is turned into vertices, and from there onto the draw those vertices
 * landed in. {@link EntityDraw} reads the last of the three.
 * <p>
 * <strong>This is what Iris does and it is not how Iris does it</strong>, because two of its pieces
 * are gone from 26.2. It wraps the render type in a marked subclass, which is not available here:
 * {@code RenderType}'s constructor is private and {@code create} is the only way in. And it marks
 * three kinds of submission where this version has two, {@code submitModelPart} having become a
 * default method that delegates to {@code submitModel}. What is reproduced is the answer, which is
 * that a block entity is drawn with {@code gbuffers_block} and a mob with {@code gbuffers_entities},
 * and the packs are written against that.
 * <p>
 * All three are read and written on the render thread alone, and none of them is a switch: they are
 * raised and lowered around a call, so an unbalanced one is a mob lit as a chest for the rest of the
 * frame rather than a crash. That is why every injection that touches them is required rather than
 * optional, and why the door drops all three at the frame boundary whatever happened in between.
 */
public final class BlockEntityGeometry {

	/** Raised around the block entity dispatcher, which is the only moment the answer is known. */
	private static volatile boolean submitting;

	/** Raised around one submission being turned into vertices, which is where a draw is picked. */
	private static volatile boolean building;

	/** Raised around one draw being executed, which is where the door reads it. */
	private static volatile boolean drawing;

	private BlockEntityGeometry() {
	}

	public static void submitting(boolean blockEntity) {
		submitting = blockEntity;
	}

	public static boolean submitting() {
		return submitting;
	}

	public static void building(boolean blockEntity) {
		building = blockEntity;
	}

	public static boolean building() {
		return building;
	}

	public static void drawing(boolean blockEntity) {
		drawing = blockEntity;
	}

	public static boolean drawing() {
		return drawing;
	}

	/** Drops all three, at the frame boundary and wherever else a window is closed. */
	static void clear() {
		submitting = false;
		building = false;
		drawing = false;
	}
}
