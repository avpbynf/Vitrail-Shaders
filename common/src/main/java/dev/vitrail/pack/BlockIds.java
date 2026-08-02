package dev.vitrail.pack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The numbers a pack gives to blocks in {@code block.properties}, which is what {@code mc_Entity.x}
 * carries.
 * <p>
 * The number is the pack's own and never the game's: BSL writes {@code block.10100=} for its
 * flowers and reads {@code int(mc_Entity.x / 100)} back, so a value invented here would light the
 * wrong blocks rather than fail. All eight packs of the corpus ship the file, from seven
 * declarations in Body Camera to three hundred and nine in Bliss.
 * <p>
 * <strong>Order is significant and this class keeps it.</strong> Two declarations may both match one
 * block state, and Iris resolves that with {@code putIfAbsent} while walking the file from the top,
 * so the FIRST one to match wins. A map keyed by name would lose that and light a block with
 * whichever declaration hashing happened to put last.
 * <p>
 * Nothing here knows what a block is. The entries are names and property filters, which is all the
 * file contains; matching them against real block states needs the registry and lives on the other
 * side of the line, so this stays measurable against the corpus without starting the game.
 */
public final class BlockIds {

	private static final String PREFIX = "block.";

	/**
	 * Folding a continuation can put two declarations on one line, and a properties reader would
	 * then take the second one as part of the first one's value. Iris splits them apart with this
	 * same substitution and calls it the worst code it has ever made; it is still the rule the packs
	 * are written against.
	 */
	private static final Pattern RUN_ON = Pattern.compile("(?<=\\S)[ \\t]*(?=block\\.\\d)");

	private static final Pattern KEY = Pattern.compile("^\\s*block\\.(\\d+)\\s*$");

	private final List<Entry> entries;
	private final List<String> problems;

	private BlockIds(List<Entry> entries, List<String> problems) {
		this.entries = List.copyOf(entries);
		this.problems = List.copyOf(problems);
	}

	/**
	 * One declared block, with the filters that narrow it to some of its states.
	 *
	 * @param tag        whether the entry named a block tag rather than a block, written {@code %oak}
	 * @param namespace  {@code minecraft} when the entry left it out
	 * @param predicates the block state properties that have to match, empty for all states
	 * @param id         the number the pack gave it, which is what the shader reads
	 */
	public record Entry(boolean tag, String namespace, String path, Map<String, String> predicates,
			int id) {

		public Entry {
			predicates = Map.copyOf(predicates);
		}

		/** {@code minecraft:tall_grass}, the key a lookup by name is indexed on. */
		public String name() {
			return this.namespace + ":" + this.path;
		}
	}

	/**
	 * Reads the file the pack ships, or answers empty when it ships none.
	 *
	 * @param defines what the conditionals in the file are evaluated against, since a pack may guard
	 *                a declaration on one of its own settings
	 */
	public static BlockIds read(ShaderPackSource source, Map<String, String> defines)
			throws IOException {
		PropertiesFile file = PropertiesFile.read(source, "block.properties");
		List<Entry> entries = new ArrayList<>();
		List<String> problems = new ArrayList<>();

		file.walk(defines, line -> {
			for (String statement : RUN_ON.split(line)) {
				readStatement(statement, entries, problems);
			}
		});

		return new BlockIds(entries, problems);
	}

	private static void readStatement(String statement, List<Entry> entries, List<String> problems) {
		int equals = statement.indexOf('=');
		if (equals < 0) {
			return;
		}

		Matcher key = KEY.matcher(statement.substring(0, equals));
		if (!key.matches()) {
			// layer.translucent and the like. Another feature of the same file, not ours to read.
			return;
		}

		int id;
		try {
			id = Integer.parseInt(key.group(1));
		} catch (NumberFormatException e) {
			problems.add("block." + key.group(1) + " is not a number this engine can hold");
			return;
		}

		for (String entry : statement.substring(equals + 1).trim().split("\\s+")) {
			if (!entry.isEmpty()) {
				parse(entry, id, entries, problems);
			}
		}
	}

	/**
	 * One entry, in the grammar of {@code BlockEntry.parse} of Iris.
	 * <p>
	 * The awkward part is that a colon separates the namespace from the path AND the path from each
	 * property filter, so what the second term is depends on whether it holds an equals sign:
	 * {@code tall_grass:half=upper} and {@code minecraft:tall_grass} have the same shape and mean
	 * different things.
	 */
	private static void parse(String entry, int id, List<Entry> entries, List<String> problems) {
		boolean tag = entry.startsWith("%");
		String[] parts = (tag ? entry.substring(1) : entry).split(":");
		if (parts.length == 0 || parts[0].isEmpty()) {
			problems.add("block." + id + " lists '" + entry + "', which names nothing");
			return;
		}

		String namespace = "minecraft";
		String path = parts[0];
		int from = 1;
		if (parts.length > 1 && !parts[1].contains("=")) {
			namespace = parts[0];
			path = parts[1];
			from = 2;
		}

		Map<String, String> predicates = new LinkedHashMap<>();
		for (int at = from; at < parts.length; at++) {
			int split = parts[at].indexOf('=');
			if (split < 0) {
				// Named rather than dropped: the entry still binds, on more states than the pack
				// meant, and that is a block lit as something else rather than a block left out.
				problems.add("block." + id + " lists '" + entry + "', where '" + parts[at]
						+ "' is not a property filter of the form key=value");
				continue;
			}

			predicates.put(parts[at].substring(0, split), parts[at].substring(split + 1));
		}

		entries.add(new Entry(tag, namespace, path, predicates, id));
	}

	/** Every declaration, in the order the file made them, which is the order that resolves them. */
	public List<Entry> entries() {
		return this.entries;
	}

	/** What could not be read, in whole sentences. Normally empty. */
	public List<String> problems() {
		return this.problems;
	}

	public boolean isEmpty() {
		return this.entries.isEmpty();
	}

	/**
	 * The largest number the pack hands out, which is what says whether the mesh can carry it.
	 * The corpus stops at 32016 and sixteen bits reach 65535.
	 */
	public int highest() {
		return this.entries.stream().mapToInt(Entry::id).max().orElse(0);
	}
}
