package dev.vitrail.pack.load;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.SyntheticPacks.Shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Holds the lexical measurements of a pack, which are compared against numbers taken before any of this
 * existed and so have to match those definitions to the letter, the parts that look arbitrary included:
 * an {@code #else} counts as nothing, nesting restarts at every file, and a directive is counted only in
 * the exact spacing the measurements used.
 */
class PackStatsTest {

	@TempDir
	Path temp;

	private PackStats measure(Shape shape, Map<String, String> files) throws IOException {
		Map<String, String> all = new LinkedHashMap<>();
		files.forEach((name, text) -> all.put("shaders/" + name, text));
		Path packPath = shape.build(Files.createTempDirectory(this.temp, "packs"), "pack", all);
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return PackStats.measure(source, source.options());
		}
	}

	private static Map<String, String> corpus() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("composite.fsh", """
				#version 330 core // comment
				#include "/lib/common.glsl"
				#include "local.glsl"
				#ifdef BLOOM
				#include "/lib/bloom.glsl"
				#endif
				#if QUALITY >= 2 && defined(FOG)
				#elif SHADOW_RES > 512
				#endif
				#ifndef GUARD
				#endif
				#else
				""");
		files.put("lib/common.glsl", "#version 330\n#define BLOOM\n#define QUALITY 2 //[1 2 3]\n");
		files.put("lib/bloom.glsl", "// bloom\n");
		files.put("composite.vsh", "#version 330\nvoid main() {}\n");
		files.put("data.json", "#include \"not a source\"\n#ifdef X\n");

		return files;
	}

	@ParameterizedTest
	@EnumSource(Shape.class)
	void countsFilesLinesIncludesConditionalsSymbolsAndGatingSettings(Shape shape) throws IOException {
		PackStats stats = measure(shape, corpus());

		assertEquals(4, stats.files());
		assertEquals(22, stats.lines());
		assertEquals(3, stats.includes());
		assertEquals(1, stats.conditionalIncludes());
		assertEquals(2, stats.absoluteIncludes());
		assertEquals(1, stats.relativeIncludes());
		// #ifdef, #if, #elif and #ifndef: the #else and the #endif are neither.
		assertEquals(4, stats.conditionals());
		assertEquals(5, stats.symbolCount());
		assertEquals(2, stats.options());
		assertEquals(1, stats.binaryGates());
		assertEquals(1, stats.multiValueGates());
		assertEquals(2, stats.gatingOptions());
		assertEquals(3, stats.maxValues());
		assertEquals(Map.of("fsh", 1, "glsl", 2, "vsh", 1), stats.filesByExtension());
		assertEquals(2, stats.stageFiles());
		assertEquals(2, stats.includeFiles());
		assertEquals("330 (2)", stats.majorityVersion());
	}

	@Test
	void writesTheColumnsOfTheReferenceMeasurementsInTheirOrder() throws IOException {
		PackStats stats = measure(Shape.DIRECTORY, corpus());

		assertEquals("pack\tfiles\tincludes\tincl_cond\tincl_abs\tincl_rel\tconditionals\tsymbols\toptions"
				+ "\topt_gating\tbin\tmulti\tmax_values\tversion", PackStats.tsvHeader());
		assertEquals("p\t4\t3\t1\t2\t1\t4\t5\t2\t2\t1\t1\t3\t330 (2)", stats.tsvLine("p"));
	}

	@Test
	void countsADirectiveOnlyInTheSpacingTheMeasurementsUsedAndAnIncludeOnlyBetweenDelimiters() throws IOException {
		PackStats stats = measure(Shape.DIRECTORY, Map.of("a.fsh", """
				#if(A)
				#elif(B)
				   #  ifdef  C
				#  endif
				\t#ifndef D
				#endif // trailing
				#include <x.glsl>
				#include "y.glsl" // note
				# include "z.glsl"
				#include w.glsl
				#version 450 // trailing
				#ifdef
				"""));

		// "#if(A)" and "#elif(B)" have no space after the word and are not counted; the two #ifdef
		// forms are, spaces after the hash included.
		assertEquals(2, stats.conditionals());
		assertEquals(3, stats.includes());
		assertEquals(3, stats.relativeIncludes());
		assertEquals("450 (1)", stats.majorityVersion());
		assertEquals(2, stats.symbolCount());
	}

	@Test
	void anElseIsNeitherAConditionalNorAChangeOfDepth() throws IOException {
		PackStats stats = measure(Shape.DIRECTORY, Map.of("a.fsh",
				"#ifdef A\n#include \"one.glsl\"\n#else\n#include \"two.glsl\"\n#endif\n#include \"three.glsl\"\n"));

		// The include after the #else still stands inside the group, and the one after the #endif does not.
		assertEquals(1, stats.conditionals());
		assertEquals(3, stats.includes());
		assertEquals(2, stats.conditionalIncludes());
	}

	@Test
	void restartsTheNestingAtEveryFileSoAnOpenGroupDoesNotLeak() throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("a.glsl", "#ifdef LEFT_OPEN\n");
		files.put("b.glsl", "#include \"c.glsl\"\n");
		files.put("c.glsl", "#endif\n#endif\n#include \"d.glsl\"\n");

		PackStats stats = measure(Shape.DIRECTORY, files);

		assertEquals(2, stats.includes());
		assertEquals(0, stats.conditionalIncludes());
	}

	@Test
	void aPackWithNothingMeasuresNothing() throws IOException {
		PackStats stats = measure(Shape.DIRECTORY, Map.of("a.txt", "x"));

		assertEquals(0, stats.files());
		assertEquals(0, stats.lines());
		assertEquals("none", stats.majorityVersion());
		assertEquals(Map.of(), stats.filesByExtension());
		assertEquals("p\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\t0\tnone", stats.tsvLine("p"));
	}
}
