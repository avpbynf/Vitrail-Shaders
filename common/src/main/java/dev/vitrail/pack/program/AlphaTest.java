package dev.vitrail.pack.program;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;

/**
 * The alpha test a program is drawn under, which the fixed function pipeline used to hold and which
 * has to be written into the shader instead.
 * <p>
 * OptiFine packs count on {@code glAlphaFunc}, gone from core profiles for fifteen years. Sodium
 * emulates it in its own fragment stage with a {@code discard} behind an {@code ALPHA_CUTOUT}
 * define; a pack's program replaces that stage, so a pack drawn over the cutout pass without this
 * draws leaves and grass as solid cubes, which is worse than not drawing them at all.
 * <p>
 * The test is a property of the pass and not of the program: the same {@code gbuffers_terrain}
 * serves the solid pass with no test at all and the cutout pass at a half. A pack overrides it with
 * {@code alphaTest.<program>} in {@code shaders.properties}, under the name of the file that really
 * serves the pass rather than the name that was asked for, which is what Iris looks up too.
 * <p>
 * The comparison is written negated, {@code if (!(a > r)) discard}, because that is the form Iris
 * emits and a pack is written against Iris. It also differs from the plain form on a NaN alpha,
 * which discards here and would be kept by {@code if (a <= r)}.
 *
 * @param function  what the pack asked to compare with. {@link Function#ALWAYS} is no test at all
 * @param reference what to compare the alpha against
 */
public record AlphaTest(Function function, float reference) {

	/** No test. What the solid pass wants, and what {@code alphaTest.x=off} means. */
	public static final AlphaTest OFF = new AlphaTest(Function.ALWAYS, 0.0F);

	/** The cutout default. Iris uses a half here and so does Sodium's own {@code ALPHA_CUTOUT}. */
	public static final AlphaTest CUTOUT = new AlphaTest(Function.GREATER, 0.5F);

	/**
	 * The translucent default. A ten thousandth, which is Iris's number; Sodium uses a hundredth for
	 * its own shader, and a pack is written against Iris.
	 */
	public static final AlphaTest NON_ZERO = new AlphaTest(Function.GREATER, 0.0001F);

	/** The eight names {@code glAlphaFunc} took, with what each one is in GLSL. */
	public enum Function {

		NEVER(null),
		LESS("<"),
		EQUAL("=="),
		LEQUAL("<="),
		GREATER(">"),
		NOTEQUAL("!="),
		GEQUAL(">="),
		ALWAYS(null);

		private final String operator;

		Function(String operator) {
			this.operator = operator;
		}
	}

	/**
	 * Reads one {@code alphaTest.<program>} value, or answers empty when it says nothing this can
	 * act on.
	 * <p>
	 * {@code off} and {@code false} are both written by packs of the corpus and both mean no test.
	 * Anything else is a function and a reference, and a line that is neither is refused rather than
	 * guessed at: a wrong threshold is a picture with holes in it or none where there should be.
	 */
	public static Optional<AlphaTest> parse(String raw) {
		String value = raw.trim();
		if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("false")) {
			return Optional.of(OFF);
		}

		// A third token is ignored rather than refused, which is what Iris does with it: a trailing
		// comment on the line would otherwise cost the pack its threshold.
		String[] parts = value.split("\\s+");
		if (parts.length < 2) {
			return Optional.empty();
		}

		// GL_ALWAYS is the spelling shaders.properties documents, and every other name is written
		// bare. Both are taken, as Iris takes both.
		String name = parts[0].startsWith("GL_") ? parts[0].substring(3) : parts[0];
		try {
			return Optional.of(new AlphaTest(Function.valueOf(name.toUpperCase(Locale.ROOT)),
					Float.parseFloat(parts[1])));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** Whether this discards anything at all. False for {@code ALWAYS}, which is no test. */
	public boolean tests() {
		return this.function != Function.ALWAYS;
	}

	/**
	 * The statement to put at the end of a fragment {@code main}, comparing the alpha of the output
	 * named here. Empty when there is no test to write.
	 */
	public String discard(String alpha) {
		if (this.function == Function.ALWAYS) {
			return "";
		}

		if (this.function == Function.NEVER) {
			return "discard;";
		}

		return "if (!(" + alpha + " " + this.function.operator + " " + literal() + ")) { discard; }";
	}

	/**
	 * The reference as GLSL. Written out in full rather than through {@link Float#toString}, which
	 * gives {@code 1.0E-4} for the translucent threshold: legal GLSL, and unreadable in a shader
	 * dump next to the {@code 0.0001} the pack wrote.
	 */
	private String literal() {
		String plain = new BigDecimal(Float.toString(this.reference)).toPlainString();

		return plain.indexOf('.') < 0 ? plain + ".0" : plain;
	}
}
