package dev.vitrail.uniform;

import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

import java.util.Locale;

/**
 * Writes a block as {@code name = value} text instead of as bytes.
 * <p>
 * The instrument the rest of the milestone is verified with. A uniform that is wrong does not look
 * wrong: it looks like a slightly different picture, and the project has already paid twice for
 * reading one of those as though it were right. Sent through here the same walk that fills the
 * buffer produces numbers, so {@code timeBrightness} at noon is either exactly 1.0 or it is not,
 * and a {@code sunPosition} handed over in world space stops being a plausible reflection and
 * becomes three numbers that do not match the eye space ones.
 * <p>
 * <strong>Iris cannot do this</strong>, and not for want of trying: it sets each uniform through
 * the GL entry points, and a GL uniform is not readable back from the CPU. Holding the values in a
 * block of our own is what makes them printable, so this costs one class rather than a redesign.
 * <p>
 * It is the same walk and not a second one, which is the only reason a line here proves anything
 * about the bytes. A member that reached the buffer through a coercion is printed after that
 * coercion, and a member nothing supplies prints the zeroes it really writes, marked, because a
 * zero that arrived through a registered source is the one failure a screenshot can never show.
 */
public final class TextSink implements UniformSink {

	private static final String UNSUPPLIED = "   <- nothing supplies this";

	private final StringBuilder text = new StringBuilder();

	private String name = "";
	private boolean supplied = true;
	private int elements = 1;
	private int element;

	@Override
	public UniformSink member(String name, int elements, boolean supplied) {
		this.name = name;
		this.elements = elements;
		this.supplied = supplied;
		this.element = 0;

		return this;
	}

	/** What has been written so far, one member per line and matrices over five. */
	public String text() {
		return this.text.toString();
	}

	@Override
	public UniformSink align(int alignment) {
		return this;
	}

	@Override
	public UniformSink putFloat(float v) {
		return line(num(v));
	}

	@Override
	public UniformSink putInt(int v) {
		return line(Integer.toString(v));
	}

	@Override
	public UniformSink putVec2(float x, float y) {
		return line("(" + num(x) + ", " + num(y) + ")");
	}

	@Override
	public UniformSink putVec3(float x, float y, float z) {
		return line("(" + num(x) + ", " + num(y) + ", " + num(z) + ")");
	}

	@Override
	public UniformSink putVec4(float x, float y, float z, float w) {
		return line("(" + num(x) + ", " + num(y) + ", " + num(z) + ", " + num(w) + ")");
	}

	@Override
	public UniformSink putIVec2(int x, int y) {
		return line("(" + x + ", " + y + ")");
	}

	@Override
	public UniformSink putIVec3(int x, int y, int z) {
		return line("(" + x + ", " + y + ", " + z + ")");
	}

	@Override
	public UniformSink putIVec4(int x, int y, int z, int w) {
		return line("(" + x + ", " + y + ", " + z + ", " + w + ")");
	}

	@Override
	public UniformSink putMat3(Matrix3fc m) {
		return rows(new float[][] {
				{ m.m00(), m.m01(), m.m02() },
				{ m.m10(), m.m11(), m.m12() },
				{ m.m20(), m.m21(), m.m22() } });
	}

	@Override
	public UniformSink putMat4(Matrix4fc m) {
		return rows(new float[][] {
				{ m.m00(), m.m01(), m.m02(), m.m03() },
				{ m.m10(), m.m11(), m.m12(), m.m13() },
				{ m.m20(), m.m21(), m.m22(), m.m23() },
				{ m.m30(), m.m31(), m.m32(), m.m33() } });
	}

	/**
	 * Column by column, the order std140 lays a matrix out and the order the sink is handed it, so
	 * that a transposed matrix reads as transposed here rather than as a plausible other matrix.
	 */
	private UniformSink rows(float[][] columns) {
		StringBuilder block = new StringBuilder();
		for (float[] column : columns) {
			block.append("\n       (");
			for (int i = 0; i < column.length; i++) {
				block.append(i == 0 ? "" : ", ").append(num(column[i]));
			}

			block.append(')');
		}

		return line(block.toString());
	}

	private UniformSink line(String value) {
		this.text.append(this.elements > 1 ? this.name + "[" + this.element + "]" : this.name)
				.append(" = ")
				.append(value)
				.append(this.supplied ? "" : UNSUPPLIED)
				.append('\n');
		this.element++;

		return this;
	}

	/** Six significant digits, and never the locale's decimal comma: this is read by machines too. */
	private static String num(float v) {
		return String.format(Locale.ROOT, "%.6g", v);
	}
}
