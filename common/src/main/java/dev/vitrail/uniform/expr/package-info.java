/**
 * The uniforms a pack declares for itself, as expressions in {@code shaders.properties}.
 * <p>
 * Eighty-five of the two hundred and seventy-three names a pack reads are not the engine's at
 * all: the pack writes them itself, as {@code uniform.<type>.<name> = <expression>} or
 * {@code variable.<type>.<name> = <expression>}, over the engine's own values. All eight packs of
 * the corpus do it, in two hundred and thirty-seven lines of which a hundred and ninety-nine
 * survive their own conditionals. They are not implemented one at a time; they are implemented
 * once, as an evaluator.
 * <p>
 * The evaluator is kroppeb's stareval, taken from Iris and relocated under this package rather
 * than reimplemented: the type resolution with implicit casts, the vectorisation of scalar
 * functions and the exact spelling of fifty-odd OptiFine builtins are the sort of thing that a
 * from-scratch reading gets subtly wrong, and a pack would then be quietly wrong too. See NOTICE.
 * <p>
 * Nothing here names a graphics API or a Minecraft type: the whole package runs against a
 * {@link dev.vitrail.uniform.WorldState} in the off-game harness, which is what makes the two
 * hundred and thirty-seven declarations of the corpus a regression suite rather than something
 * one checks by looking at the screen.
 */
package dev.vitrail.uniform.expr;
