package dev.vitrail.render;

import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;

/**
 * Whether the game's own per face brightness is left out of the chunk mesh, which it is for every
 * pack that does not ask for the old lighting back.
 * <p>
 * The game tints the colour of every block face by the way it points, out of a table of six numbers
 * the dimension carries ({@code CardinalLighting}): in the overworld a one on top, a half
 * underneath and 0.8 and 0.6 on the sides. A pack computes its own directional shading from the
 * normal it is handed, so leaving the game's in means the surface is shaded twice, and only a face
 * whose factor is already the top one comes out where the pack drew it.
 * <p>
 * Measured on the frozen world, RenderPearl against the reference: the ground at the camera's feet
 * matched to a level and the sky to half a level, while the canopy above them came out about twenty
 * levels darker. <strong>What named the cause is the shape of that gap and not its size</strong>:
 * the per pixel ratio over the canopy does not spread around one value, it piles up near 0.56 and
 * 0.72 with a tail at one. Those are not the table's numbers to the digit, a leaf being blended
 * against what stands behind it, but a term belonging to the surface rather than to the quad, an
 * occlusion or a shadow tap or a texture read, could not pile up at a few values at all.
 * <p>
 * The reference does the same thing and reads the same directive:
 * {@code pipeline/IrisRenderingPipeline.java:1159-1160} answers {@code !oldLighting} and
 * {@code mixin/vertices/block_rendering/MixinClientLevel.java:16-23} applies it by handing
 * {@code CardinalLighting.byFace} the up direction whatever face it was asked about. The default is
 * off there too, {@code shaderpack/properties/PackDirectives.java:92}.
 * <p>
 * <strong>Handing over the up direction is not the same as handing over a one</strong>, and the
 * difference is the nether: its table gives 0.9 to the top as well as to the bottom, so what a face
 * is worth there stays the dimension's own answer and only stops depending on which face it is.
 * <p>
 * <strong>It follows the terrain switch as well as the pack</strong>, which the reference has no
 * reason to do: where a pack's own terrain program is not served, the chunks are drawn by the
 * game's own shader, and that shader expects the brightness to be there. Taking it away then would
 * flatten a world nothing else is shading.
 * <p>
 * <strong>What the table reaches was read rather than assumed, and in BOTH jars</strong>, which
 * matters because the half that decides here is not the game's. In the game,
 * {@code CardinalLighting.byFace} is called from {@code BlockModelLighter} alone, reached from
 * {@code SectionCompiler} and from {@code ModelBlockRenderer}, and that second one outside the mesh
 * only from {@code MovingBlockFeatureRenderer}. In Sodium, which is what really meshes the chunks
 * here, it is called from {@code FlatLightPipeline} and {@code SmoothLightPipeline}. The mixin sits
 * on the table itself rather than on any of its callers, so every one of them is covered whichever
 * renderer is building.
 * <p>
 * Two consequences worth keeping. A block entity and a block held in the hand never go through the
 * table at all, so nothing here touches them. And a block in flight, the falling one and the one a
 * piston carries, is lit through it PER FRAME rather than out of a mesh, so that one follows the
 * answer the instant it moves, with no rebuild in it.
 * <p>
 * <strong>For everything else the number is baked into the mesh</strong>, so a change here is worth
 * a rebuilt world, through the same door {@link BlockStateIds} uses and for the same reason: the
 * sections standing at this instant carry the other answer, and nothing else would ever reach them.
 */
public final class FaceShading {

	/**
	 * Read on every chunk build thread and written on the render thread and on the pack load worker,
	 * hence volatile; a face is asked about once per quad, so the read has to stay a field read and
	 * nothing more.
	 * <p>
	 * A build already running when this moves reads whichever value it happens to see, and that is
	 * why the move asks for the whole world again rather than trusting the timing.
	 */
	private static volatile boolean dropped;

	private FaceShading() {
	}

	/** Whether the game's per face brightness is to be left out of what a chunk mesh carries. */
	public static boolean dropped() {
		return dropped;
	}

	/**
	 * What the pack asked for, taken with its other directives.
	 *
	 * @param oldLighting whether the pack wrote {@code oldLighting=true}, which is it asking for the
	 *                    game's own per face brightness to be left in
	 */
	public static void install(boolean oldLighting) {
		follow(!oldLighting && TerrainDraw.asked(), true);
	}

	/**
	 * The game's own brightness back.
	 * <p>
	 * Asked from the five places a chain or the terrain family stops, which is what covers every road
	 * that ends with the game's shader drawing the chunks: {@code PackChain.stop} for the three
	 * refusals at load, {@code PackChain.putAway} for the thirteen the frame can take,
	 * {@link TerrainDraw#wanted(boolean)} for every road that switches the family off, and the two
	 * places {@link TerrainDraw} lowers that same field directly, one after a failed read and one
	 * after a failed prepare. All of them leave
	 * the mesh where it stands and hand the pass back, so the world would otherwise be drawn by a
	 * shader that expects the factor, out of a mesh built without it.
	 * <p>
	 * More than one can fire for a single event, which is why this is written to be idempotent and
	 * why a call that moves nothing says nothing: the guard in {@code follow} is the whole of what
	 * makes the second call free.
	 */
	public static void none() {
		follow(false, false);
	}

	/**
	 * The state, said in both directions.
	 * <p>
	 * A switch that logs only when it is armed makes a morning of before and after readings
	 * unusable, because a silent log reads the same as a pack the engine never reached. So the load
	 * says its answer whichever way it falls, which is what {@code said} is for, while the roads that
	 * put the brightness back say nothing unless they really moved it: several of them can fire for
	 * one event, and a line printed four times for one refusal is its own kind of unreadable.
	 */
	private static void follow(boolean asked, boolean said) {
		if (said) {
			Vitrail.logger().info("The game's own per face brightness is {} the chunk mesh",
					asked ? "left out of" : "in");
		}

		if (dropped == asked) {
			return;
		}

		if (!said) {
			Vitrail.logger().info("The game's own per face brightness is in the chunk mesh again, "
					+ "the pack's own terrain program having stopped drawing the chunks");
		}

		dropped = asked;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null) {
			return;
		}

		Vitrail.logger().info("The sections carry it, so they are all built again");
		minecraft.levelExtractor.allChanged();
	}
}
