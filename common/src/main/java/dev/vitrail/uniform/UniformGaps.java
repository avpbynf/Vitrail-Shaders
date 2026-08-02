package dev.vitrail.uniform;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the engine knows it is not answering properly, and why, said in one place so that the log
 * can be read rather than counted.
 * <p>
 * A block member has three ways of being wrong and only one of them is visible from the block
 * itself. It can be a name nothing in the table answers, which {@link UniformBlock#unanswered()}
 * already reports. It can be a name the table answers with a stand-in, which reports as supplied
 * and is the dangerous one: a zero that arrived through a registered source looks exactly like a
 * measured value. And it can be a name that waits on machinery that does not exist yet, which is
 * neither a gap in the catalogue nor a mistake by the pack, and which reading a count cannot tell
 * apart from either.
 * <p>
 * Nothing here is guessed, and nothing here is in the table merely because it is a constant.
 * {@code entityColor} and {@code renderStage} are constants under a full screen pass and that is
 * the right answer, the same one Iris gives; they belong here only once a gbuffers pass reads
 * them. A name is listed because the accessor that answers it says in its own javadoc why it
 * cannot do better, and the sentence is carried across so that the two do not drift apart. A name
 * that stops being a stand-in has to be taken out, and that is the point: a list somebody has to
 * maintain is a list somebody reads.
 */
public final class UniformGaps {

	/** Registered, and answered with something that is not the value, whatever pass reads it. */
	private static final Map<String, String> STAND_INS = standIns();

	/** Not registered at all, because what would answer them does not run. */
	private static final Map<String, String> AWAITED = Map.of("centerDepthSmooth",
			"the pass that reduces the depth buffer to its centre sample does not run");

	private UniformGaps() {
	}

	/**
	 * Why this name is answered with a stand-in rather than a value, or null when it is answered
	 * properly, or not at all.
	 */
	public static String standIn(String name) {
		return STAND_INS.get(name);
	}

	/** Why nothing answers this name yet, or null when it is not one the engine means to answer. */
	public static String awaited(String name) {
		return AWAITED.get(name);
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
}
