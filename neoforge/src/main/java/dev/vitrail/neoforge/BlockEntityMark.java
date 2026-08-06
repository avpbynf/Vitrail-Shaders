package dev.vitrail.neoforge;

/**
 * The write side of {@link BlockEntityOrigin}, for the one carrier that is marked from outside.
 * <p>
 * A draw is made by the group and only then told where its geometry came from, which is the one
 * place in the chain where the answer travels rather than being taken on the spot. The submissions
 * take theirs in their own constructor and implement the read side alone.
 */
public interface BlockEntityMark extends BlockEntityOrigin {

	void vitrail$fromBlockEntity(boolean fromBlockEntity);
}
