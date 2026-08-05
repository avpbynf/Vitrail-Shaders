package dev.vitrail.render;

import dev.vitrail.pack.id.BlockIds;
import dev.vitrail.Vitrail;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What {@code block.properties} means once the registry is there to read it against: every block
 * state the pack named, mapped to the number it gave it.
 * <p>
 * The other half of {@link BlockIds}, which reads the file and knows nothing about blocks. The split
 * is what keeps the reading measurable over the whole corpus without starting the game; this side
 * needs the registry and the tags and can only run inside it.
 * <p>
 * <strong>The first declaration that matches wins</strong>, which is why the file's order is carried
 * this far and why every write here is a {@code putIfAbsent}. Iris resolves the same way and the
 * packs are written against Iris: two declarations covering one state is ordinary rather than a
 * mistake, and resolving it the other way round lights the block as something else.
 * <p>
 * Read on the chunk build threads and written on whichever thread loads a pack, so what they share
 * is one immutable map swapped whole. Nothing is ever added to a table that is being read.
 */
public final class BlockStateIds {

	/**
	 * What a state no declaration matched packs as. The shader takes {@code (packed >> 1) - 1}, so
	 * nought is the -1 every pack of the corpus tests against.
	 */
	public static final int NONE = 0;

	/**
	 * How large a packed value may be, which is what the int carrying it to the encoder has room for
	 * above the material byte. Twenty-three bits reach four million ids where the corpus stops at
	 * 32016, so a declaration this refuses is a pack doing something no pack has yet done.
	 */
	public static final int PACKED_MASK = 0x7FFFFF;

	private static volatile Object2IntMap<BlockState> table = empty();

	private BlockStateIds() {
	}

	/**
	 * Builds the table from what the pack declared, or empties it when the pack declared nothing.
	 * Called where the pack is read, which is also where it is read again once the world's own
	 * registries exist: tags come from the data pack, so at startup there are none to resolve.
	 */
	static void install(BlockIds ids) {
		Object2IntMap<BlockState> built = empty();
		List<String> unknown = new ArrayList<>();
		for (BlockIds.Entry entry : ids.entries()) {
			if (packedFrom(entry.id()) > PACKED_MASK) {
				// Refused rather than truncated. A number that does not fit would come back out of
				// the mesh as a different number, which lights the wrong blocks and says nothing.
				unknown.add("block." + entry.id() + ", too large for the mesh to carry");
			} else if (entry.tag()) {
				addTag(entry, built, unknown);
			} else {
				addBlock(entry, built, unknown);
			}
		}

		Object2IntMap<BlockState> previous = table;
		table = built;
		if (!ids.isEmpty()) {
			Vitrail.logger().info("{} of this pack's {} block declarations reach {} block states",
					ids.entries().size() - unknown.size(), ids.entries().size(), built.size());
		}

		// Named rather than counted. Every one of them is a block another mod was meant to add, or a
		// name the pack spelled wrong, and both read as "the pack does nothing for that block".
		if (!unknown.isEmpty()) {
			Vitrail.logger().info("{} name nothing this game has and are dropped: {}", unknown.size(),
					unknown);
		}

		ids.problems().forEach(problem -> Vitrail.logger().warn("{}", problem));
		rebuildIfMoved(previous, built);
	}

	/**
	 * Chunk meshes carry the number they were built with, so a table that changes under them does not
	 * reach what is already meshed: every section in sight keeps the previous pack's numbering until
	 * it is built again.
	 * <p>
	 * Which is not cosmetic, because the numbers are the pack's own and no two packs share them. BSL
	 * reads Complementary's stone, 10080, as its own id 100, which is waving grass: swap one pack for
	 * the other in a running game and every stone wall is lit and displaced as foliage until something
	 * rebuilds it. The only cure the player has then is to place a block, which rebuilds one section,
	 * so the whole thing reads as the light being broken rather than as a stale mesh.
	 * <p>
	 * So the world is rebuilt, through the door F3+A itself uses: {@code LevelExtractor.allChanged}
	 * raises a flag the next extract consumes, which is a frame boundary, rather than tearing the
	 * sections down inside the frame this is called from. Iris rebuilds at the same moment, where it
	 * installs its own ids. What stays decided once for the whole run is the mesh <em>format</em>,
	 * whether the number is carried at all; that one no reload can move.
	 * <p>
	 * Silent at startup, where the level is null and nothing has been meshed yet. That is also the
	 * ordinary case: a settings file saved with no change to {@code block.properties} rebuilds an
	 * equal table and asks for nothing.
	 */
	private static void rebuildIfMoved(Object2IntMap<BlockState> previous,
			Object2IntMap<BlockState> built) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || previous.equals(built)) {
			return;
		}

		Vitrail.logger().info("The block ids moved, from {} states to {}. The sections carry them, so "
				+ "they are all built again", previous.size(), built.size());
		minecraft.levelExtractor.allChanged();
	}

	/**
	 * The number to put on the mesh for one block state, packed the way the shader unpacks it:
	 * {@code ((id + 1) << 1) | isFluid}. The fluid bit is nought here, since every quad this is asked
	 * for comes from the block renderer and the fluid renderer pushes its own.
	 */
	public static int packed(BlockState state) {
		int id = table.getInt(state);

		return id < 0 ? NONE : packedFrom(id);
	}

	private static int packedFrom(int id) {
		return (id + 1) << 1;
	}

	private static void addBlock(BlockIds.Entry entry, Object2IntMap<BlockState> into,
			List<String> unknown) {
		Identifier identifier;
		try {
			identifier = Identifier.fromNamespaceAndPath(entry.namespace(), entry.path());
		} catch (RuntimeException e) {
			unknown.add(entry.name());

			return;
		}

		Block block = BuiltInRegistries.BLOCK.get(identifier).map(Holder::value).orElse(null);
		if (block == null) {
			unknown.add(entry.name());

			return;
		}

		add(block, entry, into);
	}

	/**
	 * A tag is matched case insensitively on both halves of its name, which is Iris's rule and not
	 * an obvious one: the registry holds the tag under its own identifier and a pack that wrote
	 * {@code %Minecraft:Logs} would otherwise name nothing.
	 */
	private static void addTag(BlockIds.Entry entry, Object2IntMap<BlockState> into,
			List<String> unknown) {
		List<HolderSet.Named<Block>> found = BuiltInRegistries.BLOCK.getTags()
				.filter(tag -> tag.key().location().getNamespace().equalsIgnoreCase(entry.namespace())
						&& tag.key().location().getPath().equalsIgnoreCase(entry.path()))
				.toList();
		if (found.isEmpty()) {
			unknown.add("%" + entry.name());

			return;
		}

		found.forEach(tag -> tag.forEach(block -> add(block.value(), entry, into)));
	}

	private static void add(Block block, BlockIds.Entry entry, Object2IntMap<BlockState> into) {
		StateDefinition<Block, BlockState> states = block.getStateDefinition();
		if (entry.predicates().isEmpty()) {
			states.getPossibleStates().forEach(state -> into.putIfAbsent(state, entry.id()));

			return;
		}

		// Resolved to properties once rather than compared as strings per state: a block with four
		// properties has a hundred states and the corpus filters two thirds of its declarations.
		Map<Property<?>, String> wanted = new LinkedHashMap<>();
		entry.predicates().forEach((key, value) -> {
			Property<?> property = states.getProperty(key);
			if (property == null) {
				// Not a problem of ours and not worth a warning per state: a pack keeps declarations
				// for blocks whose properties changed between versions, and dropping the filter would
				// bind the whole block instead of some of it.
				Vitrail.logger().debug("block.{} filters {} on {}, which has no such property",
						entry.id(), entry.name(), key);

				return;
			}

			wanted.put(property, value);
		});

		for (BlockState state : states.getPossibleStates()) {
			if (matches(state, wanted)) {
				into.putIfAbsent(state, entry.id());
			}
		}
	}

	// The property types are compared as the strings the file writes, so the wildcards the generics
	// would need buy nothing.
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static boolean matches(BlockState state, Map<Property<?>, String> wanted) {
		for (Map.Entry<Property<?>, String> condition : wanted.entrySet()) {
			Property property = condition.getKey();
			if (!condition.getValue().equals(property.getName(state.getValue(property)))) {
				return false;
			}
		}

		return true;
	}

	private static Object2IntMap<BlockState> empty() {
		Object2IntMap<BlockState> map = new Object2IntOpenHashMap<>();
		map.defaultReturnValue(-1);

		return map;
	}
}
