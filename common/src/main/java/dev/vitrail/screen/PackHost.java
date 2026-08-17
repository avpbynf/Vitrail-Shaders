package dev.vitrail.screen;

/**
 * What the pack list needs from the screen holding it, which is the little that cannot be worked out
 * inside a list: the fade the screen is driving, and two things that have to happen elsewhere when a
 * row is clicked.
 * <p>
 * An interface rather than a reference to the screen, so that the list can be built and read on its
 * own. Iris passes its screen straight in and reaches three of its members,
 * {@code ShaderPackSelectionList.java:297,528} and its {@code listTransition} field; naming those
 * three is the whole of this.
 */
public interface PackHost {

	/**
	 * The screen's list fade, between nought and one. Read once per frame by the list, for the
	 * background it draws and the colour of its two separators.
	 * <p>
	 * The screen holds the smoothing, in a {@link dev.vitrail.uniform.Smoothed}, which is this project's
	 * one copy of the exponential smoothing Iris hands its own screen through a {@code SmoothedFloat}.
	 * A second copy of it lived here for an afternoon and was the same arithmetic twice.
	 */
	float listAlpha();

	/**
	 * Called when the shaders toggle flips, because the button that walks into a pack's settings is
	 * live or dead depending on it and that button belongs to the screen.
	 */
	void shadersToggled();

	/**
	 * Called when a row is picked, moving the focus down to the screen's bottom row. Iris does this so
	 * that a keyboard lands on the way out rather than staying in the list, since picking a pack is
	 * the last thing anybody does on this view.
	 */
	void focusBottomRow();
}
