package dev.vitrail.pack.source;

/**
 * How a pack wants the world walked for the light, which is the whole of what {@code shadow.culling}
 * says.
 * <p>
 * The four states are Iris's, {@code shaderpack/properties/ShadowCullState.java}, and so are the
 * words that reach them. Two of those words are {@code true} and {@code false}, which is why this is
 * not read as a boolean: {@code false} does not turn culling off, it asks for the one state that
 * culls on distance alone, and reading it as a no would give a pack asking for a cheaper walk the
 * most expensive one there is.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
public enum ShadowCulling {

	/** Nothing asked for. The engine walks the light's frustum and no more. */
	DEFAULT,

	/**
	 * The walk a pack asks for with {@code true}: everything the light can see, whether or not the
	 * camera could. It is the widest of the four and the only one that cannot drop a caster whose
	 * shadow falls into view from off screen.
	 */
	ADVANCED,

	/**
	 * The walk a pack asks for with {@code reversed} or {@code safe_zone}, which is what six of the
	 * eight packs of the corpus write. A band around the camera is kept whatever the light sees, so
	 * that a caster just out of the light's frustum still darkens what is under it.
	 */
	SAFE_ZONE,

	/**
	 * The walk a pack asks for with {@code false}: distance alone, and the light's frustum is not
	 * consulted. The cheapest of the four and the one that drops the most.
	 */
	DISTANCE;

	/**
	 * The state this word asks for, or null for a word this directive does not take.
	 * <p>
	 * Null rather than {@link #DEFAULT} on purpose: the caller has to tell "the pack said nothing"
	 * from "the pack said something nobody understands", and only the second is worth a line in the
	 * log.
	 */
	public static ShadowCulling of(String value) {
		return switch (value) {
			case "true" -> ADVANCED;
			case "false" -> DISTANCE;
			case "reversed", "safe_zone" -> SAFE_ZONE;
			default -> null;
		};
	}
}
