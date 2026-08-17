package dev.vitrail.uniform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the engine knows it is not answering properly, and why, said in one place so that the log
 * can be read rather than counted.
 * <p>
 * A block member has two ways of being wrong and only one of them is visible from the block
 * itself. It can be a name nothing in the table answers, which {@link UniformBlock#unanswered()}
 * already reports. Or it can be a name the table answers with a stand-in, which reports as supplied
 * and is the dangerous one: a zero that arrived through a registered source looks exactly like a
 * measured value.
 * <p>
 * Nothing here is guessed, and nothing here is in the table merely because it is a constant.
 * {@code renderStage} was in that sentence and has left it: the passes that draw the world and the
 * sky each say what they are, and a full screen pass says the phase Iris calls NONE because that is
 * what it is. A name is listed because the accessor that answers it says in its own javadoc why it
 * cannot do better, and the sentence is carried across so that the two do not drift apart. A name
 * that stops being a stand-in has to be taken out, and that is the point: a list somebody has to
 * maintain is a list somebody reads.
 * <p>
 * <strong>There are two lists and not one, because whether a name is a stand-in is a question about
 * the PASS.</strong> The three identifiers below are the standing example of it: a constant under a
 * full screen pass is the right answer there and the answer Iris gives as well, the two block
 * entity and held item ones as constants ({@code uniforms/CommonUniforms.java:164-165}) and
 * {@code entityId} through a live fallback its own comment names, for the lightning
 * ({@code :72-73}). The very same constant under a pass drawn from the entity mesh is one number for
 * every mob on screen. Listing that kind in the everywhere list would put a false alarm on every
 * composite that reads it, which is the one thing a list like this cannot afford.
 * <p>
 * {@code entityColor} was the fourth name of that list until the vertex stage was given the overlay
 * to make it out of, and where it went is worth keeping. Iris gates the two halves separately: the
 * identifiers where the mesh carries the overlay OR is text
 * ({@code pipeline/transform/transformer/VanillaTransformer.java:20-25}), the colour where it
 * carries the overlay and is NOT text ({@code VanillaCoreTransformer.java:21-26}). The colour is now
 * asked of the bound format here as well, {@code glsl/VertexInputs.overlay}, which is the same
 * question; the identifiers are still asked of the PASS, which is not, so the day a text family is
 * served this split has to follow.
 */
public final class UniformGaps {

	/** Registered, and answered with something that is not the value, whatever pass reads it. */
	private static final Map<String, String> STAND_INS = standIns();

	/** The same, for a pass drawn from the entity mesh alone. See the class comment. */
	private static final Map<String, String> ENTITY_MESH = entityMesh();

	private UniformGaps() {
	}

	/**
	 * Why this name is answered with a stand-in rather than a value, or null when it is answered
	 * properly, or not at all.
	 *
	 * @param entityMesh whether the pass asking draws the mesh Iris reads these off, which adds the
	 *                   second list. False is the answer for a full screen pass and for every other
	 *                   mesh, and it is what keeps a composite reading {@code blockEntityId} from
	 *                   being told a value is a placeholder when it is exactly what Iris supplies
	 */
	public static String standIn(String name, boolean entityMesh) {
		String reason = STAND_INS.get(name);

		return reason != null || !entityMesh ? reason : ENTITY_MESH.get(name);
	}

	private static Map<String, String> standIns() {
		Map<String, String> reasons = new LinkedHashMap<>();

		String noTable = "nothing looks a held item, a block or a vehicle up in the pack's own "
				+ "identifier table yet";
		reasons.put("heldItemId", noTable);
		reasons.put("heldItemId2", noTable);
		reasons.put("currentSelectedBlockId", noTable);
		reasons.put("vehicleId", noTable);

		// And only this one of the four settings values. The other three are read off the game's
		// own options and are as true as anything else here; listing them said a measured value was
		// a placeholder, which is the same mistake as the reverse and costs the list its point.
		reasons.put("currentColorSpace", "there is no settings screen to choose it from, which is "
				+ "also what Iris answers outside the mode concerned");

		reasons.put("rainfall",
				"vanilla has no accessor for it and this module compiles against vanilla alone");
		reasons.put("constantMood", "Iris reads it through an interface it mixes into the player");

		return Map.copyOf(reasons);
	}

	/**
	 * The names that are a stand-in only where the entity mesh is drawn, each because Iris answers
	 * it there from an element of that mesh and answers it elsewhere with the very constant this
	 * engine supplies.
	 * <p>
	 * Both reasons end at the same place on our side, {@code glsl/EntityVertex.java:74-76}: the mesh
	 * this engine decodes is the game's own, which carries neither element, and there is nowhere for
	 * one to go.
	 */
	private static Map<String, String> entityMesh() {
		Map<String, String> reasons = new LinkedHashMap<>();

		// Iris adds a vertex element of its own for the three, four unsigned shorts wide of which
		// three lanes are read, as an ivec3 (vertices/IrisVertexFormats.java:30). Where the mesh
		// carries it, the uniform declaration is deleted outright and every read is rewritten onto
		// the element (pipeline/transform/transformer/EntityPatcher.java:130-152), so the uniform
		// values this engine registers are what Iris hands a pass the element never reached.
		String noElement = "Iris reads these off a vertex element the game's entity format has "
				+ "not got, so they are the same numbers for every draw";
		reasons.put("entityId", noElement);
		reasons.put("blockEntityId", noElement);
		reasons.put("currentRenderedItemId", noElement);

		// entityColor was the third of these and has left, which is what this list is for: on a mesh
		// that carries the overlay the name is no longer answered from the table at all. The vertex
		// stage makes it out of UV1 and hands it on as a varying, the way Iris does, so there is
		// nothing here to stand in for. The table still registers it, and rightly: that answer is
		// Iris's own everywhere the mesh has no overlay.
		return Map.copyOf(reasons);
	}
}
