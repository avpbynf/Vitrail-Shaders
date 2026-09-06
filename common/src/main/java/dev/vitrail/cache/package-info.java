/**
 * The store of compiled shader modules on disk, keyed on the text the compiler was handed and on
 * everything that changes what it emits for that text.
 * <p>
 * Apart from the frame because it has no code in common with it: it is reached from the game's
 * compiler through a mixin, from the compute road, and from the settings, and it reads nothing of
 * the chain. The translation cache, its counterpart for the text before the compiler, lives with
 * the translator.
 */
package dev.vitrail.cache;
