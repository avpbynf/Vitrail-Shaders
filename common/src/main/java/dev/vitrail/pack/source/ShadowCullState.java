package dev.vitrail.pack.source;

/**
 * What a pack asks of the walk the light makes, through the one key {@code shadow.culling}.
 * <p>
 * Four states and not four spellings of one, and the names are Iris's own
 * ({@code shaderpack/properties/ShadowCullState.java}): each of them picks a DIFFERENT shape to
 * measure a section against. Which shape each state buys is settled once, where the frustum is
 * built, and never here.
 * <p>
 * <strong>The words a pack writes do not say what they look like they say.</strong>
 * {@code shadow.culling=false} is not "do not cull", it is {@link #DISTANCE}, which keeps a box
 * around the player and adds no view frustum to it; {@code true} is not "cull more", it is
 * {@link #ADVANCED}, which is what {@link #DEFAULT} already gives. Iris maps them at
 * {@code shaderpack/properties/ShaderProperties.java:180-185} and this enum keeps that mapping in
 * {@link #of}.
 */
public enum ShadowCullState {

	/**
	 * The pack said nothing. Iris then decides on whether the pack VOXELISES, a geometry stage on
	 * the shadow program or an image load / store still standing on it, and lands on
	 * {@link #ADVANCED} where it does not ({@code shadows/ShadowRenderer.java:302}).
	 */
	DEFAULT,

	/** The camera's volume swept along the light, which is the tightest of the four. */
	ADVANCED,

	/** The same, widened by a box at the pack's own {@code voxelDistance} that is never cut. */
	SAFE_ZONE,

	/** No sweep at all: a box around the player, or everything when that box does not apply. */
	DISTANCE;

	/**
	 * The state a written word means, or null for a word this cannot read.
	 * <p>
	 * {@code reversed} and {@code safe_zone} are the same state under two spellings, the first being
	 * the older one. A null answer is a word Iris logs and steps over, leaving the default standing.
	 */
	public static ShadowCullState of(String word) {
		return switch (word) {
			case "false" -> DISTANCE;
			case "true" -> ADVANCED;
			case "reversed", "safe_zone" -> SAFE_ZONE;
			default -> null;
		};
	}
}
