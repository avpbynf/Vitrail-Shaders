package dev.vitrail.pack.source;

/**
 * Which of the world's families a pack wants drawn into its shadow map.
 * <p>
 * Answered as a set rather than one word at a time, because that is how they have to be served. A
 * pack that asks to keep the entities out of its map and sees them in it is worse off than a pack
 * with no shadows at all: the picture stays believable and is wrong, and nothing on screen says
 * which of the two it is looking at.
 *
 * @param terrain       the opaque world seen from the light
 * @param translucent   water, ice and stained glass seen from the light
 * @param entities      every entity of the level, the player among them
 * @param player        the player alone, and only where {@link #entities} is off. Iris reads it that
 *                      way round ({@code shadows/ShadowRenderer.java:548-550}, an else branch and
 *                      not a second test), so it is not a flag that adds the player to the others:
 *                      it is what is left when the others are refused
 * @param blockEntities chests, banners, signs and the rest of what a section renders beside its mesh
 */
public record ShadowCasters(boolean terrain, boolean translucent, boolean entities, boolean player,
		boolean blockEntities) {

	/**
	 * Whether anything submitted through the feature renderers is drawn into the map at all, which
	 * is what decides whether the second walk of the world is worth making.
	 */
	public boolean anyFeature() {
		return this.entities || this.player || this.blockEntities;
	}
}
