package dev.vitrail.uniform.expr;

/**
 * How a {@code smooth()} call site learns how long the last frame took.
 * <p>
 * Iris reads a static timer from inside the function. That cannot be tested and cannot run twice
 * in one process, so the length of the frame arrives here through the evaluation context instead:
 * {@link CustomUniforms} is the context, and it is also the clock. A context that is not one
 * smooths with a step of zero, which holds the accumulator still rather than jumping it.
 */
public interface FrameClock {

	/** Seconds elapsed in the frame just finished. */
	float deltaSeconds();
}
