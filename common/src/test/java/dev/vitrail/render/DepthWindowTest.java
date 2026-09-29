package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.ClipSpace;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Holds the three shader texts that move a depth between the device's volume and the pack's window to
 * the one pair of constants they are all written from, and to each other.
 * <p>
 * {@code PackDepth} writes {@code readA * d + readB} so that {@code depthtex1} holds the forward
 * window a pack reads, and {@code MotionVectors} puts the device depth back with
 * {@code (t - readB) / readA}. The three texts are formatted from {@link ClipSpace#REVERSED} at class
 * load, in an order the compiler cannot check: swapping the two arguments of one {@code format} call
 * would still compile and would write a plausible depth that is wrong everywhere. What is parsed here
 * is the GLSL each one really holds.
 */
class DepthWindowTest {

	private static final Pattern PACK = Pattern.compile("vec4\\((\\S+) \\* texture\\(InSampler, ofTexCoord\\)\\.r \\+ (\\S+)\\)");
	private static final Pattern DISTANT = Pattern.compile("vec4\\((\\S+) \\* far \\+ (\\S+)\\)");
	private static final Pattern UNDO = Pattern.compile("\\(texture\\(DepthSampler, uv\\)\\.r - (\\S+)\\) / (\\S+);");

	private static String text(Class<?> owner, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);

		return (String) field.get(null);
	}

	private static float[] pair(Pattern pattern, String source, String what) {
		Matcher matcher = pattern.matcher(source);
		assertTrue(matcher.find(), what + " holds the conversion line: " + source);

		return new float[] {Float.parseFloat(matcher.group(1)), Float.parseFloat(matcher.group(2))};
	}

	@Test
	void theTwoDepthCopiesWriteTheReversedPairAsItIsAndInTheOrderReadAThenReadB() throws ReflectiveOperationException {
		float[] world = pair(PACK, text(PackDepth.class, "FRAGMENT"), "the world's depth copy");
		float[] distant = pair(DISTANT, text(PackDepth.class, "DISTANT_FRAGMENT"), "the far terrain's depth copy");

		assertEquals(ClipSpace.REVERSED.z, world[0], "readA of the world's copy");
		assertEquals(ClipSpace.REVERSED.w, world[1], "readB of the world's copy");
		assertEquals(ClipSpace.REVERSED.z, distant[0], "readA of the far terrain's copy");
		assertEquals(ClipSpace.REVERSED.w, distant[1], "readB of the far terrain's copy");
	}

	@Test
	void thePackWindowIsForwardNoughtAtTheNearPlaneAndOneAtTheFarOne() throws ReflectiveOperationException {
		// The device stores a reversed Z, one at the near plane and nought at the far one, and a pack is
		// written against the OpenGL window the other way round.
		float[] world = pair(PACK, text(PackDepth.class, "FRAGMENT"), "the world's depth copy");

		assertEquals(0.0F, world[0] * 1.0F + world[1], "the near plane");
		assertEquals(1.0F, world[0] * 0.0F + world[1], "the far plane");
		assertEquals(0.75F, world[0] * 0.25F + world[1], "and in between it is a reflection");
	}

	@Test
	void theMotionVectorPassUndoesExactlyWhatTheDepthCopyDoesFromTheSamePair() throws ReflectiveOperationException {
		float[] write = pair(PACK, text(PackDepth.class, "FRAGMENT"), "the world's depth copy");
		float[] undo = pair(UNDO, text(MotionVectors.class, "FRAGMENT"), "the motion vector pass");

		assertEquals(ClipSpace.REVERSED.w, undo[0], "it subtracts readB");
		assertEquals(ClipSpace.REVERSED.z, undo[1], "and divides by readA");

		double worst = 0.0;
		for (double depth : new double[] {0.0, 1.0e-7, 7.3e-5, 5.0e-4, 0.005, 0.1, 0.5, 0.75, 0.99, 1.0}) {
			float texel = (float) (write[0] * depth + write[1]);
			double back = (texel - undo[0]) / undo[1];
			worst = Math.max(worst, Math.abs(back - depth));
		}

		assertTrue(worst <= 6.0e-8, "worst round trip error " + worst + " against a whole float step below one (5.96e-8), "
				+ "measured on the code as it stands: 2.4e-8, half a float step, at the depths above");
	}
}
