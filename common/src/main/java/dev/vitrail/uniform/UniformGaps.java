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
 * <strong>There used to be two lists here and there is one, which is what a list like this looks
 * like when it works.</strong> The second held the names that were a right answer under a full
 * screen pass and a stand-in under the entity mesh, so that listing them everywhere would not put a
 * false alarm on every composite: {@code entityColor} first, then the three identifiers. Both are
 * now made where the mesh carries them, out of an element apiece, so neither is a stand-in anywhere
 * and the question the second list answered has no instance left. It is not kept warm for the day
 * one turns up: an empty list is a distinction nobody can check, and the reason it existed is
 * written here rather than in code nothing reaches.
 */
public final class UniformGaps {

	/** Registered, and answered with something that is not the value, whatever pass reads it. */
	private static final Map<String, String> STAND_INS = standIns();

	private UniformGaps() {
	}

	/**
	 * Why this name is answered with a stand-in rather than a value, or null when it is answered
	 * properly, or not at all.
	 */
	public static String standIn(String name) {
		return STAND_INS.get(name);
	}

	private static Map<String, String> standIns() {
		Map<String, String> reasons = new LinkedHashMap<>();

		// The tables themselves are read and live, block.properties, item.properties and
		// entity.properties alike; what is missing is the asking, each of these four naming a thing
		// the frame holds rather than a thing being drawn.
		String noTable = "nothing looks the held item, the block in front or the vehicle up in the "
				+ "pack's own identifier table yet";
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

}
