package dev.vitrail.render;

/**
 * What the three identifiers were worth when one submission was made, carried on the submission
 * itself.
 * <p>
 * The middle of the three moments {@link EntityIdentifiers} describes. A submission is built during
 * the level walk, where the mob or the chest or the item is still in hand, and turned into vertices
 * much later out of one batch; nothing at that end knows what any of it was. So the answer travels
 * on the node, exactly as {@link BlockEntityOrigin} does, and on the same two kinds of node.
 * <p>
 * One number and not three, because this is taken in the constructor of every model submission of
 * the frame: three fields and three calls would be the same three numbers in three times the room,
 * and {@link EntityIdentifiers#packed} already has to lay them side by side for the vertex.
 * <p>
 * Reading only, like its neighbour: a submission takes its answer in its own constructor and never
 * changes it.
 */
public interface SubmittedIdentifiers {

	long vitrail$identifiers();
}
