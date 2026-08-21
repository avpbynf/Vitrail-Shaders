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
 * measured value. Beside those two stands a name that is not wrong at all, one NO engine answers,
 * Iris included; naming those here is what keeps the log from claiming them as debts and sending a
 * reader through Iris for a source that was never written.
 * <p>
 * Nothing here is guessed, and nothing here is in the table merely because it is a constant.
 * {@code renderStage} is not one of them: the passes that draw the world and the sky each say what
 * they are, and a full screen pass says the phase Iris calls NONE because that is
 * what it is. A name is listed because the accessor that answers it says in its own javadoc why it
 * cannot do better, and the sentence is carried across so that the two do not drift apart. A name
 * that stops being a stand-in has to be taken out, and that is the point: a list somebody has to
 * maintain is a list somebody reads.
 * <p>
 * <strong>The stand-ins are one list and not two, which is what a list like this looks like when it
 * works.</strong> A second list would hold the names that are a right answer under a full screen
 * pass and a stand-in under the entity mesh, so that listing them everywhere would not put a false
 * alarm on every composite: {@code entityColor} and the three identifiers. Neither is a stand-in
 * anywhere, both being made where the mesh carries them out of an element apiece, so that list has
 * no instance left to hold. It is not kept warm for the day one turns up: an empty list is a
 * distinction nobody can check, and the reason it would exist is written here rather than in code
 * nothing reaches.
 */
public final class UniformGaps {

	/** Registered, and answered with something that is not the value, whatever pass reads it. */
	private static final Map<String, String> STAND_INS = standIns();

	/** Read by a pack, answered by nobody, Iris included. */
	private static final Map<String, String> UNANSWERABLE = unanswerable();

	private UniformGaps() {
	}

	/**
	 * Why this name is answered with a stand-in rather than a value, or null when it is answered
	 * properly, or not at all.
	 */
	public static String standIn(String name) {
		return STAND_INS.get(name);
	}

	/**
	 * Why no engine answers this name, or null when it is one the engine really owes.
	 * <p>
	 * <strong>A name is here to keep the log from claiming a debt that is not one.</strong> What
	 * the block does with such a name is exactly what it does with a real gap, zeroes, and that is
	 * right: it is what the pack reads under Iris as well, an unset uniform being nought there. What
	 * is not right is calling it a value this engine does not supply YET, which reads as work owed
	 * and sends whoever follows the log looking through Iris for a source that was never written.
	 * Measured on 19 August 2026: the one line about {@code farPlane} sent a reader through the
	 * whole Distant Horizons path of two packs.
	 */
	public static String unanswerable(String name) {
		return UNANSWERABLE.get(name);
	}

	private static Map<String, String> unanswerable() {
		Map<String, String> reasons = new LinkedHashMap<>();

		// Bliss reads it in the two passes that mix Distant Horizons' depth with the world's,
		// dimensions/composite1.fsh:777 and dimensions/composite3.fsh:248, both times as the far
		// argument of a linearisation. Iris registers dhFarPlane, dhNearPlane and dhRenderDistance
		// beside each other (uniforms/CommonUniforms.java:184-186) and near and far in
		// uniforms/CameraUniforms.java:26-27; a farPlane UNIFORM is in none of them, the name
		// living in that repository only as shadow arithmetic and a pack directive
		// (shaderpack/properties/PackShadowDirectives.java:38 among others). So the pack reads
		// nought there too, and what that does to it, it does under Iris as well: the guard the
		// linearisation feeds is a disjunction (composite1.fsh:783), a far plane of nought kills
		// only the half that compares the linearised depths, and the depthOpaque >= 1.0 half keeps
		// deciding on both engines alike.
		reasons.put("farPlane", "no engine answers it, Iris included, so a pack reads the same "
				+ "nought there");

		return Map.copyOf(reasons);
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
