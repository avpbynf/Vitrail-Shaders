/**
 * The accessor interfaces, which are the only mixin classes the rest of the engine may name.
 * <p>
 * An accessor is an interface Mixin implements onto a game or Sodium class at load, so it can be
 * imported and called like any other type. An injecting mixin cannot: it is never loaded as a
 * class of its own, and a reference to one from ordinary code is a defect the compiler could not
 * see while the two lived in one package. Keeping the accessors here and the injectors in the
 * parent package turns that mistake into a compilation error.
 */
package dev.vitrail.mixin.access;
