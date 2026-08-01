package dev.vitrail.pack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which program takes over when a pack does not ship the one being asked for.
 * <p>
 * A pack is not expected to write all of these. It writes {@code gbuffers_terrain} and lets
 * everything drawn like terrain inherit from it, so the chain is not an error path: it is how
 * the format is meant to be used, and a pack with four programs is a working pack.
 * <p>
 * The table below is taken from Iris, which is the reference implementation of this format and
 * is licensed LGPL-3.0, the same licence as this project. The mapping lives in
 * {@code net.irisshaders.iris.shaderpack.loading.ProgramId}. It is reproduced rather than
 * guessed because there is no other authority for it: the format's own documentation does not
 * spell the chain out, and getting an edge wrong means a pack renders something with the wrong
 * program and nothing reports it.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, by the Iris team, LGPL-3.0</a>
 */
public final class ProgramFallbacks {

	private static final Map<String, String> PARENTS = buildParents();

	private ProgramFallbacks() {
	}

	private static Map<String, String> buildParents() {
		Map<String, String> parents = new LinkedHashMap<>();

		// The shadow family. Everything falls back to the one shadow program, except lightning,
		// which is an entity first.
		parents.put("shadow", null);
		parents.put("shadow_solid", "shadow");
		parents.put("shadow_cutout", "shadow");
		parents.put("shadow_water", "shadow");
		parents.put("shadow_entities", "shadow");
		parents.put("shadow_lightning", "shadow_entities");
		parents.put("shadow_block", "shadow");

		// The gbuffers family. Two roots: basic for untextured geometry, and textured_lit for
		// everything that is lit by the world.
		parents.put("gbuffers_basic", null);
		parents.put("gbuffers_line", "gbuffers_basic");
		parents.put("gbuffers_textured", "gbuffers_basic");
		parents.put("gbuffers_textured_lit", "gbuffers_textured");
		parents.put("gbuffers_skybasic", "gbuffers_basic");
		parents.put("gbuffers_skytextured", "gbuffers_textured");
		parents.put("gbuffers_clouds", "gbuffers_textured");
		parents.put("gbuffers_terrain", "gbuffers_textured_lit");
		parents.put("gbuffers_terrain_solid", "gbuffers_terrain");
		parents.put("gbuffers_terrain_cutout", "gbuffers_terrain");
		parents.put("gbuffers_damagedblock", "gbuffers_terrain");
		parents.put("gbuffers_block", "gbuffers_terrain");
		parents.put("gbuffers_block_translucent", "gbuffers_block");
		parents.put("gbuffers_beaconbeam", "gbuffers_textured");
		parents.put("gbuffers_item", "gbuffers_textured_lit");
		parents.put("gbuffers_entities", "gbuffers_textured_lit");
		parents.put("gbuffers_entities_translucent", "gbuffers_entities");
		parents.put("gbuffers_entities_glowing", "gbuffers_entities");
		parents.put("gbuffers_lightning", "gbuffers_entities");
		parents.put("gbuffers_particles", "gbuffers_textured_lit");
		parents.put("gbuffers_particles_translucent", "gbuffers_particles");
		parents.put("gbuffers_armor_glint", "gbuffers_textured");
		parents.put("gbuffers_spidereyes", "gbuffers_textured");
		parents.put("gbuffers_hand", "gbuffers_textured_lit");
		parents.put("gbuffers_weather", "gbuffers_textured_lit");
		parents.put("gbuffers_water", "gbuffers_terrain");
		parents.put("gbuffers_hand_water", "gbuffers_hand");

		// Distant Horizons. Its shadow program stands alone rather than joining the shadow
		// family above.
		parents.put("dh_terrain", null);
		parents.put("dh_water", "dh_terrain");
		parents.put("dh_generic", "dh_terrain");
		parents.put("dh_shadow", null);

		// Composition has no fallback at all. A missing composite pass is simply a pass that
		// does not run, which is a normal thing for a pack to want.
		parents.put("final", null);

		// Not Map.copyOf: the roots of the tree map to null, and the immutable factories reject
		// a null value rather than storing it.
		return Collections.unmodifiableMap(parents);
	}

	/** Every program name that takes part in the fallback tree. */
	public static Set<String> names() {
		return PARENTS.keySet();
	}

	public static boolean knows(String program) {
		return PARENTS.containsKey(program);
	}

	public static Optional<String> parentOf(String program) {
		return Optional.ofNullable(PARENTS.get(program));
	}

	/**
	 * The programs to try, in order, starting with the one asked for. Walking this list and
	 * taking the first one the pack ships is the whole of the resolution.
	 */
	public static List<String> chain(String program) {
		List<String> chain = new ArrayList<>();
		String current = program;

		while (current != null && !chain.contains(current)) {
			chain.add(current);
			current = PARENTS.get(current);
		}

		return List.copyOf(chain);
	}
}
