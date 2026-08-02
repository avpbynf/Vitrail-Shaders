package dev.vitrail.uniform;

/**
 * Writes a value into the shape the pack DECLARED, not the shape the engine holds it in.
 * <p>
 * This is where we are correct and Iris has to disable the uniform: it picks the type at
 * registration, before it knows what the program declares, so a pack that writes
 * {@code uniform float isSneaking} where the engine registered an int gets nothing at all. The
 * corpus does exactly that: Body Camera declares the flags as floats and other packs declare the
 * same names as bools. Here the declaration decides and the name only chooses the value.
 * <p>
 * The rules, written once so that nowhere else has to guess: a declared rank below the natural one
 * truncates, above it fills with zeroes, integer and float convert plainly, a mat3 from a mat4
 * takes the upper left three by three block. The fog struct is the one thing that cannot be
 * coerced either way, because it is not a vector of eight floats to the compiler, so a mismatch
 * there is written as zeroes and the name is left for the block to report.
 */
public final class UniformCoercion {

	private UniformCoercion() {
	}

	public static void write(UniformSink sink, UniformShape declared, Val value) {
		// Rank eight belongs to the fog struct and to nothing else, which is what makes this test
		// enough without a flag of its own on the carrier.
		if ((declared == UniformShape.FOG) != (value.rank() == UniformShape.FOG.rank())) {
			declared.zero(sink);

			return;
		}

		declared.write(sink, value);
	}
}
