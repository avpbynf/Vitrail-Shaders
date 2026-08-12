package dev.vitrail.pack.source;

/**
 * Which of the world's families a pack wants drawn into its shadow map.
 * <p>
 * Answered as a set rather than one word at a time, because that is how they have to be served. A
 * pack that asks to keep the entities out of its map and sees them in it is worse off than a pack
 * with no shadows at all: the picture stays believable and is wrong, and nothing on screen says
 * which of the two it is looking at.
 *
 * @param terrain            the opaque world seen from the light
 * @param translucent        water, ice and stained glass seen from the light
 * @param entities           every entity of the level, the player among them
 * @param player             the player alone, and only where {@link #entities} is off. Iris reads it
 *                           that way round ({@code shadows/ShadowRenderer.java:548-550}, an else
 *                           branch and not a second test), so it is not a flag that adds the player
 *                           to the others: it is what is left when the others are refused
 * @param blockEntities      chests, banners, signs and the rest of what a section renders beside its
 *                           mesh
 * @param lightBlockEntities the ones that give off light, and only where {@link #blockEntities} is
 *                           off. The same shape as the player again, and the same reference:
 *                           {@code shadows/ShadowRenderer.java:576-577} extracts as soon as either
 *                           word is on and filters down to the emitters only when the wider one is
 *                           off
 */
public record ShadowCasters(boolean terrain, boolean translucent, boolean entities, boolean player,
		boolean blockEntities, boolean lightBlockEntities) {

	/**
	 * What a pack asks for by saying nothing, which is Iris's own set
	 * ({@code shaderpack/properties/PackShadowDirectives.java:87-92}). Spelled once and read from
	 * here by everything that needs it, the reading included: a default written twice is two answers
	 * waiting to disagree, and the one that drifted would put a family into a map the pack asked to
	 * keep it out of.
	 */
	public static final ShadowCasters DEFAULT =
			new ShadowCasters(true, true, true, false, true, false);

	/**
	 * Whether anything submitted through the feature renderers is drawn into the map at all, which
	 * is what decides whether the second walk of the world is worth making.
	 */
	public boolean anyFeature() {
		return this.entities || this.player || this.blockEntities || this.lightBlockEntities;
	}

	/**
	 * Whether the block entities are wanted at all, by either of the two words that ask for them.
	 * Iris tests the pair in exactly this shape before it extracts any
	 * ({@code shadows/ShadowRenderer.java:576}).
	 */
	public boolean anyBlockEntity() {
		return this.blockEntities || this.lightBlockEntities;
	}

	/**
	 * Whether what was extracted has to be cut down to the emitters, which is the narrower word
	 * standing alone ({@code shadows/ShadowRenderer.java:577}, the argument it passes for its own
	 * filter).
	 */
	public boolean emittersOnly() {
		return !this.blockEntities && this.lightBlockEntities;
	}
}
