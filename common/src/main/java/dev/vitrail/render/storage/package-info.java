/**
 * The storage a pack's programs write into rather than draw into: the images its {@code image.}
 * lines declare, the buffers its {@code bufferObject.} lines declare, and the encoder work that
 * creating or freeing either records outside a pass.
 * <p>
 * A leaf of the frame: nothing here reads the chain or a program. The colour targets allocate
 * and resize through it, the descriptor writes look a name up in it, and that is the whole of
 * the traffic.
 */
package dev.vitrail.render.storage;
