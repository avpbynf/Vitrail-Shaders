package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.target.SamplerPlan.Binding;
import dev.vitrail.pack.target.SamplerPlan.Kind;
import dev.vitrail.pack.target.TargetSchedule.Side;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins what every sampler name a program declares is bound to, and which names this backend cannot
 * serve: a name nothing answers for, and a declaration the API cannot express at all. The two cost
 * different things, one black pixel and the whole program, and are told apart.
 */
class SamplerPlanTest {

	@TempDir
	Path temp;

	/**
	 * Five targets, two of them the pack writes only in the deferred stage, and a chain whose halves
	 * are worked out by hand: deferred writes 0:A 3:A, composite writes 0:M 3:M, so composite1 reads
	 * colortex0 and colortex3 on M.
	 */
	private TargetPlan bsl() {
		String head = "#version 330 compatibility\n";
		return SyntheticPack.read(SyntheticPack.write(this.temp, "pack", Map.of(
				"gbuffers_terrain.fsh", head + "/* DRAWBUFFERS:012 */\nvoid main() {\n}\n",
				"deferred.fsh", head + "/* DRAWBUFFERS:03 */\nuniform sampler2D colortex0;\nvoid main() {\n}\n",
				"composite.fsh", head + "/* DRAWBUFFERS:03 */\nuniform sampler2D colortex3;\nvoid main() {\n}\n",
				"composite1.fsh", head + "/* DRAWBUFFERS:0 */\nuniform sampler2D colortex4;\nvoid main() {\n}\n",
				"final.fsh", head + "uniform sampler2D colortex0;\nvoid main() {\n}\n"))).plan();
	}

	// ------------------------------------------------------------------------------------------
	// What a bare name is
	// ------------------------------------------------------------------------------------------

	@Test
	void everyNameTheEngineServesIsClassified() {
		for (String name : List.of("colortex0", "colortex7", "colortex31", "gcolor", "gdepth", "gnormal",
				"composite", "gaux1", "gaux4")) {
			assertEquals(Kind.COLORTEX, SamplerPlan.classify(name), name);
		}

		for (String name : List.of("colorimg0", "colorimg31")) {
			assertEquals(Kind.COLOUR_IMAGE, SamplerPlan.classify(name), name);
		}

		for (String name : List.of("depthtex0", "depthtex1", "depthtex2", "gdepthtex")) {
			assertEquals(Kind.DEPTH, SamplerPlan.classify(name), name);
		}

		for (String name : List.of("shadowtex0", "shadowtex1", "shadow", "watershadow", "shadowtex0HW",
				"shadowtex1HW")) {
			assertEquals(Kind.SHADOW_DEPTH, SamplerPlan.classify(name), name);
		}

		for (String name : List.of("dhDepthTex", "dhDepthTex0", "dhDepthTex1")) {
			assertEquals(Kind.DISTANT_DEPTH, SamplerPlan.classify(name), name);
		}

		for (String name : List.of("shadowcolor", "shadowcolor0", "shadowcolor1", "shadowcolor7")) {
			assertEquals(Kind.SHADOW_COLOUR, SamplerPlan.classify(name), name);
		}

		assertEquals(Kind.NOISE, SamplerPlan.classify("noisetex"));
		assertEquals(Kind.PACK_TEXTURE, SamplerPlan.classify("ofPackTexture_terrain"));
		assertEquals(Kind.CENTER_DEPTH, SamplerPlan.classify("ofCenterDepthSmooth"));
	}

	/**
	 * The names that read like something this engine serves and are not. A colour target stops at
	 * colortex31 and its digits at two, a legacy name stops at gaux4, a shadow colour is one digit
	 * below eight, and nothing else is a name at all.
	 */
	@Test
	void everyNameNothingAnswersForIsUnserved() {
		for (String name : List.of("colortex32", "colortex100", "colortex", "colortexA", "gaux5", "gaux0",
				"colorimg32", "shadowcolor8", "shadowcolor9", "shadowcolor10", "shadowcolora", "lightmap",
				"texture", "gtexture", "tex", "depthtex3", "shadowtex2", "noisetex2", "dhDepthTex2", "")) {
			assertEquals(Kind.UNSERVED, SamplerPlan.classify(name), "'" + name + "'");
		}
	}

	/** The type is asked first, and it decides before the name gets a say. */
	@Test
	void aDeclarationTheBackendCannotExpressIsRefusedWhateverItIsCalled() {
		for (String type : List.of("sampler3D", "isampler3D", "usampler3D", "sampler1D", "sampler1DArray",
				"sampler2DRect", "sampler2DRectShadow", "samplerBuffer", "sampler1DShadow")) {
			assertTrue(SamplerTypes.refused(type), type);
			assertEquals(Kind.UNBINDABLE, SamplerPlan.classify("colortex6", type), type);
		}

		for (String type : List.of("sampler2D", "isampler2D", "usampler2D", "sampler2DShadow",
				"sampler2DArray", "sampler2DArrayShadow", "sampler2DMS", "sampler2DMSArray", "samplerCube",
				"samplerCubeShadow", "samplerCubeArray", "samplerCubeArrayShadow", "image2D", "uniform")) {
			assertFalse(SamplerTypes.refused(type), type);
		}

		// Mellow declares sampler3D colortex6 in a shared include; the name is no evidence.
		assertEquals(Kind.UNBINDABLE, SamplerPlan.classify("colortex6", "sampler3D"));
		assertEquals(Kind.COLORTEX, SamplerPlan.classify("colortex6", "sampler2D"));
		assertEquals(Kind.COLORTEX, SamplerPlan.classify("colortex6", null));
		assertEquals("3D", SamplerTypes.shapeOf("usampler3D"));
		assertEquals("2D", SamplerTypes.shapeOf("sampler2D"));
		assertNull(SamplerTypes.shapeOf("image2D"));
	}

	/**
	 * A name the pack supplies a file for is the pack's texture, and a name an {@code image.}
	 * directive hangs on is that image, in that order of precedence: the image first, the refused type
	 * second and what the pack supplies third.
	 */
	@Test
	void anImageThenARefusedTypeThenASuppliedFileThenTheName() {
		Set<String> images = Set.of("cimg0");

		assertEquals(Kind.CUSTOM_IMAGE, SamplerPlan.classify("cimg0", "sampler3D", Set.of("cimg0"), images));
		assertEquals(Kind.UNBINDABLE, SamplerPlan.classify("colortex3", "sampler3D", Set.of("colortex3"), images));
		assertEquals(Kind.PACK_TEXTURE, SamplerPlan.classify("colortex3", "sampler2D", Set.of("colortex3"), images));
		assertEquals(Kind.COLORTEX, SamplerPlan.classify("colortex3", "sampler2D", Set.of(), images));
	}

	// ------------------------------------------------------------------------------------------
	// The default sampler
	// ------------------------------------------------------------------------------------------

	/**
	 * A name nothing answers for, in a full screen program, and typed as a plain two dimensional
	 * sampler, reads colortex0: the OptiFine rule that the first target is the default texture of a
	 * composite. Every other combination leaves it unserved.
	 */
	@Test
	void onlyAPlainFlatSamplerOfAnUnservedNameTakesTheDefault() {
		Set<String> none = Set.of();
		assertTrue(SamplerPlan.takesDefault("tex", "sampler2D", none, none));
		assertFalse(SamplerPlan.takesDefault("tex", "sampler3D", none, none));
		assertFalse(SamplerPlan.takesDefault("tex", "isampler2D", none, none));
		assertFalse(SamplerPlan.takesDefault("tex", "usampler2D", none, none));
		assertFalse(SamplerPlan.takesDefault("tex", "sampler2DShadow", none, none));
		assertFalse(SamplerPlan.takesDefault("tex", "samplerCube", none, none));
		assertFalse(SamplerPlan.takesDefault("tex", null, none, none), "a name nobody typed takes none");
		assertFalse(SamplerPlan.takesDefault("colortex1", "sampler2D", none, none));
		assertFalse(SamplerPlan.takesDefault("depthtex0", "sampler2D", none, none));
		assertFalse(SamplerPlan.takesDefault("tex", "sampler2D", Set.of("tex"), none), "a file is supplied");
		assertFalse(SamplerPlan.takesDefault("tex", "sampler2D", none, Set.of("tex")), "an image is hung");
	}

	@Test
	void theDefaultStandsOnTheColourTargetUnlessAPictureIsLaidOverIt() {
		assertEquals(Kind.COLORTEX, SamplerPlan.byDefault(Set.of()));
		assertEquals(Kind.COLORTEX, SamplerPlan.byDefault(Set.of("colortex1")));
		assertEquals(Kind.PACK_TEXTURE, SamplerPlan.byDefault(Set.of("colortex0")));
		assertEquals(0, SamplerPlan.DEFAULT_TARGET);
	}

	@Test
	void onlyTheFullScreenFamiliesAreFullScreen() {
		for (String program : List.of("begin", "begin3", "prepare", "prepare_a", "deferred", "deferred4",
				"composite", "composite12", "composite1_a", "final", "world0/composite2")) {
			assertTrue(SamplerPlan.fullscreen(program), program);
		}

		for (String program : List.of("gbuffers_terrain", "shadow", "shadowcomp", "dh_terrain", "setup",
				"nothing")) {
			assertFalse(SamplerPlan.fullscreen(program), program);
		}
	}

	// ------------------------------------------------------------------------------------------
	// A program's bindings
	// ------------------------------------------------------------------------------------------

	private static final List<String> DECLARED = List.of("colortex0", "colortex3", "colortex9",
			"depthtex0", "noisetex", "shadowtex1", "shadowcolor1", "dhDepthTex", "gaux4", "foo", "volume",
			"ofCenterDepthSmooth", "colorimg3");

	private static final Map<String, String> TYPES = Map.ofEntries(
			Map.entry("colortex0", "sampler2D"), Map.entry("colortex3", "sampler2D"),
			Map.entry("colortex9", "sampler2D"), Map.entry("depthtex0", "sampler2D"),
			Map.entry("noisetex", "sampler2D"), Map.entry("shadowtex1", "sampler2D"),
			Map.entry("shadowcolor1", "sampler2D"), Map.entry("dhDepthTex", "sampler2D"),
			Map.entry("gaux4", "sampler2D"), Map.entry("foo", "sampler2D"),
			Map.entry("volume", "sampler3D"), Map.entry("ofCenterDepthSmooth", "sampler2D"),
			Map.entry("colorimg3", "image2D"));

	/**
	 * By hand, for composite1 of the chain in {@link #bsl()}: it reads colortex0 and colortex3 on M
	 * (composite wrote them there), the colour targets of the place are 0 to 4, so colortex9 and
	 * {@code gaux4}, which is colortex7, were never allocated and are unserved; {@code foo} is a flat
	 * sampler nothing answers for in a full screen program and takes colortex0; {@code volume} is a
	 * volume the API cannot express.
	 */
	@Test
	void everyNameOfAProgramGetsABindingAndTheOnesNothingServesAreNamed() {
		TargetPlan plan = bsl();
		SamplerPlan bindings = SamplerPlan.of(DECLARED, TYPES, plan, "composite1");

		assertEquals(DECLARED, bindings.bindings().stream().map(Binding::sampler).toList());
		assertEquals(new Binding("colortex0", Kind.COLORTEX, 0, Side.MAIN, false), bindings.binding("colortex0"));
		assertEquals(new Binding("colortex3", Kind.COLORTEX, 3, Side.MAIN, false), bindings.binding("colortex3"));
		assertEquals(new Binding("colortex9", Kind.UNSERVED, -1, Side.MAIN, false), bindings.binding("colortex9"));
		assertEquals(Kind.DEPTH, bindings.binding("depthtex0").kind());
		assertEquals(Kind.NOISE, bindings.binding("noisetex").kind());
		assertEquals(Kind.SHADOW_DEPTH, bindings.binding("shadowtex1").kind());
		assertEquals(new Binding("shadowcolor1", Kind.SHADOW_COLOUR, 1, Side.MAIN, false),
				bindings.binding("shadowcolor1"));
		assertEquals(Kind.DISTANT_DEPTH, bindings.binding("dhDepthTex").kind());
		assertEquals(new Binding("gaux4", Kind.UNSERVED, -1, Side.MAIN, false), bindings.binding("gaux4"));
		assertEquals(new Binding("foo", Kind.COLORTEX, 0, Side.MAIN, true), bindings.binding("foo"));
		assertEquals(new Binding("volume", Kind.UNBINDABLE, -1, Side.MAIN, false), bindings.binding("volume"));
		assertEquals(Kind.CENTER_DEPTH, bindings.binding("ofCenterDepthSmooth").kind());
		assertEquals(new Binding("colorimg3", Kind.COLOUR_IMAGE, 3, Side.MAIN, false),
				bindings.binding("colorimg3"));

		assertEquals(List.of("colortex9", "gaux4"), bindings.unserved());
		assertEquals(List.of("volume"), bindings.unbindable());
		assertEquals(List.of("foo"), bindings.defaulted());
		assertEquals(List.of("colortex0", "colortex3", "foo"), bindings.byKind().get(Kind.COLORTEX));

		// A name the program never declared is unserved rather than absent.
		assertEquals(Kind.UNSERVED, bindings.binding("nothing").kind());
	}

	/** Which half a program reads follows the schedule: the same name on the other side of a pass. */
	@Test
	void theSideOfATargetFollowsWhereTheProgramStandsInTheChain() {
		TargetPlan plan = bsl();
		List<String> names = List.of("colortex0", "colortex3");
		Map<String, String> flat = Map.of("colortex0", "sampler2D", "colortex3", "sampler2D");

		// deferred reads both before anything turned them over, composite after deferred wrote them.
		assertEquals(Side.MAIN, SamplerPlan.of(names, flat, plan, "deferred").binding("colortex0").side());
		assertEquals(Side.ALT, SamplerPlan.of(names, flat, plan, "composite").binding("colortex0").side());
		assertEquals(Side.ALT, SamplerPlan.of(names, flat, plan, "composite").binding("colortex3").side());
		assertEquals(Side.MAIN, SamplerPlan.of(names, flat, plan, "composite1").binding("colortex3").side());
		assertEquals(Side.ALT, SamplerPlan.of(names, flat, plan, "final").binding("colortex0").side());

		// The chunk pass handed a step reads the halves of that step: the translucent pass is drawn on
		// the snapshot the deferred stage leaves, where deferred has turned colortex0 over once.
		Side after = SamplerPlan.of(names, flat, plan, plan.schedule().stepAfterDeferred("gbuffers_terrain"))
				.binding("colortex0").side();
		assertEquals(Side.ALT, after);
		assertEquals(Side.MAIN, SamplerPlan.of(names, flat, plan, plan.schedule().step("gbuffers_terrain"))
				.binding("colortex0").side());
	}

	/** Geometry has no default sampler, and neither has a caller that knows no types. */
	@Test
	void theDefaultSamplerIsOffForGeometryAndForNamesNobodyTyped() {
		TargetPlan plan = bsl();

		assertEquals(Kind.UNSERVED, SamplerPlan.of(List.of("foo"), Map.of("foo", "sampler2D"), plan,
				"gbuffers_terrain").binding("foo").kind());
		assertEquals(Kind.UNSERVED, SamplerPlan.of(List.of("foo"), plan, "composite1").binding("foo").kind());
		assertEquals(Kind.UNSERVED, SamplerPlan.of(List.of("foo"), Map.of(), plan, "composite1").binding("foo").kind());
		assertEquals(List.of(), SamplerPlan.of(List.of("foo"), Map.of("foo", "sampler2D"), plan,
				"composite1").unserved());
	}

	/**
	 * A file the pack lays over a name for a stage stands until a program of that stage has written the
	 * target, and then is given up. By hand: composite wrote colortex3, so composite1 no longer gets the
	 * pack's picture for it, and deferred, which comes before, does; colortex5 is written by nobody.
	 */
	@Test
	void aSuppliedFileIsGivenUpOnceAProgramOfTheStageHasWrittenTheTarget() {
		TargetPlan plan = bsl();
		List<String> names = List.of("colortex3", "colortex5");
		Map<String, String> flat = Map.of("colortex3", "sampler2D", "colortex5", "sampler2D");
		Set<String> supplied = Set.of("colortex3", "colortex5");

		SamplerPlan later = SamplerPlan.of(names, flat, plan, "composite1", supplied);
		assertEquals(Kind.COLORTEX, later.binding("colortex3").kind());
		assertEquals(Kind.PACK_TEXTURE, later.binding("colortex5").kind());

		SamplerPlan earlier = SamplerPlan.of(names, flat, plan, "deferred", supplied);
		assertEquals(Kind.PACK_TEXTURE, earlier.binding("colortex3").kind());

		// Geometry is handed an empty snapshot, so its override stands for the whole frame.
		SamplerPlan geometry = SamplerPlan.of(names, flat, plan, "gbuffers_terrain", supplied);
		assertEquals(Kind.PACK_TEXTURE, geometry.binding("colortex3").kind());
	}

	/** A picture laid over colortex0 stands where the target would, for the default name too. */
	@Test
	void theDefaultNameFollowsAPictureLaidOverTheFirstTarget() {
		TargetPlan plan = bsl();
		Map<String, String> flat = Map.of("foo", "sampler2D");
		Set<String> supplied = Set.of("colortex0");

		Binding early = SamplerPlan.of(List.of("foo"), flat, plan, "deferred", supplied, supplied).binding("foo");
		assertEquals(new Binding("foo", Kind.PACK_TEXTURE, -1, Side.MAIN, true), early);

		// composite1 comes after composite wrote colortex0, so the picture is given up there.
		Binding late = SamplerPlan.of(List.of("foo"), flat, plan, "composite1", supplied, supplied).binding("foo");
		assertEquals(new Binding("foo", Kind.COLORTEX, 0, Side.MAIN, true), late);
	}

	// ------------------------------------------------------------------------------------------
	// Shadow depth and colour names
	// ------------------------------------------------------------------------------------------

	@Test
	void theShadowNamesAreNumberedTheWayTheFormatNumbersThem() {
		assertEquals(0, SamplerPlan.shadowColour("shadowcolor"));
		assertEquals(0, SamplerPlan.shadowColour("shadowcolor0"));
		assertEquals(7, SamplerPlan.shadowColour("shadowcolor7"));
		assertTrue(SamplerPlan.isShadowColour("shadowcolor"));
		assertTrue(SamplerPlan.isShadowColour("shadowcolor7"));
		assertFalse(SamplerPlan.isShadowColour("shadowcolor8"));
		assertFalse(SamplerPlan.isShadowColour("shadowcolor12"));
		assertFalse(SamplerPlan.isShadowColour("shadowtex0"));
	}

	/**
	 * {@code shadowtex1} is the map without translucents and {@code shadowtex0} never is; the
	 * {@code HW} spelling reads the image its twin does; and {@code shadow} moves to the map without
	 * translucents only where the program also declares {@code watershadow}.
	 */
	@Test
	void theShadowMapWithoutTranslucentsIsTheSecondOneAndShadowMovesWithWatershadow() {
		assertTrue(SamplerPlan.withoutTranslucents("shadowtex1", false));
		assertTrue(SamplerPlan.withoutTranslucents("shadowtex1HW", false));
		assertFalse(SamplerPlan.withoutTranslucents("shadowtex0", false));
		assertFalse(SamplerPlan.withoutTranslucents("shadowtex0HW", true));
		assertFalse(SamplerPlan.withoutTranslucents("shadow", false));
		assertTrue(SamplerPlan.withoutTranslucents("shadow", true));
		assertFalse(SamplerPlan.withoutTranslucents("watershadow", true));

		TargetPlan plan = bsl();
		SamplerPlan plain = SamplerPlan.of(List.of("shadow"), plan, "composite1");
		SamplerPlan water = SamplerPlan.of(List.of("shadow", "watershadow"), plan, "composite1");
		assertFalse(plain.withoutTranslucents("shadow"));
		assertTrue(water.withoutTranslucents("shadow"));
		assertFalse(water.withoutTranslucents("watershadow"));
	}

	@Test
	void theNamesTheTranslationForgesAreRecognisedOnSight() {
		assertEquals("ofPackTexture_colortex3", SamplerPlan.forged("colortex3"));
		assertEquals("colortex3", SamplerPlan.behind("ofPackTexture_colortex3"));
		assertEquals("colortex3", SamplerPlan.behind("colortex3"));
		assertEquals("ofCenterDepthSmooth", SamplerPlan.centerDepth());
		assertEquals(Kind.PACK_TEXTURE, SamplerPlan.classify(SamplerPlan.forged("noisetex")));
		assertEquals(Kind.PACK_TEXTURE, SamplerPlan.classify(SamplerPlan.forged("anything")));
	}

	@Test
	void theDepthCopiesAreTheTwoLaterNames() {
		assertTrue(SamplerPlan.depthCopy("depthtex1"));
		assertTrue(SamplerPlan.depthCopy("depthtex2"));
		assertFalse(SamplerPlan.depthCopy("depthtex0"));
		assertTrue(SamplerPlan.preHandCopy("depthtex2"));
		assertFalse(SamplerPlan.preHandCopy("depthtex1"));
		assertTrue(SamplerPlan.distantWithoutWater("dhDepthTex1"));
		assertFalse(SamplerPlan.distantWithoutWater("dhDepthTex0"));
	}
}
