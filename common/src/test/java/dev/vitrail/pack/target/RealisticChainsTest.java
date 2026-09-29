package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.TargetSchedule.Side;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Chains shaped like the ones real packs ship, written out as files and read through the same entry
 * points the game uses, with every expected side worked out on paper from the sentence the frame
 * documents and never copied from what the code printed.
 * <p>
 * The three of them are the three shapes the corpus has: a gbuffers, deferred and composite chain
 * with a temporal history buffer (BSL), one that also opens with begin and prepare stages and takes
 * the ping pong into its own hands with {@code flip} directives (Complementary), and one with no
 * final and no draw buffer directive anywhere, which reads the screen under a name of its own (I Like
 * Vanilla). The others are the levers a pack pulls on the walk: turning a pass off, and the pass
 * filter the user pulls.
 */
class RealisticChainsTest {

	@TempDir
	Path temp;

	private static Map<String, String> pack(String... namesAndTexts) {
		Map<String, String> files = new LinkedHashMap<>();
		for (int at = 0; at < namesAndTexts.length; at += 2) {
			files.put(namesAndTexts[at], namesAndTexts[at + 1]);
		}

		return files;
	}

	private SyntheticPack.Read read(Map<String, String> files) {
		return SyntheticPack.read(SyntheticPack.write(this.temp, "pack", files));
	}

	/** {@code 0:ALT,3:MAIN}: the attachments of a pass, in draw buffer order. */
	private static String written(ChainPlan.Pass pass) {
		return pass.attachments().stream()
				.map(attachment -> attachment.target() + ":" + attachment.side())
				.collect(Collectors.joining(","));
	}

	/** The half each of these targets is read from by one program. */
	private static String reads(TargetPlan plan, String program, int... targets) {
		TargetSchedule.Bound step = plan.schedule().step(program).orElseThrow();
		StringBuilder text = new StringBuilder();
		for (int target : targets) {
			text.append(text.isEmpty() ? "" : ",").append(target).append(':').append(step.read(target));
		}

		return text.toString();
	}

	private static ChainPlan.Pass passNamed(ChainPlan chain, String program) {
		return chain.passes().stream().filter(pass -> pass.program().equals(program)).findFirst()
				.orElseThrow();
	}

	private static Set<Integer> set(Integer... indices) {
		return new TreeSet<>(Arrays.asList(indices));
	}

	private static Set<String> names(String... programs) {
		return new TreeSet<>(Arrays.asList(programs));
	}

	// ------------------------------------------------------------------------------------------
	// A gbuffers, deferred and composite chain with a temporal history: BSL
	// ------------------------------------------------------------------------------------------

	private static final String FORMATS = """
			/*
			const int colortex0Format = R11F_G11F_B10F;
			const int colortex1Format = RGB8;
			const int colortex2Format = RGBA16;
			const int colortex3Format = RGB16F;
			const int colortex4Format = RGBA16F;
			const bool colortex4Clear = false;
			*/
			""";

	private static String program(String drawBuffers, String... samplers) {
		StringBuilder text = new StringBuilder("#version 330 compatibility\n");
		text.append("#include \"/lib/format.glsl\"\n");
		if (!drawBuffers.isEmpty()) {
			text.append("/* DRAWBUFFERS:").append(drawBuffers).append(" */\n");
		}

		for (String sampler : samplers) {
			text.append("uniform sampler2D ").append(sampler).append(";\n");
		}

		return text.append("void main() {\n\tgl_FragData[0] = vec4(1.0);\n}\n").toString();
	}

	private static Map<String, String> bsl() {
		return pack(
				"lib/format.glsl", FORMATS,
				"gbuffers_basic.fsh", program("01"),
				"gbuffers_terrain.fsh", program("012"),
				"gbuffers_water.fsh", program("012"),
				"gbuffers_hand.fsh", program("012"),
				"deferred.fsh", program("03", "colortex0", "colortex1", "colortex2"),
				"composite.fsh", program("03", "colortex0", "colortex1", "colortex3"),
				"composite1.fsh", program("0", "colortex0", "colortex3"),
				"composite2.fsh", program("0", "colortex0"),
				"composite3.fsh", program("04", "colortex0", "colortex4"),
				"composite4.fsh", program("0", "colortex0"),
				"final.fsh", program("", "colortex0"));
	}

	/**
	 * The sides of every pass, worked out by hand. M is the primary half and A the alternate; every
	 * target starts on M, a full screen pass writes the half it does not read, and a read lands on the
	 * half of the last write before it.
	 * <pre>
	 * deferred     writes 0:A 3:A
	 * composite    writes 0:M 3:M     (reads 0:A 1:M 3:A)
	 * composite1   writes 0:A         (reads 0:M 3:M)
	 * composite2   writes 0:M         (reads 0:A)
	 * composite3   writes 0:A 4:A     (reads 0:M 4:M, colortex4 being the history nothing wrote yet)
	 * composite4   writes 0:M         (reads 0:A)
	 * final        reads 0:M 3:M 4:A
	 * </pre>
	 */
	@Test
	void aGbuffersDeferredCompositeChainWithAHistoryBuffer() {
		SyntheticPack.Read read = read(bsl());
		TargetPlan plan = read.plan();
		ChainPlan chain = read.chain();

		assertEquals(List.of("deferred", "composite", "composite1", "composite2", "composite3",
				"composite4", "final"), plan.running());
		assertEquals(List.of(), chain.refusals());

		assertEquals("0:ALT,3:ALT", written(passNamed(chain, "deferred")));
		assertEquals("0:MAIN,3:MAIN", written(passNamed(chain, "composite")));
		assertEquals("0:ALT", written(passNamed(chain, "composite1")));
		assertEquals("0:MAIN", written(passNamed(chain, "composite2")));
		assertEquals("0:ALT,4:ALT", written(passNamed(chain, "composite3")));
		assertEquals("0:MAIN", written(passNamed(chain, "composite4")));

		assertEquals("0:ALT,1:MAIN,3:ALT", reads(plan, "composite", 0, 1, 3));
		assertEquals("0:MAIN,3:MAIN", reads(plan, "composite1", 0, 3));
		assertEquals("0:ALT", reads(plan, "composite2", 0));
		assertEquals("0:MAIN,4:MAIN", reads(plan, "composite3", 0, 4));
		assertEquals("0:ALT", reads(plan, "composite4", 0));
		assertEquals("0:MAIN,3:MAIN,4:ALT", reads(plan, "final", 0, 3, 4));

		assertEquals(set(0, 3, 4), plan.schedule().doubled());
		assertEquals(set(4), plan.schedule().flippedAtEnd());
		assertEquals(set(4), plan.persistent());

		// Ending the frame on the alternate half is a fault only for a target kept across frames,
		// and colortex4 is the one: it is what the frame has to bring back.
		assertEquals(List.of(4), chain.swapBack());
		assertEquals(Optional.empty(), chain.present());
		assertTrue(chain.last().isPresent());
	}

	/**
	 * The world's own passes stand on the halves before and after the deferred stage. The opaque
	 * terrain writes 0, 1 and 2 on M. The one deferred pass turned colortex0 over once, so the water
	 * drawn after it writes colortex0 on A, while colortex1 and colortex2 no deferred pass wrote and
	 * stay on M. The scene seed is painted where the terrain writes its first target, on M.
	 */
	@Test
	void theWorldIsDrawnOnTheHalvesBeforeAndAfterTheDeferredStage() {
		SyntheticPack.Read read = read(bsl());
		ChainPlan chain = read.chain();

		assertEquals("0:MAIN,1:MAIN,2:MAIN", written(chain.geometry(TerrainPass.SOLID).orElseThrow()));
		assertEquals("0:ALT,1:MAIN,2:MAIN", written(chain.geometry(TerrainPass.TRANSLUCENT).orElseThrow()));

		ChainPlan.Seed seed = chain.seed().orElseThrow();
		assertEquals(0, seed.target());
		assertEquals(Side.MAIN, seed.side());
		assertEquals("gbuffers_terrain", seed.from());
		assertEquals(0, seed.at());
		assertEquals(0, read.plan().geometryAt());

		assertEquals(0, chain.beginEnd());
		assertEquals(0, chain.prepareEnd());
		assertEquals(1, chain.deferredEnd());
	}

	/**
	 * Formats, promotions and sizes of that pack, and what they cost, by hand. Two of the five are
	 * promoted from three components to four, and only those two say so.
	 * <pre>
	 * colortex0  R11F_G11F_B10F  4 bytes, doubled
	 * colortex1  RGB8            promoted to RGBA8, 4 bytes, not doubled
	 * colortex2  RGBA16          8 bytes, not doubled
	 * colortex3  RGB16F          promoted to RGBA16F, 8 bytes, doubled
	 * colortex4  RGBA16F         8 bytes, doubled
	 * </pre>
	 * At 1920 by 1080 that is 2073600 pixels times (8 + 4 + 8 + 16 + 16) bytes.
	 */
	@Test
	void theFormatsAndWhatTheyCostAtFullHd() {
		TargetPlan plan = read(bsl()).plan();

		assertEquals(List.of(0, 1, 2, 3, 4), plan.ordered());
		assertEquals("RG11B10_FLOAT", plan.directives().format(0).used().name());
		assertEquals("RGBA8_UNORM", plan.directives().format(1).used().name());
		assertEquals("RGBA16_UNORM", plan.directives().format(2).used().name());
		assertEquals("RGBA16_FLOAT", plan.directives().format(3).used().name());
		assertEquals("RGBA16_FLOAT", plan.directives().format(4).used().name());

		assertEquals(2_073_600L * (8 + 4 + 8 + 16 + 16),
				plan.bytesAt(1920, 1080, plan.schedule().doubled()));
		assertEquals(107_827_200L, plan.bytesAt(1920, 1080, plan.schedule().doubled()));
	}

	/**
	 * What the chain says of the picture. composite3 reads colortex4 before anything has written the
	 * half it reads, and colortex4 is a target the pack never clears: that is the pack's own history
	 * and not a fault of the engine, so it is filed with the facts and not with the faults. Every
	 * other read of the chain lands on a half something has already filled.
	 */
	@Test
	void aReadOfTheHistoryBufferIsAFactAndNotAFault() {
		ChainPlan chain = read(bsl()).chain();

		assertEquals(List.of("nothing writes the half of colortex4 that composite3 reads, and the pack "
				+ "keeps that target between frames, so composite3 reads the frame before, and the clear "
				+ "colour on the first one"), chain.history());
		assertEquals(List.of(), chain.notes());
	}

	// ------------------------------------------------------------------------------------------
	// Begin and prepare stages, and the ping pong taken into the pack's own hands: Complementary
	// ------------------------------------------------------------------------------------------

	private static Map<String, String> complementary() {
		String formats = """
				/*
				const bool colortex5Clear = false;
				const bool colortex7Clear = false;
				*/
				""";
		return pack(
				"shaders.properties", """
						flip.deferred_pre.colortex5=true
						flip.composite_pre.colortex4=true
						flip.composite3.colortex7=false
						""",
				"lib/format.glsl", formats,
				"begin.fsh", program("6", "colortex6"),
				"prepare.fsh", program("5", "colortex6"),
				"gbuffers_terrain.fsh", program("012"),
				"gbuffers_water.fsh", program("012"),
				"deferred.fsh", program("05", "colortex0", "colortex1", "colortex5"),
				"deferred1.fsh", program("0", "colortex0"),
				"composite.fsh", program("0", "colortex0", "colortex1"),
				"composite1.fsh", program("0", "colortex0", "colortex4"),
				"composite2.fsh", program("06", "colortex0"),
				"composite3.fsh", program("07", "colortex0", "colortex7"),
				"composite4.fsh", program("0", "colortex0"),
				"final.fsh", program("", "colortex0", "colortex6"));
	}

	/**
	 * The walk by hand, with the pack's three lines. The latest half of a target is where its last
	 * write went, primary until something writes it:
	 * <pre>
	 * begin        reads 6:M          writes 6:A
	 * prepare      reads 6:A          writes 5:A
	 * -- deferred opens, and flip.deferred_pre.colortex5 turns colortex5 over: A becomes M
	 * deferred     reads 0:M 1:M 5:M  writes 0:A 5:A
	 * deferred1    reads 0:A          writes 0:M
	 * -- the translucents are drawn here, on the halves 0:M 5:A 6:A
	 * -- composite opens, and flip.composite_pre.colortex4 turns colortex4 over: M becomes A
	 * composite    reads 0:M 1:M      writes 0:A
	 * composite1   reads 0:A 4:A      writes 0:M
	 * composite2   reads 0:M          writes 0:A 6:M
	 * composite3   reads 0:A 7:M      writes 0:M 7:A, and flip.composite3.colortex7=false keeps 7 on M
	 * composite4   reads 0:M          writes 0:A
	 * final        reads 0:A 6:M
	 * </pre>
	 * The frame ends with 0, 4 and 5 on the alternate half.
	 */
	@Test
	void beginPrepareAndTheLinesOfAPackThatTurnsTargetsOverItself() {
		SyntheticPack.Read read = read(complementary());
		TargetPlan plan = read.plan();
		ChainPlan chain = read.chain();

		assertEquals(List.of("begin", "prepare", "deferred", "deferred1", "composite", "composite1",
				"composite2", "composite3", "composite4", "final"), plan.running());
		assertEquals(List.of(), chain.refusals());

		assertEquals("6:ALT", written(passNamed(chain, "begin")));
		assertEquals("5:ALT", written(passNamed(chain, "prepare")));
		assertEquals("0:ALT,5:ALT", written(passNamed(chain, "deferred")));
		assertEquals("0:MAIN", written(passNamed(chain, "deferred1")));
		assertEquals("0:ALT", written(passNamed(chain, "composite")));
		assertEquals("0:MAIN", written(passNamed(chain, "composite1")));
		assertEquals("0:ALT,6:MAIN", written(passNamed(chain, "composite2")));
		assertEquals("0:MAIN,7:ALT", written(passNamed(chain, "composite3")));
		assertEquals("0:ALT", written(passNamed(chain, "composite4")));

		assertEquals("6:MAIN", reads(plan, "begin", 6));
		assertEquals("6:ALT", reads(plan, "prepare", 6));
		assertEquals("0:MAIN,1:MAIN,5:MAIN", reads(plan, "deferred", 0, 1, 5));
		assertEquals("0:ALT", reads(plan, "deferred1", 0));
		assertEquals("0:MAIN,1:MAIN", reads(plan, "composite", 0, 1));
		assertEquals("0:ALT,4:ALT", reads(plan, "composite1", 0, 4));
		assertEquals("0:MAIN", reads(plan, "composite2", 0));
		assertEquals("0:ALT,7:MAIN", reads(plan, "composite3", 0, 7));
		assertEquals("0:MAIN", reads(plan, "composite4", 0));
		assertEquals("0:ALT,6:MAIN", reads(plan, "final", 0, 6));

		assertEquals(set(0, 4, 5, 6, 7), plan.schedule().doubled());
		assertEquals(set(0, 4, 5), plan.schedule().flippedAtEnd());
		assertEquals(set(5, 7), plan.persistent());

		// The frame ends with colortex5 kept between frames and on the alternate half: that one is
		// brought back. colortex0 and colortex4 end there too and nothing keeps them.
		assertEquals(List.of(5), chain.swapBack());
	}

	@Test
	void theStagesAreCutWhereTheirPassesEnd() {
		SyntheticPack.Read read = read(complementary());
		ChainPlan chain = read.chain();

		assertEquals(1, chain.beginEnd());
		assertEquals(2, chain.prepareEnd());
		assertEquals(4, chain.deferredEnd());
		assertEquals(2, read.plan().geometryAt());
		assertEquals(9, chain.passes().size());
	}

	/**
	 * The two flips the pack wrote at the opening of stages are what the translucents stand on. Both
	 * deferred passes wrote colortex0, so the water is back on M; colortex5 was turned over at the
	 * opening of the stage after prepare wrote it on A, so the snapshot has it on A after deferred
	 * wrote it there; and composite_pre has not been played when the snapshot is taken.
	 */
	@Test
	void theWaterIsDrawnOnTheSnapshotTheDeferredStageLeaves() {
		SyntheticPack.Read read = read(complementary());
		ChainPlan chain = read.chain();

		assertEquals("0:MAIN,1:MAIN,2:MAIN", written(chain.geometry(TerrainPass.SOLID).orElseThrow()));
		assertEquals("0:MAIN,1:MAIN,2:MAIN", written(chain.geometry(TerrainPass.TRANSLUCENT).orElseThrow()));

		TargetSchedule.Bound water = read.plan().schedule().stepAfterDeferred("gbuffers_water")
				.orElseThrow();
		assertEquals(Side.ALT, water.read(5));
		assertEquals(Side.ALT, water.read(6));
		assertEquals(Side.MAIN, water.read(4));
		assertEquals(Side.MAIN, water.read(0));

		assertEquals(2, chain.seed().orElseThrow().at());
	}

	/**
	 * Each of the four reads that land on a half nothing has filled, said in the pack's own terms.
	 * <ul>
	 * <li>begin reads colortex6 on M, which composite2 writes later in the frame, so it reads the
	 * clear;
	 * <li>composite1 reads colortex4, which no program writes and which the flip only turns over;
	 * <li>deferred reads colortex5 on M after the flip, where prepare wrote A and nothing writes M:
	 * a kept target, so the frame before;
	 * <li>composite3 reads colortex7 on M, where its own write of A is not: the history.
	 * </ul>
	 * The two of a kept target go to the facts, the other two to the faults.
	 */
	@Test
	void theReadsOfHalvesNothingHasFilledAreNamed() {
		ChainPlan chain = read(complementary()).chain();

		assertEquals(List.of(
				"colortex6 is not written until composite2, later in the same frame, so begin reads what "
						+ "the clear left there",
				"colortex4 is written by no program of this place, so it holds its clear colour for ever, "
						+ "and composite1 reads that"), chain.notes());
		assertEquals(List.of(
				"nothing writes the half of colortex5 that deferred reads, and the pack keeps that target "
						+ "between frames, so deferred reads the frame before, and the clear colour on the "
						+ "first one",
				"nothing writes the half of colortex7 that composite3 reads, and the pack keeps that target "
						+ "between frames, so composite3 reads the frame before, and the clear colour on the "
						+ "first one"), chain.history());
	}

	// ------------------------------------------------------------------------------------------
	// No final, and the screen read under a name of the pack's own: I Like Vanilla
	// ------------------------------------------------------------------------------------------

	private static Map<String, String> vanilla(int composites) {
		Map<String, String> files = new LinkedHashMap<>();
		for (int at = 0; at < composites; at++) {
			files.put(at == 0 ? "composite.fsh" : "composite" + at + ".fsh", """
					#version 330 compatibility
					uniform sampler2D tex;
					void main() {
						gl_FragData[0] = texture2D(tex, vec2(0.5));
					}
					""");
		}

		return files;
	}

	/**
	 * Four composites, none naming a draw buffer or colortex0: every one writes colortex0 by the
	 * inference, reads it under the name {@code tex}, and the target is allocated on the strength of
	 * that default alone. By hand: 0:A, 0:M, 0:A, 0:M, so an even count leaves colortex0 on the
	 * primary half, which is what the screen is brought from where the pack ships no final.
	 */
	@Test
	void aPackWithNoFinalBringsTheFirstTargetToTheScreenFromTheHalfTheChainEndsOn() {
		SyntheticPack.Read read = read(vanilla(4));
		TargetPlan plan = read.plan();
		ChainPlan chain = read.chain();

		assertEquals(List.of("composite", "composite1", "composite2", "composite3"), plan.running());
		assertEquals(names("composite", "composite1", "composite2", "composite3"), plan.inferredWrites());
		assertEquals(set(0), plan.allocated());
		assertEquals(set(0), plan.sampled());
		assertEquals(Set.of(), plan.samples("composite"));

		assertEquals("0:ALT", written(passNamed(chain, "composite")));
		assertEquals("0:MAIN", written(passNamed(chain, "composite1")));
		assertEquals("0:ALT", written(passNamed(chain, "composite2")));
		assertEquals("0:MAIN", written(passNamed(chain, "composite3")));

		assertEquals(Optional.empty(), chain.last());
		assertEquals(Optional.of(new ChainPlan.Attachment(0, Side.MAIN)), chain.present());
		assertEquals(List.of(), chain.swapBack());
		assertEquals(set(), plan.schedule().flippedAtEnd());
	}

	/** Three composites: 0:A, 0:M, 0:A, and the screen is brought from the alternate half. */
	@Test
	void anOddChainWithNoFinalEndsOnTheAlternateHalf() {
		ChainPlan chain = read(vanilla(3)).chain();

		assertEquals(Optional.of(new ChainPlan.Attachment(0, Side.ALT)), chain.present());
		assertEquals(List.of("this place runs no final, so colortex0 is brought to the screen as it "
				+ "stands, which is what Iris does"),
				chain.notes().stream().filter(note -> note.contains("no final")).toList());
	}

	// ------------------------------------------------------------------------------------------
	// Taking a pass out shifts the halves of every pass after it
	// ------------------------------------------------------------------------------------------

	private static Map<String, String> fourOverZero(String properties) {
		return pack(
				"shaders.properties", properties,
				"composite.fsh", program("0", "colortex0"),
				"composite1.fsh", program("0", "colortex0"),
				"composite2.fsh", program("0", "colortex0"),
				"composite3.fsh", program("0", "colortex0"),
				"final.fsh", program("", "colortex0"));
	}

	/**
	 * Four composites over colortex0 and a final. Whole: r M w A, r A w M, r M w A, r A w M, and the
	 * final reads M. With composite2 off the pack has three: r M w A, r A w M, r M w A, and the final
	 * reads A. Nothing was trimmed after the fact: composite3 reads M where it read A.
	 */
	@Test
	void aPassThePackSwitchesOffMovesEveryReadAfterIt() {
		TargetPlan whole = read(fourOverZero("")).plan();
		assertEquals("0:MAIN", reads(whole, "composite", 0));
		assertEquals("0:ALT", reads(whole, "composite1", 0));
		assertEquals("0:MAIN", reads(whole, "composite2", 0));
		assertEquals("0:ALT", reads(whole, "composite3", 0));
		assertEquals("0:MAIN", reads(whole, "final", 0));

		SyntheticPack.Read cut = read(fourOverZero("program.composite2.enabled=false\n"));
		assertEquals(List.of("composite", "composite1", "composite3", "final"), cut.plan().running());
		assertEquals("0:MAIN", reads(cut.plan(), "composite", 0));
		assertEquals("0:ALT", reads(cut.plan(), "composite1", 0));
		assertEquals("0:MAIN", reads(cut.plan(), "composite3", 0));
		assertEquals("0:ALT", reads(cut.plan(), "final", 0));
		assertEquals("0:ALT", written(passNamed(cut.chain(), "composite3")));
		assertEquals(Map.of("composite2", "false"), cut.plan().disabled());
	}

	/**
	 * A pass turned off by one of the pack's own settings says which: the expression stands in the
	 * plan as the pack wrote it, and the option behind it is read off the {@code #define} comment.
	 */
	@Test
	void aPassSwitchedOffBySettingNamesTheSetting() {
		Map<String, String> files = fourOverZero("program.composite3.enabled=MOTION_BLUR\n");
		files.put("lib/settings.glsl", "//#define MOTION_BLUR\n");
		SyntheticPack.Read read = read(files);

		assertEquals(List.of("composite", "composite1", "composite2", "final"), read.plan().running());
		assertEquals(Map.of("composite3", "MOTION_BLUR"), read.plan().disabled());
		assertEquals("0:ALT", reads(read.plan(), "final", 0));
		assertEquals(List.of("programs this place ships and does not run: composite3 (MOTION_BLUR)"),
				read.plan().notes().stream().filter(note -> note.startsWith("programs this place")).toList());
	}

	/**
	 * A pass the engine itself refuses spends its place in the count: {@code passes=3} keeps the first
	 * three the pack keeps, and if the second of them cannot be built the third is still the third.
	 * Renumbering would make {@code passes=6} mean six other programs the moment one of them turned out
	 * unbuildable, and the picture would change for a reason nobody asked for.
	 * <p>
	 * By hand: composite r M w A, composite1 refused, composite2 r A w M, composite3 is the fourth and
	 * cut, and the final reads M.
	 */
	@Test
	void aPassTheEngineRefusesStillSpendsItsPlaceInTheCount() {
		Path pack = SyntheticPack.write(this.temp, "refused", fourOverZero(""));
		ChainFilter filter = new ChainFilter(List.of(), 3).without(List.of("composite1"));
		SyntheticPack.Read read = SyntheticPack.read(pack, "", filter);

		assertEquals(List.of("composite", "composite2", "final"), read.plan().running());
		assertEquals(Map.of("composite1", TargetPlan.UNBINDABLE, "composite3", TargetPlan.LEFT_OUT),
				read.plan().disabled());
		assertEquals("0:MAIN", reads(read.plan(), "composite", 0));
		assertEquals("0:ALT", reads(read.plan(), "composite2", 0));
		assertEquals("0:MAIN", reads(read.plan(), "final", 0));
	}

	/**
	 * The user's filter counts the full screen passes the pack keeps, the final never among them, and
	 * a pass it cuts is left out of the walk rather than trimmed afterwards. {@code passes=3} keeps
	 * composite to composite2, so the final reads what composite2 wrote: A after three turns.
	 */
	@Test
	void theUsersPassFilterRebuildsTheWalkOnWhatItLeaves() {
		Path pack = SyntheticPack.write(this.temp, "filtered", fourOverZero(""));

		SyntheticPack.Read three = SyntheticPack.read(pack, "", new ChainFilter(List.of(), 3));
		assertEquals(List.of("composite", "composite1", "composite2", "final"), three.plan().running());
		assertEquals("0:ALT", reads(three.plan(), "final", 0));
		assertEquals(Map.of("composite3", TargetPlan.LEFT_OUT), three.plan().disabled());

		SyntheticPack.Read none = SyntheticPack.read(pack, "", new ChainFilter(List.of(), 0));
		assertEquals(List.of("final"), none.plan().running());
		assertEquals("0:MAIN", reads(none.plan(), "final", 0));

		SyntheticPack.Read named = SyntheticPack.read(pack, "", ChainFilter.parse("composite1,composite3"));
		assertEquals(List.of("composite1", "composite3", "final"), named.plan().running());
		assertEquals("0:MAIN", reads(named.plan(), "composite1", 0));
		assertEquals("0:ALT", reads(named.plan(), "composite3", 0));
		assertEquals("0:MAIN", reads(named.plan(), "final", 0));
	}
}
