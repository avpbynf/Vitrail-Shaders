package dev.vitrail.pack.source;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.PackOption;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.target.TargetSize;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code shaders.properties} declares: profiles, which programs are on, the settings
 * screens, the custom uniforms, and the blending each program wants.
 * <p>
 * The file is not a Java properties file and reading it as one gets it wrong in two ways. Only
 * {@code =} separates a key from a value, never {@code :}; and everything after that first
 * {@code =} is the value, there being no end of line comment. A line continued with a backslash
 * swallows the next line's indentation and stops at the following newline, which is spelled out at
 * {@link #CONTINUATION}: crossing a blank line there is what makes a continued key eat the block
 * underneath it.
 * <p>
 * It also carries conditionals, and they matter: packs switch whole programs off with an
 * {@code #if} on a setting. Reading the file flat would report those programs as present.
 */
public final class ShaderProperties {

	private static final String FILE_NAME = "shaders.properties";

	// Only the indentation of the joined line is swallowed, never a blank line: {@code \\s*}
	// would cross one and make a continued key absorb whatever block follows it. Bliss ends
	// three of its continuations on a blank line, and its main screen swallowed the commented
	// block underneath.
	private static final Pattern CONTINUATION = Pattern.compile("\\\\\\r?\\n[ \\t]*");
	private static final Pattern DIRECTIVE = Pattern.compile("^\\s*#\\s*(if|ifdef|ifndef|else|elif|endif)\\b.*$");

	private static final Pattern PROFILE = Pattern.compile("^\\s*profile\\.(\\w+)\\s*=\\s*(.*)$");
	private static final Pattern PROGRAM_ENABLED = Pattern.compile("^\\s*program\\.(.+?)\\.enabled\\s*=\\s*(.*)$");
	private static final Pattern CUSTOM_UNIFORM =
			Pattern.compile("^\\s*(uniform|variable)\\.(\\w+)\\.(\\w+)\\s*=\\s*(.*)$");
	private static final Pattern SCREEN = Pattern.compile("^\\s*screen(\\.\\w+)?\\s*=\\s*(.*)$");
	// Both are read before SCREEN, and in this order. "screen.columns=2" matches SCREEN as well,
	// as a page named columns, which is how a page nobody can reach appears in the one pack that
	// writes the line; and "screen.NAME.columns=1" matches neither, so it used to be counted as an
	// unknown key. Packs indent these lines and put spaces around the equals sign, both of which
	// are why the pattern has to be this loose.
	private static final Pattern MAIN_COLUMNS = Pattern.compile("^\\s*screen\\.columns\\s*=\\s*(\\d+)\\s*$");
	private static final Pattern PAGE_COLUMNS = Pattern.compile("^\\s*screen\\.(\\w+)\\.columns\\s*=\\s*(\\d+)\\s*$");
	private static final Pattern BLEND = Pattern.compile("^\\s*blend\\.([^=\\s.]+)(?:\\.(\\w+))?\\s*=\\s*(.*)$");
	private static final Pattern ALPHA_TEST = Pattern.compile("^\\s*alphaTest\\.(\\S+)\\s*=\\s*(.*)$");
	private static final Pattern SLIDERS = Pattern.compile("^\\s*sliders\\s*=\\s*(.*)$");
	// Two lines that say what a pack cannot draw without, and what it would use if it were there.
	// Whitespace separated lists, and the last line of each wins, which is how Iris reads them.
	private static final Pattern IRIS_FEATURES =
			Pattern.compile("^\\s*iris\\.features\\.(required|optional)\\s*=\\s*(.*)$");
	private static final Pattern END_FLASH_SHADOWS = Pattern.compile("^\\s*endFlashShadows\\s*=\\s*(.*)$");
	// Whether the rain and the snow write the world's depth. Flat like the line above and unlike the
	// family below, because that is where Iris reads it: it is one of its plain boolean directives,
	// ShaderProperties.java:208, and no pack of the corpus writes it under a condition.
	private static final Pattern RAIN_DEPTH = Pattern.compile("^\\s*rain\\.depth\\s*=\\s*(.*)$");
	private static final Pattern SIZE_BUFFER = Pattern.compile("^\\s*size\\.buffer\\.([^=\\s.]+)\\s*=\\s*(.*)$");
	private static final Pattern SKY_ELEMENT = Pattern.compile("^\\s*(sun|moon|stars|sky)\\s*=\\s*(.*)$");
	// The fifth word of that family, kept apart because it is the one that takes neither a yes nor a
	// no: a pack writes off, fast or fancy, which are the game's own three cloud settings.
	private static final Pattern CLOUDS = Pattern.compile("^\\s*clouds\\s*=\\s*(.*)$");
	// And the one of that same family that carries two words rather than one, so it has a pattern
	// of its own: the first says whether the rain and the snow are drawn at all, the second whether
	// the splashes they leave on the ground are.
	private static final Pattern WEATHER = Pattern.compile("^\\s*weather\\s*=\\s*(.*)$");
	// Where the pack wants its particles drawn about the deferred stage. Two spellings, and the
	// second is the older one: Iris reads particles.before.deferred=true as an ordering of BEFORE
	// where nothing has already been said, so it is folded into the same answer here rather than
	// being a directive of its own that a reader would have to know about separately.
	private static final Pattern PARTICLE_ORDERING =
			Pattern.compile("^\\s*particles\\.ordering\\s*=\\s*(.*)$");
	private static final Pattern PARTICLES_BEFORE_DEFERRED =
			Pattern.compile("^\\s*particles\\.before\\.deferred\\s*=\\s*(.*)$");
	// The noise image is answered here because everything else about it is settled: one path, one
	// sampler, every stage. The general family is not, and is read by customTextures instead.
	private static final Pattern TEXTURE_NOISE = Pattern.compile("^\\s*texture\\.noise\\s*=\\s*(.*)$");
	private static final Pattern CUSTOM_TEXTURE =
			Pattern.compile("^\\s*((?:texture|customTexture)\\.[^=\\s]+)\\s*=\\s*(.*)$");
	private static final Pattern IMAGE = Pattern.compile("^\\s*image\\.[^=\\s.]+\\s*=\\s*(.*)$");
	private static final Pattern FLIP = Pattern.compile("^\\s*flip\\.([^=\\s.]+)\\.([^=\\s.]+)\\s*=\\s*(.*)$");
	private static final Pattern OTHER_KEY = Pattern.compile("^\\s*([A-Za-z_][\\w]*)[.=].*$");

	private static final Pattern SCREEN_TOKEN = Pattern.compile("^[A-Za-z_]\\w*$");

	/** Deep enough for any real profile chain, and a stop for one that refers to itself. */
	private static final int MAX_PROFILE_DEPTH = 8;

	/**
	 * A depth limit alone does not bound the work, for the reason {@link IncludeExpander} spells
	 * out about its own file budget: profiles form a graph rather than a tree, and one that names
	 * the next one ten times multiplies at every level. Nine profiles with no cycle in them, each
	 * naming the next ten times, run for four minutes on this machine and never reach the depth
	 * limit. A pack is downloaded content and this is read while the client is starting, so the
	 * total is bounded too. The corpus expands twenty-eight settings at its worst.
	 */
	private static final int MAX_PROFILE_EXPANSIONS = 10_000;

	private final List<String> lines;
	private final Map<String, String> profiles;
	private final Map<String, String> customUniformTypes;
	private final Set<String> screenTokens;
	private final Map<String, List<String>> screens;
	private final Map<String, List<ScreenToken>> screenLayout;
	private final Map<String, Integer> columns;
	private final List<String> sliders;
	private final List<String> requiredFeatures;
	private final List<String> optionalFeatures;
	private final List<BlendDirective> blend;
	private final Map<String, String> sizeBuffers;
	private final List<FlipDirective> flips;
	private final Map<String, AlphaTest> alphaTest;
	private final Map<String, String> malformedAlphaTests;
	private final Map<String, Integer> ignoredPrefixes;
	private final String noiseTexturePath;
	private final int directiveCount;
	private final int continuationCount;
	private final boolean present;
	private final boolean endFlashShadows;
	private final boolean rainDepth;

	private ShaderProperties(Builder builder) {
		this.lines = List.copyOf(builder.lines);
		// Ordered, like the layout below: a profile selector walks these in the order the pack
		// wrote them, and BSL's five run from MINIMUM to ULTRA rather than in any order.
		this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(builder.profiles));
		this.customUniformTypes = Map.copyOf(builder.customUniformTypes);
		this.screenTokens = Set.copyOf(builder.screenTokens);
		this.screens = Map.copyOf(builder.screens);
		// Not Map.copyOf: the order pages are declared in is the order a screen offers them, and
		// an immutable map does not keep one.
		Map<String, List<ScreenToken>> layout = new LinkedHashMap<>();
		builder.screenLayout.forEach((page, tokens) -> layout.put(page, List.copyOf(tokens)));
		this.screenLayout = Collections.unmodifiableMap(layout);
		this.columns = Map.copyOf(builder.columns);
		this.sliders = List.copyOf(builder.sliders);
		this.requiredFeatures = List.copyOf(builder.requiredFeatures);
		this.optionalFeatures = List.copyOf(builder.optionalFeatures);
		this.blend = List.copyOf(builder.blend);
		this.sizeBuffers = Map.copyOf(builder.sizeBuffers);
		this.flips = List.copyOf(builder.flips);
		this.alphaTest = Map.copyOf(builder.alphaTest);
		this.malformedAlphaTests = Map.copyOf(builder.malformedAlphaTests);
		this.ignoredPrefixes = Map.copyOf(builder.ignoredPrefixes);
		this.noiseTexturePath = builder.noiseTexturePath;
		this.directiveCount = builder.directiveCount;
		this.continuationCount = builder.continuationCount;
		this.present = builder.present;
		this.endFlashShadows = builder.endFlashShadows;
		this.rainDepth = builder.rainDepth;
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

		Matcher mainColumns = MAIN_COLUMNS.matcher(line);
		if (mainColumns.matches()) {
			putColumns(builder, "", mainColumns.group(1));
			return;
		}

		Matcher pageColumns = PAGE_COLUMNS.matcher(line);
		if (pageColumns.matches()) {
			putColumns(builder, pageColumns.group(1), pageColumns.group(2));
			return;
		}

		Matcher screen = SCREEN.matcher(line);
		if (screen.matches()) {
			// The name of the page, "" for the one the pack opens on. A sub page is referred to
			// from its parent by its own name, so the two are joined by name rather than nested.
			String page = screen.group(1) == null ? "" : screen.group(1).substring(1);
			List<String> layout = builder.screens.computeIfAbsent(page, _ -> new ArrayList<>());
			List<ScreenToken> slots = builder.screenLayout.computeIfAbsent(page, _ -> new ArrayList<>());

			for (String token : screen.group(2).trim().split("\\s+", -1)) {
				if (token.isEmpty()) {
					continue;
				}

				// A blank slot, written <empty>, is layout and has to be kept: it is how a pack
				// lines its options up in columns.
				if (SCREEN_TOKEN.matcher(token).matches()) {
					builder.screenTokens.add(token);
					layout.add(token);
				} else if (token.equals("<empty>")) {
					layout.add("");
				}

				slots.add(slotOf(token));
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
			String value = alpha.group(2).trim();
			AlphaTest.parse(value).ifPresentOrElse(
					test -> builder.alphaTest.put(alpha.group(1), test),
					() -> builder.malformedAlphaTests.put(alpha.group(1), value));
			return;
		}

		// Replaced and not added to: a second line of the same name is the one that counts, which is
		// how Iris reads both of them, and not how the sliders below are read, where the tokens of a
		// second line join the first. Read even though this engine serves no feature at all, because that is
		// exactly what makes the refusal say something: a pack that names what it cannot do without
		// is answered with the names, instead of falling over later on whichever sampler its
		// storage buffers happened to sit behind.
		Matcher features = IRIS_FEATURES.matcher(line);
		if (features.matches()) {
			List<String> named = new ArrayList<>();
			for (String token : features.group(2).trim().split("\\s+", -1)) {
				if (!token.isEmpty()) {
					named.add(token);
				}
			}

			if (features.group(1).equals("required")) {
				builder.requiredFeatures = named;
			} else {
				builder.optionalFeatures = named;
			}

			return;
		}

		Matcher sliders = SLIDERS.matcher(line);
		if (sliders.matches()) {
			for (String token : sliders.group(1).trim().split("\\s+", -1)) {
				if (SCREEN_TOKEN.matcher(token).matches()) {
					builder.sliders.add(token);
				}
			}

			return;
		}

		// Whether the pack means its shadows to follow the End flash. Off unless the pack says so,
		// which is what decides where the shadow light points in that one dimension, and no pack of
		// the corpus writes it.
		Matcher endFlash = END_FLASH_SHADOWS.matcher(line);
		if (endFlash.matches()) {
			builder.endFlashShadows = endFlash.group(1).trim().equalsIgnoreCase("true");
			return;
		}

		// Whether the rain and the snow are written into the world's depth, which the game only does
		// when its own transparency chain is on. Off unless the pack says so, which is Iris's default
		// for it as well. Three packs of the corpus write the line and all three ask for false, so
		// what reading it buys today is the pack that will ask for true rather than a picture that
		// changes now.
		Matcher rainDepth = RAIN_DEPTH.matcher(line);
		if (rainDepth.matches()) {
			Boolean value = truth(rainDepth.group(1).trim());
			if (value != null) {
				builder.rainDepth = value;
				return;
			}
		}

		// The value is kept exactly as written. It is often not a number at all but the name of
		// one of the pack's own settings, and substituting it needs those settings resolved,
		// which this class deliberately knows nothing about.
		Matcher size = SIZE_BUFFER.matcher(line);
		if (size.matches()) {
			builder.sizeBuffers.put(size.group(1), size.group(2).trim());
			return;
		}

		// Consumed only when the value is one of the four words that mean something, and read again
		// conditionally by skyElements. A line that says anything else falls through to the ignored
		// keys, which is where a reader has to be able to find it: two packs of the corpus write
		// sun=off, which neither Iris nor OptiFine reads as false, so their sun keeps being drawn and
		// nothing else would say why.
		Matcher element = SKY_ELEMENT.matcher(line);
		if (element.matches() && truth(element.group(2).trim()) != null) {
			return;
		}

		// The same rule for the fifth word, on its own three values: a clouds= line naming something
		// else is a line nothing here reads, and it belongs among the keys that say so.
		Matcher clouds = CLOUDS.matcher(line);
		if (clouds.matches() && CloudSetting.of(clouds.group(1).trim()) != null) {
			return;
		}

		// The same rule for the two word member of that family: consumed only where the first word
		// is one this engine reads, so that a "weather=on" falls through and is counted among the
		// keys nothing reads rather than looking honoured.
		Matcher weather = WEATHER.matcher(line);
		if (weather.matches() && truth(weather.group(1).trim().split("\\s+", 0)[0]) != null) {
			return;
		}


		Matcher flip = FLIP.matcher(line);
		if (flip.matches()) {
			String value = flip.group(3).trim();
			if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
				builder.flips.add(new FlipDirective(flip.group(1), flip.group(2),
						value.equalsIgnoreCase("true")));
				return;
			}
		}

		// The path is relative to shaders/, as every path in this file is. Four packs of the
		// corpus write the line, and their water and clouds are built against that image: the
		// generated noise is a stand in with the same look and none of the same values.
		Matcher noise = TEXTURE_NOISE.matcher(line);
		if (noise.matches()) {
			builder.noiseTexturePath = noise.group(1).trim();
			return;
		}

		// Consumed and not kept: the rest of the family is read by customTextures, which walks the
		// conditionals. Falling through here would leave those lines among the keys nothing reads
		// and make that count say the engine ignores what it now honours.
		if (CUSTOM_TEXTURE.matcher(line).matches()) {
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
	 * A count is a number the pack typed, so it can be one no screen could be laid out in. An
	 * unusable one is dropped here and the line is still consumed either way: letting it fall
	 * through to the screen pattern is what turns it into a page.
	 */
	private static void putColumns(Builder builder, String page, String text) {
		try {
			int count = Integer.parseInt(text);
			if (count > 0) {
				builder.columns.put(page, count);
			}
		} catch (NumberFormatException e) {
			// More digits than an int holds. The page keeps the default.
		}
	}

	/**
	 * One slot of a page, from the word the pack wrote. Whether the option exists, whether the
	 * page was ever written, and whether the pack declares any profile at all are three questions
	 * this class cannot answer, so none of them is asked here: the token is kept as written and
	 * resolved by whoever holds the rest of the pack.
	 * <p>
	 * A link is anything between brackets rather than a name matching a pattern, which is the rule
	 * packs are written against; one pack in the corpus links to a page it never wrote, and that
	 * link is shown rather than dropped.
	 */
	private static ScreenToken slotOf(String token) {
		if (token.equals("<empty>")) {
			return new ScreenToken.Blank();
		}

		if (token.equals("<profile>")) {
			return new ScreenToken.Profiles();
		}

		if (token.startsWith("[") && token.endsWith("]")) {
			return new ScreenToken.Link(token.substring(1, token.length() - 1));
		}

		if (token.equals("*")) {
			return new ScreenToken.Rest();
		}

		return new ScreenToken.Name(token);
	}

	/**
	 * Which programs the file says are on, with both the conditionals around the line and the
	 * expression on the line itself evaluated. The value is not a word but an expression over
	 * the settings, so a pack writes {@code program.world0/shadow.enabled=SHADOW} and expects
	 * the program to disappear when that setting is off.
	 * <p>
	 * The two are read in two different languages, and that is the packs' doing rather than a
	 * choice made here. The {@code #if} lines around the value are preprocessor conditions, so a
	 * pack opens {@code #if AA_MODE == 1} and expects arithmetic; the value itself is a boolean
	 * expression, where a name that is not one of the pack's own switches stands for true. Reading
	 * the value as a preprocessor condition switches off every pass a pack conditions on a
	 * numbered setting, and a pass removed moves the half every later one reads.
	 *
	 * @param options what the pack declares, which is the only way to tell a switch the pack owns
	 *                from a name it never declared or a setting that carries a number
	 */
	public Map<String, Boolean> programToggles(Map<String, String> defines, OptionIndex options) {
		Map<String, Boolean> toggles = new LinkedHashMap<>();
		programConditions(defines).forEach((program, expression) ->
				toggles.put(program, enabled(expression, defines, options)));

		return toggles;
	}

	/**
	 * The same programs with the expression left as written, so that a log can say which setting
	 * switched a pass off rather than only that something did. The key is the path the pack wrote,
	 * {@code world0/composite1} or {@code composite} at the root, and never the bare name: BSL
	 * conditions {@code world0/composite1} and {@code world-1/composite1} on two different
	 * expressions.
	 */
	public Map<String, String> programConditions(Map<String, String> defines) {
		Map<String, String> expressions = new LinkedHashMap<>();
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
				expressions.put(program.group(1), program.group(2).trim());
			}
		}

		return expressions;
	}

	/**
	 * An empty value leaves the program on, which is how a pack writes down that it exists
	 * without conditioning it. An expression that cannot be decided leaves it on too: dropping a
	 * program is the more damaging way to be wrong, since nothing then reports its absence.
	 */
	private static boolean enabled(String expression, Map<String, String> defines,
			OptionIndex options) {
		if (expression.isEmpty()) {
			return true;
		}

		Boolean value = new EnabledExpression(expression, defines, options).parse();

		return value == null || value;
	}

	/**
	 * The little boolean language a {@code program.NAME.enabled} value is written in: names,
	 * {@code true} and {@code false}, {@code !}, {@code &&}, {@code ||} and brackets.
	 * <p>
	 * Every term that is not a literal is a name, and a name stands for one thing only: the state
	 * of a switch the pack declares. Anything else, a setting that carries a number or a name the
	 * pack never declared at all, stands for true. That is what packs are written against, and the
	 * two shapes of the same mistake are both live in the corpus: one pack conditions a pass on a
	 * setting declared {@code #define LUT 0 //[0 1]}, another writes the literal {@code true}, and
	 * reading either as a preprocessor condition takes the pass away.
	 */
	private static final class EnabledExpression {

		/**
		 * How far a value may nest before it is called unreadable. A pack is downloaded content and
		 * this runs while the client is starting: a {@code StackOverflowError} is an {@code Error},
		 * so it walks straight through the {@code catch (IOException | RuntimeException)} of
		 * {@link dev.vitrail.pack.target.TargetPlan}, of the chain, and of the report that reads
		 * every pack of the folder at startup, selected or not.
		 */
		private static final int MAX_DEPTH = 64;

		private final String text;
		private final Map<String, String> defines;
		private final OptionIndex options;

		private int position;
		private int depth;
		private boolean failed;

		private EnabledExpression(String text, Map<String, String> defines, OptionIndex options) {
			this.text = text;
			this.defines = defines;
			this.options = options;
		}

		/** @return null when the expression cannot be read at all, which the caller takes as true */
		private Boolean parse() {
			boolean value = or();
			skipSpace();

			return (this.failed || this.position < this.text.length()) ? null : value;
		}

		private boolean or() {
			boolean left = and();
			while (accept("||") || accept("|")) {
				// Both sides are read whatever the left one said: short circuiting here would
				// leave the right one in the text and the whole expression unreadable.
				boolean right = and();
				left = left || right;
			}

			return left;
		}

		private boolean and() {
			boolean left = unary();
			while (accept("&&") || accept("&")) {
				boolean right = unary();
				left = left && right;
			}

			return left;
		}

		/** The only place the grammar nests, so the one place the budget has to be spent. */
		private boolean unary() {
			this.depth++;
			try {
				if (this.depth > MAX_DEPTH) {
					this.failed = true;
					return false;
				}

				if (accept("!")) {
					return !unary();
				}

				if (accept("(")) {
					boolean value = or();
					if (!accept(")")) {
						this.failed = true;
					}

					return value;
				}

				return truth(name());
			} finally {
				this.depth--;
			}
		}

		private String name() {
			skipSpace();
			int start = this.position;
			while (this.position < this.text.length() && isNamePart(this.text.charAt(this.position))) {
				this.position++;
			}

			if (start == this.position) {
				this.failed = true;
			}

			return this.text.substring(start, this.position);
		}

		private static boolean isNamePart(char c) {
			return Character.isLetterOrDigit(c) || c == '_' || c == '.';
		}

		/**
		 * A switch the pack declares answers with its state; everything else answers true. The
		 * declaration is what decides, not the value: {@code #define BLOOM} is a switch and
		 * {@code #define BLOOM_STRENGTH 1.5} is not, however the pack later writes them.
		 */
		private boolean truth(String name) {
			switch (name) {
				case "true", "1" -> {
					return true;
				}
				case "false", "0" -> {
					return false;
				}
				default -> {
				}
			}

			PackOption option = this.options.get(name).orElse(null);
			if (option == null) {
				return true;
			}

			if (option.kind() == PackOption.Kind.TOGGLE) {
				return this.defines.containsKey(name);
			}

			if (option.kind() != PackOption.Kind.CONST || !"bool".equals(option.constType())) {
				return true;
			}

			String value = this.defines.get(name);

			return value != null && (value.trim().equals("true") || value.trim().equals("1"));
		}

		private boolean accept(String token) {
			skipSpace();
			if (!this.text.startsWith(token, this.position)) {
				return false;
			}

			this.position += token.length();

			return true;
		}

		private void skipSpace() {
			while (this.position < this.text.length()
					&& Character.isWhitespace(this.text.charAt(this.position))) {
				this.position++;
			}
		}
	}

	private static void applyDirective(String keyword, String line, ConditionStack conditions,
			Map<String, String> defines) {
		switch (keyword) {
			case "ifdef", "ifndef" -> {
				String name = line.replaceAll("^\\s*#\\s*\\w+\\s+", "").trim().split("\\s+", -1)[0];
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
	 * The uniforms the pack declares for itself, in the order it writes them, with the
	 * conditionals around them evaluated and the engine's symbols substituted into the expression.
	 * <p>
	 * Both halves of that are load bearing, and reading the file flat gets both wrong. BSL writes
	 * its eleven biome uniforms twice, once against {@code BIOME_*} and once against the numeric
	 * identifiers of an older Minecraft, under an {@code #if} on the version; flat reading takes
	 * the second set and lands every biome on the wrong one. Bliss writes
	 * {@code variable.float.lightningFlash} three times in three branches, the last of which is
	 * the constant zero. And an expression that still says {@code BIOME_GROVE} does not parse at
	 * all, because the evaluator has no such name.
	 */
	public List<CustomUniform> customUniforms(Map<String, String> defines) {
		List<CustomUniform> uniforms = new ArrayList<>();
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

			Matcher uniform = CUSTOM_UNIFORM.matcher(line);
			if (uniform.matches()) {
				uniforms.add(new CustomUniform(uniform.group(1).equals("uniform"), uniform.group(2),
						uniform.group(3), Macros.expand(uniform.group(4).trim(), defines)));
			}
		}

		return uniforms;
	}

	/**
	 * The textures the pack supplies with a file of its own, by the key it wrote, in that order,
	 * with the conditionals around each line evaluated. {@code texture.noise} is not among them:
	 * it is answered by {@link #noiseTexturePath()} and is not one of these.
	 * <p>
	 * Conditionally and not flat, for the reason every other reader here is conditional.
	 * Complementary writes two of its {@code customTexture} lines under {@code #if} on its own
	 * settings, and a {@code texture.deferred.colortex3} read out of a dead branch would take a
	 * live colour target away from the pass that reads it.
	 * <p>
	 * The value is left exactly as written. What it means depends on how many words are in it, and
	 * on files this class has no business opening.
	 */
	public Map<String, String> customTextures(Map<String, String> defines) {
		Map<String, String> declared = new LinkedHashMap<>();
		ConditionStack conditions = new ConditionStack();

		for (String line : this.lines) {
			Matcher directive = DIRECTIVE.matcher(line);
			if (directive.matches()) {
				applyDirective(directive.group(1), line, conditions, defines);
				continue;
			}

			if (!conditions.active() || TEXTURE_NOISE.matcher(line).matches()) {
				continue;
			}

			Matcher texture = CUSTOM_TEXTURE.matcher(line);
			if (texture.matches()) {
				declared.put(texture.group(1), texture.group(2).trim());
			}
		}

		return declared;
	}

	/**
	 * Which pieces of the game's own sky the pack still wants drawn, live lines only.
	 * <p>
	 * Conditionally and not flat, and here that is not a nicety: both packs of the corpus that write
	 * one of these lines write it under an {@code #if} on a setting of their own, and both would be
	 * read backwards flat. Bliss switches its sun and its moon off in the {@code #else} of
	 * {@code RESOURCEPACK_SKY == 2 || RESOURCEPACK_SKY == 3}, which is the live branch at its
	 * default of nought; Mellow switches them off under a {@code ROUND_SUN} that its own settings
	 * leave commented out, so that line is dead and its sun stays.
	 * <p>
	 * A value this cannot read leaves the piece as it was, which is what Iris does with the same
	 * word, and the line stays among the keys nothing reads so that the pack's author can see it.
	 *
	 * @see SkyElements
	 */
	public SkyElements skyElements(Map<String, String> defines) {
		Map<String, Boolean> drawn = new LinkedHashMap<>();

		for (Matcher element : live(SKY_ELEMENT, defines)) {
			Boolean value = truth(element.group(2).trim());
			if (value != null) {
				drawn.put(element.group(1), value);
			}
		}

		// A walk of its own for the fifth word rather than a second test inside the first: what the
		// four words answer is a boolean and what this one answers is not, and one loop reading both
		// would carry the difference in the middle of it.
		CloudSetting clouds = CloudSetting.DEFAULT;
		for (Matcher asked : live(CLOUDS, defines)) {
			CloudSetting setting = CloudSetting.of(asked.group(1).trim());
			if (setting != null) {
				clouds = setting;
			}
		}

		return new SkyElements(drawn.getOrDefault("sun", true), drawn.getOrDefault("moon", true),
				drawn.getOrDefault("stars", true), drawn.getOrDefault("sky", true), clouds);
	}

	/**
	 * Whether the pack still wants the game's own rain and snow, and the splashes they leave on the
	 * ground, live lines only.
	 * <p>
	 * The same family as {@link #skyElements} and read the same way, for the same measured reason:
	 * the packs of the corpus that write one of these words write it under an {@code #if} on a
	 * setting of their own, and read flat two of them come out backwards. Iris reads this one flat
	 * ({@code shaderpack/properties/ShaderProperties.java:259-267}); no pack of the corpus writes it
	 * at all, so what that difference decides today is nothing, and what it buys is one reader for
	 * one family rather than two that can drift.
	 * <p>
	 * <strong>The second word is a word about particles and not about the rain.</strong> It leaves
	 * the curtain of rain alone and takes away the splash particles the ground throws up under it,
	 * which is a separate thing the game does on a tick rather than in the frame.
	 */
	public Weather weather(Map<String, String> defines) {
		boolean drawn = true;
		boolean particles = true;

		for (Matcher line : live(WEATHER, defines)) {
			// Split on whitespace, the first word being the rain and the second the splashes, and a
			// second word left out leaves the splashes alone. Iris takes the words the same way and
			// reads anything that is not "true" as false; this reads the four words the rest of the
			// file takes and leaves the piece as it was on anything else, which is the rule every
			// other boolean of this class already follows.
			String[] words = line.group(1).trim().split("\\s+", 0);
			Boolean rain = truth(words[0]);
			if (rain != null) {
				drawn = rain;
			}

			if (words.length > 1) {
				Boolean splashes = truth(words[1]);
				if (splashes != null) {
					particles = splashes;
				}
			}
		}

		return new Weather(drawn, particles);
	}

	/**
	 * Where the pack asked for its particles to be drawn about the deferred stage, lowercased, and
	 * empty where it did not ask.
	 * <p>
	 * <strong>Empty means the pack wrote nothing, and no default is worked out here.</strong> Iris
	 * computes one, {@code AFTER} for a pack that ships deferred programs and does not ask for
	 * separate entity draws, and then reaches for the answer nowhere in its own rendering; a default
	 * invented here would therefore be a word about a placement neither engine performs, said about
	 * packs that never wrote it. Five packs of the corpus write the line and all five write
	 * {@code mixed}.
	 * <p>
	 * The older spelling is folded in: {@code particles.before.deferred=true} is {@code before}, and
	 * it loses to an explicit ordering wherever the pack writes both, which is the precedence Iris
	 * gives them.
	 */
	public Optional<String> particleOrdering(Map<String, String> defines) {
		String ordering = null;

		for (Matcher before : live(PARTICLES_BEFORE_DEFERRED, defines)) {
			if (Boolean.TRUE.equals(truth(before.group(1).trim()))) {
				ordering = "before";
			}
		}

		for (Matcher line : live(PARTICLE_ORDERING, defines)) {
			String word = line.group(1).trim().toLowerCase(Locale.ROOT);
			if (!word.isEmpty()) {
				ordering = word;
			}
		}

		return Optional.ofNullable(ordering);
	}

	/**
	 * Every line one pattern matches in the branches of this file that are really live, in file
	 * order.
	 * <p>
	 * Shared by every reader of the sky and weather family rather than written once each: what counts
	 * as live is the whole subtlety of them, and walks that agree today are walks that can be edited
	 * on different days. Three lines read it, the sky's four words, the fifth about the clouds and
	 * the weather's two.
	 */
	private List<Matcher> live(Pattern pattern, Map<String, String> defines) {
		List<Matcher> matched = new ArrayList<>();
		ConditionStack conditions = new ConditionStack();

		for (String line : this.lines) {
			Matcher directive = DIRECTIVE.matcher(line);
			if (directive.matches()) {
				applyDirective(directive.group(1), line, conditions, defines);
				continue;
			}

			Matcher match = pattern.matcher(line);
			if (conditions.active() && match.matches()) {
				matched.add(match);
			}
		}

		return matched;
	}

	/**
	 * The four words a boolean directive of this file may carry, and null for anything else.
	 * <p>
	 * Iris reads {@code true} and {@code 1} as one answer and {@code false} and {@code 0} as the
	 * other; OptiFine reads {@code true} and {@code false} alone. The wider of the two is taken,
	 * because a pack written against either is then read the way its author meant it.
	 */
	private static Boolean truth(String value) {
		if (value.equals("true") || value.equals("1")) {
			return Boolean.TRUE;
		}

		return (value.equals("false") || value.equals("0")) ? Boolean.FALSE : null;
	}

	/**
	 * The sampler names an {@code image.NAME} directive hangs a storage image on, live lines only.
	 * <p>
	 * Read for one purpose and no more: to tell a refusal from a refusal. A {@code sampler3D} that
	 * nothing in the pack backs with a file is a name this engine has nothing to put behind, and one
	 * an {@code image} directive names is a volume a compute pass would have filled. Neither can be
	 * bound, and only the second one says what is missing is a pass rather than a texture.
	 * <p>
	 * The sampler is the first word of the value, and {@code none} is the format's way of writing
	 * that the image is never sampled at all, so it names nothing.
	 */
	public Set<String> imageSamplers(Map<String, String> defines) {
		Set<String> samplers = new LinkedHashSet<>();
		ConditionStack conditions = new ConditionStack();

		for (String line : this.lines) {
			Matcher directive = DIRECTIVE.matcher(line);
			if (directive.matches()) {
				applyDirective(directive.group(1), line, conditions, defines);
				continue;
			}

			Matcher image = IMAGE.matcher(line);
			if (conditions.active() && image.matches()) {
				String[] words = image.group(1).trim().split("\\s+", -1);
				if (!words[0].isEmpty() && !words[0].equalsIgnoreCase("none")) {
					samplers.add(words[0]);
				}
			}
		}

		return samplers;
	}

	/**
	 * The settings a profile chooses. A profile may pull in another one, so this resolves the
	 * chain; the later choice wins, which is what lets a profile refine the one it includes.
	 */
	public Map<String, OptionValue> expandProfile(String name) {
		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		expandProfile(name, chosen, 0, new int[1]);

		return chosen;
	}

	/**
	 * @param budget how many expansions the whole nest has left, shared rather than restarted at
	 *               each hop. Neither a set of names already seen nor a set of names on the path
	 *               would do instead: the first changes what the format means, since the later
	 *               choice wins and a profile named twice is written twice on purpose, and the
	 *               second stops only cycles, which are not what costs
	 */
	private void expandProfile(String name, Map<String, OptionValue> chosen, int depth, int[] budget) {
		String body = this.profiles.get(name);
		if (body == null || depth > MAX_PROFILE_DEPTH || ++budget[0] > MAX_PROFILE_EXPANSIONS) {
			return;
		}

		for (String token : body.trim().split("\\s+", -1)) {
			if (token.isEmpty()) {
				continue;
			}

			if (token.startsWith("profile.")) {
				expandProfile(token.substring("profile.".length()), chosen, depth + 1, budget);
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

	/**
	 * Whether the pack asked for its shadows to follow the End flash, off unless it says otherwise.
	 * It is the pack opting in, so it decides where the shadow light points in the End and nothing
	 * else; the flash's own position is published either way.
	 */
	public boolean endFlashShadows() {
		return this.endFlashShadows;
	}

	/**
	 * Whether the pack asked for the rain and the snow to write the world's depth, off unless it
	 * says otherwise.
	 * <p>
	 * The game writes it only where its own transparency chain is running, so a pack asking for this
	 * is asking for the curtain to occlude whatever is drawn behind it afterwards, and what that
	 * decides on our side is which of the game's two weather pipelines the pack's program inherits
	 * its depth state from.
	 */
	public boolean rainDepth() {
		return this.rainDepth;
	}

	/** Each profile's unexpanded body, in the order the pack declares them. */
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

	/**
	 * The same pages with every token kept: a blank slot, an option, a link to another page, the
	 * profile selector, or the dump of everything no page named.
	 * <p>
	 * {@link #screens()} and {@link #screenTokens()} keep the shape and the role they had, because
	 * the measurements this project compares itself against were taken with them, and they drop the
	 * three hundred and eighteen tokens of the corpus that are not an option name. They lose one
	 * thing: reading {@code screen.columns} now consumes that line, so it no longer leaves behind a
	 * page named {@code columns} that nothing could reach. Bliss is the one pack that writes it, and
	 * it goes from sixty three pages to sixty two. This is the form a screen reads. Pages come in
	 * the order the pack declares them, the one it opens on being "".
	 */
	public Map<String, List<ScreenToken>> screenLayout() {
		return this.screenLayout;
	}

	/** How many columns a page asks for, when it asks. "" is the page the pack opens on. */
	public OptionalInt columns(String page) {
		Integer count = this.columns.get(page);

		return count == null ? OptionalInt.empty() : OptionalInt.of(count);
	}

	public List<String> sliders() {
		return this.sliders;
	}

	/**
	 * What the pack says it cannot be drawn without, in the pack's own spelling and unfiltered.
	 * <p>
	 * Whoever compares this list decides what the names mean; this class only reads them. Iris
	 * refuses a pack naming one it cannot serve, and the same answer is the honest one here: this
	 * engine serves none of them, so any name at all is a name it cannot answer.
	 */
	public List<String> requiredFeatures() {
		return this.requiredFeatures;
	}

	/**
	 * What the pack would use if it were there, and takes another path without. It is the list Iris
	 * turns into {@code IRIS_FEATURE_} defines for the GLSL; this engine defines none, which is what
	 * tells a pack to take the other path.
	 * <p>
	 * Nothing in the engine reads this list, and that is deliberate rather than an oversight: there
	 * is nothing here to decide from it. What taking the line out of the ignored keys buys is
	 * already bought by reading it at all, since that is what stops it being counted; what the list
	 * itself is for is the measurement made out of the game, which is where the corpus is counted,
	 * exactly as {@link #malformedAlphaTests} is.
	 */
	public List<String> optionalFeatures() {
		return this.optionalFeatures;
	}

	public List<BlendDirective> blend() {
		return this.blend;
	}

	/**
	 * The size a pack asks a colour target to be, by the name it wrote, raw. The two tokens are
	 * often settings rather than numbers, so reading them needs {@link TargetSize} and the
	 * resolved settings.
	 */
	public Map<String, String> sizeBuffers() {
		return this.sizeBuffers;
	}

	/** Where a pack takes the ping pong into its own hands. One line in the whole corpus. */
	public List<FlipDirective> flips() {
		return this.flips;
	}

	/**
	 * What alpha a program discards at, when the pack overrides the default of the pass it is drawn
	 * in. Empty for a line this could not read, which {@link #malformedAlphaTests()} names.
	 *
	 * @param program the name of the file that serves the pass, not the name the pass asked for
	 */
	public Optional<AlphaTest> alphaTest(String program) {
		return Optional.ofNullable(this.alphaTest.get(program));
	}

	/**
	 * The {@code alphaTest} lines that name neither a function and a reference nor {@code off}.
	 * <p>
	 * Kept rather than dropped because the failure it guards against is silent: a program whose
	 * override could not be read falls back to the default of its pass, which is a working picture
	 * with the wrong threshold in it. Nothing in the corpus writes one.
	 */
	public Map<String, String> malformedAlphaTests() {
		return this.malformedAlphaTests;
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

	/** The pack's own noise image, {@code texture.noise}, relative to {@code shaders/}. */
	public Optional<String> noiseTexturePath() {
		return Optional.ofNullable(this.noiseTexturePath);
	}

	/**
	 * One slot of a settings page, in the order the pack wrote it.
	 * <p>
	 * Sealed rather than a string, so that a form a screen forgets to draw is a compile error
	 * instead of a blank square nobody notices. A blank is one of the five forms and not the
	 * absence of a form: it is how a pack lines its columns up by hand, and the corpus writes
	 * seven hundred and seventy three of them.
	 */
	public sealed interface ScreenToken {

		/** {@code <empty>}. */
		record Blank() implements ScreenToken {
		}

		/** An option, if the pack turns out to declare one by that name. */
		record Name(String name) implements ScreenToken {
		}

		/** {@code [NAME]}. Pages are flat and joined by name, never nested. */
		record Link(String page) implements ScreenToken {
		}

		/** {@code <profile>}, which a pack declaring no profile does not get. */
		record Profiles() implements ScreenToken {
		}

		/** {@code *}, everything no page named, at this position. */
		record Rest() implements ScreenToken {
		}
	}

	/**
	 * One {@code uniform.<type>.<name>} or {@code variable.<type>.<name>} line. A {@code uniform.}
	 * reaches the shader, a {@code variable.} is only there for the ones that do.
	 */
	public record CustomUniform(boolean exposed, String type, String name, String expression) {
	}

	/** How a program wants its output blended, either off or four GL factors. */
	public record BlendDirective(String program, String buffer, String value) {

		public boolean off() {
			return this.value.equalsIgnoreCase("off");
		}
	}

	/** Whether a program swaps a target's two halves, said outright rather than inferred. */
	public record FlipDirective(String program, String buffer, boolean value) {
	}

	/**
	 * What a pack still wants of the game's own weather. A pack that says nothing wants both, which
	 * is what every pack of the corpus is: none of the eight writes the line.
	 *
	 * @param drawn     whether the curtain of rain and snow is drawn at all. Like the sky's four
	 *                  words this is a removal and not a choice of shader: a pack that says no draws
	 *                  its own, and handing it the game's on top puts two curtains in the air
	 * @param particles whether the splashes the ground throws up under the rain are drawn. A
	 *                  different thing in a different place: they are ordinary particles, spawned on
	 *                  a tick by the level rather than drawn by the weather renderer
	 */
	public record Weather(boolean drawn, boolean particles) {
	}

	/**
	 * How the game's own three cloud settings are named in {@code shaders.properties}, plus the
	 * absence of the line.
	 * <p>
	 * Not a boolean, alone in its family, and that is why it is an enum rather than a fifth flag: a
	 * pack asking for {@code fast} is not refusing the clouds, it is asking for the flat ones, and
	 * the game has a pipeline for each.
	 */
	public enum CloudSetting {

		/** The line is missing or says something this cannot read. The user's own setting stands. */
		DEFAULT,
		OFF,
		FAST,
		FANCY;

		/** The setting a value names, or null for anything else. */
		static CloudSetting of(String value) {
			return switch (value.toLowerCase(Locale.ROOT)) {
				case "off" -> OFF;
				case "fast" -> FAST;
				case "fancy" -> FANCY;
				default -> null;
			};
		}
	}

	/**
	 * Which pieces of the game's own sky a pack still wants drawn, and how it wants the clouds. A
	 * pack that says nothing wants all four and leaves the clouds to the user, which is what every
	 * one of them was written against.
	 * <p>
	 * The four booleans are a way of saying "I draw that myself", so the piece has to go rather than
	 * fall back to the game's own shader: a pack drawing its own sun in {@code gbuffers_skybasic} and
	 * handed the game's on top has two suns in the sky.
	 * <p>
	 * The fifth is not one of those and reads the other way round: it overrules the user's cloud
	 * setting so that the pack's own {@code gbuffers_clouds} is handed the geometry it was written
	 * for. It was deliberately not honoured while nothing here drew a cloud, because {@code off}
	 * would then have taken the game's clouds away and put nothing back.
	 */
	public record SkyElements(boolean sun, boolean moon, boolean stars, boolean sky,
			CloudSetting clouds) {

		/**
		 * Whether the piece one directive names is still drawn.
		 *
		 * @param directive the key as the file spells it, or anything else for a piece no directive
		 *                  of the format names, which is then always drawn
		 */
		public boolean allows(String directive) {
			return switch (directive) {
				case "sun" -> this.sun;
				case "moon" -> this.moon;
				case "stars" -> this.stars;
				case "sky" -> this.sky;
				default -> true;
			};
		}

	}

	private static final class Builder {

		private List<String> lines = List.of();
		private final Map<String, String> profiles = new LinkedHashMap<>();
		private final Map<String, String> customUniformTypes = new LinkedHashMap<>();
		private final Set<String> screenTokens = new LinkedHashSet<>();
		private final Map<String, List<String>> screens = new LinkedHashMap<>();
		private final Map<String, List<ScreenToken>> screenLayout = new LinkedHashMap<>();
		private final Map<String, Integer> columns = new LinkedHashMap<>();
		private final List<String> sliders = new ArrayList<>();
		private List<String> requiredFeatures = List.of();
		private List<String> optionalFeatures = List.of();
		private final List<BlendDirective> blend = new ArrayList<>();
		private final Map<String, String> sizeBuffers = new LinkedHashMap<>();
		private final List<FlipDirective> flips = new ArrayList<>();
		private final Map<String, AlphaTest> alphaTest = new LinkedHashMap<>();
		private final Map<String, String> malformedAlphaTests = new LinkedHashMap<>();
		private final Map<String, Integer> ignoredPrefixes = new LinkedHashMap<>();
		private String noiseTexturePath;
		private int directiveCount;
		private int continuationCount;
		private boolean present;
		private boolean endFlashShadows;
		private boolean rainDepth;
	}
}
