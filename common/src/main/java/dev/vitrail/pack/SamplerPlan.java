package dev.vitrail.pack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
 * the scene, which is what made the first pass look as though it worked.
 * <p>
 * The side of a colour target is answered here too, from the schedule and for this program, so
 * that no caller has to work out where the ping pong stands.
 */
public final class SamplerPlan {

	private static final Set<String> DEPTH = Set.of("depthtex0", "depthtex1", "depthtex2", "gdepthtex");
	private static final Set<String> SHADOW_DEPTH = Set.of("shadowtex0", "shadowtex1", "shadow");
	private static final String SHADOW_COLOUR_PREFIX = "shadowcolor";
	private static final String NOISE = "noisetex";

	/** Iris allows eight, and the corpus stops at three. */
	private static final int MAX_SHADOW_COLOURS = 8;

	private final List<Binding> bindings;
	private final Map<String, Binding> byName;

	private SamplerPlan(List<Binding> bindings) {
		this.bindings = List.copyOf(bindings);

		Map<String, Binding> byName = new LinkedHashMap<>();
		bindings.forEach(binding -> byName.putIfAbsent(binding.sampler(), binding));
		this.byName = Map.copyOf(byName);
	}

	public enum Kind { COLORTEX, DEPTH, SHADOW_DEPTH, SHADOW_COLOUR, NOISE, UNSERVED }

	/** @param index the colour target for {@link Kind#COLORTEX}, -1 otherwise */
	public record Binding(String sampler, Kind kind, int index, TargetSchedule.Side side) {
	}

	public static Kind classify(String name) {
		if (TargetName.index(name).isPresent()) {
			return Kind.COLORTEX;
		}

		if (DEPTH.contains(name)) {
			return Kind.DEPTH;
		}

		if (SHADOW_DEPTH.contains(name)) {
			return Kind.SHADOW_DEPTH;
		}

		if (isShadowColour(name)) {
			return Kind.SHADOW_COLOUR;
		}

		return name.equals(NOISE) ? Kind.NOISE : Kind.UNSERVED;
	}

	/**
	 * @param declared the sampler names the translated program declares, in that order
	 * @param program  the program the plan is for, so the flip snapshot is the right one
	 */
	public static SamplerPlan of(List<String> declared, TargetPlan plan, String program) {
		Optional<TargetSchedule.Bound> step = plan.schedule().step(program);
		List<Binding> bindings = new ArrayList<>();

		for (String name : declared) {
			Kind kind = classify(name);
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
			grouped.computeIfAbsent(binding.kind(), ignored -> new ArrayList<>()).add(binding.sampler());
		}

		grouped.replaceAll((kind, names) -> List.copyOf(names));

		return grouped;
	}

	public List<String> unserved() {
		return this.bindings.stream()
				.filter(binding -> binding.kind() == Kind.UNSERVED)
				.map(Binding::sampler)
				.toList();
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
