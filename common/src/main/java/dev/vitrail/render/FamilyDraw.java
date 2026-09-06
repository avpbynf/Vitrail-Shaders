package dev.vitrail.render;

import dev.vitrail.pack.source.OpenedPack;

import java.util.Collection;

/**
 * What the six families read after the terrain have in common, so that the chain holds them as
 * one list and walks it wherever it used to name all six.
 * <p>
 * The order of that list is the order the pack-load worker reads them in, the order their rings
 * are rotated in as each comes ready, and the order the dump names them in: one place says it,
 * {@link PackChain}'s constructor, and a family moved there is moved everywhere. One added still
 * wants its field, its constructor call and its accessor there, and then the one line of the list.
 * The class of defect this closes has no signature of its own: a ring rotated for the wrong
 * family leaves the right one on the buffer the GPU is still reading.
 */
abstract class FamilyDraw {

	/** The programs read so far, empty until the family is read. */
	abstract Collection<? extends DumpedProgram> programs();

	/** Reads the family's programs off an opening the worker shares between all six. */
	abstract void prefetch(OpenedPack shared);

	/** Turns the family's ring buffers, once a frame and only once the family is ready. */
	abstract void rotate();

	/** Hands back everything the family holds on the device. */
	abstract void release();
}
