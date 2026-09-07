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

		// Iris does register it, in uniforms/HardcodedCustomUniforms.java:54, as the distance the
		// camera moved between the two positions its tracker holds. That file is not wired to
		// anything: addHardcodedCustomUniforms has no caller in the whole of the 26.2 tree, so the
		// whole shim it belongs to is unreachable and Complementary reads the same nought there,
		// shaders/lib/uniforms.glsl:195 on Reimagined and Unbound alike. AstraLex is the one pack
		// of the corpus that does not, and it does not because it declares the value itself,
		// shaders/shaders.properties:634, which is answered as any other declaration is.
		String shimNotWired = "Iris registers it in a shim nothing in that repository calls, so it "
				+ "is a nought there too";
		reasons.put("velocity", shimNotWired);

		// The uniforms of the Voxy mod. The mod sets the symbol a pack guards them with, and neither
		// engine sets it: it appears nowhere in the Iris tree either, so a pack that guards both the
		// declaration and the read never puts these names in front of an engine at all. What reaches
		// a block is a pack that declares them whatever that symbol says, which is what raises the
		// line: Solas, shaders/programs/deferred1.glsl:12, has them beside Distant Horizons' render
		// distance with no guard over either.
		String voxyMod = "it belongs to the Voxy mod, and no engine answers it with that mod absent";
		reasons.put("vxRenderDistance", voxyMod);
		reasons.put("vxProj", voxyMod);
		reasons.put("vxProjInv", voxyMod);
		reasons.put("vxProjPrev", voxyMod);
		reasons.put("vxModelView", voxyMod);
		reasons.put("vxModelViewInv", voxyMod);
		reasons.put("vxModelViewPrev", voxyMod);

		// Reverie's, shaders/lib/all_the_uniforms.glsl:27, beside the previousCameraPosition it
		// really does read. Iris spells the split pair previousCameraPositionInt and
		// previousCameraPositionFract, uniforms/CameraUniforms.java:33-34, with the camera's C in
		// capitals; GLSL is case sensitive, so what the pack wrote reaches nothing on either engine.
		reasons.put("previouscameraPositionFract", "the name is misspelt: Iris supplies "
				+ "previousCameraPositionFract, with a capital C, and so does this engine");

		// What is left is a pack reading a name of its own that no engine ever registered, and each
		// one is answered with the same nought under Iris. The name is the pack's to declare and
		// several of them nearly do: I Like Vanilla writes the declaration for rainReflectionStrength
		// and leaves it commented out, shaders/shaders.properties:442.
		String packsOwn = "no engine registers it, Iris included, and the pack that reads it does "
				+ "not declare it either";
		// BVS, shaders/world0/composite.fsh:15, and Body Camera and Cursed Fog at :13 of theirs.
		reasons.put("playerPosition", packsOwn);
		// Sildur's, shaders/deferred.fsh:80, in the three dimensions alike.
		reasons.put("isNether", packsOwn);
		// Noble, shaders/include/uniforms.glsl:73. Iris builds the family from one supplier and
		// gives it three shapes, uniforms/MatrixUniforms.java:34-38: the matrix, its inverse and
		// the previous frame's. There is no inverse OF the previous one, on either engine.
		reasons.put("gbufferPreviousModelViewInverse", packsOwn);
		// Photon, shaders/program/d3_ao.fsh:58 and d2_clouds_upscaling.fsh:66.
		reasons.put("clouds_offset", packsOwn);
		// Bliss, shaders/dimensions/composite11.fsh:34.
		reasons.put("Moon_Weather_properties", packsOwn);
		// I Like Vanilla, shaders/basics/uniforms.glsl:85, against the commented line above.
		reasons.put("rainReflectionStrength", packsOwn);
		// Pegasus, shaders/shaders/composite1.fsh:96 among four of its composites.
		reasons.put("focolortex5", packsOwn);
		// Bliss again, six of them together at shaders/dimensions/all_translucent.fsh:85-101, and
		// the first two once more in shaders/world1/gbuffers_weather.fsh:13-14.
		reasons.put("skyIntensity", packsOwn);
		reasons.put("skyIntensityNight", packsOwn);
		reasons.put("moonIntensity", packsOwn);
		reasons.put("sunIntensity", packsOwn);
		reasons.put("sunColor", packsOwn);
		reasons.put("nsunColor", packsOwn);
		// The same weather program asks for one more, shaders/world1/gbuffers_weather.fsh:9, where
		// every other program of the pack carries that name as a flat varying it fills itself
		// (shaders/dimensions/composite1.vsh:52). No engine registers it: the whole Iris tree has
		// the name nowhere, uniform or otherwise.
		reasons.put("lightCol", packsOwn);

		return Map.copyOf(reasons);
	}

	private static Map<String, String> standIns() {
		Map<String, String> reasons = new LinkedHashMap<>();

		// Only this one of the four settings values. The other three are read off the game's
		// own options and are as true as anything else here; listing them said a measured value was
		// a placeholder, which is the same mistake as the reverse and costs the list its point.
		reasons.put("currentColorSpace", "there is no settings screen to choose it from, which is "
				+ "also what Iris answers outside the mode concerned");

		reasons.put("constantMood", "Iris reads it through an interface it mixes into the player");

		return Map.copyOf(reasons);
	}

}
