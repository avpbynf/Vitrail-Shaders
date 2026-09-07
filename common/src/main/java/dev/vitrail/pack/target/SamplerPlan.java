package dev.vitrail.pack.target;

import dev.vitrail.pack.model.TargetName;
import dev.vitrail.pack.model.ProgramNames;
import dev.vitrail.pack.texture.CustomImages;

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
 * and be given something harmless.
 * <p>
 * <strong>A full screen program is the one place where a name nothing answers for is given the
 * scene on purpose</strong>, because that is the rule its pack was written against and the reason
 * is in {@link #of(List, Map, TargetPlan, Optional, Set, Kind, boolean)}. It is not given quietly:
 * {@link Binding#defaulted} marks every name that took it, {@link #defaulted()} lists them, and
 * the caller prints that list once per program. A name given the scene without being named would
 * draw a program that looks as though it works.
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

	/**
	 * The names that read the light's own depth. The two spelled {@code HW} are the same two images
	 * as {@code shadowtex0} and {@code shadowtex1}, under a second name, and that is the whole of
	 * what SEPARATE_HARDWARE_SAMPLERS is: Iris hands the plain names the texture's own state and the
	 * {@code HW} names a sampler carrying GL_COMPARE_REF_TO_TEXTURE
	 * ({@code samplers/IrisSamplers.java:147-152} and {@code :179-183}), so a pack can read one map
	 * both compared and plain in the one program, which one name never could.
	 * <p>
	 * Which of the two roads a name takes is settled here by the pack's own declaration and not by a
	 * flag, because that is already this engine's rule: the comparison rides the binding wherever the
	 * pack spelled the type {@code sampler2DShadow} over a name of the shadow map's, which is what
	 * {@code GlslTranslator.collectComparisonSamplers} decides and {@code render/ShadowCompare} then
	 * carries. A pack posing the flag declares the plain pair {@code sampler2D} and the {@code HW}
	 * pair {@code sampler2DShadow}, so it gets Iris's arrangement out of its own text, with nothing
	 * to switch.
	 * <p>
	 * The divergence that leaves is which packs see the names at all. Iris binds an {@code HW} name
	 * only where the pack both poses the flag and asks for hardware filtering on that index; here
	 * both are served to anyone who spells them. What that costs is a pack reading a real shadow map
	 * where Iris would have left the sampler unbound, and nothing else: the plain pair is untouched,
	 * and a pack that never writes the names cannot tell the difference.
	 */
	private static final Set<String> SHADOW_DEPTH = Set.of("shadowtex0", "shadowtex1", "shadow",
			"watershadow", "shadowtex0HW", "shadowtex1HW");

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

	/**
	 * The highest a {@code shadowcolor} name goes, and deliberately not the ceiling the pack it
	 * belongs to may reach: that one is {@link TargetPlan#shadowCeiling} and it decides what is
	 * ALLOCATED. A name above the pack's own ceiling is still a shadow colour here, and what it
	 * reads is the white stand-in, which is the answer every buffer nothing filled already gets.
	 */
	private static final int MAX_SHADOW_COLOURS = PackDirectives.MAX_SHADOW_COLOURS;

	/** The colour target a full screen program reads under a name nothing else answers for. */
	public static final int DEFAULT_TARGET = 0;

	/**
	 * The one declared type the default sampler is moved onto, spelled out rather than reduced to a
	 * shape: {@link SamplerTypes#shapeOf} strips the integer and unsigned prefixes
	 * ({@code SamplerTypes.java:59-63}), so a shape of {@code 2D} also covers {@code isampler2D}
	 * and {@code usampler2D}, which read the same unit through a different sampling type and come
	 * back with something that is not the scene.
	 */
	private static final String FLAT = "sampler2D";

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
		DISTANT_DEPTH, CUSTOM_IMAGE, UNSERVED, UNBINDABLE
	}

	/**
	 * What one sampler name of the program is bound to.
	 *
	 * @param index     the colour target for {@link Kind#COLORTEX}, the shadow colour target for
	 *                  {@link Kind#SHADOW_COLOUR}, -1 otherwise. The two families are numbered apart
	 *                  and never share a texture, so the kind has to be read before the index means
	 *                  anything
	 * @param defaulted whether the name got what it got as the default sampler of a full screen
	 *                  program rather than by naming it. The kind is then whatever {@code colortex0}
	 *                  resolves to, so the binding side needs no second rule; what this flag is for
	 *                  is telling the log, and telling a supplied texture apart from one the name
	 *                  really asked for, since the file was named against {@code colortex0} and has
	 *                  to be looked up under that name and not under this one
	 */
	public record Binding(String sampler, Kind kind, int index, TargetSchedule.Side side,
			boolean defaulted) {
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
		return classify(name, type, supplied, CustomImages.names());
	}

	/**
	 * The same, told the pack's image names instead of asking the registry for them.
	 *
	 * @param images every name an {@code image.} directive of THIS pack hangs on,
	 *               {@link CustomImages#namesOf} being where they come from either way. The
	 *               registry is installed by the translation, so a caller running before it, the
	 *               target plan above all, would otherwise be answered for the pack before
	 */
	public static Kind classify(String name, String type, Set<String> supplied, Set<String> images) {
		if (images.contains(name)) {
			return Kind.CUSTOM_IMAGE;
		}

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
	 * <p>
	 * The full screen flag is read off the program's own name, so the default sampler is on for a
	 * composite and off for a gbuffers pass. <strong>No name takes it here whatever the family</strong>,
	 * because no types are handed in and a name the reader could not type takes no default: what
	 * this overload reports for a pack that reads the screen under a name of its own is the
	 * {@link Kind#UNSERVED} the name has before a type is known, not the scene.
	 *
	 * @param declared the sampler names the translated program declares, in that order
	 * @param program  the program the plan is for, so the flip snapshot is the right one
	 */
	public static SamplerPlan of(List<String> declared, TargetPlan plan, String program) {
		return of(declared, Map.of(), plan, program);
	}

	/**
	 * The same, with the types the reader managed to put on those names. The full screen flag is
	 * still the program's own name, and a name typed {@code sampler2D} that nothing else answers
	 * for now takes the default in the families that have one.
	 *
	 * @param types the declared type of each name. A name missing from it is one the reader could
	 *              not type, and is taken at its word rather than refused on a guess, except for
	 *              the default sampler, which no untyped name takes
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			String program) {
		return of(declared, types, plan, program, Set.of(), Set.of());
	}

	/**
	 * The same, told which names the pack supplies a file for and nothing about what the pack lays
	 * over {@code colortex0}, so the default sampler stands on the colour target. That is the
	 * answer a reader measuring what a pack takes over under its own names wants, the file behind
	 * the first target changing none of those.
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			String program, Set<String> supplied) {
		return of(declared, types, plan, program, supplied, Set.of());
	}

	/**
	 * The same, told which of those names a flat picture stands on. The full screen flag is the
	 * program's own name here too, and what the default sampler stands on is decided from those
	 * names: a picture the pack laid over {@code colortex0} for the stage stands there instead of
	 * the target.
	 *
	 * @param supplied every name the pack supplies a file for at this program's stage. What is
	 *                 still standing by the time this program draws is worked out here rather than
	 *                 handed in, because it is the plan that knows
	 * @param pictures the ones of those the default sampler can stand on, which
	 *                 {@link #byDefault} says the rest of
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			String program, Set<String> supplied, Set<String> pictures) {
		// The picture is narrowed the way the declared override is, and by the same walk: Iris
		// deactivates an override once its target has been flipped, whichever road reads it
		// (gl/program/ProgramSamplers.java:258).
		return of(declared, types, plan, plan.schedule().step(program),
				standing(plan, program, supplied),
				byDefault(standing(plan, program, pictures)), fullscreen(program));
	}

	/**
	 * The same binding with the step handed in rather than looked up, which is what a chunk pass
	 * needs: its halves are the pass's and not the file's. The translucent pass reads its colour
	 * targets on the sides the deferred stage leaves them, and looking the file up in the schedule
	 * would answer for the wrong side of that boundary.
	 * <p>
	 * <strong>There is no full screen flag to read off a step, so this overload turns the default
	 * sampler OFF.</strong> That is the answer these callers want rather than a shortcut: the step
	 * is handed in exactly by the geometry programs, which reserve their first texture units for
	 * the game's own atlas and have no default unit to spare
	 * ({@code samplers/IrisSamplers.java:36} against {@code :39}).
	 *
	 * @param step where the reader stands in the frame, deciding the half of every colour target.
	 *             Empty falls back to MAIN everywhere, as it always has
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			Optional<TargetSchedule.Bound> step) {
		return of(declared, types, plan, step, Set.of());
	}

	/**
	 * The same, with the program's step in the schedule and its overrides already worked out. The
	 * default sampler is OFF here as well, and for the same reason: a step names no family.
	 *
	 * @param supplied the names the pack supplies a file for, already narrowed to this program's
	 *                 stage and to the overrides that still stand
	 */
	public static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			Optional<TargetSchedule.Bound> step, Set<String> supplied) {
		return of(declared, types, plan, step, supplied, Kind.UNSERVED, false);
	}

	/**
	 * The same, told whether the program is drawn over the whole screen.
	 * <p>
	 * <strong>What the flag decides is the name nothing else answers for.</strong> A full screen
	 * program of Iris is built with no reserved texture unit at all
	 * ({@code samplers/IrisSamplers.java:39}), and {@code colortex0} is handed the first one through
	 * {@code addDefaultSampler} ({@code :93-95}), which takes unit nought whatever names the program
	 * carries and refuses to run anywhere else
	 * ({@code gl/program/ProgramSamplers.java:155-162}). Every sampler uniform Iris then never
	 * assigns a unit to keeps the value GLSL gives it, which is nought, so it reads whatever unit
	 * nought holds. That is the whole of OptiFine's rule that the first colour target is the
	 * default texture of a composite, and Iris never binds the geometry names over it either: the
	 * composite and the final renderers ask for the render target samplers alone
	 * ({@code pipeline/CompositeRenderer.java:398}, {@code pipeline/FinalPassRenderer.java:368}) and
	 * call {@code addLevelSamplers} nowhere.
	 * <p>
	 * A descriptor set has no such default: a name is bound or the draw throws, so a name nothing
	 * served used to be given one black texel. That reads as a rule until a pack writes its chain
	 * against the OptiFine one. I Like Vanilla calls the screen {@code tex} throughout
	 * ({@code shaders/basics/common.glsl:59}) and names {@code colortex0} only in directives, in
	 * the format block it keeps commented ({@code shaders/basics/settings.glsl:9}) and in the
	 * mipmap flag of the pass that reads the screen at a level
	 * ({@code shaders/program/composite10.glsl:7}, whose body fetches {@code tex} at that level):
	 * the one target it never declares a sampler for is the one every pass of its chain reads. All
	 * of them read black, and the picture the pack presents is exactly zero.
	 * <p>
	 * <strong>What unit nought holds is not always the colour target</strong>, so the name nothing
	 * answers for is given whatever the default sampler itself stands on, standing overrides and
	 * all. A pack may lay a flat picture of its own over {@code colortex0} for a stage, and Iris
	 * then replaces the default sampler by it rather than binding the target
	 * ({@code gl/program/ProgramSamplers.java:298-306}); the defaulted name follows, which is why
	 * {@link Binding#defaulted} exists, the file having been named against {@code colortex0}.
	 * <p>
	 * <strong>A picture is the one shape that reaches it, and the two engines agree on that.</strong>
	 * Only the picture form of {@code texture.<stage>.<name>} enters the map the interceptor looks
	 * in ({@code ShaderProperties.java:485-486}); a declaration that spells a raw blob out, the 1D,
	 * the rectangle and the volume alike, goes to the renaming road instead ({@code :480}), which
	 * retypes the declaration naming it and never touches a name nothing serves. {@link #byDefault}
	 * counts the same one form, so a blob laid over {@code colortex0} leaves the default sampler on
	 * the target on both sides. Photon lays a 64 cube of noise there for its composites
	 * ({@code shaders/shaders.properties:357}) and its composites go on reading the colour target
	 * under that name in either engine, with no defaulted name at all besides.
	 * <p>
	 * Only a plain two dimensional sampler takes the default, and only one whose type was really
	 * read: a name the reader could not type is left alone, since the name is not evidence for a
	 * question that is about a name nothing answers for. A cube or a shadow declaration under an
	 * unknown name would read unit nought through its own type, where Iris put a 2D texture and
	 * nothing else, so black is what it reads there as well; a storage image is not a sampler at
	 * all and keeps its own road. A volume the backend refuses stays refused, and a texture of the
	 * pack's own that could not be read stays black, both for the reasons they already carry.
	 *
	 * @param stands     what the default sampler stands on for this program, which is a property of
	 *                   the program and not of any name: what a reader with no unit of its own lands
	 *                   on is one texture unit, whatever the pack called it
	 * @param fullscreen whether the program covers the screen, which is the begin, prepare,
	 *                   deferred, composite and final families
	 */
	private static SamplerPlan of(List<String> declared, Map<String, String> types, TargetPlan plan,
			Optional<TargetSchedule.Bound> step, Set<String> supplied, Kind stands,
			boolean fullscreen) {
		List<Binding> bindings = new ArrayList<>();
		Kind byDefault = fullscreen ? stands : Kind.UNSERVED;
		Set<String> images = CustomImages.names();

		for (String name : declared) {
			String type = types.get(name);
			Kind kind = classify(name, type, supplied, images);
			if (fullscreen && takesDefault(name, type, supplied, images)) {
				// Through the same allocation test the target's own name goes through below, and
				// for the same reason: a plan that decides everything before a frame has nothing to
				// bind for an index nothing allocated. TargetPlan allocates it wherever a full
				// screen program of the place declares a name that lands here, which is where Iris
				// creates it on demand (samplers/IrisSamplers.java:60-62 and
				// targets/RenderTargets.java:118), so the target is there whenever a pack asks for
				// it; the name falls back to the black texel where it is not, on the pass road and
				// on the compute road both, rather than the plan handing out an index the binding
				// then has to refuse.
				if (byDefault == Kind.COLORTEX && plan.allocated().contains(DEFAULT_TARGET)) {
					bindings.add(new Binding(name, Kind.COLORTEX, DEFAULT_TARGET,
							side(step, DEFAULT_TARGET), true));
					continue;
				}

				if (byDefault == Kind.PACK_TEXTURE) {
					bindings.add(new Binding(name, Kind.PACK_TEXTURE, -1,
							TargetSchedule.Side.MAIN, true));
					continue;
				}
			}

			if (kind == Kind.SHADOW_COLOUR) {
				bindings.add(new Binding(name, kind, shadowColour(name), TargetSchedule.Side.MAIN,
						false));
				continue;
			}

			if (kind != Kind.COLORTEX) {
				bindings.add(new Binding(name, kind, -1, TargetSchedule.Side.MAIN, false));
				continue;
			}

			int index = TargetName.index(name).orElse(-1);

			// A target no program of this dimension writes or samples was never allocated, so
			// there is nothing to bind. Saying so by name is the whole difference between a gap
			// and a wrong image.
			if (!plan.allocated().contains(index)) {
				bindings.add(new Binding(name, Kind.UNSERVED, -1, TargetSchedule.Side.MAIN, false));
				continue;
			}

			bindings.add(new Binding(name, Kind.COLORTEX, index, side(step, index), false));
		}

		return new SamplerPlan(bindings);
	}

	/** Which half of one colour target a reader standing at this step reads. */
	private static TargetSchedule.Side side(Optional<TargetSchedule.Bound> step, int index) {
		return step.map(bound -> bound.read(index)).orElse(TargetSchedule.Side.MAIN);
	}

	/**
	 * Whether a declaration reads the one texture unit the default sampler fills, which is a plain
	 * two dimensional one and nothing else.
	 * <p>
	 * The type has to be KNOWN and it has to be exactly that one, which is the opposite of the rule
	 * the rest of this class follows for a name it could not type. Everywhere else an untyped name
	 * is taken at its word because the name is evidence; here there is no name to go on, the whole
	 * question being what to do with a name nothing answers for, so a reader that saw no type saw
	 * nothing at all. Letting one through moved the default onto declarations of every shape under
	 * a caller that hands in no types: Reverie's {@code sampler3D worleyNoiseTexture} read as
	 * colour target nought that way.
	 */
	private static boolean flat(String type) {
		return FLAT.equals(type);
	}

	/**
	 * Whether a name a full screen program declares falls to the default sampler, asked in the one
	 * place so that what is ALLOCATED for the default and what is BOUND to it cannot drift apart.
	 * {@code TargetPlan} asks it while the plan is built and {@link #of} asks it again per program.
	 * <p>
	 * Two questions stand between them and have to be answered the same way twice. The type: the
	 * allocation reads the pack's own text, the binding reads a translated unit, and a translation
	 * may declare ordinary a comparison sampler it had to move into arithmetic. The name is handed
	 * in untyped there rather than under the type the rewrite left ({@code PackProgram.typesIn}),
	 * so a comparison is a comparison on both sides and neither moves it. And the pack's images,
	 * which is why they are handed in: the registry behind {@link CustomImages#named} is installed
	 * by the translation, so the allocation reads the directives itself and both ends are answered
	 * for the pack in hand.
	 *
	 * @param type     the declared type as the PACK wrote it, or null for a name that could not be
	 *                 typed, which takes no default
	 * @param supplied the names the pack supplies a file for at this program's stage
	 * @param images   the names the pack's own {@code image.} directives hang on
	 */
	public static boolean takesDefault(String name, String type, Set<String> supplied,
			Set<String> images) {
		return classify(name, type, supplied, images) == Kind.UNSERVED && flat(type);
	}

	/**
	 * What the default sampler stands on at a stage: the colour target, or the flat picture the
	 * pack laid over its name there. {@link Kind#COLORTEX} is the only answer that needs an
	 * allocation.
	 *
	 * @param pictures the names the pack lays a flat picture over IN THIS STAGE, which is the one
	 *                 form of override that reaches the default sampler and which
	 *                 {@code PackTextures.picturesTo} answers for. Iris takes that form of
	 *                 {@code texture.<stage>.<name>} into the map its interceptor replaces the
	 *                 default sampler out of ({@code ShaderProperties.java:485-486} into
	 *                 {@code pipeline/CustomTextureManager.java:56-67}); a raw blob goes to the
	 *                 renaming road at {@code ShaderProperties.java:480} instead and the default
	 *                 sampler stays on the target, and so does the whole {@code customTexture.}
	 *                 family, which names no stage and is bound by name alone
	 *                 ({@code samplers/IrisSamplers.java:237-239})
	 */
	public static Kind byDefault(Set<String> pictures) {
		return pictures.contains(TargetName.canonical(DEFAULT_TARGET))
				? Kind.PACK_TEXTURE
				: Kind.COLORTEX;
	}

	/**
	 * Whether a program is drawn over the whole screen, which is the one thing the default sampler
	 * turns on. Read off the family the way {@link #standing} reads it, so the two answers cannot
	 * drift apart.
	 * <p>
	 * A compute file answers yes through its own family, {@code composite1_a} parsing as a
	 * composite, and that is wanted rather than tolerated: Iris builds the computes of a stage with
	 * the same call and the same full screen flag as the passes they hang off
	 * ({@code pipeline/CompositeRenderer.java:454} beside {@code :398}), so the name that reads the
	 * screen in {@code composite1.fsh} reads it in {@code composite1_a.csh} too.
	 */
	public static boolean fullscreen(String program) {
		return stageOf(TargetName.bareName(program)) != null;
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
		String bare = TargetName.bareName(program);
		String stage = stageOf(bare);
		if (supplied.isEmpty() || stage == null) {
			return supplied;
		}

		// The pass a compute hangs off, because a compute is in no running order: composite1_a is
		// not a pass and a walk looking for its own name would run to the end of the stage, count
		// the writes of every pass AFTER it, and drop an override the pass beside it keeps. Iris
		// snapshots what has been flipped once it reaches the index and hands that same snapshot to
		// the pass (CompositeRenderer.java:134 taken, :151 given it), to the computes hanging off
		// it (:154) and to a compute whose program draws nothing (:141), so all of them stop here.
		String upTo = ProgramNames.computeBase(bare).orElse(bare);

		// Stopped where the name FALLS in the frame rather than where it is found, because the
		// program a compute hangs off may draw nothing and be in no running order either: Pegasus
		// ships prepare_a and no prepare.fsh, and a walk looking for that name would again run to
		// the end of the stage. This is the moment the chain dispatches such a compute at
		// (PackChain.standaloneOf places it before the first pass that does not sort ahead of it),
		// and the two have to be one answer.
		Set<String> abandoned = new LinkedHashSet<>();
		for (String earlier : plan.running()) {
			if (!ProgramNames.before(earlier, upTo)) {
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

	/** In declaration order, one entry per name, never short. */
	public List<Binding> bindings() {
		return this.bindings;
	}

	/** Never null: a name nothing serves comes back as {@link Kind#UNSERVED}. */
	public Binding binding(String sampler) {
		Binding found = this.byName.get(sampler);

		return found == null
				? new Binding(sampler, Kind.UNSERVED, -1, TargetSchedule.Side.MAIN, false)
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

	/**
	 * The names that took the default sampler without asking for it, in declaration order.
	 * <p>
	 * Meant to be printed once for the program: it is the one binding a reader cannot work out from
	 * the pack's own text, since the pack never wrote {@code colortex0} beside these names, and it
	 * decides whether a pass reads the scene or reads nothing. Empty for everything that is not
	 * drawn over the whole screen.
	 */
	public List<String> defaulted() {
		return this.bindings.stream()
				.filter(Binding::defaulted)
				.map(Binding::sampler)
				.toList();
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
	 * {@code shadowtex1} is always that one and {@code shadowtex0} is never it, and the {@code HW}
	 * spelling of each reads the image its plain twin reads. The pair is the whole
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
		return name.equals("shadowtex1") || name.equals("shadowtex1HW")
				|| (this.waterShadow && name.equals("shadow"));
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

	/** Whether a name reads one of the light's colour targets, the bare {@code shadowcolor} included. */
	public static boolean isShadowColour(String name) {
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
