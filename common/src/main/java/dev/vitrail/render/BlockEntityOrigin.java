package dev.vitrail.render;

/**
 * Whether one object of the game's immediate rendering came from a block entity renderer.
 * <p>
 * Implemented by mixin on the two kinds of submission node a block entity can produce that this
 * engine serves, and on the draw those nodes end up in. It exists because there is no other carrier:
 * a block entity is only recognisable while it is being submitted, and what reaches the door is a
 * draw assembled long afterwards out of nodes from every renderer at once.
 * <p>
 * <strong>Reading only, and the write side is {@link BlockEntityMark}.</strong> A submission takes
 * its answer in its own constructor and never changes it, so a setter offered here would be a key
 * nothing turns on two of the three implementors, which is the shape of a method somebody later
 * calls expecting it to do something.
 * <p>
 * Outside the mixin package on purpose. A class in there is read as a mixin, and this is a plain
 * interface that both the mixins and their callers implement or cast to.
 */
public interface BlockEntityOrigin {

	boolean vitrail$fromBlockEntity();
}
