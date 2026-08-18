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
 * The numbers a pack gives to the items and the entities it wants to recognise, from
 * {@code item.properties} and {@code entity.properties}.
 * <p>
 * A line reads {@code item.<n> = <name> <name> ...} or {@code entity.<n> = ...}, and the shader then
 * compares {@code heldItemId}, {@code currentRenderedItemId} or {@code entityId} against {@code n}.
 * The number is the pack's own: BSL spells out a three digit scheme in a comment at the top of its
 * item file, where the hundreds digit is a category and the rest is the colour of the light the item
 * emits.
 * <p>
 * <strong>One class for the two files because it is one format.</strong> Iris parses both through a
 * single {@code parseIdMap}, differing in the keyword alone
 * ({@code shaderpack/IdMap.java:149-155}), and the two would otherwise be the same eighty lines
 * twice over. {@code block.properties} is not in here and must not be: it maps block STATES, with
 * tags and state selectors, and {@link BlockIds} is that.
 * <p>
 * Names are namespaced, and a bare one means {@code minecraft}. A token carrying a state
 * selector, {@code stone:variant=granite} and the like, is skipped and reported: OptiFine matched
 * on block state there, and answering the wrong item is worse than answering none.
 * <p>
 * Nothing here reaches the game's registries. This is the pack's half of the table, and it is
 * built as soon as the pack is read so that whatever holds a live item or entity only has to ask a
 * name.
 */
public final class NameIds {

	/** Which of the two files this table was read from, and the word its lines begin with. */
	public enum Kind {

		ITEM("item"),
		ENTITY("entity");

		private final String keyword;

		Kind(String keyword) {
			this.keyword = keyword;
		}

		String keyword() {
			return this.keyword;
		}

		String fileName() {
			return this.keyword + ".properties";
		}

		/**
		 * The lines of this file, {@code <keyword>.<digits> = <names>}. Built per kind rather than
		 * shared, a pattern being cheap to hold and the keyword being what tells the two apart.
		 */
		Pattern entry() {
			return Pattern.compile("^\\s*" + this.keyword + "\\.(\\d+)\\s*=\\s*(.*)$");
		}
	}

	/**
	 * What this table answers for a name the pack never mapped, and it is not nought: nought is a
	 * number a pack may hand out itself, {@code item.0} being a legal line. Iris answers the same
	 * ({@code IdMap.java:162}, {@code defaultReturnValue(-1)}), and what a pack meets on the mesh is
	 * that value carried unsigned, which is 65535.
	 */
	public static final int NONE = -1;

	private static final NameIds EMPTY = new NameIds(false, Map.of(), List.of());

	private final boolean present;
	private final Map<String, Integer> byName;
	private final List<String> problems;

	private NameIds(boolean present, Map<String, Integer> byName, List<String> problems) {
		this.present = present;
		this.byName = Map.copyOf(byName);
		this.problems = List.copyOf(problems);
	}

	public static NameIds empty() {
		return EMPTY;
	}

	public static NameIds read(ShaderPackSource source, Map<String, String> defines, Kind kind)
			throws IOException {
		PropertiesFile file = PropertiesFile.read(source, kind.fileName());
		if (!file.present()) {
			return EMPTY;
		}

		Map<String, Integer> byName = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();
		Pattern lines = kind.entry();

		file.walk(defines, line -> {
			Matcher entry = lines.matcher(line);
			if (!entry.matches()) {
				return;
			}

			int id;
			try {
				id = Integer.parseInt(entry.group(1));
			} catch (NumberFormatException e) {
				problems.add(kind.keyword() + "." + entry.group(1) + ": the identifier is not a number");
				return;
			}

			for (String token : Macros.expand(entry.group(2).trim(), defines).split("\\s+", -1)) {
				if (token.isEmpty()) {
					continue;
				}

				if (token.indexOf('=') >= 0) {
					problems.add(kind.keyword() + "." + id + ": " + token
							+ " selects on state, which is not read here");
					continue;
				}

				// The last declaration wins, which is what a pack that overrides its own table
				// upgrade path expects, and the earlier one is named rather than lost quietly.
				Integer previous = byName.put(namespaced(token), id);
				if (previous != null && previous != id) {
					problems.add(token + ": claimed by " + kind.keyword() + "." + previous + " and by "
							+ kind.keyword() + "." + id);
				}
			}
		});

		return new NameIds(true, byName, problems);
	}

	private static String namespaced(String token) {
		return token.indexOf(':') < 0 ? "minecraft:" + token : token;
	}

	public boolean present() {
		return this.present;
	}

	/** The pack's number for a name, or {@link #NONE} when the pack says nothing about it. */
	public int id(String namespacedName) {
		return this.byName.getOrDefault(namespacedName, NONE);
	}

	public Map<String, Integer> byName() {
		return this.byName;
	}

	/** One line per entry that was skipped, naming it. */
	public List<String> problems() {
		return this.problems;
	}
}
