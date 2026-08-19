package dev.vitrail.pack.target;

import dev.vitrail.pack.program.ProgramNames;
import dev.vitrail.pack.program.ProgramResolver;
import dev.vitrail.pack.program.TerrainPass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The chain of one place, unfolded into what a render pass needs: which attachments, in which
 * order, on which side, at which size.
 * <p>
 * It decides nothing about the ping pong. The schedule has already walked the frame and this
 * only reads it, which is what keeps a single answer to "which half does this pass write".
 * What it does own is the guards: an API that throws at the first draw is an API whose refusal
 * belongs at load time, with the program named.
 * <p>
 * The other thing it owns is the honesty of the picture. The chunk passes fill the targets the
 * pack sends them to and the game's own finished frame stands in for the gbuffers nothing draws
 * yet, in a single target; every other target a pass reads before anything writes it holds a clear
 * colour. Which ones those are, and why, is worked out here and said in the pack's own terms,
 * because the alternative is a plausible image nobody can account for.
 */
public final class ChainPlan {

	/** A render pass carries at most eight colour attachments, and its pipeline as many states. */
	public static final int MAX_ATTACHMENTS = 8;

	/**
	 * Where the deferred stage sits in the frame. Everything at this rank or below belongs before the
	 * world's translucents, everything above it after the world.
	 * <p>
	 * Not offered outside: whoever needs the boundary needs {@link #deferredEnd()}, and a caller
	 * handed the rank instead would walk the passes again and could reach another answer.
	 */
	private static final int DEFERRED_RANK = ProgramNames.frameRank("deferred");

	private static final String FINAL = "final";

	/** What the game's frame is painted in place of. Its fallback tree is followed, not its file. */
	private static final String SEED_PROGRAM = "gbuffers_terrain";

	private final String place;
	/**
	 * The one name of the sky's three drawn on the far side of the deferred stage, and the reason
	 * this walk has a side at all. See {@link #namedKeysOf}.
	 */
	private static final String CLOUD_PROGRAM = "gbuffers_clouds";

	/** What the game may draw its sky with, in the OptiFine split. Not ours to choose. */
	private static final List<String> SKY_PROGRAMS =
			List.of("gbuffers_skybasic", "gbuffers_skytextured", CLOUD_PROGRAM);

	/**
	 * The answer for a family no line of {@code options.txt} can have drawn in every place, which is
	 * the sky's three and the weather: their switch decides whether they are drawn at all and never
	 * where, so it cannot change what a verdict may claim.
	 */
	private static final Predicate<Families> NOT_EVERYWHERE = _ -> false;

	/**
	 * Every geometry program asked for by the name the format gives it rather than by a pass of the
	 * renderer, walked through the fallback tree and into the shared table.
	 * <p>
	 * <strong>What lands in the table is the RESOLVED program and never the name walked for.</strong>
	 * A caller asking under the name it wanted is answered empty wherever the tree led elsewhere,
	 * which on the corpus is over a third of the places once both entity names are counted: it has
	 * to ask under what {@code ProgramResolver} really served, as every family here does.
	 * <p>
	 * <strong>A name left out of this list is not half served, it is silently unanswered.</strong>
	 * Nothing else fills the table, so {@link #geometryOf} answers empty for a program no walk
	 * reached, and empty is what a family draws on when this place has no answer for it: one output,
	 * on the game's own target. Measured on the corpus in August 2026 with the entities missing from
	 * this list: seven packs of eight answered empty, and their entity programs wrote a single output
	 * where SIX of them had asked for two, three and four draw buffers, with nothing anywhere saying
	 * so. The eighth answered, and only because its entities fall back on the very program that
	 * serves its terrain, which the walk above it had already put in. Taking both entity names back
	 * out is the negative control, and it is what proved the count above rather than a reading of
	 * this list.
	 * <p>
	 * <strong>Each name carries the side of the deferred stage its family is drawn on</strong>, and
	 * that is not a property of the program but of the moment the game draws it: the sky's first two
	 * and the entities come before the deferreds and read the halves the prepares left, while the
	 * clouds and the weather are drawn once the whole main pass is finished and read the halves the
	 * deferreds left, exactly as the world's translucents do. A name walked on the wrong side answers
	 * a real pass on the wrong half of every target, which is a picture nothing reports.
	 * <p>
	 * <strong>And each carries WHEN that family fills its targets in EVERY place</strong>, which is
	 * a different question with a different answer and is what {@link #verdicts} counts. Being in this
	 * list means a pack's program is resolved and its attachments worked out; being counted means a
	 * verdict may take those targets for filled wherever this plan is built. Six of these names
	 * answer that with a switch of {@link Families} rather than with a constant, because their family
	 * is drawn or not on a line somebody wrote.
	 */
	private static final List<NamedProgram> NAMED_PROGRAMS = Stream.concat(
			// None of the sky's three is counted, the clouds included, but not for one reason: the
			// sky's two hang off a pass the game opens nowhere the place's skybox is NONE, which in
			// vanilla is the Nether, while the clouds hang off a pass of their own that the game
			// opens in the overworld alone. It is spelled out, with what it was measured to cost,
			// where the verdicts are handed their map.
			SKY_PROGRAMS.stream()
					.map(program -> new NamedProgram(program, CLOUD_PROGRAM.equals(program),
							false, NOT_EVERYWHERE)),
			Stream.of(
					// The entity halves are the two the file has to ask for, and they are drawn in
					// every place once it has: what decides them is the line and not the format.
					new NamedProgram("gbuffers_entities", false, false, Families::entities),
					new NamedProgram("gbuffers_block", false, false, Families::entities),
					// The blending half of those same two, on the far side of the stage: the game
					// draws them among its translucent features, which is after the deferreds have
					// run. Five entries for four names because the side is half the key, and the
					// entry below says which name is here twice and why.
					//
					// A name that never enters this list answers empty, and empty is the same word
					// this table uses for a walk it REFUSED, so the caller cannot tell the two
					// apart: both make the family draw one output. That is why the entry matters
					// more than it looks, and it is what the harness gate measures.
					new NamedProgram("gbuffers_entities_translucent", true, false,
							Families::entities),
					new NamedProgram("gbuffers_block_translucent", true, false, Families::entities),
					// The OPAQUE entity name on the far side, which reads like a contradiction and is
					// the energy swirl over a charged creeper: Iris pins that row to ENTITIES_CUTOUT
					// outright (pipeline/IrisPipelines.java:60) while the game blends it, so it is
					// drawn among the translucent features and still asks for gbuffers_entities. It is
					// the one row of render/EntityDraw whose name and side disagree, and no other walk
					// reaches that pair: the two entries above ask for the blending names, and the
					// opaque entry asks on the near side.
					//
					// Not counted, on the hand's argument rather than the entities': the family this
					// key answers for is one row, and a swirl is drawn where somebody has charged a
					// creeper or armoured a wither, which is a per frame answer no per place map may
					// carry. Where a pack ships no gbuffers_entities_translucent of its own the
					// blending name falls back on this very file, and that entry counts it; three
					// packs of the corpus are in that case and this entry moves nothing for them.
					new NamedProgram("gbuffers_entities", true, false, NOT_EVERYWHERE),
					// The glowing eyes of a mob, drawn among the translucent features beside the two
					// above and on the same side of the stage. One entry and not two: its two
					// pipelines ask for the one name, both blend, and neither is ever drawn before
					// the deferreds.
					//
					// Not counted, and it is the hand's reason rather than the entities': the line
					// being on does not put one of the six mobs that reach these two pipelines in
					// front of the camera. A verdict that took the eyes for drawn would report
					// targets as written in every place a plan is built for, which is every place
					// none of the six is standing.
					new NamedProgram("gbuffers_spidereyes", true, false, NOT_EVERYWHERE),
					// The hand's two passes, which straddle the stage as the particles do and for the
					// same kind of reason: the solid one is drawn among the game's opaque features and
					// the blending one at the end of the level. Not counted, and not by a switch:
					// even with the line written, the hand is only drawn where a player has one,
					// which is no place at all from a third person camera - a per frame answer no
					// per place map may carry, so its entries take the weather's constant.
					//
					// The two really can resolve to ONE file, gbuffers_hand_water falling back on
					// gbuffers_hand, and that is exactly why they are two entries rather than one: a
					// single walk would put one key in and leave the other side of the same file
					// unanswered, which is the silence the head of this list is about.
					new NamedProgram("gbuffers_hand", false, false, NOT_EVERYWHERE),
					new NamedProgram("gbuffers_hand_water", true, false, NOT_EVERYWHERE),
					// An enchantment's glint, which straddles the stage for a reason of its own: what
					// CARRIES it decides which half the game executes it in. An enchanted book is
					// submitted among the solid features, foil and all, since the sort looks at the
					// item's own quads and not at its glint (SubmitNodeCollection.java:325), while an
					// enchanted armour piece is submitted among the translucent ones. So both sides are
					// walked, as the hand's two are, and for the same reason: one key in and the other
					// side of the same file unanswered is the silence the head of this list is about.
					//
					// Not counted, and on the hand's argument rather than the weather's: a glint is
					// drawn where somebody is holding or wearing something enchanted, which is a per
					// frame answer no per place map may carry.
					new NamedProgram("gbuffers_armor_glint", false, false, NOT_EVERYWHERE),
					new NamedProgram("gbuffers_armor_glint", true, false, NOT_EVERYWHERE),
					// Drawn, and still not counted: the game draws no rain and no snow where there is
					// no weather, which is every place but the overworld.
					new NamedProgram("gbuffers_weather", true, false, NOT_EVERYWHERE),
					// The one family that straddles the stage: the game submits every particle group
					// twice, and the two land on either side of it. Counted wherever its line is on,
					// and what earns it is that particles are drawn in every place there is.
					//
					// And the last name of this list riding on the seed: its opaque half never asks
					// for a coverage mask, so its first output makes the trip through the game's
					// own target and reaches the pack's picture through the seed and nowhere else.
					new NamedProgram("gbuffers_particles", false, true, Families::particles),
					new NamedProgram("gbuffers_particles_translucent", true, false,
							Families::particles),
					// And Distant Horizons' far terrain, on both sides of the stage: that mod draws
					// its own two halves from the head of the game's opaque chunk group and from the
					// head of its translucent one, which is where the world's own two chunk passes
					// stand. Left out of this list, a pack's far terrain program writes its FIRST
					// draw buffer and no other, which is what BSL was measured doing on 18 August
					// 2026: it asks for colortex0 and colortex6 and colortex6 went nowhere.
					//
					// Neither half rides the seed: the family owns its first draw buffer outright,
					// as Iris binds its dh programs, and GeometryProgram says in the one place the
					// rule is decided why the seed could not carry it. Neither half is counted, for
					// the reason no family with a switch is: the far terrain is drawn where that mod
					// is installed and running, which is no place in particular.
					new NamedProgram("dh_terrain", false, false, NOT_EVERYWHERE),
					new NamedProgram("dh_water", true, false, NOT_EVERYWHERE)))
			.toList();

	/**
	 * One name of that list, the side of the deferred stage the family asking for it draws on, and
	 * when a verdict may take its targets for filled.
	 *
	 * @param ridesTheSeed whether this name's FIRST output reaches the pack's picture through the
	 *                   scene seed rather than being written into the pack's own target. It is what
	 *                   holds a name to the seed's own target below, and it is a question about the
	 *                   coverage mask and not about the side of the stage: a family drawn before the
	 *                   seed that writes the mask keeps the seed off the pixels it wrote and takes
	 *                   its first draw buffer outright, so the seed's target is no longer its
	 *                   business. Only the opaque particles ride it, which
	 *                   {@code render/ParticleDraw} says in the same words and {@code GeometryProgram}
	 *                   lists among the halves that never asked for a mask
	 * @param everywhere whether this engine draws that family, with the switches it was handed, in
	 *                   EVERY place a plan is built for. Not simply whether it draws it: a plan is
	 *                   per place, and a family drawn in the overworld alone would have a verdict
	 *                   report the world as drawn in the two places the format reserves for the
	 *                   Nether and the End. It decides nothing about the attachments and everything
	 *                   about the verdicts, which are the one place that must not tell a reader a
	 *                   target holds a clear colour when a family of ours has just written it
	 */
	private record NamedProgram(String program, boolean afterDeferred, boolean ridesTheSeed,
			Predicate<Families> everywhere) {
	}

	private final List<Pass> passes;
	private final Pass last;
	private final Seed seed;

	/**
	 * What a geometry program writes, keyed by the file that serves it and by the side of the
	 * deferred stage it draws on.
	 * <p>
	 * <strong>Both halves of that key are load bearing, and neither can be dropped.</strong> The
	 * file, because two names of the format are commonly served by one file and the answer belongs
	 * to what was read; the side, because the schedule hands the same draw buffers different halves
	 * before and after the deferreds, so one file serving the solid pass and the translucent pass
	 * has two answers and they differ.
	 * <p>
	 * One table for every family rather than one per family, which is what lets a family that does
	 * not exist yet ask the same question: the terrain asks it three times, the sky three times, and
	 * the entities once per FILE AND SIDE that serves them. Their pieces ask under four names, two on
	 * the near side and three on the far one, {@code gbuffers_entities} being asked on both, and those
	 * five collapse onto fewer keys wherever the fallback tree lands two of them on one program - but
	 * never across the sides, the side being half the key.
	 * What differs between families is only how they reach a key, and that is the two tables below
	 * plus {@link #geometryOf} for whoever needs neither.
	 * <p>
	 * <strong>Nothing is in it that a walk did not put there</strong>, and there are two walks,
	 * {@code terrainKeysOf} and {@code namedKeysOf}. Which of the two put a key in does not matter to
	 * whoever reads it, and it is not always the family's own: a pack whose entities fall back on the
	 * program that serves its terrain is answered by the terrain's walk. What matters is that a
	 * program neither walk reached is answered empty, which reads exactly like a program this place
	 * has no answer for.
	 */
	private final Map<Key, Pass> attachments;

	/**
	 * Which key each chunk pass reaches. A pass is absent when nothing here serves it, and also when
	 * the key it reaches has no answer, which {@link #geometry} spells out.
	 * <p>
	 * Kept apart from the answer because the question is the pass's and the answer is the program's:
	 * the translucent pass draws after the deferred stage even when the very same program serves the
	 * solid pass before them.
	 */
	private final Map<TerrainPass, Key> terrainKeys;

	/**
	 * Which key each sky program reaches, by the bare name the pack serves it under.
	 * <p>
	 * By name and not by an enum of passes, unlike the terrain: the game draws the sky in eight
	 * passes of its own but they share three programs between them, and what decides the halves is
	 * the program rather than the pass. All of them draw before the deferred stage, so all of them
	 * read the snapshot the prepares leave, which is the ordinary geometry walk.
	 */
	private final Map<String, Key> skyKeys;

	private final List<Integer> swapBack;
	private final List<String> refusals;
	private final List<String> notes;
	private final List<String> history;

	private ChainPlan(String place, List<Pass> passes, Pass last, Seed seed,
			Map<Key, Pass> attachments, Map<TerrainPass, Key> terrainKeys, Map<String, Key> skyKeys,
			List<Integer> swapBack, List<String> refusals, List<String> notes,
			List<String> history) {
		this.place = place;
		this.passes = List.copyOf(passes);
		this.last = last;
		this.seed = seed;
		this.attachments = Map.copyOf(attachments);
		this.terrainKeys = Map.copyOf(terrainKeys);
		this.skyKeys = Map.copyOf(skyKeys);
		this.swapBack = List.copyOf(swapBack);
		this.refusals = List.copyOf(refusals);
		this.notes = List.copyOf(notes);
		this.history = List.copyOf(history);
	}

	public record Attachment(int target, TargetSchedule.Side side) {
	}

	/**
	 * One full screen pass of the chain, with what it draws into.
	 *
	 * @param attachments in draw buffer order, at most eight, no index named twice. Empty on the
	 *                    final alone, which writes the game's own target and no colortex; on
	 *                    everything in {@link #passes()} there is at least one, so nothing has to
	 *                    pad index zero, which the command encoder refuses outright
	 * @param size        the one size every attachment of this pass shares
	 * @param inferred    the pack named no draw buffer and colortex0 was inferred, as Iris does
	 */
	public record Pass(String program, List<Attachment> attachments, TargetSize size,
			boolean inferred) {

		public Pass {
			attachments = List.copyOf(attachments);
		}

		/** The targets, without their side, which is what a log and a sampler check want. */
		public List<Integer> targets() {
			return this.attachments.stream().map(Attachment::target).toList();
		}

		/**
		 * Where in the frame this pass belongs, on the scale {@link ProgramNames#frameRank} uses.
		 * <p>
		 * Read off the name and never off a position in a list. A stage boundary expressed as an
		 * index into the running order shifts the moment one program of the chain is refused, and it
		 * shifts without a word: the passes still run, in the right order, at the wrong moment.
		 */
		public int frameRank() {
			return ProgramNames.frameRank(ProgramNames.familyOf(this.program));
		}

		/**
		 * Whether this pass belongs to the deferred stage, which OptiFine runs after the opaque
		 * geometry and before the translucent geometry rather than after the world.
		 */
		public boolean deferred() {
			return frameRank() == ProgramNames.frameRank("deferred");
		}
	}

	/**
	 * What a geometry program's answer depends on: the file that ends up serving it, and whether it
	 * draws after the deferred stage.
	 *
	 * @param servedBy      the bare name of the PROGRAM the fallback tree landed on, never the one
	 *                      that was asked for and never a file name: it is what
	 *                      {@code ProgramResolver.Resolution} calls servedBy, and asking with
	 *                      anything else answers empty. Two names of the format commonly resolve to
	 *                      the same one, and they then share every answer, which is what makes this
	 *                      the key
	 * @param afterDeferred which snapshot of the flip counter the schedule gives it. The same file
	 *                      answers twice when it serves geometry on both sides, and the two answers
	 *                      differ by exactly the halves the deferred stage turned over
	 */
	public record Key(String servedBy, boolean afterDeferred) {
	}

	/**
	 * Where the game's finished frame is painted in place of the gbuffers.
	 *
	 * @param from the geometry program that would have written it, for the log
	 * @param at   how many of {@link #passes()} run before it, which is where the world would have
	 *             been drawn. Carried rather than left to the caller: a begin or a prepare runs
	 *             ahead of the world, the schedule gave the seed its half on that footing, and a
	 *             frame painting it anywhere else would contradict the walk without erroring
	 */
	public record Seed(int target, TargetSchedule.Side side, String from, int at) {
	}

	/**
	 * What this engine is really drawing into the pack's targets, from the lines of
	 * {@code vitrail/options.txt} that decide it rather than from anything the pack says.
	 * <p>
	 * Four lines and not thirteen, and the nine others are not missing: what a verdict has to know is
	 * which targets are filled in EVERY place a plan is built for, and the sky, the clouds and the
	 * weather are not drawn in every place however their line reads, so their line cannot move that
	 * answer; {@code chain} and {@code passes} take the frame away entirely, {@code shadow} draws
	 * into the shadow map's own targets, which this plan does not hold, and {@code screen} and
	 * {@code dump} draw nothing; and the {@code hand} is only drawn where a player holds one, which
	 * is a question about the frame's camera and not about the place. These four can. Measured on
	 * the corpus in August 2026, each line
	 * moved on its own and everything else left at its default: read as defaults instead,
	 * {@code terrain=off} leaves
	 * twenty-five true notes unsaid, {@code particles=off} nine and {@code seed=off} six, and
	 * {@code entities=off} keeps in the map a family that no longer draws, which no pack of the
	 * corpus is caught by and which is therefore the same failure waiting rather than a second one.
	 *
	 * @param terrain   whether the pack's own terrain program draws the world's opaque chunks. Off,
	 *                  the game's own shader draws them and the seed carries them in, so nothing of
	 *                  the pack's writes the targets its terrain program declares
	 * @param entities  whether the game's own opaque entity geometry is drawn with the pack's
	 *                  programs
	 * @param particles whether the game's quad particles are, both halves of them
	 * @param seed      whether the game's finished frame is painted where the world would be. It is
	 *                  the one road into the pack's picture for a family drawn BEFORE the deferred
	 *                  stage that writes no coverage mask, so off it takes the seed's own target with
	 *                  it and hands the opaque particles back to the game, which is
	 *                  {@code render/ParticleDraw.writes} refusing on the same answer. The entities
	 *                  are no longer on that road: they write the mask and take their first draw
	 *                  buffer in the pack's own targets, so this switch moves nothing for them
	 */
	public record Families(boolean terrain, boolean entities, boolean particles, boolean seed) {

		/**
		 * What a caller with no {@code options.txt} to read gets, which is the harness and the corpus
		 * measurements: the entities off and the three others on, which is what {@code EngineOptions}
		 * defaults those four lines to. Written out here rather than read, nothing in this package
		 * having a game directory to read it from.
		 */
		public static final Families DEFAULT = new Families(true, false, true, true);
	}

	/** What a caller with no {@code options.txt} to read gets. See {@link Families#DEFAULT}. */
	public static ChainPlan of(TargetPlan plan, ProgramResolver resolver) {
		return of(plan, resolver, List.of(), Families.DEFAULT);
	}

	/**
	 * Builds the chain of one place from the programs that could be resolved.
	 *
	 * @param refused  why programs of this place can have no pipeline built for them at all, in
	 *                 whole sentences. They are put first because the caller shows one of them and
	 *                 the one worth showing is the one nothing downstream could have worked around
	 * @param families what this engine is really drawing, from the lines that were written. It
	 *                 decides nothing about the passes and nothing about where the seed would be
	 *                 painted, and everything about which of the two a verdict may take for already
	 *                 written
	 */
	public static ChainPlan of(TargetPlan plan, ProgramResolver resolver, List<String> refused,
			Families families) {
		List<Pass> passes = new ArrayList<>();
		List<String> refusals = new ArrayList<>(refused);
		List<String> notes = new ArrayList<>();
		List<String> history = new ArrayList<>();
		Pass last = null;

		for (String program : plan.running()) {
			if (program.equals(FINAL)) {
				last = new Pass(program, List.of(), TargetSize.ofScreen(), false);
				continue;
			}

			Pass pass = passOf(plan, program, refusals);
			if (pass != null) {
				passes.add(pass);
			}
		}

		if (last == null) {
			// Not a refusal: whoever loaded the chain has already decided whether a place without
			// a final is worth drawing, and every pass here still writes what it says it writes.
			notes.add("this place runs no final, so nothing the chain writes reaches the screen");
		}

		// Worked out before the verdicts rather than in the return, so that they are handed the
		// answer instead of asking for it twice: what the chunk passes write is exactly what the
		// verdicts must not blame the clear for.
		//
		// One walk fills the three: the answers land in the shared table and each family keeps the
		// keys it reaches. A program serving two families, which is the ordinary case for a pack
		// whose sky falls back on gbuffers_basic, is therefore walked once where the two families
		// used to memoise apart and walk it twice, saying anything it had to say twice with it. No
		// pack of the corpus reaches that case, so nothing moved in the measurements; it is the
		// shared table that now guarantees it rather than each family's own bookkeeping.
		Map<Key, Pass> attachments = new LinkedHashMap<>();
		Map<TerrainPass, Key> terrainKeys = terrainKeysOf(plan, resolver, notes, attachments);
		Map<NamedProgram, Key> namedKeys = namedKeysOf(plan, resolver, notes, attachments);
		Map<String, Key> skyKeys = new LinkedHashMap<>();
		namedKeys.forEach((named, key) -> {
			if (SKY_PROGRAMS.contains(named.program())) {
				skyKeys.put(named.program(), key);
			}
		});

		Seed seed = seedOf(plan, resolver, notes);
		// Where the seed is painted is one question and whether it is painted is another, and only
		// the second belongs to the frame: the families that ride on the seed compare their first
		// output against its target whatever the line says, so the plan's own answer stands and this
		// is what the walk below counts with.
		Seed painted = families.seed() ? seed : null;

		// The terrain and whatever the switches leave, and the rule that admits those and no others
		// is ONE question: does this engine draw that family, with the lines that were really
		// written, in every place a plan is built for? A plan is per PLACE, so a family drawn in
		// the overworld alone cannot be counted at all - counting it reports the world as drawn in
		// the two places the format reserves for the Nether and the End.
		//
		// Three families fail that question however options.txt is written, and each fails it
		// differently. The sky's two hang off the game's own sky pass, and a place whose skybox is
		// NONE opens none at all (LevelRenderer.addSkyPass, the Skybox.NONE gate at :337). Vanilla
		// answers NONE in the Nether and nowhere else, but the field is a datapack one with a codec
		// and OVERWORLD for a default (DimensionType.java:97), so a modded dimension may answer it
		// anywhere and the gate is what this depends on, not the name. Both branches of that pass
		// are served now, the End's two methods as much as the other six, RenderPipelines.END_SKY
		// being assigned gbuffers_skytextured as Iris assigns it (IrisPipelines.java:69), so what
		// keeps the sky out of this count is that one gate and no longer two. The clouds are held
		// out by something else entirely: a pass of their own, opened on the cloud setting and on
		// the alpha of the cloud colour (LevelRenderer:220-233) and in the overworld alone.
		// The weather is drawn only where the level has weather,
		// which the weather renderer decides on its own; what is certain here is that it is not every
		// place.
		//
		// The terrain, the entities, the particles and the seed pass it or fail it on their line
		// alone, and asking the line rather than its default is why this method is handed the
		// switches at all. Measured on the corpus, each line moved on its own: reading the defaults
		// instead leaves twenty-five true notes unsaid under terrain=off, nine under particles=off
		// and six under seed=off, and keeps the entities in the map wherever entities=off is
		// written, which no pack of the corpus is caught by.
		//
		// It is measured rather than argued, and the measurement is a trade rather than a clean win.
		// Counting the sky silences Bliss's colortex0 note in all three of its places and two of the
		// three are true. Counting the weather rewrites the colortex12 note of both Complementary in
		// all three of theirs, which makes it true in world0 and makes it name, in the other two, a
		// program that will never run there: four lines lost against two gained. Holding the three out
		// keeps the true notes and pays one false one per family in every place that family really
		// draws, which is the cheaper half of both trades and the one this engine takes.
		//
		// WHAT IT LEAVES OPEN, said rather than hidden: those notes stay wrong, and the only honest
		// way to close them is a per place answer rather than a per name one, which this record
		// cannot carry. The End's sky widened that by one place the day it was served.
		// AND, on the near side, only where the draw is really taken. A family whose FIRST output
		// reaches the pack's picture through the scene seed takes over a pass the renderer opened
		// with one attachment of its own, so it can only be redirected when that output is the seed's
		// target and half; where it is not, the game keeps its own shader and nothing of the pack's
		// is written. That is leadsWithSeed, and ParticleDraw refuses on the same answer. Bliss is
		// why it is here: its seed is colortex1 against a first output of colortex2, so its opaque
		// particles are handed back in all three places, and counting them would silence three notes
		// that are true. Nothing on the corpus moves for it - Bliss's colortex9 is silenced by the
		// translucent half, which carries no such condition - and that is the point: it costs no note
		// and it stops the map claiming a draw that never happened.
		//
		// IT IS THE SEED'S ROAD AND NOT THE SIDE OF THE STAGE, and the two parted company when the
		// entities took their first draw buffer in the pack's own targets. A family drawn before the
		// seed that writes the coverage mask keeps the seed off the pixels it wrote, so its first
		// output never makes the trip and the seed's target says nothing about whether its draw was
		// taken; render/EntityDraw stopped asking, and a map still asking would hand the entities
		// back on any pack whose entity program leads with a target other than the seed's - and on
		// every pack under seed=off, where the family is served all the same. ridesTheSeed carries
		// which names are left on that road, and only the opaque particles are.
		//
		// WHAT THAT OVER-CLAIMS, said rather than hidden: this map cannot know whether the mask was
		// really placed, which is the translation's answer and per FILE. A pack whose entity program
		// declares eight outputs already, or more draw buffers than outputs, is drawn by the game's
		// shader while these notes count it as drawn. That is the same shape as the per NAME against
		// per FAMILY asymmetry below, it is the rarer of the two directions, and the engine says so
		// by name in the log at the moment it happens.
		//
		// AND at the size of the screen, which is the same condition read off the other side: every
		// family below shares the pass the renderer opened for the game's own target, one render pass
		// has one render area, and each of their files turns the draw down when the pack asked for
		// targets of another size. No pack of the corpus scales one, so this moved nothing on the
		// August 2026 corpus; it
		// is here because the two readings must not be able to disagree. The terrain carries no such
		// condition and is not held to it: it draws into a pass of its own. Iris carries no such
		// refusal either way - it scales single targets (ShaderProperties.java:277,
		// TextureScaleOverride) and no size condition was found on its geometry routing - so the
		// refusal is this render-pass shape's price, and where a pack scaled a target a family
		// writes, that family would be drawn by the game's shader, the note this map keeps being
		// what says so. One asymmetry stands with it and is said: this map counts per NAME where
		// render/EntityDraw refuses per FAMILY, so two entity names resolving to two files, one of
		// them refused here, would be counted half drawn while the family served nothing. No pack of
		// the corpus reaches that case.
		//
		// WHAT THIS MAP DELIBERATELY DOES NOT FOLLOW: with chain=off the opaque particles are still
		// drawn even with seed=off, which render/ParticleDraw spells out - it is the one
		// configuration that tells a wrong gbuffer from a wrong composite. This map has them handed
		// back there. It costs nothing worth the branch: with the chain off, no pass of it draws, so
		// every line these notes carry is about a frame that does not happen.
		Map<Key, Pass> world = new LinkedHashMap<>();
		if (families.terrain()) {
			terrainKeys.values().forEach(key -> world.put(key, attachments.get(key)));
		}

		for (NamedProgram named : NAMED_PROGRAMS) {
			Key key = named.everywhere().test(families) ? namedKeys.get(named) : null;
			Pass drawing = key == null ? null : attachments.get(key);
			if (drawing != null && drawing.size().equals(TargetSize.ofScreen())
					&& (!named.ridesTheSeed() || leadsWithSeed(painted, drawing))) {
				world.put(key, drawing);
			}
		}

		verdicts(plan, painted, world, passes, last, notes, history);

		Set<Integer> back = new TreeSet<>(plan.schedule().flippedAtEnd());
		back.retainAll(plan.persistent());

		// The nulls are dropped on the way out, and they had to be there on the way in: a key that
		// this place cannot answer is remembered as answered so that the three families reaching it
		// say what there is to say once. Past this point nobody asks twice, and an absent key and a
		// key mapped to nothing mean the same thing to every reader.
		Map<Key, Pass> answered = new LinkedHashMap<>();
		attachments.forEach((key, pass) -> {
			if (pass != null) {
				answered.put(key, pass);
			}
		});

		return new ChainPlan(plan.place(), passes, last, seed, answered, terrainKeys, skyKeys,
				List.copyOf(back), refusals, notes, history);
	}

	/**
	 * Which key each chunk pass this engine can draw reaches, walking every answer it needs into the
	 * shared table on the way.
	 * <p>
	 * The pass is the question and the key is where the answer lives, and the two are not the same:
	 * one program usually serves two of the three passes, and those two stand on either side of the
	 * deferred stage, so the same draw buffers land on different halves depending on which pass is
	 * asking. Iris keys the same answer the same way, one flip snapshot per {@code Pass}.
	 * <p>
	 * The solid and the cutout pass therefore share one key and one walk, which is also what keeps a
	 * program this place cannot carry from being reported twice.
	 */
	private static Map<TerrainPass, Key> terrainKeysOf(TargetPlan plan, ProgramResolver resolver,
			List<String> notes, Map<Key, Pass> into) {
		Map<TerrainPass, Key> keys = new EnumMap<>(TerrainPass.class);
		for (TerrainPass pass : TerrainPass.values()) {
			// A shadow pass writes shadowcolor and never colortex, so this walk has no answer for
			// it: its draw buffers index a set of targets this plan does not hold, and a number read
			// as the wrong family would send the shadow map's albedo into somebody's normals.
			if (pass.shadow()) {
				continue;
			}

			Optional<String> served = resolver.lookup(plan.place(), pass.program())
					.map(ProgramResolver.Resolution::servedBy);
			if (served.isEmpty()) {
				continue;
			}

			Key key = new Key(served.get(), pass.afterDeferred());
			if (answer(plan, key, notes, into) != null) {
				keys.put(pass, key);
			}
		}

		return keys;
	}

	/**
	 * Walks one key into the shared table, once, and answers what it holds.
	 * <p>
	 * containsKey rather than computeIfAbsent, because null is an answer here: a file this place
	 * cannot carry the targets of has to be remembered as answered, or every family reaching the
	 * same key would say the same note about it again.
	 */
	private static Pass answer(TargetPlan plan, Key key, List<String> notes, Map<Key, Pass> into) {
		if (!into.containsKey(key)) {
			into.put(key, geometryOf(plan, key.servedBy(), notes, key.afterDeferred()));
		}

		return into.get(key);
	}

	/**
	 * The same walk for every geometry program asked for by name, which is the sky's three, the four
	 * entity names with the opaque one on both sides, the hand's two, the glint on both sides, the
	 * weather and the two particle halves.
	 * <p>
	 * The names are the OptiFine split and they are not ours to choose: untextured sky geometry goes
	 * to {@code gbuffers_skybasic}, the sun and the moon to {@code gbuffers_skytextured}, the clouds
	 * to {@code gbuffers_clouds}, and the game's own entity meshes to {@code gbuffers_entities}. Each
	 * is looked up through the fallback tree, so a pack that ships none of them still answers here
	 * through {@code gbuffers_basic} or {@code gbuffers_textured} if it has those.
	 * <p>
	 * Which side of the deferred stage each is walked on is the list's to say, and it is not the same
	 * for all of them. The sky is drawn at the third rank of the frame and the game's opaque features
	 * between the opaque chunks and the deferred stage, so both read the halves the prepares leave;
	 * the clouds, the weather, the translucent half of the particles and the translucent chunk pass
	 * are taken after the deferred stage.
	 * <p>
	 * <strong>The clouds are the exception inside their own family</strong>: the game draws them
	 * after the main pass, which is after this engine has run the first half of the chain, so their
	 * halves are the ones the deferred stage turned over. Answered on the near side they write the
	 * dead half of the ping pong, and nothing says so - the pass runs, the program draws, the final
	 * composes from the other half and the sky comes back empty.
	 * <p>
	 * The map it answers is by the ENTRY of the list above and not by the name it asks for, which is
	 * one key finer than it reads: a name may be in that list twice, once per side, and the glint is,
	 * so a map by name would hold one row where the walk made two and the second would overwrite the
	 * first without a word. Both callers want the finer key anyway - the sky asks by the name it
	 * wanted rather than by the program it got, and the verdicts ask which SIDE of a family this
	 * engine really fills. Every other reader is served through {@link #geometryOf}, and what this
	 * walk owes them is the entry in the shared table.
	 */
	private static Map<NamedProgram, Key> namedKeysOf(TargetPlan plan, ProgramResolver resolver,
			List<String> notes, Map<Key, Pass> into) {
		Map<NamedProgram, Key> keys = new LinkedHashMap<>();

		// Computed once per serving FILE AND SIDE and not once per program, the same reason the
		// geometry walk gives above: the fallback tree sends skybasic to gbuffers_basic and both
		// skytextured and clouds to gbuffers_textured, so one file commonly serves two or three of
		// these names, and walking it again would say the same note about it twice. The side is part
		// of it because the clouds and the weather ask on the far one, so a pack with no cloud
		// program of its own really is one file answering twice here, which is what the key is for.
		for (NamedProgram named : NAMED_PROGRAMS) {
			Optional<String> served = resolver.lookup(plan.place(), named.program())
					.map(ProgramResolver.Resolution::servedBy);
			if (served.isEmpty()) {
				continue;
			}

			Key key = new Key(served.get(), named.afterDeferred());
			if (answer(plan, key, notes, into) != null) {
				keys.put(named, key);
			}
		}

		return keys;
	}

	private static Pass passOf(TargetPlan plan, String program, List<String> refusals) {
		List<Integer> writes = plan.writes(program);
		if (writes.isEmpty()) {
			// Every full screen program is inferred to colortex0, so the only way here is a file
			// the expander could not read at all.
			refusals.add(program + " is meant to run and this plan knows nothing it writes, which "
					+ "is what an entry point that could not be read looks like");
			return null;
		}

		return attachmentsOf(plan, program, writes, refusals);
	}

	/**
	 * The same walk for a geometry program, which is asked for by name rather than found in the
	 * running chain.
	 * <p>
	 * Every reason to answer nothing goes to the notes and none of them refuses anything: a geometry
	 * program is not part of the chain, so a place that cannot carry its targets is a place where
	 * the geometry writes one attachment instead of several, not a place that draws nothing.
	 * <p>
	 * The empty writes handed in below - the walk's own refusals keep to the one list on
	 * {@code attachmentsOf} - are down to two cases since the inference took the geometry in, and both
	 * are honest: a program drawn from the light, whose draw buffers name shadow targets this plan
	 * does not hold, and a file the expander could not read at all. A pack that simply declares no
	 * directive no longer arrives here - it arrives with colortex0, as it does under Iris, and its
	 * name is in the one line {@link #notes()} carries for the lot.
	 */
	private static Pass geometryOf(TargetPlan plan, String program, List<String> notes,
			boolean afterDeferred) {
		List<Integer> writes = plan.writes(program);
		if (writes.isEmpty()) {
			return null;
		}

		Optional<TargetSchedule.Bound> bound = afterDeferred
				? plan.schedule().stepAfterDeferred(program)
				: plan.schedule().step(program);

		return attachmentsOf(plan, program, writes, notes, bound);
	}

	private static Pass attachmentsOf(TargetPlan plan, String program, List<Integer> writes,
			List<String> problems) {
		return attachmentsOf(plan, program, writes, problems, plan.schedule().step(program));
	}

	private static Pass attachmentsOf(TargetPlan plan, String program, List<Integer> writes,
			List<String> problems, Optional<TargetSchedule.Bound> bound) {
		if (writes.size() > MAX_ATTACHMENTS) {
			problems.add(program + " writes " + writes.size() + " targets and one pass carries at "
					+ "most " + MAX_ATTACHMENTS + ": " + writes);
			return null;
		}

		if (new LinkedHashSet<>(writes).size() != writes.size()) {
			problems.add(program + " names the same target twice in its draw buffers, " + writes
					+ ", and one image cannot be two attachments of one pass");
			return null;
		}

		if (bound.isEmpty()) {
			problems.add(program + " writes " + writes + " and the schedule holds no step for it");
			return null;
		}

		List<Attachment> attachments = new ArrayList<>();
		TargetSize size = null;
		for (int index : writes) {
			if (!plan.allocated().contains(index)) {
				problems.add(program + " writes " + TargetName.canonical(index)
						+ ", which nothing of this place allocates");
				return null;
			}

			TargetSize here = plan.directives().size(index);
			if (size == null) {
				size = here;
			} else if (!size.equals(here)) {
				// One pass, one render area: the backend refuses attachments of two sizes, and it
				// refuses them at the first draw rather than at load time.
				problems.add(program + " writes targets of two sizes, " + describe(size) + " and "
						+ describe(here) + " for " + TargetName.canonical(index));
				return null;
			}

			attachments.add(new Attachment(index, bound.get().write(index)));
		}

		return new Pass(program, attachments, size, plan.inferredWrites().contains(program));
	}

	private static Seed seedOf(TargetPlan plan, ProgramResolver resolver, List<String> notes) {
		Optional<ProgramResolver.Resolution> resolution = resolver.lookup(plan.place(), SEED_PROGRAM);
		if (resolution.isEmpty()) {
			notes.add("nothing here serves " + SEED_PROGRAM + ", so the game's own frame is painted "
					+ "nowhere and every program of the chain reads clear colours");
			return null;
		}

		// The fallback tree is followed rather than the file: Sildur's ships no gbuffers_terrain
		// and inherits it from gbuffers_textured, whose draw buffers are 412 and not 0.
		String from = resolution.get().servedBy();
		List<Integer> writes = plan.writes(from);
		if (writes.isEmpty()) {
			notes.add(from + " serves " + SEED_PROGRAM + " and declares no draw buffer, so there is "
					+ "nowhere to paint the game's own frame");
			return null;
		}

		int target = writes.get(0);
		if (!plan.allocated().contains(target)) {
			notes.add(from + " serves " + SEED_PROGRAM + " and writes " + TargetName.canonical(target)
					+ ", which nothing of this place allocates, so the game's own frame is painted "
					+ "nowhere");
			return null;
		}

		TargetSchedule.Side side = plan.schedule().step(from)
				.map(step -> step.write(target))
				.orElse(TargetSchedule.Side.MAIN);

		return new Seed(target, side, from, plan.geometryAt());
	}

	/**
	 * What will be wrong once the chain is drawn, in the pack's own terms.
	 * <p>
	 * The unit is the half, not the target: a pass that reads and writes one target reads the half
	 * it is not writing, so asking whether "the target" has been written answers the wrong
	 * question and answers it reassuringly. A half is filled from the moment something in this
	 * frame has written it, and anything read before that holds either a clear colour or the
	 * frame before. Which of the two, and why, is what these lines say.
	 * <p>
	 * The seed counts from where it is drawn and not from the start of the frame. A begin or a
	 * prepare runs before the world does, so it reads its target as the clear left it, and calling
	 * the seed filled from the first pass onwards would drop exactly the notes those passes need.
	 * <p>
	 * The world's own passes count from where they are drawn for the same reason, and they are counted
	 * at all because they really are drawn: the opaque ones before the whole chain, since the chunk
	 * renderer has finished with them by the time the first half of the frame runs, and the
	 * translucent ones after the last deferred, which is the whole reason their targets are taken on
	 * the other snapshot. Reading the geometry off the schedule alone, as this did, blamed the clear
	 * for a half {@code gbuffers_water} had just written.
	 *
	 * @param seed  where the game's finished frame is really painted, or null where the line that
	 *              paints it is off - or where the plan itself could not say where it would go,
	 *              which arrives here the same way and counts the same. Not the same as where the
	 *              plan says it would be painted, which
	 *              stands whatever is written: this one is what the frame does
	 * @param world every family this engine really draws into the pack's targets, by the key its
	 *              answer lives under. It is not the terrain alone, and with the terrain switched off
	 *              it does not hold the terrain either: a map short of a family says a target holds a
	 *              clear colour where that family has just written, and a map holding a family that
	 *              was switched off says the target is filled where nothing wrote it. The first is
	 *              the one these lines exist to rule out; the second leaves no trace at all
	 * @param notes what the picture will be wrong about
	 * @param history the reads that land on what the frame before wrote, which is the pack's own
	 *                doing rather than a fault of this engine. See {@link #history()}
	 */
	private static void verdicts(TargetPlan plan, Seed seed, Map<Key, Pass> world,
			List<Pass> passes, Pass last, List<String> notes, List<String> history) {
		List<Pass> ordered = new ArrayList<>(passes);
		if (last != null) {
			ordered.add(last);
		}

		// Every target a gbuffers program declares, less the ones the world's own passes really fill.
		// What is left is written by geometry alone and by nothing this engine puts in the pack's
		// targets.
		Set<Integer> undrawn = new TreeSet<>();
		plan.schedule().steps().stream()
				.filter(step -> !step.fullscreen())
				.forEach(step -> undrawn.addAll(step.writes()));

		// One entry per point of the frame the world goes in at, every family drawn on the same side
		// sharing theirs. A set rather than a list, since several of them are commonly one file and
		// hence one identical Pass: the solid and the cutout pass always, and a pack with no cloud
		// program of its own has its clouds and its particles answered by the same one too.
		//
		// The near side goes in at nought and not where the seed goes. The seed is painted where
		// OptiFine draws the world, in the middle of the chain, but the game is not: it has drawn the
		// opaque terrain, the opaque particles and everything else on that side before the first half
		// of the chain is even asked to run, so a prepare of this engine reads what they wrote in THIS
		// frame.
		int afterDeferred = pastDeferred(ordered);
		Map<Integer, Set<Pass>> drawn = new LinkedHashMap<>();
		world.forEach((key, drawing) -> {
			drawn.computeIfAbsent(key.afterDeferred() ? afterDeferred : 0, _ -> new LinkedHashSet<>())
					.add(drawing);
			undrawn.removeAll(drawing.targets());
		});

		Set<Attachment> filled = new LinkedHashSet<>();
		Set<Attachment> told = new LinkedHashSet<>();
		for (int at = 0; at < ordered.size(); at++) {
			if (seed != null && at == seed.at()) {
				filled.add(new Attachment(seed.target(), seed.side()));
			}

			for (Pass drawing : drawn.getOrDefault(at, Set.of())) {
				filled.addAll(drawing.attachments());
			}

			Pass pass = ordered.get(at);
			Optional<TargetSchedule.Bound> bound = plan.schedule().step(pass.program());

			for (int index : plan.samples(pass.program())) {
				Attachment half = new Attachment(index, bound
						.map(step -> step.read(index))
						.orElse(TargetSchedule.Side.MAIN));
				if (filled.contains(half) || !told.add(half)) {
					continue;
				}

				Verdict said = verdict(plan, undrawn, ordered, drawn, at, half, pass.program());
				(said.temporal() ? history : notes).add(said.text());
			}

			filled.addAll(pass.attachments());
		}
	}

	/**
	 * How many of {@code ordered} run before the world's translucents, which is where the chunk pass
	 * that draws them goes in.
	 * <p>
	 * Counted off the ranks rather than off the length of the deferred stage, so that a place
	 * shipping no deferred at all still puts them before its composites instead of at the end. The
	 * final may be on the end of the list and never changes the answer: its rank is above the
	 * deferreds, so the walk has stopped long before reaching it.
	 */
	private static int pastDeferred(List<Pass> ordered) {
		int at = 0;
		while (at < ordered.size() && ordered.get(at).frameRank() <= DEFERRED_RANK) {
			at++;
		}

		return at;
	}

	/**
	 * @param undrawn the targets geometry writes and no pass of this engine fills
	 * @param drawn   the world's own passes, by the point of the frame they are drawn at
	 */
	private static Verdict verdict(TargetPlan plan, Set<Integer> undrawn, List<Pass> ordered,
			Map<Integer, Set<Pass>> drawn, int at, Attachment half, String reader) {
		boolean kept = plan.persistent().contains(half.target());
		String name = TargetName.canonical(half.target());
		String clear = ", so " + reader + " reads what the clear left there";
		String before = ", so " + reader + " reads the frame before, and the clear colour on the "
				+ "first one";

		// WHAT FILLS IT IS ASKED BEFORE WHY IT IS EMPTY, and the order is the whole of this method.
		// These branches do not add lines to one another, they REPLACE one another, so putting the
		// weakest first costs the reader the strongest. Bliss's colortex0 is the case that proved it:
		// a composite of the pack writes it later in the same frame, and because the sky's programs
		// had put it in undrawn the reader was told instead that no geometry of ours reaches the
		// pack's targets - true about the sky, useless about the target, and it had displaced the one
		// sentence that named the real writer.
		String later = writtenLater(ordered, drawn, at, half);
		if (later != null) {
			// Which of the two the reader really gets is the clear directive's answer and never the
			// order's: a target the pack clears is emptied on BOTH halves at the top of every frame,
			// so nothing of the frame before is left there to be read.
			return new Verdict(name + " is not written until " + later + ", later in the same frame"
					+ (kept ? before : clear), kept);
		}

		String off = disabledWriter(plan, half.target());
		if (off != null) {
			return new Verdict(name + " is written by " + off + ", which this place does not run"
					+ clear, false);
		}

		if (undrawn.contains(half.target())) {
			// What ends up here is the geometry no pass of this engine fills in EVERY place: the sky's
			// three, held out by two gates and no longer by one, since a place whose skybox is none
			// opens no sky pass at all and the clouds hang off a pass of their own that the game
			// opens in the overworld alone, the weather, which is drawn only where the level has
			// weather, and every family whose line is off - the entity halves by default, and the
			// terrain, the particles or the seed's riders wherever somebody writes those lines. Why
			// that is the question, and what holding each out was measured to cost, is where the
			// verdicts are handed their map.
			//
			// The particles used to be here and no longer are. The sentence went on saying no geometry
			// of ours reached the pack's targets while a particle program of the pack was writing them,
			// which is a note that reads like a diagnosis and is a lie: Bliss's colortex9 carried it in
			// all three of its places.
			//
			// The tail is flat, and it is flat because of where this branch now sits: nothing later in
			// the frame writes this half, so the pack keeping the target between frames buys the
			// reader nothing - the frame before had nothing written there either.
			return new Verdict(name + " is written by geometry, none of which this engine draws into "
					+ "the pack's targets" + clear, false);
		}

		// Nothing fills this half at any point of the frame. Whether that is a hole or a history
		// buffer is decided by the pack's own clear directive and by nothing else.
		return kept
				? new Verdict("nothing writes the half of " + name + " that " + reader + " reads, and "
						+ "the pack keeps that target between frames" + before, true)
				: new Verdict(name + " is written by no program of this place, so it holds its clear "
						+ "colour for ever, and " + reader + " reads that", false);
	}

	/**
	 * One verdict about a half nothing has filled by the time it is read.
	 *
	 * @param temporal whether the pack keeps that target between frames, so the read lands on what
	 *                 the frame before wrote there rather than on a clear colour. That is a
	 *                 mechanism of the pack, and Iris hands it the same one, so it belongs with the
	 *                 facts and not with what the picture will be wrong about
	 */
	private record Verdict(String text, boolean temporal) {
	}

	/**
	 * The first thing that fills this half after the reader, the world included: a prepare reads
	 * targets the chunk passes fill later in the same frame, and naming the composite that gets to
	 * them afterwards would send the next diagnostic to the wrong end of the chain.
	 * <p>
	 * The bound runs one past the last full screen pass, because a place shipping neither composite
	 * nor final still draws its translucents, and they still fill what they write.
	 */
	private static String writtenLater(List<Pass> ordered, Map<Integer, Set<Pass>> drawn, int at,
			Attachment half) {
		for (int next = at + 1; next <= ordered.size(); next++) {
			// The world goes in ahead of the full screen pass standing at the same point, which is
			// the order the walk fills them in.
			for (Pass drawing : drawn.getOrDefault(next, Set.of())) {
				if (drawing.attachments().contains(half)) {
					return drawing.program();
				}
			}

			if (next < ordered.size() && ordered.get(next).attachments().contains(half)) {
				return ordered.get(next).program();
			}
		}

		return null;
	}

	/** Named rather than lumped in with the targets nobody writes: the pack can switch it back on. */
	private static String disabledWriter(TargetPlan plan, int index) {
		List<String> writers = plan.disabled().entrySet().stream()
				.filter(entry -> plan.writes(entry.getKey()).contains(index))
				.map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
				.toList();

		return writers.isEmpty() ? null : String.join(", ", writers);
	}

	private static String describe(TargetSize size) {
		return size.relative()
				? String.format(Locale.ROOT, "%.3f by %.3f of the screen", size.width(), size.height())
				: (int) size.width() + " by " + (int) size.height() + " pixels";
	}

	public String place() {
		return this.place;
	}

	/** Full screen, frame order, the final EXCLUDED. */
	public List<Pass> passes() {
		return this.passes;
	}

	/**
	 * How many of {@link #passes()} belong before the world's translucents, the deferred stage
	 * included. It is where the renderer cuts the frame in two.
	 * <p>
	 * Answered here and not counted again by the renderer, for the reason this whole class exists:
	 * the same number already decides where {@link #notes()} puts the translucent chunk pass, and
	 * two walks of one list in two files are two chances of cutting the frame at different points
	 * without a word.
	 */
	public int deferredEnd() {
		return pastDeferred(this.passes);
	}

	/** The final. Its attachments are always empty: it writes the game's own target. */
	public Optional<Pass> last() {
		return Optional.ofNullable(this.last);
	}

	/**
	 * Where one chunk pass's outputs belong, in draw buffer order and each on the half the
	 * schedule gives it, or empty when this place cannot answer.
	 * <p>
	 * Keyed by the pass and not by the file that serves it, because the halves are the pass's: the
	 * translucent pass draws after the deferred stage and its targets are on the sides the
	 * deferreds leave them, even when the very same file serves the solid pass before them.
	 * <p>
	 * <strong>Empty is not a failure, and this is the one list of what puts a file there</strong>,
	 * which every family reads rather than keeping its own: the file could not be read at all; it
	 * writes more than the eight draw buffers one pass carries; it names the same target twice, and
	 * one image cannot be two attachments; the schedule holds no step for it; it writes a target
	 * nothing of this place allocates; or its targets are not all the same size, which one render
	 * pass cannot carry. All of them but the first are said in {@link #notes()} - the first returns
	 * before anything is added, and the line naming it belongs to {@code TargetPlan.notes()}, which
	 * is a different list.
	 * <p>
	 * <strong>A pack that declares no draw buffer is not one of them and used to be</strong>: it is
	 * answered colortex0, as Iris answers it. Measured on the corpus in August 2026, no place answers
	 * empty for any family at all.
	 */
	public Optional<Pass> geometry(TerrainPass pass) {
		return Optional.ofNullable(this.terrainKeys.get(pass)).map(this.attachments::get);
	}

	/**
	 * The same answer for any geometry program at all, asked by the file that serves it and by the
	 * side of the deferred stage it draws on.
	 * <p>
	 * This is the question the three families share, and {@link #geometry} and {@link #sky} are
	 * shorthands for it: the terrain knows its pass, the sky knows its name, and a family that knows
	 * neither, which is every family the game hands over as a render type, asks here. Empty covers
	 * the list {@link #geometry} carries, plus the one that matters most for a caller holding a name
	 * from the game: this place was never asked about that program, so nothing was walked for it.
	 * <p>
	 * The entities are the family that asks it, once per program the game hands them, and they were
	 * not there when it was written: it was put in place and proved on its own first, which is what
	 * a question three families share is worth doing.
	 */
	public Optional<Pass> geometryOf(String servedBy, boolean afterDeferred) {
		return Optional.ofNullable(this.attachments.get(new Key(servedBy, afterDeferred)));
	}

	/**
	 * Where one sky program's outputs belong, in draw buffer order and each on the half the schedule
	 * gives it, or empty when this place cannot answer.
	 * <p>
	 * Empty covers the list {@link #geometry} carries, plus one of its own: a pack that ships no sky
	 * program at all and whose fallback tree leads nowhere. None of them is a failure, and a place
	 * that cannot answer leaves the game drawing its own sky.
	 *
	 * @param program the bare name, {@code gbuffers_skybasic}, not the file that ends up serving it
	 */
	public Optional<Pass> sky(String program) {
		return Optional.ofNullable(this.skyKeys.get(program)).map(this.attachments::get);
	}

	public Optional<Seed> seed() {
		return Optional.ofNullable(this.seed);
	}

	/**
	 * Whether a pass's FIRST output lands exactly where the scene seed is painted, target and half.
	 * <p>
	 * It is the one condition under which a family drawn before the deferred stage may take over a
	 * draw the game would otherwise make into its own target: the renderer opens that pass with one
	 * attachment of its own, so an engine that redirects the draw can only redirect the first output,
	 * and the only target where that is not a loss is the one already carrying the game's frame.
	 * <p>
	 * Answered here because two readers need the same answer and they are far apart. The one that
	 * acts on it is {@code render/ParticleDraw}, which hands the opaque half back to the game when
	 * this is false; the one that reports on it is {@link #verdicts}, which must not count a family's
	 * targets as filled where the draw was handed back. Bliss is the pack that separates them: its
	 * seed is colortex1 and its particle program writes colortex2 first, so the opaque half is
	 * refused in all three of its places while the translucent half, which has no such condition,
	 * draws.
	 */
	public static boolean leadsWithSeed(Seed seed, Pass pass) {
		if (seed == null || pass == null || pass.attachments().isEmpty()) {
			return false;
		}

		Attachment first = pass.attachments().get(0);

		return seed.target() == first.target() && seed.side() == first.side();
	}

	/** ALT to MAIN at end of frame: still flipped, and kept between frames. */
	public List<Integer> swapBack() {
		return this.swapBack;
	}

	/** Why the chain must not be drawn at all. Empty means it may be. */
	public List<String> refusals() {
		return this.refusals;
	}

	/** What will be wrong once it is drawn, in the pack's own terms. */
	public List<String> notes() {
		return this.notes;
	}

	/**
	 * The reads that land on what the frame before wrote, in the pack's own terms. Facts about the
	 * pack and not faults of this engine, which is why they are handed back apart from
	 * {@link #notes()}.
	 * <p>
	 * They used to be in that list, and the list is introduced to the reader as what the picture
	 * will be wrong about. BSL's {@code colortex2} is the case that proved the cost: its line says
	 * the target is not written until {@code composite7} and that {@code composite5} therefore reads
	 * the frame before, which is exactly the temporal chain the pack is built on and exactly what
	 * Iris gives it. Read among the faults it became a suspect, and stayed one for a fortnight.
	 */
	public List<String> history() {
		return this.history;
	}
}
