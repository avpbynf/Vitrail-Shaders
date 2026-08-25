package dev.vitrail.pack.program;

import dev.vitrail.pack.source.DimensionSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out, for one dimension, which file actually serves each program.
 * <p>
 * A dimension directory replaces the root rather than being layered over it. A pack that ships
 * a {@code world-1} folder holding two programs has exactly two programs in the Nether, and
 * everything else there resolves through the fallback tree inside that folder, not through the
 * root. This is worth stating because the other reading is the intuitive one and it is wrong;
 * Iris says so in as many words in {@code ShaderPack.getProgramSet}.
 */
public final class ProgramResolver {

	private final Map<String, List<Resolution>> byDimension;

	private ProgramResolver(Map<String, List<Resolution>> byDimension) {
		this.byDimension = Map.copyOf(byDimension);
	}

	/**
	 * @param switchedOff the programs the pack's own settings turn off, under the paths it wrote,
	 *                    from {@link dev.vitrail.pack.source.ShaderProperties#switchedOff}. One of
	 *                    those counts as not shipped rather than as served by nothing, so the
	 *                    chain carries on to the next candidate and a family whose parent is still
	 *                    there keeps drawing through the parent. Skipping the family outright
	 *                    would leave the geometry unserved, which the reference does not do and
	 *                    which is worse than serving it: a pack that switches off its translucent
	 *                    entity program is asking for those entities to be drawn by its ordinary
	 *                    entity program, not for them to disappear.
	 */
	public static ProgramResolver resolve(ProgramSet programs, DimensionSet dimensions,
			Set<String> switchedOff) {
		// One index per dimension of what that dimension ships, keyed by program name.
		Map<String, Map<String, String>> shipped = new LinkedHashMap<>();
		for (ProgramSet.ProgramKey key : programs.keys()) {
			if (key.stage() == ProgramStage.FRAGMENT && !switchedOff.contains(pathOf(key))) {
				shipped.computeIfAbsent(key.dimension(), _ -> new LinkedHashMap<>())
						.put(key.name().baseName(), key.file());
			}
		}

		List<String> places = new ArrayList<>();
		places.add(ProgramSet.ROOT);
		places.addAll(dimensions.names());

		Map<String, List<Resolution>> resolved = new LinkedHashMap<>();
		for (String place : places) {
			Map<String, String> here = shipped.getOrDefault(place, Map.of());
			List<Resolution> resolutions = new ArrayList<>();

			for (String program : ProgramFallbacks.names()) {
				List<String> chain = ProgramFallbacks.chain(program);
				for (int depth = 0; depth < chain.size(); depth++) {
					String candidate = chain.get(depth);
					String file = here.get(candidate);
					if (file != null) {
						resolutions.add(new Resolution(program, candidate, file, depth));
						break;
					}
				}
			}

			resolved.put(place, List.copyOf(resolutions));
		}

		return new ProgramResolver(resolved);
	}

	/** The file the pack ships with its extension taken off, which is how a toggle names it. */
	private static String pathOf(ProgramSet.ProgramKey key) {
		String file = key.file();
		int dot = file.lastIndexOf('.');
		return dot < 0 ? file : file.substring(0, dot);
	}

	public List<Resolution> resolutions(String dimension) {
		return this.byDimension.getOrDefault(dimension, List.of());
	}

	public Optional<Resolution> lookup(String dimension, String program) {
		return resolutions(dimension).stream()
				.filter(resolution -> resolution.requested().equals(program))
				.findFirst();
	}

	/** Programs a dimension serves with a file of its own rather than through the tree. */
	public int directCount(String dimension) {
		return (int) resolutions(dimension).stream().filter(Resolution::direct).count();
	}

	/** Programs a dimension serves by falling back to another one. */
	public int inheritedCount(String dimension) {
		return (int) resolutions(dimension).stream().filter(resolution -> !resolution.direct()).count();
	}

	/**
	 * Programs nothing can serve, because neither they nor anything they fall back to exists.
	 * These are the ones that will draw nothing at all.
	 */
	public int unservedCount(String dimension) {
		return ProgramFallbacks.names().size() - resolutions(dimension).size();
	}

	/** One answer: what was asked for, what serves it, and how far up the tree that was. */
	public record Resolution(String requested, String servedBy, String file, int depth) {

		public boolean direct() {
			return this.depth == 0;
		}
	}
}
