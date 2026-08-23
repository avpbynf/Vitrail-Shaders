package dev.vitrail.render;

import dev.vitrail.uniform.WorldState;

import com.mojang.blaze3d.systems.GpuDevice;

/**
 * What {@link PackDump} needs of a program to be able to name it and read it back.
 * <p>
 * One interface rather than one loop per family. The families have nothing else in common at this
 * level, each of them holding a {@link GeometryProgram} and answering what its own geometry answers
 * differently, and the dump wants exactly these three: what to call it, which file it came out of,
 * and what its block holds this frame.
 * <p>
 * Written when the weather arrived and the loop would have been copied a fifth time. What the copies
 * cost is not their length: the dump matches a line of {@code options.txt} against the path AND the
 * label, and a family whose loop tested one of the two would be a family the line could not reach,
 * with nothing anywhere to say so.
 */
interface DumpedProgram {

	/** What a line of {@code options.txt} may name this program by, besides its path. */
	String label();

	/** The file inside the pack this program was read out of. */
	String path();

	/** This frame's uniform block, read back as text. */
	String decoded(WorldState world);

	/**
	 * Compiles this program into the device cache, with no atlas, no block and no draw.
	 *
	 * @return false when the program will never be drawn
	 */
	boolean compile(GpuDevice device);

	/** Whether {@link #compile} has already paid shaderc for this pipeline. */
	boolean compiled();

	/**
	 * A resource reload emptied the device cache, so the next {@link #compile} has to pay again.
	 */
	void forgetCompiled();
}
