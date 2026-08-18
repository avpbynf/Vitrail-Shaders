package dev.vitrail.render;

import dev.vitrail.pack.id.NameIds;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * What {@code entity.properties} and {@code item.properties} mean once the registries are there to
 * read them against: the number the pack gave a kind of entity, and the number it gave an item.
 * <p>
 * The other half of {@link NameIds}, which reads the two files and knows nothing about the game, and
 * the same split {@link BlockStateIds} makes for {@code block.properties}: the reading side is
 * measurable over the whole corpus without starting the game, and this side can only run inside it.
 * <p>
 * <strong>One class for the two tables, where the block states get one of their own.</strong> The
 * split follows the work rather than the file: a block state has to be resolved into every state of
 * every block a declaration covers, tags included, which is two hundred lines; a name here is looked
 * up as a name, and the only thing to do with it is remember the answer.
 * <p>
 * Two names of the entity file are not entity types at all, and they are the pack's own way of
 * asking about a case the registry has no key for: {@code minecraft:current_player} for the entity
 * the camera is riding, and {@code minecraft:zombie_villager_converting} for one that is turning
 * back. Iris answers both here as well
 * ({@code mixin/entity_render_context/MixinEntityRenderDispatcher.java:44-47,64-77}).
 * <p>
 * The tables are read on the render thread and written on whichever thread loads a pack, so what
 * they share is one immutable table swapped whole. The cache is not immutable and does not need to
 * be: it is filled by the render thread alone, and a load happening in the middle of that leaves the
 * render thread writing into a map already thrown away. What it must never do is read a map another
 * thread is changing, and no other thread ever changes one.
 */
public final class PackNameIds {

	/** The name a pack asks about the entity the camera is riding under. */
	private static final Identifier CURRENT_PLAYER =
			Identifier.fromNamespaceAndPath("minecraft", "current_player");

	/** And the one it asks about a zombie villager that is being cured under. */
	private static final Identifier CONVERTING_VILLAGER =
			Identifier.fromNamespaceAndPath("minecraft", "zombie_villager_converting");

	private static volatile NameIds entities = NameIds.empty();

	private static volatile NameIds items = NameIds.empty();

	/**
	 * The answer for an entity type, worked out once each. Kept beside the table rather than inside
	 * it because the key is the game's object and {@link NameIds} names no game class; replaced whole
	 * when the table is, so a stale answer cannot outlive the pack that gave it.
	 */
	private static volatile Object2IntMap<EntityType<?>> byType = emptyCache();

	private PackNameIds() {
	}

	/** Takes both tables from a pack that has just been read, and drops what the last one answered. */
	static void install(NameIds entityIds, NameIds itemIds) {
		entities = entityIds;
		items = itemIds;
		byType = emptyCache();
	}

	/** Whether the pack named the entity the camera rides, which is what makes that case worth asking. */
	public static boolean namesCurrentPlayer() {
		return entities.id(CURRENT_PLAYER.toString()) != NameIds.NONE;
	}

	/** The same for a zombie villager being cured, which Iris also asks before looking. */
	public static boolean namesConvertingVillager() {
		return entities.id(CONVERTING_VILLAGER.toString()) != NameIds.NONE;
	}

	public static int currentPlayer() {
		return entities.id(CURRENT_PLAYER.toString());
	}

	public static int convertingVillager() {
		return entities.id(CONVERTING_VILLAGER.toString());
	}

	/**
	 * The pack's number for a kind of entity, or {@link NameIds#NONE} when it named none.
	 * <p>
	 * A type whose key the registry has not got answers {@code NONE} as well, and is remembered as
	 * such: the alternative is looking the same missing key up once per submission.
	 */
	public static int entity(EntityType<?> type) {
		Object2IntMap<EntityType<?>> cache = byType;
		int known = cache.getInt(type);
		if (known != NameIds.NONE || cache.containsKey(type)) {
			return known;
		}

		Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		int id = key == null ? NameIds.NONE : entities.id(key.toString());
		cache.put(type, id);

		return id;
	}

	/** The pack's number for an item, named by its model identifier or by its registry key. */
	public static int item(Identifier name) {
		return items.id(name.toString());
	}

	/**
	 * A cache with nothing in it, answering {@link NameIds#NONE} for a type it has not been asked
	 * about, which is what the lookup above tells apart from a real {@code NONE} by asking whether
	 * the key is there at all.
	 */
	private static Object2IntMap<EntityType<?>> emptyCache() {
		Object2IntMap<EntityType<?>> cache = new Object2IntOpenHashMap<>();
		cache.defaultReturnValue(NameIds.NONE);

		return cache;
	}
}
