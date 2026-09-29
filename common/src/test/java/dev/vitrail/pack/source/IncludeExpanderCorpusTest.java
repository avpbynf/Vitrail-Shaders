package dev.vitrail.pack.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Freezes what {@link IncludeExpander} writes for a fixed corpus and for seeded random packs, so that a
 * change to how it reads a line has to leave every unit exactly as it was.
 * <p>
 * A unit is compared by a SHA-256 over everything it carries: its lines, the live bit of every line, its
 * version, its counters and its final define table, and over the loose directives the expander reported.
 * The constants below were taken from the reading as it stood before its per-line dispatch was changed,
 * and they are only ever moved for a change that is meant to move the text. A digest names no line: a
 * mismatch is chased by expanding the pack it names (the position in the list is the seed offset given to
 * {@code randomPack}) under the reading before the change and diffing the two.
 * <p>
 * The random packs are generated to be hostile to a shortcut: every directive in every spacing, most of
 * them inside a branch that is off, block comments opened and closed mid line, constants of the closed
 * list and off it, continuation lines, includes that cycle or are missing, and words that only begin
 * like a directive.
 */
class IncludeExpanderCorpusTest {

	private static final int RANDOM_PACKS = 40;

	/** One digest per random pack, over three settings and both ways the expander is built. */
	private static final List<String> RANDOM_DIGESTS = List.of(
			"1eaaf14f256fe6688f71dea5", "2080cc15a016a0a86857dd0a", "bb7b3f64b6a454fabb936898",
			"d0a5aac3addeffb936e7f476", "b8e74c9702c84e7c3412a810", "9e2ba29f50afa5b2215b44f5",
			"6848cf1cb0dc6fb8049ae9a1", "6e1e63bccd2247b56b0334ca", "3f2b340e2eef666f9e5fa59d",
			"9feb75ae95f83424370e0f6c", "34149005c00fdc9ecb063fae", "77a3ff038beb5f6bb88aa923",
			"45872dd06545c504abf883b5", "24624436f07caab630cf9799", "0aa7315e28f9612cc940618f",
			"723be977e64fce45c63dedf0", "45c31848557cae754d9e7106", "d9a8f3c478a9dd01c294a87d",
			"490361c779e691bbf723c109", "b37e454df887d7b13d6ee565", "4e38e5f9cce7097aa4f616bd",
			"eb3e48994c312a2f1e67e882", "629d86a15e7b38d7c4b4a1bf", "c6ff412fbc9d3bb13e6294fb",
			"3f78ad791c0460caf3c98fc3", "aa96c9e73bc09ac2c7f00aa3", "7b0fbe8ef3790ab1dd97e32b",
			"d51aad8197b0144937d49721", "bd983568b718c3f3b3fd422f", "05475e87cc90f16d564a3660",
			"61b660eead7f5a0d620453ac", "c72f1235a3daf6e17036c82d", "b65f66ca2427fbc8edfa6732",
			"77bd419d48663b7ed36d5684", "ece252888f095f4eb529689b", "2105cad117766b24aaf4ec0f",
			"6fd315d60b6d746b25f35fcb", "6cba26f8036e3de148e6a4e4", "7a3471361d6e42a06cbce22e",
			"51096c28c0cc8dea169ae1b7");

	private static final String KITCHEN_SINK_DIGEST = "82261a3b42d6f5f91a351573";

	@TempDir
	Path temp;

	private int scale;

	@BeforeEach
	void rememberTheShadowMapScale() {
		this.scale = SettingSet.askedShadowMapScale();
	}

	@AfterEach
	void restoreTheShadowMapScale() {
		SettingSet.shadowMapScale(this.scale);
	}

	@Test
	void theKitchenSinkFlattensAsItAlwaysDid() throws IOException {
		assertEquals(KITCHEN_SINK_DIGEST, digestOf(kitchenSink()));
	}

	@Test
	void seededRandomPacksFlattenAsTheyAlwaysDid() throws IOException {
		List<String> digests = new ArrayList<>();
		for (int pack = 0; pack < RANDOM_PACKS; pack++) {
			digests.add(digestOf(randomPack(pack)));
		}

		assertEquals(RANDOM_DIGESTS, digests);
	}

	@Test
	void theRandomPacksReachEveryPathTheCorpusIsThereToGuard() throws IOException {
		long lines = 0;
		long dead = 0;
		long directivesOff = 0;
		long rewritten = 0;
		long loose = 0;
		ExpansionStats total = ExpansionStats.NONE;
		for (int pack = 0; pack < RANDOM_PACKS; pack++) {
			Path packs = Files.createTempDirectory(this.temp, "packs");
			try (ShaderPackSource source = ShaderPackSource.open(Shape.DIRECTORY.build(packs, "pack", randomPack(pack)))) {
				SettingSet set = SettingSet.resolve(Map.of(), chosen(), "chosen");
				IncludeExpander expander = IncludeExpander.forTheReport(source, set);
				for (Path file : source.sourceFiles()) {
					ExpandedUnit unit = expander.expand(file);
					total = total.plus(unit.stats());
					lines += unit.lines().size();
					for (int line = 0; line < unit.lines().size(); line++) {
						String text = unit.lines().get(line);
						if (!unit.isLive(line)) {
							dead++;
							if (text.stripLeading().startsWith("#")) {
								directivesOff++;
							}
						}

						if (text.startsWith("#if 0") || text.startsWith("#if 1") || text.startsWith("// no conditional")
								|| text.startsWith("// include not taken") || text.startsWith("// error not written")
								|| text.contains("shadowMapResolution = 1024")) {
							rewritten++;
						}
					}
				}

				loose += expander.looseDirectives().size();
			}
		}

		// Measured at 118 899 lines, 60 680 of them dead. The floors are far under that, and are there so that
		// a change to the generator cannot quietly turn this into a corpus of plain text.
		assertTrue(lines > 100_000, "lines " + lines);
		assertTrue(dead > 40_000, "dead lines " + dead);
		assertTrue(directivesOff > 8_000, "directives on dead lines " + directivesOff);
		assertTrue(rewritten > 6_000, "rewritten lines " + rewritten);
		assertTrue(loose > 150, "loose directives " + loose);
		assertTrue(total.conditionals() > 6_000, "conditionals " + total.conditionals());
		assertTrue(total.skipped() > 4_000, "includes not taken " + total.skipped());
		assertTrue(total.followed() > 1_500, "includes followed " + total.followed());
		assertTrue(total.missing() > 1_000, "missing includes " + total.missing());
		assertTrue(total.cycles() > 1_000, "cycles " + total.cycles());
		assertTrue(total.undecidable() > 100, "undecidable conditions " + total.undecidable());
	}

	// --- digest ---------------------------------------------------------------------------------

	private String digestOf(Map<String, String> files) throws IOException {
		Path packs = Files.createTempDirectory(this.temp, "packs");
		Path packPath = Shape.DIRECTORY.build(packs, "pack", files);
		MessageDigest digest = sha256();

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			List<SettingSet> settings = new ArrayList<>();
			SettingSet.shadowMapScale(100);
			settings.add(SettingSet.defaults());
			settings.add(SettingSet.resolve(Map.of(), chosen(), "chosen"));
			SettingSet.shadowMapScale(50);
			settings.add(SettingSet.resolve(Map.of(), chosen(), "scaled"));
			SettingSet.shadowMapScale(100);

			for (SettingSet set : settings) {
				IncludeExpander loaded = new IncludeExpander(source, set);
				IncludeExpander reported = IncludeExpander.forTheReport(source, set);
				for (Path file : source.sourceFiles()) {
					ExpandedUnit fromLoad = loaded.expand(file);
					ExpandedUnit fromReport = reported.expand(file);
					// The two ways the expander is built are one reading, and are told apart here so that a
					// mismatch names which of them moved.
					assertEquals(describe(fromLoad), describe(fromReport), source.rel(file));
					feed(digest, describe(fromReport));
				}

				feed(digest, String.join("\n", loaded.looseDirectives()));
				assertEquals(loaded.looseDirectives(), reported.looseDirectives());
			}
		}

		return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
	}

	private static Map<String, OptionValue> chosen() {
		Map<String, OptionValue> chosen = new LinkedHashMap<>();
		chosen.put("QUALITY", OptionValue.of("2"));
		chosen.put("FOG", OptionValue.on());
		chosen.put("A", OptionValue.off());
		chosen.put("MODE", OptionValue.of("3"));
		chosen.put("shadowMapResolution", OptionValue.of("1024"));
		chosen.put("sunPathRotation", OptionValue.of("30.0"));
		chosen.put("shadowHardwareFiltering", OptionValue.off());

		return chosen;
	}

	private static String describe(ExpandedUnit unit) {
		StringBuilder text = new StringBuilder();
		text.append(unit.entry()).append('\u0001').append(unit.version()).append('\u0001');
		for (int line = 0; line < unit.lines().size(); line++) {
			text.append(unit.isLive(line) ? '+' : '-').append(unit.lines().get(line)).append('\n');
		}

		text.append('\u0001').append(unit.stats());
		new TreeMap<>(unit.defines()).forEach((name, value) -> text.append('\u0002').append(name).append('=').append(value));

		return text.toString();
	}

	private static void feed(MessageDigest digest, String text) {
		digest.update(text.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// --- corpus ---------------------------------------------------------------------------------

	/** Every shape the expander has a rule for, in files a pack would write them in. */
	private static Map<String, String> kitchenSink() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders/main.fsh", """
				#version 330 core // entry
				#include "/lib/settings.glsl"
				#include "lib/noise.glsl"
				#include <lib/noise.glsl>
				#ifdef FOG
				#include "lib/fog.glsl"
				#else
				// no fog
				#endif
				#if QUALITY >= 2 && !defined(LOW_END)
				#define HIGH 1
				#elif QUALITY == 1
				#define HIGH 0
				#else
				#error quality is not one of the offered values
				#endif
				#if MOTION > 0.0
				vec3 blur = vec3(MOTION);
				#endif
				  #  ifdef   HIGH   // trailing words
				const int shadowMapResolution = 2048; //[512 1024 2048 4096]
				const float sunPathRotation = 0.0; //[0.0 30.0]
				const bool shadowHardwareFiltering = true;
				const int notASetting = 4; //[1 2]
				#endif
				#if
				never
				#endif
				#ifdef
				always
				#endif
				#endif
				#else
				/* a licence block
				#define IN_A_COMMENT 1
				#include "lib/comment.glsl"
				*/
				#ifdef IN_A_COMMENT
				#error the comment defined something
				#endif
				void main() { /* inline */ gl_FragData[0] = vec4(1.0); } // done
				vec3 joined = a + \\
					b;
				#define LONG 1 + \\
					2
				""");
		files.put("shaders/main.vsh", "#version 330\n#include \"lib/noise.glsl\"\n#ifdef GL_VERTEX_SHADER\nvoid main() {}\n#endif\n");
		files.put("shaders/lib/settings.glsl", """
				#ifndef SETTINGS_GLSL
				#define SETTINGS_GLSL
				#define QUALITY 1 //[1 2 3]
				//#define FOG
				#define MOTION 0.5
				#define LOW_END
				#undef LOW_END
				#version 120
				#extension GL_ARB_gpu_shader5 : enable
				#pragma optimize(off)
				#line 20
				#elsewhere is not else
				#iffy business
				#include_next <other>
				#endif
				""");
		files.put("shaders/lib/noise.glsl", "#include \"settings.glsl\"\n#include \"/lib/settings.glsl\"\n#include \"missing.glsl\"\nfloat noise() { return 0.5; }\n");
		files.put("shaders/lib/fog.glsl", "#include \"fog.glsl\"\n#include \"../main.fsh\"\nfloat fog;\n");
		files.put("shaders/lib/comment.glsl", "// included from a comment\n");

		return files;
	}

	// --- seeded random packs --------------------------------------------------------------------

	private static final String[] NAMES = {"QUALITY", "FOG", "A", "B", "MODE", "IS_IRIS", "GL_FRAGMENT_SHADER",
			"shadowMapResolution", "NEVER_DEFINED"};
	private static final String[] TARGETS = {"a.glsl", "/a.glsl", "lib/c.glsl", "/lib/c.glsl", "c.glsl",
			"../a.glsl", "missing.glsl", "main.fsh", "/main.fsh", "lib/d.inc", "b.glsl", "x/../a.glsl"};
	private static final String[] EXPRESSIONS = {"defined(A)", "!defined(B)", "QUALITY == 2", "QUALITY >= 2 && FOG",
			"MODE > 0.5", "A || B", "((", "", "1 / 0", "defined FOG", "MODE % 2 == 1", "NEVER_DEFINED", "0", "1",
			"QUALITY /* c */ > 1 // t"};
	private static final String[] CONSTANTS = {
			"const int shadowMapResolution = 2048; //[512 1024 2048 4096]",
			"  const float sunPathRotation = 0.0; //[0.0 30.0]",
			"const bool shadowHardwareFiltering = true;",
			"const int notOnTheList = 3; //[1 2 3]",
			"const uint shadowMapResolution = 7u;",
			"const vec3 K = vec3(1.0);",
			"\tconst   int   ambientOcclusionLevel   =   1  ;// trailing",
			"constant int notAConst = 1;",
			"const int"};
	private static final String[] ODD = {
			"#elsewhere", "#elseif A", "#iffy", "#include_next <x>", "#includ \"a.glsl\"", "#\tdefine TABBED 1",
			"# define SPACED 2", "#extension GL_ARB_foo : enable", "#pragma once", "#line 4", "#version 450",
			"#version 120 // again", "#error boom", "#error /* opens", "#error", "\t#\tifdef\tA", "#ifdef A B C",
			"#ifdef 3", "#ifdef (A)", "#ifndef", "#if ((A))", "#elif", "#else // trailing", "#endif // trailing",
			"#  endif", "#ifdef A // c", "#ifdef A /* c */", "#ifdef A /* c", "# include \"a.glsl\"",
			"#include \"a.glsl\" trailing", "#include a.glsl", "#include", "#include \"\"", "#define", "#define 3x",
			"#undef", "#undef NEVER", "##", "# ", "#", " ", "", "#ifdef\u00e9", "#else\u00e9", "#include\u00e9 <a.glsl>"};

	private static Map<String, String> randomPack(int pack) {
		Random random = new Random(0x51AB0000L + pack);
		Map<String, String> files = new LinkedHashMap<>();
		String[] names = {"main.fsh", "main.vsh", "a.glsl", "b.glsl", "lib/c.glsl", "lib/d.inc"};
		for (String name : names) {
			StringBuilder text = new StringBuilder();
			int lines = 30 + random.nextInt(90);
			if (name.startsWith("main")) {
				text.append("#version ").append(330 + random.nextInt(3) * 10).append('\n');
			}

			for (int i = 0; i < lines; i++) {
				text.append(line(random)).append(random.nextInt(40) == 0 ? "\r\n" : "\n");
			}

			// Now and then a file that does not end with a newline, and one that ends on a backslash.
			if (random.nextInt(6) == 0) {
				text.setLength(text.length() - 1);
			}

			if (random.nextInt(12) == 0) {
				text.append("last line \\");
			}

			files.put("shaders/" + name, text.toString());
		}

		return files;
	}

	private static String line(Random random) {
		int roll = random.nextInt(100);
		if (roll < 30) {
			return switch (random.nextInt(8)) {
				case 0 -> "vec3 c" + random.nextInt(50) + " = texture2D(colortex0, uv).rgb * " + random.nextInt(9) + ".5;";
				case 1 -> "// " + NAMES[random.nextInt(NAMES.length)] + " is only mentioned here";
				case 2 -> "";
				case 3 -> "\tfloat f = " + NAMES[random.nextInt(NAMES.length)] + " * 2.0; // # not a directive";
				case 4 -> "string with a #hash inside and const word constant";
				case 5 -> "gl_FragData[0] = vec4(a, b, c, 1.0);";
				case 6 -> "  " + "\t".repeat(random.nextInt(3)) + "indented();";
				default -> "uniform sampler2D colortex" + random.nextInt(8) + ";";
			};
		}

		if (roll < 40) {
			return switch (random.nextInt(8)) {
				case 0 -> "/*";
				case 1 -> "*/";
				case 2 -> "/* one line */";
				case 3 -> "code /* opens";
				case 4 -> "closes */ code";
				case 5 -> "// line comment /* not a block";
				case 6 -> " * inside a block, maybe";
				default -> "/**/";
			};
		}

		if (roll < 55) {
			String name = NAMES[random.nextInt(NAMES.length)];
			return switch (random.nextInt(8)) {
				case 0 -> "#define " + name + " " + random.nextInt(4);
				case 1 -> "#define " + name;
				case 2 -> "//#define " + name;
				case 3 -> "#define " + name + " " + random.nextInt(4) + " //[0 1 2 3]";
				case 4 -> "#undef " + name;
				case 5 -> "  #define " + name + " (" + name + " + 1)";
				case 6 -> "#define " + name + " " + random.nextInt(3) + ".5";
				default -> "// #define " + name + " 1";
			};
		}

		if (roll < 70) {
			String name = NAMES[random.nextInt(NAMES.length)];
			String expression = EXPRESSIONS[random.nextInt(EXPRESSIONS.length)];
			return switch (random.nextInt(10)) {
				case 0 -> "#ifdef " + name;
				case 1 -> "#ifndef " + name;
				case 2 -> "#if " + expression;
				case 3 -> "#elif " + expression;
				case 4 -> "#else";
				case 5, 6 -> "#endif";
				case 7 -> "#if " + expression + " && \\\n   " + expression;
				case 8 -> "  #  if   " + expression;
				default -> "#ifdef " + name + " // trailing";
			};
		}

		if (roll < 80) {
			String target = TARGETS[random.nextInt(TARGETS.length)];
			return switch (random.nextInt(5)) {
				case 0 -> "#include \"" + target + "\"";
				case 1 -> "#include <" + target + ">";
				case 2 -> "  #  include \"" + target + "\" // note";
				case 3 -> "#include \"" + target + ">";
				default -> "#include \"" + target + "\"";
			};
		}

		if (roll < 88) {
			return CONSTANTS[random.nextInt(CONSTANTS.length)];
		}

		if (roll < 95) {
			return ODD[random.nextInt(ODD.length)];
		}

		return switch (random.nextInt(4)) {
			case 0 -> "vec3 joined = a + \\\n\tb;";
			case 1 -> "// hides the next line \\\nvec3 hidden = 1.0;";
			case 2 -> "#define LONG 1 + \\\n 2 + \\\n 3";
			default -> "/* comment \\\n still */ code";
		};
	}
}
