package dev.vitrail.uniform.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.WorldState;

import org.joml.Matrix4fc;

/** What the value tests share: asking a table for a name and reading the answer back. */
final class ValueReads {

	private ValueReads() {
	}

	/** The carrier a name fills for a world, asked for element nought. */
	static Val read(UniformCatalog catalog, String name, WorldState world) {
		return read(catalog, name, world, 0);
	}

	static Val read(UniformCatalog catalog, String name, WorldState world, int element) {
		assertNotNull(catalog.source(name), name + " is not in the table");

		Val val = new Val();
		catalog.source(name).read(world, val, element);

		return val;
	}

	/** The components of a float valued answer of the given rank. */
	static float[] floats(Val val, int rank) {
		assertEquals(rank, val.rank(), "rank");
		assertEquals(false, val.integral(), "integral");

		float[] components = new float[rank];
		for (int i = 0; i < rank; i++) {
			components[i] = val.f(i);
		}

		return components;
	}

	/** The components of an integer valued answer of the given rank. */
	static int[] ints(Val val, int rank) {
		assertEquals(rank, val.rank(), "rank");
		assertEquals(true, val.integral(), "integral");

		int[] components = new int[rank];
		for (int i = 0; i < rank; i++) {
			components[i] = val.i(i);
		}

		return components;
	}

	/** A mat4 answer, column major as JOML holds it. */
	static float[] matrix(Val val) {
		return floats(val, 16);
	}

	static float[] columnMajor(Matrix4fc m) {
		return m.get(new float[16]);
	}

	/** {@code a * b}, worked out in double precision from the floats each holds, column major. */
	static double[] product(Matrix4fc a, Matrix4fc b) {
		float[] left = columnMajor(a);
		float[] right = columnMajor(b);
		double[] product = new double[16];
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				double sum = 0.0;
				for (int k = 0; k < 4; k++) {
					sum += (double) left[k * 4 + row] * right[column * 4 + k];
				}

				product[column * 4 + row] = sum;
			}
		}

		return product;
	}
}
