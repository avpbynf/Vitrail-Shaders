package dev.vitrail.pack.option;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds the option scan and the rewrite to a second, plain reading of the same rules: the patterns
 * run on every line, exactly as they were written before any line was turned down early.
 * <p>
 * The reference below is deliberately the straightforward version and is kept as it was. Two things
 * are compared, the index a pack's lines make ({@link OptionIndex.Reader}) and the line a choice
 * makes ({@link OptionRewriter#apply}), on a generated pack, on lines chosen for being awkward and
 * on seeded random lines built from the tokens the patterns are made of, line terminators of every
 * kind among them.
 */
class OptionScanEquivalenceTest {

	// ---- the reference index -----------------------------------------------------------------

	private static final Pattern INDEX_DEFINE =
			Pattern.compile("^\\s*(//\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)\\s*(.*)$");
	private static final Pattern INDEX_CONSTANT =
			Pattern.compile("^\\s*const\\s+(int|float|bool|uint)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);(.*)$");
	private static final Pattern VALUE_LIST = Pattern.compile("//[^\\[]*\\[(.*?)]");
	private static final Pattern TRAILING_COMMENT = Pattern.compile("//.*");
	private static final Pattern CONDITIONAL =
			Pattern.compile("^\\s*#(?:ifdef|ifndef)\\s+([A-Za-z_]\\w*)\\s*$");

	private static PackOption referenceParse(String line, String where, int lineNumber) {
		Matcher define = INDEX_DEFINE.matcher(line);
		if (define.matches()) {
			String rest = define.group(3);
			String defaultText = TRAILING_COMMENT.matcher(rest).replaceAll("").trim();

			return new PackOption(define.group(2),
					defaultText.isEmpty() ? PackOption.Kind.TOGGLE : PackOption.Kind.VALUE,
					defaultText, referenceValueList(rest), define.group(1) != null, null, where, lineNumber);
		}

		Matcher constant = INDEX_CONSTANT.matcher(line);
		if (constant.matches()) {
			return new PackOption(constant.group(2), PackOption.Kind.CONST, constant.group(3).trim(),
					referenceValueList(constant.group(4)), false, constant.group(1), where, lineNumber);
		}

		return null;
	}

	private static List<String> referenceValueList(String text) {
		Matcher list = VALUE_LIST.matcher(text);
		if (!list.find()) {
			return List.of();
		}

		return List.of(list.group(1).trim().split("\\s+", -1)).stream().filter(token -> !token.isEmpty()).toList();
	}

	/** What the pack's files declare and what its conditionals test, read the plain way. */
	private record Scan(Map<String, PackOption> options, Set<String> references) {
	}

	private static Scan referenceScan(List<String> names, List<List<String>> files) {
		Map<String, PackOption> found = new LinkedHashMap<>();
		Set<String> referenced = new HashSet<>();
		for (int f = 0; f < files.size(); f++) {
			List<String> lines = files.get(f);
			for (int i = 0; i < lines.size(); i++) {
				PackOption option = referenceParse(lines.get(i), names.get(f), i + 1);
				if (option != null) {
					found.putIfAbsent(option.name(), option);
				}

				Matcher conditional = CONDITIONAL.matcher(lines.get(i));
				if (conditional.matches()) {
					referenced.add(conditional.group(1));
				}
			}
		}

		return new Scan(found, referenced);
	}

	// ---- the reference rewriter --------------------------------------------------------------

	private static final Pattern REWRITE_DEFINE =
			Pattern.compile("^(\\s*)(//\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)\\b(.*)$");
	private static final Pattern REWRITE_CONSTANT =
			Pattern.compile("^(\\s*)const\\s+(int|float|bool|uint)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);(.*)$");
	private static final Pattern VALUE_LIST_COMMENT = Pattern.compile("(//\\s*\\[.*)$");

	private static String referenceApply(String line, Map<String, OptionValue> chosen, int scale) {
		Matcher define = REWRITE_DEFINE.matcher(line);
		if (define.matches()) {
			OptionValue value = chosen.get(define.group(3));
			if (value == null) {
				return line;
			}

			String indent = define.group(1);
			String name = define.group(3);
			Matcher list = VALUE_LIST_COMMENT.matcher(define.group(4));
			String tail = list.find() ? " " + list.group(1) : "";

			if (!value.isBoolean()) {
				return indent + "#define " + name + " " + value.text() + tail;
			}

			return indent + (value.asBoolean() ? "#define " : "//#define ") + name + tail;
		}

		Matcher constant = REWRITE_CONSTANT.matcher(line);
		if (constant.matches()) {
			OptionValue value = ConstOptions.isOption(constant.group(3)) ? chosen.get(constant.group(3)) : null;

			boolean scaled = scale != 100 && "shadowMapResolution".equals(constant.group(3));
			if (value == null && !scaled) {
				return line;
			}

			if (value != null && value.isBoolean() && !"bool".equals(constant.group(2))) {
				return line;
			}

			String text = value == null ? constant.group(4).trim()
				: value.isBoolean() ? Boolean.toString(value.asBoolean()) : value.text();

			if (scaled) {
				String through = referenceThrough(text, scale);
				if (through == null && value == null) {
					return line;
				}

				if (through != null) {
					text = through;
				}
			}

			return constant.group(1) + "const " + constant.group(2) + " " + constant.group(3)
					+ " = " + text + ";" + constant.group(5);
		}

		return line;
	}

	private static String referenceThrough(String text, int scale) {
		int declared;
		try {
			declared = Integer.parseInt(text);
		} catch (NumberFormatException e) {
			return null;
		}

		return Integer.toString(Math.max(1, Math.round(declared * scale / 100.0F)));
	}

	// ---- the inputs --------------------------------------------------------------------------

	private static final List<String> FILLER = List.of("", "// shading", "varying vec2 texcoord;",
			"vec3 c = texture2D(colortex0, texcoord).rgb;", "\tif (d > 0.0) {", "gl_FragData[0] = vec4(c, 1.0);",
			"#include \"/lib/common.glsl\"", "#endif", "#else", "void main() {", "}", "/* block comment */",
			"#define PI 3.14159265", "#define EPSILON 0.0001 // not a list", "#version 330 compatibility",
			"int defined = 1;", "const vec3 up = vec3(0.0, 1.0, 0.0);", "#if defined(FOO) && BAR > 1",
			"float constant = 2.0;", "#pragma optimize(on)", "  #  define SPACED 1 // [0 1]", "#defined NOT 1");

	/** A pack of many files of code with a declaration or a conditional here and there. */
	private static List<List<String>> generatedPack(Random random, int files, int lines) {
		List<List<String>> pack = new ArrayList<>();
		int number = 0;
		for (int f = 0; f < files; f++) {
			List<String> file = new ArrayList<>();
			for (int i = 0; i < lines; i++) {
				switch (random.nextInt(9)) {
					case 0 -> file.add("#define OPT_" + random.nextInt(60) + (random.nextBoolean() ? "" : " 1 // [0 1 2]"));
					case 1 -> file.add("//#define OPT_" + random.nextInt(60));
					case 2 -> file.add("\t#ifdef OPT_" + random.nextInt(60));
					case 3 -> file.add("#ifndef OPT_" + random.nextInt(60) + (random.nextBoolean() ? "" : " // note"));
					case 4 -> file.add("const int shadowMapResolution = " + (1 << (9 + random.nextInt(4)))
							+ "; // [1024 2048 4096]");
					case 5 -> file.add("const float sunPathRotation = -" + number++ + ".0; //[-40.0 0.0 40.0]");
					case 6 -> file.add("const bool shadowHardwareFiltering = " + random.nextBoolean() + ";");
					default -> file.add(FILLER.get(random.nextInt(FILLER.size())));
				}
			}
			pack.add(file);
		}

		return pack;
	}

	private static final List<String> AWKWARD = List.of("", " ", "#", "#define", "#define ", "#define X", "#define X ",
			"#define\tX\t1\t//\t[0\t1]", "#defineX", "#define 1X", "#define X\u2028", "#define X 1 //\u2028 [1 2]",
			"#define X 1 //\u0085 // [3 4]", "#define X 1 // a\u2029 // [3 4]", "#define X\r", "#ifdef X\r",
			"#ifdef X\u2028", "#ifdef X\u0085", "//#define X", "// #define X 2 // [2 3]", "///#define X",
			"const int A = 1; const int B = 2;", "const int shadowMapResolution=2048;", "const int A = 1", "const  int A=1;",
			"const int shadowMapResolution = 1 //;", "const int shadowMapResolution = 1;\u2028", "constint A = 1;",
			"const uint shadowDistance = 4u; // [4u 8u]", "int defined = 1; // #define X",
			"const bool shadowHardwareFiltering = true;//[true false]", "  #ifndef   X  ", "#ifdef", "#if", "#ifdef 1X",
			"#ifdef X Y", "# ifdef X", "x #ifdef X", "#define X (a, b) a + b", "#define X 1 // []", "#define X 1 // [ ]",
			"#define X 1 // [a [b] c]", "#define X 1 [0 1]", "#define X 1 /* [0 1] */", "#define X \u00e9 1",
			"#define caf\u00e9 1", "\u00e9#define X", "\t\t//\t#\tdefine\tX\t1", "const\tint\tA\t=\t1\t;");

	/** The pieces the patterns are made of, so that a random line has a fair chance of nearly matching. */
	private static final List<String> TOKENS = List.of("#", "define", "#define ", "//", "// ", "//[", "/*", "*/",
			"const ", "int ", "float ", "bool ", "uint ", " ", "  ", "\t", "FOO", "BAR_2", "x", "1", "0.5", "-3", "=", ";",
			" = ", "[", "]", "[0 1 2]", "// [a b c]", "#ifdef ", "#ifndef ", "#if ", "#else", "#endif", "\r", "\u2028",
			"\u0085", "\u2029", "\n", "shadowMapResolution", "shadowDistance", "sunPathRotation", "constant", "defined",
			"(", ")", ",", "\"", "\u00e9", "{", "}", "+", "ifdef", "//#define ", "\t#define ", "const int FOO = 4; ");

	/** Lines that are one edit or three away from a declaration or a conditional. */
	private static final List<String> TEMPLATES = List.of("#define FOO", "//#define FOO",
			"\t#define BAR_2 1 // [0 1 2]", "#define x 4 //[4 8]", "  //  #  define X 2",
			"const int shadowMapResolution = 2048; // [1024 2048 4096]", "const float sunPathRotation = -40.0; //[-40.0 0.0]",
			"const bool shadowHardwareFiltering = true;", "const int shadowDistance = 128;",
			"const uint shadowDistance = 4u;", "#ifdef FOO", "#ifndef BAR_2", "  #define SPACED 1 // [0 1]",
			"#define A 1 // strength [1 2 3]", "const int A = 3;", "#define OPT_1 2 // [1 2] note");

	private static String randomLine(Random random) {
		StringBuilder line = new StringBuilder();
		if (random.nextBoolean()) {
			line.append(TEMPLATES.get(random.nextInt(TEMPLATES.size())));
			int edits = random.nextInt(4);
			for (int i = 0; i < edits && line.length() > 0; i++) {
				int at = random.nextInt(line.length() + 1);
				switch (random.nextInt(4)) {
					case 0 -> line.insert(at, TOKENS.get(random.nextInt(TOKENS.size())));
					case 1 -> line.delete(at, Math.min(line.length(), at + 1 + random.nextInt(3)));
					case 2 -> {
						if (at < line.length()) {
							line.replace(at, at + 1, TOKENS.get(random.nextInt(TOKENS.size())));
						}
					}
					default -> line.append(TOKENS.get(random.nextInt(TOKENS.size())));
				}
			}

			return line.toString();
		}

		int parts = 1 + random.nextInt(12);
		for (int i = 0; i < parts; i++) {
			line.append(TOKENS.get(random.nextInt(TOKENS.size())));
		}

		return line.toString();
	}

	private static void assertSameScan(List<String> names, List<List<String>> files) {
		OptionIndex.Reader reader = new OptionIndex.Reader();
		Set<String> words = new HashSet<>();
		for (int f = 0; f < files.size(); f++) {
			reader.read(names.get(f), files.get(f));
			for (String line : files.get(f)) {
				for (String word : line.split("[^A-Za-z0-9_]+", -1)) {
					words.add(word);
				}
			}
		}

		OptionIndex actual = reader.index();
		Scan expected = referenceScan(names, files);

		Map<String, PackOption> found = new LinkedHashMap<>();
		actual.all().forEach(option -> found.put(option.name(), option));
		assertEquals(expected.options(), found);
		assertEquals(expected.options().keySet(), actual.names());
		assertEquals(expected.options().size(), actual.count());
		for (String word : words) {
			assertEquals(expected.references().contains(word), actual.referenced(word), "referenced " + word);
		}
		for (String name : expected.references()) {
			assertTrue(actual.referenced(name), "referenced " + name);
		}
	}

	private static Map<String, OptionValue> choices(Random random) {
		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		for (String name : List.of("FOO", "BAR_2", "x", "X", "A", "SPACED", "shadowMapResolution", "shadowDistance",
				"sunPathRotation", "shadowHardwareFiltering", "OPT_1", "OPT_2", "OPT_3", "OPT_7", "OPT_11")) {
			switch (random.nextInt(4)) {
				case 0 -> chosen.put(name, OptionValue.on());
				case 1 -> chosen.put(name, OptionValue.off());
				case 2 -> chosen.put(name, OptionValue.of(Integer.toString(random.nextInt(9000))));
				default -> {
				}
			}
		}

		return chosen;
	}

	private static void assertSameRewrite(List<String> lines, Map<String, OptionValue> chosen, int scale) {
		for (String line : lines) {
			assertEquals(referenceApply(line, chosen, scale), OptionRewriter.apply(line, chosen, scale),
					"line '" + line + "' scale " + scale + " chosen " + chosen);
		}
	}

	// ---- the index ---------------------------------------------------------------------------

	@Test
	void theIndexOfAGeneratedPackIsTheReferenceIndex() {
		Random random = new Random(1);
		List<List<String>> pack = generatedPack(random, 40, 120);
		List<String> names = new ArrayList<>();
		for (int i = 0; i < pack.size(); i++) {
			names.add("f" + i + ".glsl");
		}

		assertSameScan(names, pack);
		assertTrue(referenceScan(names, pack).options().size() > 20, "the generator has to declare something");
	}

	@Test
	void theIndexOfAwkwardLinesIsTheReferenceIndex() {
		assertSameScan(List.of("awkward.glsl"), List.of(AWKWARD));
		for (String line : AWKWARD) {
			assertSameScan(List.of("one.glsl"), List.of(List.of(line)));
		}
	}

	@Test
	void theIndexOfRandomLinesIsTheReferenceIndex() {
		for (long seed : new long[] {11L, 12L, 13L}) {
			Random random = new Random(seed);
			List<List<String>> files = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (int f = 0; f < 20; f++) {
				List<String> lines = new ArrayList<>();
				for (int i = 0; i < 1000; i++) {
					lines.add(randomLine(random));
				}
				files.add(lines);
				names.add("r" + f + ".glsl");
			}

			assertSameScan(names, files);
		}
	}

	@Test
	void everyRandomLineDeclaresTheSameOneAlone() {
		Random random = new Random(21L);
		int declaring = 0;
		int testing = 0;
		for (int i = 0; i < 20_000; i++) {
			String line = randomLine(random);

			assertSameScan(List.of("l.glsl"), List.of(List.of(line)));
			Scan scan = referenceScan(List.of("l.glsl"), List.of(List.of(line)));
			declaring += scan.options().isEmpty() ? 0 : 1;
			testing += scan.references().isEmpty() ? 0 : 1;
		}

		assertTrue(declaring > 3_000, "the random lines have to declare something, " + declaring);
		assertTrue(testing > 200, "and test something, " + testing);
	}

	// ---- the rewrite -------------------------------------------------------------------------

	@Test
	void theRewriteOfAGeneratedPackIsTheReferenceRewrite() {
		Random random = new Random(2);
		List<String> lines = new ArrayList<>();
		generatedPack(random, 20, 200).forEach(lines::addAll);

		for (int scale : new int[] {100, 50, 37, 0, 250}) {
			assertSameRewrite(lines, choices(random), scale);
		}
	}

	@Test
	void theRewriteOfAwkwardLinesIsTheReferenceRewrite() {
		Random random = new Random(3);
		for (int round = 0; round < 30; round++) {
			for (int scale : new int[] {100, 50}) {
				assertSameRewrite(AWKWARD, choices(random), scale);
			}
		}
	}

	@Test
	void theRewriteOfRandomLinesIsTheReferenceRewrite() {
		for (long seed : new long[] {31L, 32L, 33L}) {
			Random random = new Random(seed);
			List<String> lines = new ArrayList<>();
			for (int i = 0; i < 20_000; i++) {
				lines.add(randomLine(random));
			}

			assertSameRewrite(lines, choices(random), 100);
			assertSameRewrite(lines, choices(random), 50);

			Map<String, OptionValue> chosen = choices(random);
			long changed = lines.stream().filter(line -> !referenceApply(line, chosen, 100).equals(line)).count();
			assertTrue(changed > 300, "the random lines have to be rewritten, " + changed);
		}
	}
}
