package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.model.TargetSize;
import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.target.TargetDirectives.Colour;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins which colour targets a pack gets, in what format and at what size, from what its text says:
 * the format table and its three kinds of departure (a promotion from three components to four, a
 * replacement of a format the device has no equivalent of, and a name nobody knows), the live
 * declaration that wins, the sizes, the clear colours, and the sparse set of indices that is
 * allocated at all.
 * <p>
 * Every expected value is read off the tables the docs give ({@code docs/internals/render-targets.md}
 * and the class comments of {@link TargetFormat} and {@link TargetDirectives}) and not off a run.
 */
class TargetPlanAllocationTest {

	@TempDir
	Path temp;

	private static Map<String, String> pack(String... namesAndTexts) {
		Map<String, String> files = new LinkedHashMap<>();
		for (int at = 0; at < namesAndTexts.length; at += 2) {
			files.put(namesAndTexts[at], namesAndTexts[at + 1]);
		}

		return files;
	}

	private TargetPlan plan(Map<String, String> files) {
		return SyntheticPack.read(SyntheticPack.write(this.temp, "pack", files)).plan();
	}

	private static Set<Integer> set(Integer... indices) {
		return new TreeSet<>(Arrays.asList(indices));
	}

	// ------------------------------------------------------------------------------------------
	// Formats
	// ------------------------------------------------------------------------------------------

	/**
	 * One composite writing eight targets, each declared in a different format, in the comment block
	 * a format directive has to sit in.
	 * <pre>
	 * colortex0  RGBA16F         exact                       8 bytes
	 * colortex1  RGB8            promoted to RGBA8, alpha    4 bytes
	 * colortex2  RGB565          replaced by RGBA8, alpha    4 bytes
	 * colortex3  RGBA4           replaced by RGBA8           4 bytes
	 * colortex4  RGB9_E5         replaced by RGBA16F, alpha  8 bytes
	 * colortex5  FANCY_FORMAT    unknown, RGBA8              4 bytes
	 * colortex6  R11F_G11F_B10F  exact                       4 bytes
	 * colortex7  r32f            exact, and case is no matter 4 bytes
	 * </pre>
	 */
	private static final String EIGHT_FORMATS = """
			#version 330 compatibility
			/*
			const int colortex0Format = RGBA16F;
			const int colortex1Format = RGB8;
			const int colortex2Format = RGB565;
			const int colortex3Format = RGBA4;
			const int colortex4Format = RGB9_E5;
			const int colortex5Format = FANCY_FORMAT;
			const int colortex6Format = R11F_G11F_B10F;
			const int colortex7Format = r32f;
			*/
			/* RENDERTARGETS: 0,1,2,3,4,5,6,7 */
			void main() {
			}
			""";

	@Test
	void aFormatIsExactPromotedReplacedOrUnknown() {
		TargetDirectives directives = plan(pack("composite.fsh", EIGHT_FORMATS)).directives();

		assertFormat(directives, 0, TargetFormat.RGBA16_FLOAT, TargetFormat.Reason.EXACT, false, 8);
		assertFormat(directives, 1, TargetFormat.RGBA8_UNORM, TargetFormat.Reason.PROMOTED, true, 4);
		assertFormat(directives, 2, TargetFormat.RGBA8_UNORM, TargetFormat.Reason.REPLACED, true, 4);
		assertFormat(directives, 3, TargetFormat.RGBA8_UNORM, TargetFormat.Reason.REPLACED, false, 4);
		assertFormat(directives, 4, TargetFormat.RGBA16_FLOAT, TargetFormat.Reason.REPLACED, true, 8);
		assertFormat(directives, 5, TargetFormat.RGBA8_UNORM, TargetFormat.Reason.UNKNOWN, false, 4);
		assertFormat(directives, 6, TargetFormat.RG11B10_FLOAT, TargetFormat.Reason.EXACT, false, 4);
		assertFormat(directives, 7, TargetFormat.R32_FLOAT, TargetFormat.Reason.EXACT, false, 4);

		// What the pack wrote is kept as it was written, whatever it was turned into.
		assertEquals("RGB565", directives.format(2).declared());
		assertEquals("r32f", directives.format(7).declared());
	}

	private static void assertFormat(TargetDirectives directives, int index, TargetFormat used,
			TargetFormat.Reason reason, boolean alphaAdded, int bytes) {
		TargetFormat.Resolution format = directives.format(index);
		assertEquals(used, format.used(), "format of colortex" + index);
		assertEquals(reason, format.reason(), "reason for colortex" + index);
		assertEquals(alphaAdded, format.alphaAdded(), "alpha added to colortex" + index);
		assertEquals(bytes, format.used().bytesPerPixel(), "bytes of colortex" + index);
	}

	/**
	 * A departure from what the pack wrote is said, once per target, in the pack's own words and with
	 * the line it came from. An exact format is not.
	 */
	@Test
	void everyDepartureIsNamedWithTheLineItCameFrom() {
		List<String> notes = plan(pack("composite.fsh", EIGHT_FORMATS)).notes();

		assertEquals(List.of(
				"colortex1 asked for RGB8, allocated RGBA8_UNORM (PROMOTED) from composite.fsh:4, "
						+ "clear alpha forced to 1 to match GL",
				"colortex2 asked for RGB565, allocated RGBA8_UNORM (REPLACED) from composite.fsh:5, "
						+ "clear alpha forced to 1 to match GL",
				"colortex3 asked for RGBA4, allocated RGBA8_UNORM (REPLACED) from composite.fsh:6",
				"colortex4 asked for RGB9_E5, allocated RGBA16_FLOAT (REPLACED) from composite.fsh:7, "
						+ "clear alpha forced to 1 to match GL",
				"colortex5 asked for FANCY_FORMAT, allocated RGBA8_UNORM (UNKNOWN) from composite.fsh:8"),
				notes);
	}

	/** A target nothing declares a format for is the default, RGBA8, whichever program writes it. */
	@Test
	void aTargetWithNoDeclarationIsRgba8FromNowhere() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* DRAWBUFFERS:0 */
				void main() {
				}
				"""));

		assertFormat(plan.directives(), 0, TargetFormat.RGBA8_UNORM, TargetFormat.Reason.EXACT, false, 4);
		assertEquals("default", plan.directives().formatSource(0));
		assertEquals(List.of(), plan.notes());
	}

	/**
	 * The line that counts is the last LIVE one, in the order the pack's programs are folded in:
	 * begin, prepare, gbuffers, deferred, composite. So the composite has the last word over the
	 * deferred and the gbuffers, whatever order their files sort in, and two live lines that disagree
	 * are said to.
	 */
	@Test
	void theLastLiveDeclarationInFoldOrderWins() {
		TargetPlan plan = plan(pack(
				"composite.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:0 */
						/*
						const int colortex0Format = RGB16F;
						*/
						void main() {
						}
						""",
				"deferred.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:0 */
						/*
						const int colortex0Format = RGBA32F;
						*/
						void main() {
						}
						""",
				"gbuffers_terrain.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:0 */
						/*
						const int colortex0Format = R8;
						*/
						void main() {
						}
						"""));

		assertEquals("RGB16F", plan.directives().format(0).declared());
		assertEquals(TargetFormat.RGBA16_FLOAT, plan.directives().format(0).used());
		assertEquals("composite.fsh:4", plan.directives().formatSource(0));
		assertEquals(List.of(
				"colortex0Format is R8 at gbuffers_terrain.fsh:4 and RGBA32F at deferred.fsh:4",
				"colortex0Format is RGBA32F at deferred.fsh:4 and RGB16F at composite.fsh:4"),
				plan.directives().conflicts());
		assertEquals(3, plan.directives().formatsSeen());
		assertEquals(3, plan.directives().formatsApplied());
	}

	/**
	 * A declaration behind an {@code #ifdef} nobody takes is seen and not applied, and does not
	 * decide the format. Reading every line would take the format of a branch the pack switched off.
	 */
	@Test
	void aDeclarationBehindADeadBranchDecidesNothing() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* DRAWBUFFERS:12 */
				/*
				const int colortex1Format = RGBA16F;
				*/
				#ifdef NEVER_DEFINED
				/*
				const int colortex1Format = RGBA32F;
				const int colortex2Format = R8;
				*/
				#endif
				void main() {
				}
				"""));

		assertEquals(TargetFormat.RGBA16_FLOAT, plan.directives().format(1).used());
		assertEquals(TargetFormat.RGBA8_UNORM, plan.directives().format(2).used());
		assertEquals(3, plan.directives().formatsSeen());
		assertEquals(1, plan.directives().formatsApplied());
		assertEquals(List.of(), plan.directives().conflicts());
	}

	/**
	 * The grammar takes a type by prefix from a closed list and checks it against the directive: a
	 * format written as a float or a clear written as an int is no directive, and a legacy name
	 * answers for its index, {@code gaux1} being colortex4.
	 */
	@Test
	void aDirectiveOfTheWrongTypeIsIgnoredAndALegacyNameNamesItsIndex() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* RENDERTARGETS: 3,4,5 */
				/*
				const float colortex3Format = RGBA16F;
				const int colortex4Clear = 0;
				const int gaux1Format = RGBA32F;
				const bool gaux2Clear = false;
				*/
				void main() {
				}
				"""));

		assertEquals(TargetFormat.RGBA8_UNORM, plan.directives().format(3).used());
		assertTrue(plan.directives().clears(3));
		assertEquals(TargetFormat.RGBA32_FLOAT, plan.directives().format(4).used());
		assertTrue(plan.directives().clears(4));
		assertFalse(plan.directives().clears(5));
		assertEquals(set(5), plan.persistent());
	}

	// ------------------------------------------------------------------------------------------
	// Clear colours
	// ------------------------------------------------------------------------------------------

	/**
	 * What a target holds before anything writes it. The first is the fog colour of the frame, which
	 * this side cannot know and stands in with opaque black; the second is opaque white and the rest
	 * transparent black, except a target whose format gained an alpha channel, which is opaque black.
	 * A colour the pack names is kept as written, alpha included.
	 */
	@Test
	void theDefaultClearColoursAreTheDocumentedOnes() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* RENDERTARGETS: 0,1,2,3,4,5 */
				/*
				const int colortex2Format = RGB16F;
				const int colortex3Format = RGBA16F;
				const vec4 colortex4ClearColor = vec4(0.5, 0.25, 0.0, 1.0);
				const vec4 colortex5ClearColor = vec4(1.0);
				*/
				void main() {
				}
				"""));

		TargetDirectives directives = plan.directives();
		Colour opaqueBlack = new Colour(0.0F, 0.0F, 0.0F, 1.0F);
		Colour transparentBlack = new Colour(0.0F, 0.0F, 0.0F, 0.0F);

		assertEquals(opaqueBlack, directives.clearColour(0));
		assertFalse(directives.declaresClearColour(0));
		assertEquals(new Colour(1.0F, 1.0F, 1.0F, 1.0F), directives.clearColour(1));
		assertEquals(opaqueBlack, directives.clearColour(2));
		assertEquals(transparentBlack, directives.clearColour(3));
		assertEquals(new Colour(0.5F, 0.25F, 0.0F, 1.0F), directives.clearColour(4));
		assertTrue(directives.declaresClearColour(4));

		// vec4(1.0) is not a four component constructor: the default stands, and the pack is told.
		assertEquals(transparentBlack, directives.clearColour(5));
		assertFalse(directives.declaresClearColour(5));
		assertEquals(List.of("colortex5ClearColor at composite.fsh:7 reads 'vec4(1.0)', which is not a vec4 "
				+ "constructor, ignored"), directives.notes());
	}

	// ------------------------------------------------------------------------------------------
	// Sizes
	// ------------------------------------------------------------------------------------------

	/**
	 * A value with a decimal point is a fraction of the screen and one without is a count of pixels,
	 * and the two axes may not disagree. A setting stands in for a number.
	 */
	@Test
	void aSizeIsAFractionOfTheScreenOrACountOfPixelsAndNeverBoth() {
		TargetPlan plan = plan(pack(
				"shaders.properties", """
						size.buffer.colortex1 = 0.5 0.5
						size.buffer.colortex2 = 960 540
						size.buffer.colortex3 = 0.5 540
						size.buffer.colortex4 = 100000 100000
						size.buffer.colortex5 = REFLECTION_RES REFLECTION_RES
						size.buffer.colortex6 = 1.0 1.0
						size.buffer.gaux4 = 0.25 0.25
						""",
				"lib/settings.glsl", "#define REFLECTION_RES 0.5\n",
				"composite.fsh", """
						#version 330 compatibility
						/* RENDERTARGETS: 0,1,2,3,4,5,6,7 */
						void main() {
						}
						"""));

		TargetDirectives directives = plan.directives();
		assertTrue(directives.size(0).full());
		assertEquals(new TargetSize(true, 0.5F, 0.5F), directives.size(1));
		assertEquals(new TargetSize(false, 960.0F, 540.0F), directives.size(2));
		assertTrue(directives.size(3).full(), "0.5 by 540 is neither and falls back to the screen");
		assertEquals(new TargetSize(false, 100000.0F, 100000.0F), directives.size(4));
		assertEquals(new TargetSize(true, 0.5F, 0.5F), directives.size(5));
		assertTrue(directives.size(6).full());
		assertEquals(new TargetSize(true, 0.25F, 0.25F), directives.size(7));

		assertEquals(960, directives.size(1).width(1920));
		assertEquals(540, directives.size(1).height(1080));
		assertEquals(960, directives.size(2).width(3840));
		assertEquals(TargetSize.MAX_DIMENSION, directives.size(4).width(1920));
		assertTrue(directives.size(4).overCap());
		assertFalse(directives.size(1).overCap());

		assertEquals(List.of(
				"size.buffer.colortex3 reads '0.5 540', which is not two numbers once the settings are "
						+ "applied; full size instead"),
				directives.notes());
		assertTrue(plan.notes().contains(
				"colortex1 is sized by shaders.properties, 0.5 by 0.5 of the screen"));
		assertTrue(plan.notes().contains(
				"colortex2 is sized by shaders.properties, 960 by 540 pixels"));
		assertTrue(plan.notes().contains("colortex4 is sized by shaders.properties, 100000 by 100000 "
				+ "pixels, capped at 16384 texels a side, which is all this engine will allocate"));
		assertTrue(plan.notes().contains(
				"colortex7 is sized by shaders.properties, 0.25 by 0.25 of the screen"));
	}

	/** What a scaled target costs is what it would be at its own size, doubled where it is. */
	@Test
	void aScaledTargetIsPaidForAtItsOwnSize() {
		TargetPlan plan = plan(pack(
				"shaders.properties", "size.buffer.colortex1 = 0.5 0.5\n",
				"composite.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:01 */
						void main() {
						}
						"""));

		// Both 4 bytes a pixel: colortex0 at 1920 by 1080, colortex1 at 960 by 540.
		assertEquals(2_073_600L * 4 + 518_400L * 4, plan.bytesAt(1920, 1080, Set.of()));
		assertEquals(2_073_600L * 4 * 2 + 518_400L * 4, plan.bytesAt(1920, 1080, Set.of(0)));
		assertEquals(2_073_600L * 4 * 2 + 518_400L * 4 * 2, plan.bytesAt(1920, 1080, Set.of(0, 1)));
	}

	// ------------------------------------------------------------------------------------------
	// Mip chains, and what is kept between frames
	// ------------------------------------------------------------------------------------------

	/**
	 * Body Camera turns {@code colortex0MipmapEnabled} on in one line of its composite and off twenty
	 * three lines later, on another branch: a table keyed by target alone reports that pack wrongly.
	 * The request belongs to the program, and the last live line of it stands.
	 */
	@Test
	void aMipChainRequestBelongsToTheProgramAndTheLastLineStands() {
		TargetPlan plan = plan(pack(
				"composite.fsh", """
						#version 330 compatibility
						/* RENDERTARGETS: 0,1 */
						/*
						const bool colortex0MipmapEnabled = true;
						const bool colortex1MipmapEnabled = true;
						const bool colortex0MipmapEnabled = false;
						*/
						void main() {
						}
						""",
				"composite1.fsh", """
						#version 330 compatibility
						/* RENDERTARGETS: 0 */
						/*
						const bool colortex0MipmapEnabled = true;
						*/
						void main() {
						}
						"""));

		assertEquals(Map.of("composite", set(1), "composite1", set(0)), plan.directives().mipmapRequests());
		assertEquals(set(0, 1), plan.directives().mipmapped());
	}

	@Test
	void aTargetTheClearSkipsIsKeptBetweenFrames() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* RENDERTARGETS: 0,3,4 */
				/*
				const bool colortex3Clear = false;
				const bool colortex4Clear = true;
				const bool colortex9Clear = false;
				*/
				void main() {
				}
				"""));

		// colortex9 is declared and never named, so nothing allocates it and nothing keeps it.
		assertEquals(set(3), plan.persistent());
		assertTrue(plan.notes().contains("targets the pack keeps between frames: [3]"));
		assertTrue(plan.directives().declared().contains(9));
	}

	// ------------------------------------------------------------------------------------------
	// Which indices exist at all
	// ------------------------------------------------------------------------------------------

	/**
	 * The set is sparse on purpose, holes and all: a pack writing 0 and 1 and sampling colortex9 and
	 * colortex16 has four targets and not seventeen. A format declared for an index nothing names is
	 * remembered in the notes and allocates nothing.
	 */
	@Test
	void onlyTheIndicesSomeProgramWritesOrSamplesAreAllocated() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/*
				const int colortex12Format = RGBA16F;
				*/
				/* DRAWBUFFERS:01 */
				uniform sampler2D colortex9;
				uniform sampler2D colortex16;
				void main() {
				}
				"""));

		assertEquals(set(0, 1, 9, 16), plan.allocated());
		assertEquals(List.of(0, 1, 9, 16), plan.ordered());
		assertEquals(set(0, 1), plan.written());
		assertEquals(set(9, 16), plan.sampled());
		assertEquals(set(9, 16), plan.samples("composite"));
		assertTrue(plan.notes().contains("targets this pack declares a format for and that no program of "
				+ "this place writes or samples, so nothing is allocated for them: [12]"));
		assertTrue(plan.notes().contains("sampled and never written by this pack, so they read their "
				+ "clear colour: [9, 16]"));
	}

	/**
	 * The note names the indices a pack declares something about and nothing allocates. What it counts is
	 * any directive naming the target, a clear flag as much as a format, and it says a format. Pinned as
	 * it reads today; the disabled test below says what it should say.
	 */
	@Test
	void theNoteAboutUnallocatedDeclarationsCallsAClearFlagAFormat() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* DRAWBUFFERS:0 */
				/*
				const bool colortex9Clear = false;
				*/
				void main() {
				}
				"""));

		assertTrue(plan.notes().contains("targets this pack declares a format for and that no program of "
				+ "this place writes or samples, so nothing is allocated for them: [9]"));
		assertEquals(TargetFormat.RGBA8_UNORM, plan.directives().format(9).used(), "no format was declared");
	}

	@Disabled("TargetPlan.notesFor says 'declares a format for' about every index some directive names, "
			+ "TargetDirectives.declared() being the indices of any of the four directives. A pack that only "
			+ "writes colortex9Clear = false is told it declared a format. The wording should follow the "
			+ "directive, or the note should count formats alone (TargetDirectives.formatsSeen).")
	@Test
	void theNoteAboutUnallocatedDeclarationsShouldNotClaimAFormatThatWasNeverDeclared() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* DRAWBUFFERS:0 */
				/*
				const bool colortex9Clear = false;
				*/
				void main() {
				}
				"""));

		assertFalse(plan.notes().stream()
				.anyMatch(note -> note.contains("declares a format for") && note.endsWith(": [9]")));
	}

	/**
	 * A program declaring no draw buffer is sent to colortex0, as Iris does, and colortex0 is then
	 * allocated for it. A geometry program is inferred too.
	 */
	@Test
	void aProgramDeclaringNoDrawBufferWritesColortex0() {
		TargetPlan plan = plan(pack(
				"composite.fsh", "#version 330 compatibility\nvoid main() {\n}\n",
				"gbuffers_terrain.fsh", "#version 330 compatibility\nvoid main() {\n}\n"));

		assertEquals(List.of(0), plan.writes("composite"));
		assertEquals(List.of(0), plan.writes("gbuffers_terrain"));
		assertEquals(Set.of("composite", "gbuffers_terrain"), plan.inferredWrites());
		assertEquals(set(0), plan.allocated());
		assertTrue(plan.notes().contains("programs declaring no draw buffer, sent to colortex0 as Iris "
				+ "does: [composite, gbuffers_terrain]"));
	}

	/**
	 * When both spellings of the directive appear, the later one wins; the directive has to open a
	 * block comment or it is not one; and DRAWBUFFERS runs its digits together while RENDERTARGETS
	 * separates them and so can name a target above nine.
	 */
	@Test
	void theDrawBufferDirectiveIsReadTheWayTheReferenceReadsIt() {
		TargetPlan plan = plan(pack(
				"composite.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:04 */
						/* RENDERTARGETS: 1,2 */
						void main() {
						}
						""",
				"composite1.fsh", """
						#version 330 compatibility
						/* RENDERTARGETS: 1,2 */
						/* DRAWBUFFERS:04 */
						void main() {
						}
						""",
				"composite2.fsh", """
						#version 330 compatibility
						// DRAWBUFFERS:07
						void main() {
						}
						""",
				"composite3.fsh", """
						#version 330 compatibility
						/* RENDERTARGETS: 3, 11,19 */
						void main() {
						}
						"""));

		assertEquals(List.of(1, 2), plan.writes("composite"));
		assertEquals(List.of(0, 4), plan.writes("composite1"));
		assertEquals(List.of(0), plan.writes("composite2"), "a line comment is not a directive");
		assertEquals(Set.of("composite2"), plan.inferredWrites());
		assertEquals(List.of(3, 11, 19), plan.writes("composite3"));
	}

	/** A directive on a branch nobody takes is not read: the idiom is one directive per branch. */
	@Test
	void aDrawBufferDirectiveOnADeadBranchIsNotRead() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				#ifdef SOMETHING_OFF
				/* DRAWBUFFERS:034 */
				#else
				/* DRAWBUFFERS:05 */
				#endif
				void main() {
				}
				"""));

		assertEquals(List.of(0, 5), plan.writes("composite"));
		assertEquals(set(0, 5), plan.allocated());
	}

	/**
	 * A sampler is found in the text, on the line it is declared on. The forms packs write are seen; a
	 * declaration spread over two lines, one behind a comment and one behind a dead branch are not; a
	 * name defined as another name is resolved; and the type is not looked at, so a volume named like a
	 * target still asks for it.
	 */
	@Test
	void aSamplerIsFoundWhereItIsDeclaredOnOneLive() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 330 compatibility
				/* DRAWBUFFERS:0 */
				uniform sampler2D colortex1;
				uniform highp sampler2D colortex2;
				layout(binding = 3) uniform sampler2D colortex3;
				uniform layout(binding = 4) sampler2D colortex4;
				uniform sampler2D colortex5, colortex6;
				uniform sampler2D colortex7[2];
				uniform sampler2D
				    colortex8;
				// uniform sampler2D colortex10;
				/*
				uniform sampler2D colortex11;
				*/
				#ifdef NEVER_DEFINED
				uniform sampler2D colortex12;
				#endif
				#define ALIAS colortex13
				uniform sampler2D ALIAS;
				uniform sampler3D colortex14;
				uniform sampler2D gaux4;
				uniform sampler2D depthtex0;
				uniform sampler2D noisetex;
				void main() {
				}
				"""));

		assertEquals(set(1, 2, 3, 4, 5, 6, 7, 13, 14), plan.samples("composite"));
		assertEquals(set(0, 1, 2, 3, 4, 5, 6, 7, 13, 14), plan.allocated());
	}

	/**
	 * A shadow composite runs over the shadow targets on a flip counter of its own, so its draw
	 * buffers name shadowcolor and never a colour target of this place; the light's own geometry the
	 * same. Neither allocates a colour target, and neither takes part in the schedule. Their format
	 * declarations still count: a const written there is written for the whole place.
	 */
	@Test
	void theShadowStageAllocatesNoColourTarget() {
		TargetPlan plan = plan(pack(
				"shadowcomp.fsh", """
						#version 330 compatibility
						/*
						const int colortex6Format = RGBA16F;
						*/
						/* RENDERTARGETS: 5 */
						void main() {
						}
						""",
				"shadow.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:1 */
						void main() {
						}
						""",
				"composite.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:6 */
						void main() {
						}
						"""));

		assertEquals(set(6), plan.allocated());
		assertEquals(List.of("composite"), plan.running());
		// The light's own geometry stands in the schedule as a geometry step that writes nothing, and the
		// shadow composite is not in it at all.
		assertEquals(List.of(), plan.schedule().step("shadow").orElseThrow().writes());
		assertEquals(Optional.empty(), plan.schedule().step("shadowcomp"));
		assertEquals(TargetFormat.RGBA16_FLOAT, plan.directives().format(6).used());
		assertEquals(set(1), plan.shadowAllocated());
		assertTrue(plan.notes().stream().anyMatch(note -> note.startsWith("shadow composites skipped")));
	}

	/**
	 * A target only a compute stores into, as a {@code colorimgN}, is allocated on that declaration
	 * alone and is written, not sampled; and it is never turned over, since a compute stores into the
	 * very half it samples.
	 */
	@Test
	void aTargetOnlyAComputeStoresIntoIsAllocatedAndNeverFlipped() {
		TargetPlan plan = plan(pack(
				"composite.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:0 */
						void main() {
						}
						""",
				"composite_a.csh", """
						#version 430 compatibility
						layout(local_size_x = 8, local_size_y = 8) in;
						layout(rgba16f) uniform image2D colorimg5;
						void main() {
						}
						"""));

		assertEquals(set(0, 5), plan.allocated());
		assertEquals(set(0, 5), plan.written());
		assertEquals(set(), plan.sampled());
		assertEquals(set(0), plan.schedule().doubled());
		assertEquals(List.of("composite_a"), plan.computes());

		// The pack ships composite.fsh and no composite.vsh, so the pass is in running() and, its compute
		// hanging off it, in passing() as well: half a source draws nothing. The compute is placed after
		// the step and not in its place, so what it reads is what the pass wrote, colortex0 on A.
		assertEquals(List.of("composite"), plan.passing());
		assertEquals(TargetSchedule.Side.ALT, plan.schedule().passing("composite").orElseThrow().read(0));

		// stored() answers for the fragment stages alone: a compute is recorded as written and as
		// nothing else, so a caller asking what the FRAGMENTS store into does not hear of it.
		assertEquals(set(), plan.stored());
	}

	/**
	 * A fragment stage storing into a colour target as an image is what iterationT does from its line
	 * program. That is a store and is written, allocates the target, and still turns nothing over.
	 */
	@Test
	void aFragmentStageStoringIntoAnImageIsStoredAndWrittenAndNeverFlipped() {
		TargetPlan plan = plan(pack("composite.fsh", """
				#version 430 compatibility
				/* DRAWBUFFERS:0 */
				layout(rgba8) writeonly uniform image2D colorimg3;
				uniform layout(rgba16f) restrict writeonly image2D colorimg4;
				layout(rgba8) readonly uniform image2D colorimg6;
				void main() {
				}
				"""));

		assertEquals(set(3, 4), plan.stored());
		assertEquals(set(0, 3, 4), plan.written());
		assertEquals(set(0, 3, 4), plan.allocated(), "an image only read from names no target");
		assertEquals(set(0), plan.schedule().doubled());
	}

	/**
	 * A stage a place ships only computes for is a moment in the frame and not a pass: its program is
	 * in {@code passing} and in no step, and the halves standing there are what its computes read.
	 */
	@Test
	void aProgramShippedAsAComputeAloneIsAMomentAndNotAPass() {
		TargetPlan plan = plan(pack(
				"composite.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:0 */
						void main() {
						}
						""",
				"composite1_a.csh", """
						#version 430 compatibility
						layout(local_size_x = 8, local_size_y = 8) in;
						void main() {
						}
						""",
				"composite2.fsh", """
						#version 330 compatibility
						/* DRAWBUFFERS:0 */
						void main() {
						}
						"""));

		assertEquals(List.of("composite1"), plan.passing());
		assertEquals(List.of("composite", "composite2"), plan.running());
		assertEquals(List.of("composite", "composite2"),
				plan.schedule().steps().stream().map(TargetSchedule.Bound::program).toList());
		// composite wrote colortex0 on the alternate half, and that is where composite1's computes stand.
		assertEquals(TargetSchedule.Side.ALT, plan.schedule().passing("composite1").orElseThrow().read(0));
		assertEquals(TargetSchedule.Side.ALT, plan.schedule().step("composite2").orElseThrow().read(0));
	}

	/**
	 * A dimension folder replaces the root rather than being layered over it, and what decides is
	 * that the folder exists and never what it holds: a pack shipping an empty {@code world0} draws
	 * nothing in the overworld, and a folder of its own has a plan of its own.
	 */
	@Test
	void aDimensionFolderReplacesTheRootWholeAndAnEmptyOneRunsNothing() {
		Path pack = SyntheticPack.write(this.temp, "dimensions", pack(
				"composite.fsh", "#version 330 compatibility\n/* DRAWBUFFERS:0 */\nvoid main() {\n}\n",
				"world-1/composite.fsh", "#version 330 compatibility\n/* DRAWBUFFERS:3 */\nvoid main() {\n}\n",
				"world-1/composite1.fsh", "#version 330 compatibility\n/* DRAWBUFFERS:3 */\nvoid main() {\n}\n",
				"world0/notes.txt", "nothing\n"));

		TargetPlan root = SyntheticPack.read(pack, "", ChainFilter.ALL).plan();
		TargetPlan nether = SyntheticPack.read(pack, "world-1", ChainFilter.ALL).plan();
		TargetPlan overworld = SyntheticPack.read(pack, "world0", ChainFilter.ALL).plan();
		TargetPlan unnamed = SyntheticPack.read(pack, "world7", ChainFilter.ALL).plan();

		assertEquals(List.of("composite"), root.running());
		assertEquals(set(0), root.allocated());
		assertEquals(List.of("composite", "composite1"), nether.running());
		assertEquals(set(3), nether.allocated());
		assertEquals("world-1", nether.place());
		assertEquals("world0", overworld.place());
		assertEquals(List.of(), overworld.running(), "the folder exists and holds no program");
		assertEquals(set(), overworld.allocated());
		assertEquals("", unnamed.place(), "a folder that does not exist falls back to the root");
		assertEquals(List.of("composite"), unnamed.running());
	}
}
