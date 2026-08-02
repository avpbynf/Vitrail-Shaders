package dev.vitrail.uniform;

/**
 * One value the engine can answer. Pure: it reads the world state and fills the carrier.
 * <p>
 * A source must not remember anything between calls. It is asked once per block and per frame,
 * and two passes of the same frame have to be handed the same number, so anything that advances
 * with time advances in {@code FrameState.advance()} and is read from here, never stepped here.
 */
@FunctionalInterface
public interface UniformSource {

	void read(WorldState world, Val out);
}
