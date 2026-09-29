package dev.vitrail.render.timing;

import dev.vitrail.Vitrail;

import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * How much of the module compilation a pack load pays for is the same text compiled again, and what
 * the engine does about it, printed once a family at the load under {@code -Dvitrail.passTimings=N}.
 * <p>
 * A program's modules are named after its row, so two rows of a pack that translate to the same
 * stage ask the compiler twice, and only {@code ModuleShare} and the disk cache know they are the
 * same text. This says, per family, on both sides of that: how many programs were built and how many
 * of them are distinct by what they hand the compiler, the vertex text, the fragment text, the
 * geometry text of a device that binds one and the mesh layout; and how the modules they asked for
 * were answered, compiled, served from the disk cache or shared with an earlier program of the same
 * load. The compiled ones are keyed by what they were compiled from, so a text that reached the
 * compiler twice shows as a repeat, and that is the number to want at nought.
 * <p>
 * The asked-for side is counted at the same three places the module cache counts its own, so its
 * compiled total is the {@code built by the compiler} of the cache's line for the same load: the
 * game's compiler, and the two computes' roads that call the same counter.
 * <p>
 * The text is the translated one {@code GeometryProgram} hands the compiler, hashed where the program
 * is built and never kept. That costs a pass over every stage of the pack on the worker that builds
 * it, a fraction of a second at load, and is why nothing here runs with the switch off.
 * <p>
 * <strong>Said when the warm-up closes, and again wherever a family has grown since.</strong> Six of
 * the families are built by the pack-load worker before that, and the terrain is built when the
 * renderer first asks for its shader, which may be after: its line comes with the next pass timing
 * report, once, and so does any other family's that was still adding programs or modules.
 */
public final class ModuleCensus {

	private static final boolean ENABLED = PassTimings.enabled();

	private static final ModuleTally TALLY = new ModuleTally();

	private ModuleCensus() {
	}

	/**
	 * One geometry program built, with what it hands the compiler. Called from the pack-load workers
	 * and from the render thread, whichever builds the program.
	 *
	 * @param family     what the log calls the family, as {@code GeometryProgram.Pass} has it
	 * @param vertexId   the identifier the vertex stage is asked for under
	 * @param fragmentId the identifier the fragment stage is asked for under
	 * @param vertex     the vertex stage's text as the compiler receives it
	 * @param fragment   the fragment stage's text, or null for a program that has none to give, which
	 *                   hands the compiler nothing and is left out
	 * @param geometry   the text of the geometry stage the device binds as a module of its own, or
	 *                   null where the program has none or the stage is folded into the fragment
	 * @param format     the layout of the mesh it reads, or null for a family with no mesh
	 */
	public static void built(String family, Identifier vertexId, Identifier fragmentId, String vertex,
			String fragment, @Nullable String geometry, Object format) {
		if (ENABLED && vertex != null && fragment != null) {
			TALLY.built(family, ModuleTally.hash(vertex), ModuleTally.hash(fragment),
					geometry == null ? 0L : ModuleTally.hash(geometry), format,
					List.of(vertexId.toDebugFileName(), fragmentId.toDebugFileName()));
		}
	}

	/**
	 * A module the compiler is about to build. Called beside the module cache's own count of the
	 * same thing, so the two agree.
	 *
	 * @param name the debug name the compile was given
	 * @param unit the digest the module is keyed by, or null where it has none
	 */
	public static void compiled(String name, @Nullable String unit) {
		if (ENABLED) {
			TALLY.unit(name, unit, ModuleTally.Supply.COMPILED);
		}
	}

	/** A module the disk cache held. */
	public static void served(String name, @Nullable String unit) {
		if (ENABLED) {
			TALLY.unit(name, unit, ModuleTally.Supply.SERVED);
		}
	}

	/** A module an earlier program of this load had made. */
	public static void shared(String name, @Nullable String unit) {
		if (ENABLED) {
			TALLY.unit(name, unit, ModuleTally.Supply.SHARED);
		}
	}

	/**
	 * Prints a line for every family that has built programs or been asked for modules since it was
	 * last said.
	 */
	public static void report() {
		if (!ENABLED) {
			return;
		}

		for (String line : TALLY.lines()) {
			Vitrail.logger().info("{}", line);
		}
	}

	/** A new pack is being read: the programs of the last one are not this one's. */
	static void reset() {
		if (ENABLED) {
			TALLY.clear();
		}
	}

	/** The census, for the tests that read it back. */
	static ModuleTally tally() {
		return TALLY;
	}
}
