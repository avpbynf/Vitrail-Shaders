package dev.vitrail.render;

import org.joml.Matrix4fc;

/**
 * A plain double precision 4x4 matrix, row major, standing in for JOML in the tests of this package.
 * <p>
 * It exists so that a test can be a second, independent reading of the production arithmetic: none
 * of it goes through JOML, and every constructor is the textbook formula written out, so a mistake
 * in the production composition order or sign cannot be repeated here by construction. An element is
 * {@code m[row][column]}, and a point is a column vector on the right, the convention JOML and the
 * game use.
 */
final class DoubleMatrix {

	private DoubleMatrix() {
	}

	static double[][] identity() {
		double[][] m = new double[4][4];
		for (int i = 0; i < 4; i++) {
			m[i][i] = 1.0;
		}

		return m;
	}

	static double[][] translation(double x, double y, double z) {
		double[][] m = identity();
		m[0][3] = x;
		m[1][3] = y;
		m[2][3] = z;

		return m;
	}

	static double[][] rotationX(double radians) {
		double c = Math.cos(radians);
		double s = Math.sin(radians);
		double[][] m = identity();
		m[1][1] = c;
		m[1][2] = -s;
		m[2][1] = s;
		m[2][2] = c;

		return m;
	}

	static double[][] rotationY(double radians) {
		double c = Math.cos(radians);
		double s = Math.sin(radians);
		double[][] m = identity();
		m[0][0] = c;
		m[0][2] = s;
		m[2][0] = -s;
		m[2][2] = c;

		return m;
	}

	static double[][] rotationZ(double radians) {
		double c = Math.cos(radians);
		double s = Math.sin(radians);
		double[][] m = identity();
		m[0][0] = c;
		m[0][1] = -s;
		m[1][0] = s;
		m[1][1] = c;

		return m;
	}

	/**
	 * The OpenGL perspective, depth from minus one at the near plane to one at the far plane, which is
	 * the form a pack reads.
	 */
	static double[][] legacyPerspective(double fovRadians, double aspect, double near, double far) {
		double focal = 1.0 / Math.tan(fovRadians / 2.0);
		double[][] m = new double[4][4];
		m[0][0] = focal / aspect;
		m[1][1] = focal;
		m[2][2] = -(far + near) / (far - near);
		m[2][3] = -2.0 * far * near / (far - near);
		m[3][2] = -1.0;

		return m;
	}

	/**
	 * The perspective the game rasterises with, reversed Z over zero to one: depth one at the near
	 * plane and nought at the far one. Written from the requirement {@code d(near) = 1},
	 * {@code d(far) = 0}, {@code d(t) = near * (far - t) / (t * (far - near))} for an eye distance
	 * {@code t}, not from any library.
	 */
	static double[][] reversedPerspective(double fovRadians, double aspect, double near, double far) {
		double focal = 1.0 / Math.tan(fovRadians / 2.0);
		double[][] m = new double[4][4];
		m[0][0] = focal / aspect;
		m[1][1] = focal;
		m[2][2] = near / (far - near);
		m[2][3] = near * far / (far - near);
		m[3][2] = -1.0;

		return m;
	}

	/** The OpenGL orthographic box of a symmetric window, {@code width} by {@code height} wide. */
	static double[][] legacyOrtho(double width, double height, double near, double far) {
		double[][] m = identity();
		m[0][0] = 2.0 / width;
		m[1][1] = 2.0 / height;
		m[2][2] = -2.0 / (far - near);
		m[2][3] = -(far + near) / (far - near);

		return m;
	}

	static double[][] mul(double[][] left, double[][] right) {
		double[][] out = new double[4][4];
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				double sum = 0.0;
				for (int k = 0; k < 4; k++) {
					sum += left[row][k] * right[k][column];
				}

				out[row][column] = sum;
			}
		}

		return out;
	}

	static double[][] mul(double[][]... chain) {
		double[][] out = chain[0];
		for (int i = 1; i < chain.length; i++) {
			out = mul(out, chain[i]);
		}

		return out;
	}

	/** Gauss-Jordan with partial pivoting, which is plenty for the well conditioned matrices here. */
	static double[][] invert(double[][] source) {
		double[][] a = new double[4][8];
		for (int row = 0; row < 4; row++) {
			System.arraycopy(source[row], 0, a[row], 0, 4);
			a[row][4 + row] = 1.0;
		}

		for (int column = 0; column < 4; column++) {
			int pivot = column;
			for (int row = column + 1; row < 4; row++) {
				if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) {
					pivot = row;
				}
			}

			double[] swap = a[column];
			a[column] = a[pivot];
			a[pivot] = swap;

			double divisor = a[column][column];
			for (int k = 0; k < 8; k++) {
				a[column][k] /= divisor;
			}

			for (int row = 0; row < 4; row++) {
				if (row != column) {
					double factor = a[row][column];
					for (int k = 0; k < 8; k++) {
						a[row][k] -= factor * a[column][k];
					}
				}
			}
		}

		double[][] out = new double[4][4];
		for (int row = 0; row < 4; row++) {
			System.arraycopy(a[row], 4, out[row], 0, 4);
		}

		return out;
	}

	/** {@code m * (x, y, z, w)} as a four vector. */
	static double[] apply(double[][] m, double x, double y, double z, double w) {
		double[] in = {x, y, z, w};
		double[] out = new double[4];
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				out[row] += m[row][column] * in[column];
			}
		}

		return out;
	}

	/** A JOML matrix read into the same layout, widened element by element. */
	static double[][] of(Matrix4fc joml) {
		double[][] m = new double[4][4];
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				m[row][column] = joml.get(column, row);
			}
		}

		return m;
	}

	/** The largest absolute difference between the elements of two matrices, NaN if either has one. */
	static double maxDifference(double[][] expected, Matrix4fc actual) {
		double worst = 0.0;
		for (int row = 0; row < 4; row++) {
			for (int column = 0; column < 4; column++) {
				double difference = Math.abs(expected[row][column] - actual.get(column, row));
				if (Double.isNaN(difference)) {
					return Double.NaN;
				}

				worst = Math.max(worst, difference);
			}
		}

		return worst;
	}
}
