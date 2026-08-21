package dev.vitrail.pack.target;

import dev.vitrail.pack.program.ProgramNames;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What every sampler a program declares is bound to.
 * <p>
 * It is total by construction, and that is the point rather than a nicety. The list of names
 * here is the same list, in the same order, that builds the bind group layout, and the Vulkan
 * backend throws {@code Missing sampler} the moment a name in the layout is not bound. So a name
 * this engine has no answer for cannot be dropped; it has to come back as {@link Kind#UNSERVED}
 * and be given something harmless, and it has to be named in the log rather than quietly given
 * the scene, which draws a program that looks as though it works.
 * <p>
 * The side of a colour target is answered here too, from the schedule and for this program, so
 * that no caller has to work out where the ping pong stands.
 * <p>
 * A name is only asked about once its declared type has been accepted, and the order matters:
 * Mellow declares {@code sampler3D colortex6} in a shared include, and reading the name first would
 * hand a three dimensional sampler a real 2D view of colour target six.
 */
public final class SamplerPlan {

	private static final Set<String> DEPTH = Set.of("depthtex0", "depthtex1", "depthtex2", "gdepthtex");
	private static final Set<String> SHADOW_DEPTH =
			Set.of("shadowtex0", "shadowtex1", "shadow", "watershadow");

	/**
	 * The depth Distant Horizons keeps apart from the game's, kept apart here as well, which is
	 * Iris's arrangement: it hands {@code dhDepthTex} and {@code dhDepthTex0} DH's own depth and
	 * {@code dhDepthTex1} a copy of it without translucents, {@code samplers/IrisSamplers.java:109}
	 * and {@code :110}. It has to: under Iris the far terrain is nowhere else, its LODs being drawn
	 * into targets of the pack's through a projection of DH's, and the road a pack keeps for its
	 * distant land - BSL v10.1.3 reaches its at {@code shaders/program/composite.glsl:282} with
	 * {@code else if (dhZ0 < 1.0)} - opens on these names and on nothing else.
	 * <p>
	 * Here they answer the image the far terrain was really drawn into, converted into the window
	 * the pack reads like every other depth, on the frames the pack drew a far terrain; and the far
	 * plane, WHITE in that window, on all the others, so that the road above stays shut exactly as
	 * it does without the mod. {@code render/PackDepth} carries the pair of images and their two
	 * moments, and {@code render/ViewMatrices} the volume the matrices under these names unproject.
	 */
	private static final Set<String> DISTANT_DEPTH =
			Set.of("dhDepthTex", "dhDepthTex0", "dhDepthTex1");
	private static final String SHADOW_COLOUR_PREFIX = "shadowcolor";
	private static final String NOISE = "noisetex";
	private static final String WATER_SHADOW = "watershadow";

	/**
	 * What the translation calls a name it has moved off its own declaration and onto a texture the
	 * pack ships.
	 * <p>
	 * A volume cannot be served by binding something else to the name: the shape of the declaration
	 * is what the backend refuses, so the declaration itself has to change, and once it has, the
	 * name is the translation's own. Forged rather than kept, so that this class recognises it on
	 * sight and no table has to be carried from the translation to the binding and kept in step.
	 */
	private static final String FORGED = "ofPackTexture_";

	/**
	 * The one texel image the translation moves {@code centerDepthSmooth} onto.
	 * <p>
	 * The value is accumulated on the card and never comes back, so the pack cannot be handed it as a
	 * member of the uniform block: what replaces the name is a lookup, and a lookup needs a sampler.
	 * That is Iris's own answer, {@code CompositeDepthTransformer}, under a name of ours.
	 */
	private static final String CENTER_DEPTH = "ofCenterDepthSmooth";

	/** Iris allows eight, and the corpus stops at three. */
	private static final int MAX_SHADOW_COLOURS = 8;

	private final List<Binding> bindings;
	private final Map<String, Binding> byName;
	private final boolean waterShadow;

	private SamplerPlan(List<Binding> bindings) {
		this.bindings = List.copyOf(bindings);

		Map<String, Binding> byName = new LinkedHashMap<>();
		bindings.forEach(binding -> byName.putIfAbsent(binding.sampler(), binding));
		this.byName = Map.copyOf(byName);
		this.waterShadow = byName.containsKey(WATER_SHADOW);
	}

	/**
	 * {@link #UNSERVED} is a name this engine has nothing to put behind; {@link #UNBINDABLE} is a
	 * declaration the API cannot express at all. The two are not the same failure and are worth
	 * telling apart: the first costs one black pixel, the second costs the whole program.
	 * <p>
	 * {@link #PACK_TEXTURE} is neither: it is a file the pack ships, under a name of its own or
	 * over a name that already meant something else.
	 */
	public enum Kind {
		COLORTEX, DEPTH, SHADOW_DEPTH, SHADOW_COLOUR, NOISE, PACK_TEXTURE, CENTER_DEPTH,
		DISTANT_DEPTH, UNSERVED, UNBINDABLE
	}

	/**
	 * What one sampler name of the program is bound to.
	 *
	 * @param index the colour target for {@link Kind#COLORTEX}, the shadow colour target for
	 *              {@link Kind#SHADOW_COLOUR}, -1 otherwise. The two families are numbered apart and
	 *              never share a texture, so the kind has to be read before the index means anything
	 */
	public record Binding(String sampler, Kind kind, int index, TargetSchedule.Side side) {
	}

	/**
	 * The type first and the name second, in that order and never the other way round.
	 *
	 * @param type what the declaration says, {@code sampler3D}, or null when the reader had only
	 *             the name to go on and cannot answer for the type
	 */
	public static Kind classify(String name, String type) {
		return classify(name, type, Set.of());
	}

	/**
	 * The type, then what the pack supplies, then the name. The middle step is the one that has to
	 * sit where it does: {@code texture.composite.colortex3} of Mellow puts a SMAA lookup table
	 * behind a name that is also a real colour target, and reading the name first would hand the
	 * composites a quarter resolution copy of the scene as a lookup table. It stays after the type
	 * for the reason it always did, and a declaration that is still three dimensional once the
	 * translation is done falls exactly as it fell before.
	 *
	 * @param supplied the names the pack supplies a file for in this program's stage, already
	 *                 narrowed to it
	 */
	public static Kind classify(String name, String type, Set<String> supplied) {
		if (type != null && SamplerTypes.refused(type)) {
			return Kind.UNBINDABLE;
		}

		return supplied.contains(name) ? Kind.PACK_TEXTURE : classify(name);
	}

	/**
	 * The two depth copies of the OptiFine model, as opposed to the live depth. Both hold the world
	 * without its translucents; what CAN separate them is the player's own hand, which
	 * {@code depthtex1} carries and {@code depthtex2} does not, and {@link #preHandCopy} is that
	 * second question.
	 * <p>
	 * Can, and only where this engine draws the hand itself, which is the {@code hand} line of
	 * {@code vitrail/options.txt} and not anything a pack asks for. That line is on unless somebody
	 * writes it off, and written off the hand goes back to the game's late call, after the whole
	 * chain has run, where NEITHER copy carries it, the two being one image.
	 * <p>
	 * At the default the two engines agree. Iris draws the hand inside the level for any pack it
	 * loads ({@code pathways/HandRenderer.java:95-98} from
	 * {@code mixin/MixinLevelRenderer.java:280}), on the frames the game would have drawn one and
	 * whose hand has a solid half at all, and so does this. Two hands holding translucent blocks are
	 * the exception on its side too: the solid guard turns it away and the whole hand goes to the
	 * translucent call, which is behind the copy. What can still hold the family off here is the
	 * switch alone and nothing about this engine's API:
	 * {@code EngineOptions} carries the default and {@code EngineOptions.announceHandOff} writes out
	 * what it costs, once per load, for the player looking at the picture it produced.
	 */
	public static boolean depthCopy(String name) {
		return name.equals("depthtex1") || name.equals("depthtex2");
	}

	/**
	 * Whether a name reads the copy taken before the player's own hand was drawn, which is
	 * {@code depthtex2} and nothing else.
	 * <p>
	 * Iris takes that copy in {@code RenderTargets.copyPreHandDepth}
	 * ({@code targets/RenderTargets.java:234}), called from a {@code beginHand} that draws no hand
	 * ({@code pipeline/IrisRenderingPipeline.java:1050-1057}). The solid hand is drawn on the line
	 * after the call to it ({@code mixin/MixinLevelRenderer.java:279-280}) and the
	 * {@code beginTranslucents} that copies {@code depthtex1} comes one step behind both, which is
	 * the order that lets a pack read past the hand it is holding.
	 * <p>
	 * This engine takes the copy at the same point of the frame, and not as often. Iris copies on
	 * every frame, a hand drawn or not; this copies on the frames it really draws one, the two
	 * moments holding the same depth on all the others. {@code render/PackDepth} carries what that
	 * saves and what it leaves standing.
	 */
	public static boolean preHandCopy(String name) {
		return name.equals("depthtex2");
	}

	/**
	 * Whether a name reads the far terrain's depth as it stood before its water half was drawn,
	 * which is {@code dhDepthTex1} and nothing else, mirroring what {@code depthtex1} is to the
	 * world. Iris keeps that copy in {@code depthTexNoTranslucent}
	 * ({@code compat/dh/DHCompatInternal.java:260-269}), taken before DH's translucent LODs render;
	 * the other two names follow the half the reading pass stands in, like {@code depthtex0}.
	 */
	public static boolean distantWithoutWater(String name) {
		return name.equals("dhDepthTex1");
	}

	/** What a name becomes once the translation has moved it onto a file the pack ships. */
	public static String forged(String sampler) {
		return FORGED + sampler;
	}

	/** What the translation declares in place of {@code centerDepthSmooth}, and what binds the texel. */
	public static String centerDepth() {
		return CENTER_DEPTH;
	}

	/** The pack's own name behind a forged one, or the name as it stands. */
	public static String behind(String sampler) {
		return sampler.startsWith(FORGED) ? sampler.substring(FORGED.length()) : sampler;
	}

	public static Kind classify(String name) {
		// First, because it is the only answer that can be right: nothing but the translation
		// produces this prefix, and it only produces it for a name it has already moved.
		if (name.startsWith(FORGED)) {
			return Kind.PACK_TEXTURE;
		}

		// And for the same reason: only the translation writes this name, and only where it has taken
		// centerDepthSmooth off its declaration.
		if (name.equals(CENTER_DEPTH)) {
			return Kind.CENTER_DEPTH;
		}

		if (TargetName.index(name).isPresent()) {
			return Kind.COLORTEX;
		}

		if (DEPTH.contains(name)) {
			return Kind.DEPTH;
		}

		if (SHADOW_DEPTH.contains(name)) {
			return Kind.SHADOW_DEPTH;
		}

		if (DISTANT_DEPTH.contains(name)) {
			return Kind.DISTANT_DEPTH;
		}

		if (isShadowColour(name)) {
			return Kind.SHADOW_COLOUR;
		}

		return name.equals(NOISE) ? Kind.NOISE : Kind.UNSERVED;
	}

	/**
	 * Works out what each sampler the program declares will be bound to.
	 *
	 * @param declared the sampler names the translated program declares, in that order
	 * @param program  the program the plan is for, so the flip snapshot is the right one
	 */
	public static SamplerPlan of(List<String> declared, TargetPlan plan, String program) {
		return of(declared, Map.of(), plan, program);
	}

	/**
	 * The same, with the types the reader managed to put on those names.
	 *
	 * @param types the declared type of each name. A name missing from it is one the reader could
	 *              not type, and is taken at its word rather than refused on a guess
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			String program) {
		return of(declared, types, plan, program, Set.of());
	}

	/**
	 * The same, told which names the pack supplies a file for.
	 *
	 * @param supplied every name the pack supplies a file for at this program's stage. What is
	 *                 still standing by the time this program draws is worked out here rather than
	 *                 handed in, because it is the plan that knows
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			String program, Set<String> supplied) {
		return of(declared, types, plan, plan.schedule().step(program),
				standing(plan, program, supplied));
	}

	/**
	 * The same binding with the step handed in rather than looked up, which is what a chunk pass
	 * needs: its halves are the pass's and not the file's. The translucent pass reads its colour
	 * targets on the sides the deferred stage leaves them, and looking the file up in the schedule
	 * would answer for the wrong side of that boundary.
	 *
	 * @param step where the reader stands in the frame, deciding the half of every colour target.
	 *             Empty falls back to MAIN everywhere, as it always has
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			Optional<TargetSchedule.Bound> step) {
		return of(declared, types, plan, step, Set.of());
	}

	/**
	 * The same, with the program's step in the schedule and its overrides already worked out.
	 *
	 * @param supplied the names the pack supplies a file for, already narrowed to this program's
	 *                 stage and to the overrides that still stand
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			Optional<TargetSchedule.Bound> step, Set<String> supplied) {
		List<Binding> bindings = new ArrayList<>();

		for (String name : declared) {
			Kind kind = classify(name, types.get(name), supplied);
			if (kind == Kind.SHADOW_COLOUR) {
				bindings.add(new Binding(name, kind, shadowColour(name), TargetSchedule.Side.MAIN));
				continue;
			}

			if (kind != Kind.COLORTEX) {
				bindings.add(new Binding(name, kind, -1, TargetSchedule.Side.MAIN));
				continue;
			}

			int index = TargetName.index(name).orElse(-1);

			// A target no program of this dimension writes or samples was never allocated, so
			// there is nothing to bind. Saying so by name is the whole difference between a gap
			// and a wrong image.
			if (!plan.allocated().contains(index)) {
				bindings.add(new Binding(name, Kind.UNSERVED, -1, TargetSchedule.Side.MAIN));
				continue;
			}

			int wanted = index;
			TargetSchedule.Side side = step.map(bound -> bound.read(wanted))
					.orElse(TargetSchedule.Side.MAIN);
			bindings.add(new Binding(name, Kind.COLORTEX, index, side));
		}

		return new SamplerPlan(bindings);
	}

	/**
	 * The overrides that are still standing when this program draws.
	 * <p>
	 * An override on a colour target is ABANDONED once a program of the same stage has written
	 * that target in this frame: the pack put a lookup table behind the name, and from the moment
	 * something in the same stage has drawn into the target, what the pack wants back is what it
	 * just drew. Iris decides this at load time from a snapshot of what has been flipped so far in
	 * the same renderer, one accumulator per stage and the final riding with the composites, so
	 * here the question is static and the plan answers it: {@link TargetPlan#running} is that same
	 * walk in that same order.
	 * <p>
	 * Nothing in the corpus reaches it. BSL's colortex7 is written by no composite, Body Camera's
	 * colortex6 by none, and Mellow's composites write 0, 1, 2 and 4 while its overrides are on 3
	 * and 5. The rule is here for the day one does, because that day the difference is a picture
	 * and not a crash, and nothing would report it.
	 * <p>
	 * Geometry is left alone: Iris hands its terrain and gbuffers programs an empty snapshot, so a
	 * gbuffers override stands for the whole frame however the targets have been flipped.
	 */
	private static Set<String> standing(TargetPlan plan, String program, Set<String> supplied) {
		String bare = bareName(program);
		String stage = stageOf(bare);
		if (supplied.isEmpty() || stage == null) {
			return supplied;
		}

		Set<String> abandoned = new LinkedHashSet<>();
		for (String earlier : plan.running()) {
			if (earlier.equals(bare)) {
				break;
			}

			if (stage.equals(stageOf(earlier))) {
				plan.writes(earlier).forEach(index -> {
					abandoned.add(TargetName.canonical(index));
					TargetName.legacyAlias(index).ifPresent(abandoned::add);
				});
			}
		}

		if (abandoned.isEmpty()) {
			return supplied;
		}

		Set<String> left = new LinkedHashSet<>(supplied);
		left.removeAll(abandoned);

		return left;
	}

	/**
	 * Which of the four full screen stages a program is drawn in, or null for anything that is not
	 * drawn in one of them. The final rides with the composites, which is where Iris puts it.
	 */
	private static String stageOf(String program) {
		String family = ProgramNames.familyOf(program);

		return switch (family) {
			case "begin", "prepare", "deferred", "composite" -> family;
			case "final" -> "composite";
			default -> null;
		};
	}

	private static String bareName(String program) {
		int slash = program.lastIndexOf('/');

		return slash < 0 ? program : program.substring(slash + 1);
	}

	/** In declaration order, one entry per name, never short. */
	public List<Binding> bindings() {
		return this.bindings;
	}

	/** Never null: a name nothing serves comes back as {@link Kind#UNSERVED}. */
	public Binding binding(String sampler) {
		Binding found = this.byName.get(sampler);

		return found == null
				? new Binding(sampler, Kind.UNSERVED, -1, TargetSchedule.Side.MAIN)
				: found;
	}

	public Map<Kind, List<String>> byKind() {
		Map<Kind, List<String>> grouped = new EnumMap<>(Kind.class);
		for (Binding binding : this.bindings) {
			grouped.computeIfAbsent(binding.kind(), _ -> new ArrayList<>()).add(binding.sampler());
		}

		grouped.replaceAll((_, names) -> List.copyOf(names));

		return grouped;
	}

	public List<String> unserved() {
		return named(Kind.UNSERVED);
	}

	/** Names declared under a type no pipeline of this backend can carry. Empty is the norm. */
	public List<String> unbindable() {
		return named(Kind.UNBINDABLE);
	}

	private List<String> named(Kind kind) {
		return this.bindings.stream()
				.filter(binding -> binding.kind() == kind)
				.map(Binding::sampler)
				.toList();
	}

	/**
	 * Whether a shadow depth name reads the map as it stood before the translucents.
	 * <p>
	 * {@code shadowtex1} is always that one and {@code shadowtex0} is never it. The pair is the whole
	 * of what a coloured shadow rests on: a point occluded in nought and clear in one has something
	 * translucent between it and the light, and the tint comes from {@code shadowcolor}.
	 * <p>
	 * <strong>{@code shadow} has no fixed meaning, which is why this is asked of the plan and not of
	 * the name.</strong> A program that also declares {@code watershadow} moves it: {@code watershadow}
	 * then reads the map with the translucents, alongside {@code shadowtex0}, and {@code shadow} falls
	 * back to the one without, alongside {@code shadowtex1}. A program that does not declare it keeps
	 * {@code shadow} on the map with everything in it. That is the rule packs are written against, from
	 * {@code IrisSamplers.addShadowSamplers}, and OptiFine numbers the same one in texture units.
	 * <p>
	 * Iris asks whether the linked program has a live uniform by that name rather than whether the
	 * source declares one, so a declaration that comes in from a shared include and is never read
	 * would move {@code shadow} here and not there. No pack of the corpus writes the name at all.
	 */
	public boolean withoutTranslucents(String name) {
		return name.equals("shadowtex1") || (this.waterShadow && name.equals("shadow"));
	}

	/**
	 * Which shadow colour target a name reads. The bare {@code shadowcolor} is nought, which is the
	 * same rule {@code colortex} follows and the same one Iris follows for both.
	 */
	public static int shadowColour(String name) {
		return name.equals(SHADOW_COLOUR_PREFIX)
				? 0
				: name.charAt(SHADOW_COLOUR_PREFIX.length()) - '0';
	}

	private static boolean isShadowColour(String name) {
		if (name.equals(SHADOW_COLOUR_PREFIX)) {
			return true;
		}

		if (!name.startsWith(SHADOW_COLOUR_PREFIX)) {
			return false;
		}

		String digits = name.substring(SHADOW_COLOUR_PREFIX.length());
		if (digits.length() != 1 || !Character.isDigit(digits.charAt(0))) {
			return false;
		}

		return digits.charAt(0) - '0' < MAX_SHADOW_COLOURS;
	}
}
