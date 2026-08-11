package dev.vitrail.uniform;

/**
 * One value the engine can answer. Pure: it reads the world state and fills the carrier.
 * <p>
 * A source must not remember anything between calls. It is asked once per block member and per
 * element of an array, every frame, and two passes of the same frame have to be handed the same
 * number, so anything that advances with time advances in {@code FrameState.advance()} and is read
 * from here, never stepped here.
 */
@FunctionalInterface
public interface UniformSource {

	void read(WorldState world, Val out);

	/**
	 * The same, for one element of a member the pack declared as an array.
	 * <p>
	 * Almost every name answers all its elements alike and inherits this, which hands each of them
	 * the one value. The exception is the one the fixed function pipeline made an array of: OpenGL
	 * gave every texture unit its own {@code gl_TextureMatrix}, and unit one is the light map's,
	 * whose matrix is not the identity the others are. Answering that member once and writing it
	 * eight times is what made every unit the same, which nothing reported and which reads as a pack
	 * sampling the wrong corner of the light map.
	 *
	 * <strong>The two forms are not interchangeable and the rule is that the short one is element
	 * nought.</strong> {@link UniformBlock} always calls this one, but it is not the only caller of
	 * the pair: a pack's custom uniform reads its inputs through the short form
	 * ({@code expr/CustomUniforms}), which has no element to give. A source whose elements differ
	 * therefore answers the short form with its first, and nothing silently gets a value that
	 * belongs to another unit.
	 *
	 * @param element which element of the declaration is being written, from nought
	 */
	default void read(WorldState world, Val out, int element) {
		read(world, out);
	}
}
