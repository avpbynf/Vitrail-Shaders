package dev.vitrail.render;

import dev.vitrail.uniform.WorldState;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;

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

	/**
	 * Compiles this program's pipeline on the pack-load worker, so its first draw finds the work
	 * already paid instead of paying shaderc on the render thread. The six on-demand families
	 * override it; the terrain does not, compiling while the world is still held back.
	 *
	 * @param compiler the worker's own compiler, never the device's: the device's belongs to the
	 *                 render thread along with the caches around it
	 * @return true when a compiled pipeline is now waiting for {@link #compile} to adopt it
	 */
	default boolean warmAhead(VulkanDevice device, GlslCompiler compiler) {
		return false;
	}

	/**
	 * The chain released before anything drew this program, so no {@link #compile} will ever adopt
	 * what the worker prepared: what waits is destroyed, and a worker still running stores nothing
	 * more here.
	 */
	default void discardAhead() {
	}
}
