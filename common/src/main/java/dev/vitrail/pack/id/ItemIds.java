package dev.vitrail.pack.id;

import dev.vitrail.pack.source.Macros;
import dev.vitrail.pack.source.PropertiesFile;
import dev.vitrail.pack.source.ShaderPackSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numbers a pack gives to the items it wants to recognise, from {@code item.properties}.
 * <p>
 * A line reads {@code item.<n> = <name> <name> ...} and the shader then compares
 * {@code heldItemId} against {@code n}. The number is the pack's own: BSL spells out a three
 * digit scheme in a comment at the top of its file, where the hundreds digit is a category and
 * the rest is the colour of the light the item emits.
 * <p>
 * Names are namespaced, and a bare one means {@code minecraft}. A token carrying a state
 * selector, {@code stone:variant=granite} and the like, is skipped and reported: OptiFine matched
 * on block state there, and answering the wrong item is worse than answering none.
 * <p>
 * Nothing here reaches the game's registries. This is the pack's half of the table, and it is
 * built as soon as the pack is read so that whatever holds a live {@code ItemStack} only has to
 * ask a name.
 */
public final class ItemIds {

	private static final String FILE_NAME = "item.properties";

	private static final Pattern ENTRY = Pattern.compile("^\\s*item\\.(\\d+)\\s*=\\s*(.*)$");

	private static final ItemIds EMPTY = new ItemIds(false, Map.of(), List.of());

	private final boolean present;
	private final Map<String, Integer> byName;
	private final List<String> problems;

	private ItemIds(boolean present, Map<String, Integer> byName, List<String> problems) {
		this.present = present;
		this.byName = Map.copyOf(byName);
		this.problems = List.copyOf(problems);
	}

	public static ItemIds empty() {
		return EMPTY;
	}

	public static ItemIds parse(ShaderPackSource source, Map<String, String> defines) throws IOException {
		PropertiesFile file = PropertiesFile.read(source, FILE_NAME);
		if (!file.present()) {
			return EMPTY;
		}

		Map<String, Integer> byName = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();

		file.walk(defines, line -> {
			Matcher entry = ENTRY.matcher(line);
			if (!entry.matches()) {
				return;
			}

			int id;
			try {
				id = Integer.parseInt(entry.group(1));
			} catch (NumberFormatException e) {
				problems.add("item." + entry.group(1) + ": the identifier is not a number");
				return;
			}

			for (String token : Macros.expand(entry.group(2).trim(), defines).split("\\s+", -1)) {
				if (token.isEmpty()) {
					continue;
				}

				if (token.indexOf('=') >= 0) {
					problems.add("item." + id + ": " + token + " selects on state, which is not read here");
					continue;
				}

				// The last declaration wins, which is what a pack that overrides its own table
				// upgrade path expects, and the earlier one is named rather than lost quietly.
				Integer previous = byName.put(namespaced(token), id);
				if (previous != null && previous != id) {
					problems.add(token + ": claimed by item." + previous + " and by item." + id);
				}
			}
		});

		return new ItemIds(true, byName, problems);
	}

	private static String namespaced(String token) {
		return token.indexOf(':') < 0 ? "minecraft:" + token : token;
	}

	public boolean present() {
		return this.present;
	}

	/** The pack's number for an item, or -1 when the pack says nothing about it. */
	public int id(String namespacedName) {
		return this.byName.getOrDefault(namespacedName, -1);
	}

	public Map<String, Integer> byName() {
		return this.byName;
	}

	/** One line per entry that was skipped, naming it. */
	public List<String> problems() {
		return this.problems;
	}
}
