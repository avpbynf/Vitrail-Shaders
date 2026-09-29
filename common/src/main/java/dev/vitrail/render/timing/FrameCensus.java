package dev.vitrail.render.timing;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What a frame binds, pushes and writes, counted rather than timed, and printed under the pass
 * table of {@link PassTimings} at the same interval.
 * <p>
 * The pass table says where the card's time goes. This says how much of the work that reaches the
 * card is work the frame before it already did: a pipeline set again on a pass that held it, a
 * uniform block written twice into a ring that turns once, a descriptor set pushed for every
 * section of a far terrain. Nothing here changes what a frame does. It is the count that says
 * whether removing one of those is worth a branch, and it is meant to be read beside the frame
 * rate rather than instead of it: a count does not say what a thing costs.
 * <p>
 * <strong>The switch is {@code -Dvitrail.passTimings=N}, the same one</strong>, and off it every
 * hook below is one read of a static final and a return, which the compiler removes. Nothing
 * allocates while counting: the sums are longs in {@link FrameTally}, a program's writes are one
 * {@link Block} made when the program is, the family of a pipeline is looked up in a map that is
 * filled as the programs are built, at the load and once more for each mesh layout another mod
 * brings to one of them, and what a pass holds under a name is one holder made the first time
 * anything is set under it.
 * <p>
 * <strong>Every bind is counted where the pass takes it, not where somebody made the call.</strong>
 * The sky, the weather, the clouds, the particles and the terrain never call
 * {@code GraphicsApi.setPipeline}: the game or Sodium does, and this engine swaps what it binds.
 * The hook that sees all of them is the one on the pass itself, so it counts the game's own and
 * other mods' binds as well, in a row of their own. The same hook is why a redundant bind can be
 * told at all: it knows the pass, and the last thing that was set on it.
 * <p>
 * <strong>Terrain draws are not counted.</strong> Sodium records them into the command buffer
 * directly, past the pass, so the terrain row has binds and no draws.
 */
public final class FrameCensus {

	private static final boolean ENABLED = PassTimings.enabled();

	private static final FrameTally TALLY = new FrameTally();

	/**
	 * The family of every pipeline a geometry program built, by identity. Written on the pack-load
	 * worker as the programs are made and read on the render thread, hence the lock; only filled when
	 * the census is on, and emptied at every pack load with the programs it names. It holds its keys
	 * strongly, which costs a pack's worth of pipeline descriptions for as long as that pack is
	 * loaded and is the price of a diagnostic switch nobody leaves on.
	 */
	private static final Map<Object, FrameTally.Family> FAMILIES =
			Collections.synchronizedMap(new IdentityHashMap<>());

	/**
	 * The last pipeline asked about and the answer, because a run of draws asks about one pipeline
	 * a hundred times and the lookup needs its lock. Render thread only.
	 */
	private static RenderPipeline lastAsked;
	private static FrameTally.Family lastAnswer = FrameTally.Family.OTHER;

	private FrameCensus() {
	}

	/**
	 * One program's uniform block, the frame it was last written in, so that the second write of a
	 * frame can be told from the first, and the frame it was last counted as drawn in. One per
	 * program, made with it.
	 */
	public static final class Block {

		long frame = -1;
		int writes;
		long placedFrame = -1;
	}

	/**
	 * Says which family a pipeline this engine built belongs to. Called where a geometry program
	 * builds one, and where it rebuilds one over another mod's mesh layout, both before anything
	 * can bind it.
	 *
	 * @param pipeline the pipeline, which is looked up by identity
	 * @param family   the program's own name for its family, as {@code GeometryProgram.Pass} has it
	 */
	public static void describe(RenderPipeline pipeline, String family) {
		if (ENABLED) {
			FAMILIES.put(pipeline, FrameTally.Family.named(family));
		}
	}

	/**
	 * A pipeline set on a pass, called from the one hook every pass passes through.
	 *
	 * @param pass      the pass, by identity
	 * @param bound     what the pass now holds, by identity. On 26.2 that is the pipeline, on 26.3 the
	 *                  compiled object made from it
	 * @param described the pipeline's description, which is what its family is read off, or null where
	 *                  nothing is known of it, which is everybody else's
	 */
	public static void bind(Object pass, Object bound, RenderPipeline described) {
		if (ENABLED) {
			TALLY.bound(pass, bound, family(described));
		}
	}

	/** A draw recorded into a pass, whoever recorded it. */
	public static void draw(Object pass) {
		if (ENABLED) {
			TALLY.drawn(pass);
		}
	}

	/**
	 * A descriptor set pushed for a draw, once for the push and however many descriptors it carries
	 * through {@link #descriptor}.
	 *
	 * @param allocated whether it was bound as an allocated set instead of pushed, which a layout the
	 *                  driver cannot push is
	 */
	public static void push(boolean allocated) {
		if (ENABLED) {
			TALLY.pushed(allocated);
		}
	}

	/** One descriptor written, which is where the game's push reaches this engine's hook. */
	public static void descriptor() {
		if (ENABLED) {
			TALLY.described();
		}
	}

	/**
	 * A geometry program's block and samplers set on a pass it was not standing in, which is once for
	 * each program and pass that meet and again where the program's own answers moved.
	 */
	public static void programBound() {
		if (ENABLED) {
			TALLY.programBound();
		}
	}

	/**
	 * A geometry program bound into a pass it was already standing in, which sets the images the draw
	 * brought and nothing else.
	 */
	public static void programKept() {
		if (ENABLED) {
			TALLY.programKept();
		}
	}

	/**
	 * A geometry program's block set on a pass. Both games mark the descriptors dirty on every set,
	 * so one that puts on the pass what it already holds is a descriptor push spent on nothing, and
	 * this is what says how many of them there are.
	 *
	 * @param pass    the pass, by identity
	 * @param name    the name the block is set under
	 * @param slice   the slice, compared by value
	 * @param counted whether the program was already standing in the pass. A bind that finds it
	 *                elsewhere writes every name it declares, and those sets are not what this
	 *                counts; they still move what the pass is known to hold
	 */
	public static void blockSet(Object pass, String name, Object slice, boolean counted) {
		if (ENABLED) {
			TALLY.set(FrameTally.Kind.BLOCK, pass, name, slice, null, counted);
		}
	}

	/**
	 * A sampled image set on a pass, by the same rule as {@link #blockSet}. The view and the sampler
	 * are compared by identity, as the pass compares nothing at all.
	 */
	public static void textureSet(Object pass, String name, Object view, Object sampler,
			boolean counted) {
		if (ENABLED) {
			TALLY.set(FrameTally.Kind.TEXTURE, pass, name, view, sampler, counted);
		}
	}

	/**
	 * The game's per draw transforms set on a pass, by the same rule as {@link #blockSet}. Counted
	 * whenever they are set, the first draw of a run included: it puts on the pass what the pass may
	 * already hold as much as any other draw does.
	 */
	public static void transformsSet(Object pass, String name, Object slice) {
		if (ENABLED) {
			TALLY.set(FrameTally.Kind.TRANSFORMS, pass, name, slice, null, true);
		}
	}

	/** A geometry program's uniform block written. */
	public static void geometryBlockWritten(Block block) {
		if (ENABLED) {
			TALLY.geometryWritten(block);
		}
	}

	/**
	 * A uniform block of the chain written: the full screen passes' and the computes', and the small
	 * ones that belong to a single helper pass.
	 */
	public static void chainBlockWritten() {
		if (ENABLED) {
			TALLY.chainWritten();
		}
	}

	/** A block of the far terrain written: its section corners and its occlusion pair. */
	public static void farBlockWritten() {
		if (ENABLED) {
			TALLY.farWritten();
		}
	}

	/**
	 * A ring buffer turned by this engine. Every turn closes a fence and creates one, so this is also
	 * the number of fences a frame makes. Called before the turn, at each of them.
	 */
	public static void rotated() {
		if (ENABLED) {
			TALLY.rotated();
		}
	}

	/**
	 * A geometry program drawn, from every prepare of it, counted once for each frame. Says which of
	 * the two places its uniform block stands in: the ring every program of the chain shares, or one
	 * of its own that costs a fence a frame.
	 *
	 * @param block  the program's, made with it
	 * @param shared whether the block stands in the shared ring
	 */
	public static void blockPlaced(Block block, boolean shared) {
		if (ENABLED) {
			TALLY.blockPlaced(block, shared);
		}
	}

	/**
	 * Where the ring the chain's uniform blocks share stands, said whenever a block takes a range of it
	 * or gives one back, and with a capacity of nought when there is none.
	 *
	 * @param capacity how many bytes one buffer of the ring holds for blocks
	 * @param claimed  how many of them stand claimed
	 * @param peak     the most that stood claimed at once
	 * @param programs how many programs hold a range
	 */
	public static void blockRing(int capacity, int claimed, int peak, int programs) {
		if (ENABLED) {
			TALLY.blockRing(capacity, claimed, peak, programs);
		}
	}

	/** One section of the far terrain given a uniform of its own inside a pass. */
	public static void farSection(boolean shadow) {
		if (ENABLED) {
			TALLY.farSection(shadow);
		}
	}

	/** One pass of the far terrain walking its sections. */
	public static void farPass(boolean shadow) {
		if (ENABLED) {
			TALLY.farPass(shadow);
		}
	}

	/** A call of the chain's {@code ready}, whatever it answers. */
	public static void ready() {
		if (ENABLED) {
			TALLY.readied();
		}
	}

	/** A call of {@code ready} that got as far as preparing the targets. */
	public static void readyPrepared() {
		if (ENABLED) {
			TALLY.readyPrepared();
		}
	}

	/** A call of {@code ready} that handed a frame back. */
	public static void readyDone() {
		if (ENABLED) {
			TALLY.readyDone();
		}
	}

	/** Closes the frame the counts belong to. Called by {@link PassTimings#endFrame}. */
	static void endFrame() {
		if (ENABLED) {
			TALLY.endFrame();
		}
	}

	/**
	 * Prints the window and empties it. Called by {@link PassTimings} when its own report is due, so
	 * the two describe the same frames.
	 */
	static void report(double seconds) {
		if (!ENABLED) {
			return;
		}

		for (String line : TALLY.lines(seconds)) {
			Vitrail.logger().info("{}", line);
		}

		TALLY.clear();
	}

	/**
	 * A new pack is being read: what the last one counted, and the pipelines it built, are not this
	 * one's. Called from {@link PassTimings#resetCensus} for the reason that method gives.
	 */
	static void reset() {
		if (ENABLED) {
			TALLY.clear();
			FAMILIES.clear();
			lastAsked = null;
			lastAnswer = FrameTally.Family.OTHER;
		}
	}

	/** The census, for the tests that read it back. */
	static FrameTally tally() {
		return TALLY;
	}

	/**
	 * Whose a pipeline is: the family its program named, or a chain pass where the pipeline is one of
	 * this engine's that no geometry program built, or somebody else's.
	 */
	@SuppressWarnings("ReferenceEquality")
	private static FrameTally.Family family(RenderPipeline described) {
		if (described == null) {
			return FrameTally.Family.OTHER;
		}

		if (described == lastAsked) {
			return lastAnswer;
		}

		FrameTally.Family family = FAMILIES.get(described);
		if (family == null) {
			family = described.getLocation().getNamespace().startsWith(Vitrail.MOD_ID)
					? FrameTally.Family.CHAIN
					: FrameTally.Family.OTHER;
		}

		lastAsked = described;
		lastAnswer = family;

		return family;
	}
}
