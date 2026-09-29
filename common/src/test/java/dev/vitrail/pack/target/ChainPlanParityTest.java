package dev.vitrail.pack.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.program.ChainFilter;
import dev.vitrail.pack.program.TerrainPass;
import dev.vitrail.pack.target.ChainPlan.Attachment;
import dev.vitrail.pack.target.ParityModel.Chain;
import dev.vitrail.pack.target.ParityModel.Expected;
import dev.vitrail.pack.target.ParityModel.Half;
import dev.vitrail.pack.target.ParityModel.Pass;
import dev.vitrail.pack.target.ParityModel.Row;
import dev.vitrail.pack.target.ParityModel.Shape;
import dev.vitrail.pack.target.ParityModel.Stage;
import dev.vitrail.pack.model.TargetSize;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads whole packs, written out as the text files a pack is, through {@link TargetPlan} and
 * {@link ChainPlan}, and holds what comes out to {@link ParityModel}: which passes run, what each
 * writes and on which half, what each reads and from which, what is doubled, what the frame ends
 * on and what is swapped back.
 * <p>
 * The schedule test beside this one proves the walk in isolation. What this adds is everything
 * that stands between a pack's text and the walk: the directives read out of the comments of a
 * fragment stage, the samplers read off the declarations, the switches of shaders.properties, the
 * order the entries are folded in, and the plan of the chain that hands the sides to a render pass.
 * A parity that is right in the walk and wrong on the way to the pass is as silent as any other.
 */
class ChainPlanParityTest {

	private static final Shape SMALL = new Shape(3, 10, 8, true, false);

	@TempDir
	Path temp;

	private void assertPipelineMatches(Chain chain, String label) {
		Path pack = SyntheticPack.write(this.temp, "pack", ParityModel.files(chain));
		ChainFilter filter = chain.limit() < 0 ? ChainFilter.ALL : new ChainFilter(List.of(), chain.limit());
		SyntheticPack.Read read = SyntheticPack.read(pack, "", filter);

		List<Pass> walked = ParityModel.walked(chain);
		Expected expected = ParityModel.interpret(walked, chain.flips());

		assertPlanMatches(read.plan(), chain, walked, expected, label);
		assertChainMatches(read.plan(), read.chain(), walked, expected, label);
	}

	/** What the plan says of the pack, before the chain unfolds it. */
	private static void assertPlanMatches(TargetPlan plan, Chain chain, List<Pass> walked,
			Expected expected, String label) {
		List<String> running = new ArrayList<>(walked.stream()
				.filter(Pass::fullscreen).map(Pass::program).toList());
		assertEquals(running, plan.running(), label + " running");

		Set<String> inferred = new TreeSet<>();
		Set<Integer> written = new TreeSet<>();
		Set<Integer> sampled = new TreeSet<>();
		for (Pass pass : chain.passes()) {
			assertEquals(pass.writes(), plan.writes(pass.program()), label + " writes of " + pass.program());
			assertEquals(pass.reads(), plan.samples(pass.program()), label + " samples of " + pass.program());
			written.addAll(pass.writes());
			sampled.addAll(pass.reads());
			if (pass.inferred()) {
				inferred.add(pass.program());
			}
		}

		assertEquals(inferred, plan.inferredWrites(), label + " inferred");
		assertEquals(written, plan.written(), label + " written");
		assertEquals(sampled, plan.sampled(), label + " sampled");

		Set<Integer> allocated = new TreeSet<>(written);
		allocated.addAll(sampled);
		assertEquals(allocated, plan.allocated(), label + " allocated");
		assertEquals(new ArrayList<>(allocated), plan.ordered(), label + " ordered");

		Set<Integer> persistent = new TreeSet<>(chain.keeps());
		persistent.retainAll(allocated);
		assertEquals(persistent, plan.persistent(), label + " persistent");

		Set<String> cut = new TreeSet<>();
		for (Pass pass : chain.passes()) {
			if (!walked.contains(pass)) {
				cut.add(pass.program());
			}
		}

		assertEquals(cut, plan.disabled().keySet(), label + " disabled");
		for (String program : cut) {
			String why = chain.disabled().contains(program) ? "false" : TargetPlan.LEFT_OUT;
			assertEquals(why, plan.disabled().get(program), label + " why " + program + " is not run");
		}

		TargetScheduleParityTest.assertScheduleMatches(plan.schedule(), expected, label);

		int before = (int) walked.stream()
				.filter(pass -> pass.fullscreen() && pass.stage().compareTo(Stage.PREPARE) <= 0).count();
		assertEquals(before, plan.geometryAt(), label + " where the world is drawn");
	}

	/** What the chain hands a render pass. */
	private static void assertChainMatches(TargetPlan plan, ChainPlan chain, List<Pass> walked,
			Expected expected, String label) {
		assertEquals(List.of(), chain.refusals(), label + " refusals");

		List<ChainPlan.Pass> passes = new ArrayList<>();
		for (Row row : expected.rows()) {
			if (!row.pass().fullscreen() || row.pass().stage() == Stage.FINAL) {
				continue;
			}

			List<Attachment> attachments = new ArrayList<>();
			for (int target : row.pass().writes()) {
				attachments.add(new Attachment(target, row.writes().get(target).side()));
			}

			passes.add(new ChainPlan.Pass(row.pass().program(), attachments, TargetSize.ofScreen(),
					row.pass().inferred()));
		}

		assertEquals(passes, chain.passes(), label + " passes");

		boolean hasFinal = walked.stream().anyMatch(pass -> pass.stage() == Stage.FINAL);
		assertEquals(hasFinal, chain.last().isPresent(), label + " final");
		if (hasFinal) {
			assertEquals(List.of(), chain.last().orElseThrow().attachments(), label + " final writes");
			assertEquals(Optional.empty(), chain.present(), label + " present");
		} else {
			// Nothing to draw the frame into the screen: colortex0 is brought there on the half the
			// last write to it left.
			assertEquals(Optional.of(new Attachment(0, expected.atEnd().of(0).side())),
					chain.present(), label + " present");
		}

		Set<Integer> back = new TreeSet<>(expected.flippedAtEnd());
		back.retainAll(plan.persistent());
		assertEquals(new ArrayList<>(back), chain.swapBack(), label + " swap back");

		assertEquals(count(walked, Stage.BEGIN), chain.beginEnd(), label + " begin end");
		assertEquals(count(walked, Stage.PREPARE), chain.prepareEnd(), label + " prepare end");
		assertEquals(count(walked, Stage.DEFERRED), chain.deferredEnd(), label + " deferred end");

		Map<String, Pass> byName = ParityModel.byProgram(walked);
		Pass terrain = byName.get("gbuffers_terrain");
		Pass water = byName.get("gbuffers_water");

		List<Attachment> solid = new ArrayList<>();
		for (int target : terrain.writes()) {
			solid.add(new Attachment(target, expected.row("gbuffers_terrain").writes().get(target).side()));
		}

		assertEquals(new ChainPlan.Pass("gbuffers_terrain", solid, TargetSize.ofScreen(), terrain.inferred()),
				chain.geometry(TerrainPass.SOLID).orElseThrow(), label + " solid terrain");

		// The translucent pass is drawn after the deferreds, on the snapshot they leave behind.
		List<Attachment> translucent = new ArrayList<>();
		for (int target : water.writes()) {
			Half half = expected.afterDeferred().of(target);
			translucent.add(new Attachment(target, half.side()));
		}

		assertEquals(new ChainPlan.Pass("gbuffers_water", translucent, TargetSize.ofScreen(), water.inferred()),
				chain.geometry(TerrainPass.TRANSLUCENT).orElseThrow(), label + " translucent terrain");

		ChainPlan.Seed seed = chain.seed().orElseThrow();
		assertEquals(terrain.writes().get(0), seed.target(), label + " seed target");
		assertEquals(expected.row("gbuffers_terrain").writes().get(terrain.writes().get(0)).side(),
				seed.side(), label + " seed side");
		assertEquals("gbuffers_terrain", seed.from(), label + " seed from");
		assertEquals(plan.geometryAt(), seed.at(), label + " seed at");
	}

	private static int count(List<Pass> walked, Stage through) {
		return (int) walked.stream()
				.filter(pass -> pass.fullscreen() && pass.stage() != Stage.FINAL)
				.filter(pass -> pass.stage().compareTo(through) <= 0)
				.count();
	}

	

	@Test
	void thePipelineAgreesWithTheReferenceOnSmallChains() {
		for (long seed = 100_000; seed < 100_900; seed++) {
			assertPipelineMatches(ParityModel.random(seed, SMALL), "seed " + seed);
		}
	}

	/** Some passes switched off by the pack and some cut by the user: every later half moves. */
	@Test
	void thePipelineAgreesWithTheReferenceOnceSomePassesAreCutOut() {
		int cuts = 0;
		for (long seed = 200_000; seed < 200_600; seed++) {
			Chain chain = ParityModel.withCuts(ParityModel.random(seed, SMALL), seed);
			cuts += chain.disabled().size() + (chain.limit() >= 0 ? 1 : 0);
			assertPipelineMatches(chain, "seed " + seed);
		}

		assertTrue(cuts > 350, "cuts made: " + cuts);
	}

	@Test
	void thePipelineAgreesWithTheReferenceInPacksTheSizeOfRealOnes() {
		Shape real = new Shape(40, 150, 16, true, false);
		for (long seed = 300_000; seed < 300_040; seed++) {
			assertPipelineMatches(ParityModel.withCuts(ParityModel.random(seed, real), seed),
					"seed " + seed);
		}
	}

	/** The seeded packs must reach what they are there for, or their agreement proves less than it says. */
	@Test
	void theSeededPacksReachTheCasesTheWalkHasToTellApart() {
		int persistentFlipped = 0;
		int inferredPasses = 0;
		int noFinal = 0;
		int stageFlips = 0;
		int keptOnPurpose = 0;
		for (long seed = 100_000; seed < 100_900; seed++) {
			Chain chain = ParityModel.random(seed, SMALL);
			List<Pass> walked = ParityModel.walked(chain);
			Expected expected = ParityModel.interpret(walked, chain.flips());
			Set<Integer> named = ParityModel.named(walked);
			persistentFlipped += (int) expected.flippedAtEnd().stream()
					.filter(target -> chain.keeps().contains(target) && named.contains(target)).count();
			inferredPasses += (int) chain.passes().stream().filter(Pass::inferred).count();
			noFinal += chain.passes().stream().anyMatch(pass -> pass.stage() == Stage.FINAL) ? 0 : 1;
			stageFlips += (int) chain.flips().stream().filter(flip -> flip.program().endsWith("_pre")).count();
			keptOnPurpose += (int) chain.flips().stream().filter(flip -> !flip.value()).count();
		}

		assertTrue(persistentFlipped > 100, "flipped targets the pack keeps between frames: " + persistentFlipped);
		assertTrue(inferredPasses > 100, "programs that declare no draw buffer: " + inferredPasses);
		assertTrue(noFinal > 80, "packs that ship no final: " + noFinal);
		assertTrue(stageFlips > 100, "stage opening lines: " + stageFlips);
		assertTrue(keptOnPurpose > 80, "lines keeping a written target: " + keptOnPurpose);
	}
}
