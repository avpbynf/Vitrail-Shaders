package dev.vitrail.pack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code shaders.properties} declares: profiles, which programs are on, the settings
 * screens, the custom uniforms, and the blending each program wants.
 * <p>
 * The file is not a Java properties file and reading it as one gets it wrong in two ways. Only
 * {@code =} separates a key from a value, never {@code :}; and a line continued with a
 * backslash swallows the leading whitespace of the next line, blank lines included, which is
 * how one pack writes a list of three hundred tokens.
 * <p>
 * It also carries conditionals, and they matter: packs switch whole programs off with an
 * {@code #if} on a setting. Reading the file flat would report those programs as present.
 */
public final class ShaderProperties {

	private static final String FILE_NAME = "shaders.properties";

	private static final Pattern CONTINUATION = Pattern.compile("\\\\\\r?\\n\\s*");
	private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(if|ifdef|ifndef|else|elif|endif)\\b.*$");

	private static final Pattern PROFILE = Pattern.compile("^\\s*profile\\.(\\w+)\\s*=\\s*(.*)$");
	private static final Pattern PROGRAM_ENABLED = Pattern.compile("^\\s*program\\.(.+?)\\.enabled\\s*=\\s*(.*)$");
	private static final Pattern CUSTOM_UNIFORM =
			Pattern.compile("^\\s*(uniform|variable)\\.(\\w+)\\.(\\w+)\\s*=\\s*(.*)$");
	private static final Pattern SCREEN = Pattern.compile("^\\s*screen(\\.\\w+)?\\s*=\\s*(.*)$");
	private static final Pattern BLEND = Pattern.compile("^\\s*blend\\.([^=\\s.]+)(?:\\.(\\w+))?\\s*=\\s*(.*)$");
	private static final Pattern ALPHA_TEST = Pattern.compile("^\\s*alphaTest\\.(\\S+)\\s*=\\s*(.*)$");
	private static final Pattern SLIDERS = Pattern.compile("^\\s*sliders\\s*=\\s*(.*)$");
	private static final Pattern OTHER_KEY = Pattern.compile("^\\s*([A-Za-z_][\\w]*)[.=].*$");

	private static final Pattern SCREEN_TOKEN = Pattern.compile("^[A-Za-z_]\\w*$");

	/** Deep enough for any real profile chain, and a stop for one that refers to itself. */
	private static final int MAX_PROFILE_DEPTH = 8;

	private final List<String> lines;
	private final Map<String, String> profiles;
	private final Map<String, String> customUniformTypes;
	private final Set<String> screenTokens;
	private final Map<String, List<String>> screens;
	private final List<String> sliders;
	private final List<BlendDirective> blend;
	private final Map<String, String> alphaTest;
	private final Map<String, Integer> ignoredPrefixes;
	private final int directiveCount;
	private final int continuationCount;
	private final boolean present;

	private ShaderProperties(Builder builder) {
		this.lines = List.copyOf(builder.lines);
		this.profiles = Map.copyOf(builder.profiles);
		this.customUniformTypes = Map.copyOf(builder.customUniformTypes);
		this.screenTokens = Set.copyOf(builder.screenTokens);
		this.screens = Map.copyOf(builder.screens);
		this.sliders = List.copyOf(builder.sliders);
		this.blend = List.copyOf(builder.blend);
		this.alphaTest = Map.copyOf(builder.alphaTest);
		this.ignoredPrefixes = Map.copyOf(builder.ignoredPrefixes);
		this.directiveCount = builder.directiveCount;
		this.continuationCount = builder.continuationCount;
		this.present = builder.present;
	}

	public static ShaderProperties parse(ShaderPackSource source) throws IOException {
		Builder builder = new Builder();
		Optional<Path> file = source.file(FILE_NAME);
		if (file.isEmpty()) {
			return new ShaderProperties(builder);
		}

		builder.present = true;

		String text = String.join("\n", source.readLines(file.get()));
		Matcher continuation = CONTINUATION.matcher(text);
		while (continuation.find()) {
			builder.continuationCount++;
		}

		builder.lines = List.of(CONTINUATION.matcher(text).replaceAll(" ").split("\n", -1));

		for (String line : builder.lines) {
			read(line, builder);
		}

		return new ShaderProperties(builder);
	}

	private static void read(String line, Builder builder) {
		if (DIRECTIVE.matcher(line).matches()) {
			builder.directiveCount++;
			return;
		}

		Matcher profile = PROFILE.matcher(line);
		if (profile.matches()) {
			builder.profiles.put(profile.group(1), profile.group(2).trim());
			return;
		}

		// Read flat here and re-read conditionally later. The keys have to be known before the
		// conditionals can be evaluated, because a profile may decide them.
		if (PROGRAM_ENABLED.matcher(line).matches()) {
			return;
		}

		Matcher uniform = CUSTOM_UNIFORM.matcher(line);
		if (uniform.matches()) {
			builder.customUniformTypes.put(uniform.group(3), uniform.group(2));
			return;
		}

		Matcher screen = SCREEN.matcher(line);
		if (screen.matches()) {
			// The name of the page, "" for the one the pack opens on. A sub page is referred to
			// from its parent by its own name, so the two are joined by name rather than nested.
			String page = screen.group(1) == null ? "" : screen.group(1).substring(1);
			List<String> layout = builder.screens.computeIfAbsent(page, ignored -> new ArrayList<>());

			for (String token : screen.group(2).trim().split("\\s+")) {
				// A blank slot, written <empty>, is layout and has to be kept: it is how a pack
				// lines its options up in columns.
				if (SCREEN_TOKEN.matcher(token).matches()) {
					builder.screenTokens.add(token);
					layout.add(token);
				} else if (token.equals("<empty>")) {
					layout.add("");
				}
			}

			return;
		}

		Matcher blend = BLEND.matcher(line);
		if (blend.matches()) {
			builder.blend.add(new BlendDirective(blend.group(1), blend.group(2), blend.group(3).trim()));
			return;
		}

		Matcher alpha = ALPHA_TEST.matcher(line);
		if (alpha.matches()) {
			builder.alphaTest.put(alpha.group(1), alpha.group(2).trim());
			return;
		}

		Matcher sliders = SLIDERS.matcher(line);
		if (sliders.matches()) {
			for (String token : sliders.group(1).trim().split("\\s+")) {
				if (SCREEN_TOKEN.matcher(token).matches()) {
					builder.sliders.add(token);
				}
			}

			return;
		}

		// Anything left is counted by its first segment rather than dropped. That is what makes
		// a typo visible: one pack writes "progam.<name>.enabled" and loses the program.
		Matcher other = OTHER_KEY.matcher(line);
		if (other.matches()) {
			builder.ignoredPrefixes.merge(other.group(1), 1, Integer::sum);
		}
	}

	/**
	 * Which programs the file says are on, with both the conditionals around the line and the
	 * expression on the line itself evaluated. The value is not a word but an expression over
	 * the settings, so a pack writes {@code program.world0/shadow.enabled=SHADOW} and expects
	 * the program to disappear when that setting is off.
	 */
	public Map<String, Boolean> programToggles(Map<String, String> defines) {
		Map<String, Boolean> toggles = new LinkedHashMap<>();
		ConditionStack conditions = new ConditionStack();

		for (String line : this.lines) {
			Matcher directive = DIRECTIVE.matcher(line);
			if (directive.matches()) {
				applyDirective(directive.group(1), line, conditions, defines);
				continue;
			}

			if (!conditions.active()) {
				continue;
			}

			Matcher program = PROGRAM_ENABLED.matcher(line);
			if (program.matches()) {
				toggles.put(program.group(1), enabled(program.group(2).trim(), defines));
			}
		}

		return toggles;
	}

	/**
	 * An empty value leaves the program on, which is how a pack writes down that it exists
	 * without conditioning it. An expression that cannot be decided leaves it on too: dropping a
	 * program is the more damaging way to be wrong, since nothing then reports its absence.
	 */
	private static boolean enabled(String expression, Map<String, String> defines) {
		if (expression.isEmpty()) {
			return true;
		}

		return PreprocessorExpression.evaluate(expression, defines).orElse(true);
	}

	private static void applyDirective(String keyword, String line, ConditionStack conditions,
			Map<String, String> defines) {
		switch (keyword) {
			case "ifdef", "ifndef" -> {
				String name = line.replaceAll("^\\s*#\\s*\\w+\\s+", "").trim().split("\\s+")[0];
				conditions.ifDirective(keyword.equals("ifdef") == defines.containsKey(name));
			}
			case "if" -> conditions.ifDirective(
					PreprocessorExpression.evaluate(after(line), defines).orElse(true));
			case "elif" -> conditions.elifDirective(
					() -> PreprocessorExpression.evaluate(after(line), defines).orElse(true));
			case "else" -> conditions.elseDirective();
			default -> conditions.endifDirective();
		}
	}

	private static String after(String line) {
		return line.replaceAll("^\\s*#\\s*\\w+\\s*", "");
	}

	/**
	 * The settings a profile chooses. A profile may pull in another one, so this resolves the
	 * chain; the later choice wins, which is what lets a profile refine the one it includes.
	 */
	public Map<String, OptionValue> expandProfile(String name) {
		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		expandProfile(name, chosen, 0);

		return chosen;
	}

	private void expandProfile(String name, Map<String, OptionValue> chosen, int depth) {
		String body = this.profiles.get(name);
		if (body == null || depth > MAX_PROFILE_DEPTH) {
			return;
		}

		for (String token : body.trim().split("\\s+")) {
			if (token.isEmpty()) {
				continue;
			}

			if (token.startsWith("profile.")) {
				expandProfile(token.substring("profile.".length()), chosen, depth + 1);
			} else if (token.startsWith("!")) {
				chosen.put(token.substring(1), OptionValue.off());
			} else {
				int equals = token.indexOf('=');
				if (equals >= 0) {
					chosen.put(token.substring(0, equals), OptionValue.of(token.substring(equals + 1)));
				} else {
					chosen.put(token, OptionValue.on());
				}
			}
		}
	}

	public boolean present() {
		return this.present;
	}

	public Map<String, String> profiles() {
		return this.profiles;
	}

	public Map<String, String> customUniformTypes() {
		return this.customUniformTypes;
	}

	/**
	 * The pages a pack lays out, by name, the one it opens on being "". Each is the options in
	 * the order they are written, with an empty string where the pack asked for a blank slot.
	 * A name that is not an option is a sub page, and the key to find it under here.
	 */
	public Map<String, List<String>> screens() {
		return this.screens;
	}

	public Set<String> screenTokens() {
		return this.screenTokens;
	}

	public List<String> sliders() {
		return this.sliders;
	}

	public List<BlendDirective> blend() {
		return this.blend;
	}

	public Map<String, String> alphaTest() {
		return this.alphaTest;
	}

	public int directiveCount() {
		return this.directiveCount;
	}

	public int continuationCount() {
		return this.continuationCount;
	}

	public Map<String, Integer> ignoredPrefixes() {
		return new TreeMap<>(this.ignoredPrefixes);
	}

	/** How a program wants its output blended, either off or four GL factors. */
	public record BlendDirective(String program, String buffer, String value) {

		public boolean off() {
			return this.value.equalsIgnoreCase("off");
		}
	}

	private static final class Builder {

		private List<String> lines = List.of();
		private final Map<String, String> profiles = new LinkedHashMap<>();
		private final Map<String, String> customUniformTypes = new LinkedHashMap<>();
		private final Set<String> screenTokens = new LinkedHashSet<>();
		private final Map<String, List<String>> screens = new LinkedHashMap<>();
		private final List<String> sliders = new ArrayList<>();
		private final List<BlendDirective> blend = new ArrayList<>();
		private final Map<String, String> alphaTest = new LinkedHashMap<>();
		private final Map<String, Integer> ignoredPrefixes = new LinkedHashMap<>();
		private int directiveCount;
		private int continuationCount;
		private boolean present;
	}
}
