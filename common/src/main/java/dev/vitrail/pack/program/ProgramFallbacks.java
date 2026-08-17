package dev.vitrail.pack.program;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which program takes over when a pack does not ship the one being asked for.
 * <p>
 * A pack is not expected to write all of these. It writes {@code gbuffers_terrain} and lets
 * everything drawn like terrain inherit from it, so the chain is not an error path: it is how
 * the format is meant to be used, and a pack with four programs is a working pack.
 * <p>
 * The parent table is taken from Iris, the reference implementation of this format. Iris is
 * copyright the Iris contributors and licensed under the GNU LGPL version 3, the same licence
 * as this project. The original is the enum
 * {@code net.irisshaders.iris.shaderpack.loading.ProgramId}, read on 1 August 2026. Adapted in
 * 2026: the enum became a name to parent map, and the public API mapping the original also
 * carried was left out, as were seven of its eight blend mode overrides. Those seven are the
 * {@code BlendModeOverride.OFF} the shadow programs each carry
 * ({@code shaderpack/loading/ProgramId.java:13-19}), and {@code EntityProgram} answers them for the
 * whole shadow family at once rather than name by name; {@link #blendOverride} carries the eighth,
 * which is the only one that is not a refusal. It is reproduced rather than guessed because there is
 * no other
 * authority for it: the format's own documentation does not spell the chain out, and getting
 * an edge wrong means a pack renders something with the wrong program and nothing reports it.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris, LGPL-3.0</a>
 */
public final class ProgramFallbacks {

	private static final Map<String, String> PARENTS = buildParents();

	private static final Map<String, BlendMode> BLEND_OVERRIDES = buildBlendOverrides();

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
		// The one edge the two authorities disagree on: Iris sends clouds to textured, while
		// OptiFine's own documentation gives them no fallback at all. Iris wins here because it
		// is the implementation packs are actually written against, but a pack shipping no
		// cloud program is the case to look at first if clouds ever come out wrong.
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

	/**
	 * What a program name blends with when the pack names no blending of its own for it.
	 * <p>
	 * One entry, and the table exists rather than the constant because the shape is Iris's: the
	 * override hangs off the program NAME in the same enum the parents above come from
	 * ({@code shaderpack/loading/ProgramId.java:47-48}), so a second one would be another row here
	 * and not another special case at a call site.
	 * <p>
	 * <strong>It is keyed by the name ASKED FOR and not by the file that ends up serving it</strong>,
	 * which is the whole reason it cannot live beside the pack's own directive. Iris reads the two
	 * from two different places on purpose: the pack's {@code blend.<program>} is looked up under the
	 * resolved source's name and this default under the asked-for id
	 * ({@code pipeline/programs/ShaderCreator.java:71}), so a pack shipping no
	 * {@code gbuffers_spidereyes} at all still draws its eyes additively through whatever
	 * {@code gbuffers_textured} it fell back on.
	 */
	private static Map<String, BlendMode> buildBlendOverrides() {
		Map<String, BlendMode> overrides = new LinkedHashMap<>();

		// Additive, and it is what makes an eye a light rather than a decal: the source is weighted by
		// its own alpha and the destination is kept whole, so the pupil adds to the mob's skin instead
		// of replacing it. The game's own pipeline blends the ordinary translucent way, which is the
		// value this one displaces; alpha keeps ZERO, ONE for the same reason Iris gives it that.
		overrides.put("gbuffers_spidereyes", new BlendMode(false, "SRC_ALPHA", "ONE", "ZERO", "ONE"));

		return Collections.unmodifiableMap(overrides);
	}

	/**
	 * What the pack falls back to for this program when it names no blending of its own, or null
	 * where the caller's own default stands.
	 */
	public static BlendMode blendOverride(String program) {
		return BLEND_OVERRIDES.get(program);
	}

	/** Every program name that takes part in the fallback tree. */
	public static Set<String> names() {
		return PARENTS.keySet();
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
