package dev.vitrail.neoforge;

/**
 * Whether one object of the game's immediate rendering came from a block entity renderer.
 * <p>
 * Implemented by mixin on the two kinds of submission node a block entity can produce that this
 * engine serves, and on the draw those nodes end up in. It exists because there is no other carrier:
 * a block entity is only recognisable while it is being submitted, and what reaches the door is a
 * draw assembled long afterwards out of nodes from every renderer at once.
 * <p>
 * Outside the mixin package on purpose. A class in there is read as a mixin, and this is a plain
 * interface that both the mixins and their callers implement or cast to.
 */
public interface BlockEntityOrigin {

	boolean vitrail$fromBlockEntity();

	void vitrail$fromBlockEntity(boolean fromBlockEntity);
}
