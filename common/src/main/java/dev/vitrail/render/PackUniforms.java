package dev.vitrail.render;

import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.uniform.UniformBlock;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformSink;
import dev.vitrail.uniform.WorldState;

import com.mojang.blaze3d.buffers.Std140Builder;

import java.util.List;

/**
 * Fills the uniform block a translated program declares.
 * <p>
 * All the work is in {@link UniformBlock}, which knows nothing about Minecraft and can therefore be
 * run over the whole corpus in the off-game harness. What is left here is the wiring: the engine's
 * catalogue on one side, the game's std140 builder on the other.
 * <p>
 * The rule that runs through the block is worth repeating where the two sides meet: <strong>the
 * type the pack declared decides what is written, not the name.</strong> Mellow declares
 * {@code hideGUI} a bool and Bliss declares the same name an int; both are served, because a value
 * is registered once under the shape the engine holds it in and converted at the member. Iris
 * cannot do this: it fixes the type when the uniform is registered, before it knows what any
 * program declares, so a mismatch there disables the uniform outright.
 */
final class PackUniforms {

	private final UniformBlock block;

	PackUniforms(List<TranslatedUnit.Uniform> members) {
		this(members, UniformCatalog.engine());
	}

	/**
	 * @param catalog the engine's table with a pack's own uniforms layered over it, for the programs
	 *                that declare expressions of their own
	 */
	PackUniforms(List<TranslatedUnit.Uniform> members, UniformCatalog catalog) {
		this.block = new UniformBlock(members, catalog);
	}

	int size() {
		return this.block.size();
	}

	/** Names the block declares that the engine does not answer yet, in declaration order. */
	List<String> unsupplied() {
		return this.block.unanswered();
	}

	void write(Std140Builder into, WorldState world) {
		this.block.write(new Std140Sink(into), world);
	}

	/**
	 * The same walk through a sink of the caller's choosing, which is how the decoded dump is taken.
	 * It has to be the same one: a second walk written to print values would be a second reading of
	 * the catalogue, and the thing worth proving is what the buffer got.
	 */
	void write(UniformSink into, WorldState world) {
		this.block.write(into, world);
	}
}
