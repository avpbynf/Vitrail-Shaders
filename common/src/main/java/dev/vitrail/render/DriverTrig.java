package dev.vitrail.render;

import dev.vitrail.Vitrail;
import dev.vitrail.glsl.GlslTranslator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a pack's {@code sin} and {@code cos} are left to the driver instead of going through the
 * translation's own reduced-argument helpers.
 * <p>
 * <strong>It exists so that a cost can be MEASURED rather than assumed.</strong> Every call to
 * either builtin, in every program of every pack, is replaced at load time by an odd polynomial
 * behind a two constant argument reduction, and the replacement never looks at the argument: one
 * pack of the corpus writes a hundred and thirty six of them in its text, and the shared includes
 * multiply that by every stage that pulls them in. The reason written down for it concerns large
 * arguments, where a single fp32 two-pi sheds the low bits a pack fed whole world coordinates
 * depends on. What the substitution costs a frame has never been read off anything.
 * <p>
 * It cannot be read off two jars either. A frame rate taken on one build and set beside a frame
 * rate taken on another carries the whole difference between the two runs, and the one variable
 * under test is the smallest part of it. So both states are in this jar, a pack load apart, and a
 * measurement is two readings in one world with one thing between them.
 * <p>
 * A file {@code vitrail/driver-trig} in the game directory, or {@code -Dvitrail.driverTrig=true}.
 * Read again at every pack load, so the reload key is the whole gesture, and the state is written
 * to the log BOTH WAYS at every load that installs a chain: a switch that says nothing when it is
 * off leaves every reading taken without a line unable to name the state it belongs to, and that
 * has already cost a morning of readings here. A load the engine refuses prints its refusal
 * instead, and no reading is taken under one of those anyway. The line carries the call sites the
 * chain and its terrain had matched when it prints, which is what says whether the switch had
 * anything to bite on in that pack at all; the other families translate on a worker after the
 * line and add to the tally it was read from, not to the line.
 * <p>
 * It is not a setting anybody should keep. Armed, a pack asking for a sine of the world position
 * gets whatever the driver makes of an argument in the hundreds of thousands, which is the defect
 * the substitution was written against.
 */
public final class DriverTrig {

	private static final boolean PROPERTY = Boolean.getBoolean("vitrail.driverTrig");

	private static final String ARM_FILE = "driver-trig";

	private static boolean armed;

	private DriverTrig() {
	}

	/**
	 * Read at the head of a pack load, before one program of it has been translated, since what it
	 * decides is what the translation emits and nothing re-emits a unit afterwards.
	 * <p>
	 * The game directory is handed in rather than asked of the game: this is settled once per load
	 * and the load already knows where it is reading from.
	 */
	public static void read(Path gameDirectory) {
		armed = PROPERTY
				|| Files.isRegularFile(gameDirectory.resolve(Vitrail.MOD_ID).resolve(ARM_FILE));
		GlslTranslator.reduceTrig(!armed);
	}

	/**
	 * Said once per installed chain and said BOTH WAYS, which is the rule this switch exists
	 * under. A line that only appeared in one of the two states would make every load without one
	 * ambiguous, and a reading taken under an ambiguous load is worth nothing at all.
	 */
	public static void announce() {
		int sites = GlslTranslator.trigSites();
		if (armed) {
			Vitrail.logger().warn("Every sin and cos of this pack is left to the DRIVER, asked for "
					+ "by vitrail/{} or by -Dvitrail.driverTrig, over {} call sites. A large "
					+ "argument sheds its low bits there, which is what the engine's own reduction "
					+ "was written against; remove it to go back", ARM_FILE, sites);
			return;
		}

		Vitrail.logger().info("Every sin and cos of this pack goes through the engine's own "
				+ "reduced-argument helper, over {} call sites, which is the default. vitrail/{} "
				+ "would leave the driver's own two in place, and this line is said either way so "
				+ "that a reading can name the state it was taken under", sites, ARM_FILE);
	}
}
