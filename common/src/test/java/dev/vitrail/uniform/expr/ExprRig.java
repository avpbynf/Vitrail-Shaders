package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.UniformSource;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.WorldState;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Matrix4f;

/**
 * Everything a test of the pack expression language needs and the game is not: a world that
 * answers from a map, a small engine catalogue whose values a test changes between frames, and the
 * one-line declarations of a {@code shaders.properties} file.
 * <p>
 * The world is a proxy over {@link WorldState}, which has two hundred accessors and needs a game to
 * implement. An accessor nobody set answers zero, false or null, so a source that reads a value the
 * test did not set reads the zero a pack would read before the engine had one, and the accessors
 * the expression package itself reads ({@code frameCounter}, for the frame a failure is named
 * after) are set by {@link #frame}.
 */
final class ExprRig {

	private static final Pattern LINE = Pattern.compile(
			"(uniform|variable)\\.(\\w+)\\.(\\w+)\\s*=\\s*(.*)");

	/** What each accessor of the world answers, by method name, until a test says otherwise. */
	final Map<String, Object> answers = new HashMap<>();

	/** How many times each engine name was read since {@link #build}, by name. */
	final Map<String, Integer> reads = new LinkedHashMap<>();

	/** The values the synthetic engine answers, which a test may overwrite before a frame. */
	float fa = 2.5F;
	int ia = 7;
	float[] va = {1.0F, 2.0F, 3.0F};
	float[] vb = {4.0F, 5.0F};
	float[] vc = {1.0F, 2.0F, 3.0F, 4.0F};
	int[] iv = {6, 9};
	final Matrix4f m4 = new Matrix4f(
			1.0F, 2.0F, 3.0F, 4.0F,
			5.0F, 6.0F, 7.0F, 8.0F,
			9.0F, 10.0F, 11.0F, 12.0F,
			13.0F, 14.0F, 15.0F, 16.0F);

	final WorldState world = (WorldState) Proxy.newProxyInstance(
			WorldState.class.getClassLoader(), new Class<?>[] {WorldState.class},
			(proxy, method, args) -> {
				if (this.answers.containsKey(method.getName())) {
					return this.answers.get(method.getName());
				}

				return switch (method.getName()) {
					case "toString" -> "ExprRig world";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> zero(method.getReturnType());
				};
			});

	private final Val scratch = new Val();

	/**
	 * The synthetic engine: one name of every shape a custom uniform can read, plus a name whose
	 * shape nothing can carry. Each read is counted, so a test can tell an input that is read every
	 * frame from one that is only looked at.
	 */
	UniformCatalog engine() {
		return UniformCatalog.builder()
				.add("fa", UniformShape.FLOAT, counted("fa", (w, o) -> o.set(this.fa)))
				.add("ia", UniformShape.INT, counted("ia", (w, o) -> o.set(this.ia)))
				.add("va", UniformShape.VEC3, counted("va", (w, o) -> o.set(
						this.va[0], this.va[1], this.va[2])))
				.add("vb", UniformShape.VEC2, counted("vb", (w, o) -> o.set(this.vb[0], this.vb[1])))
				.add("vc", UniformShape.VEC4, counted("vc", (w, o) -> o.set(
						this.vc[0], this.vc[1], this.vc[2], this.vc[3])))
				.add("iv", UniformShape.IVEC2, counted("iv", (w, o) -> o.set(this.iv[0], this.iv[1])))
				.add("m4", UniformShape.MAT4, counted("m4", (w, o) -> o.set(this.m4)))
				.add("fog", UniformShape.FOG, counted("fog", (w, o) -> o.set(0.0F)))
				.build();
	}

	private UniformSource counted(String name, UniformSource source) {
		return (w, o) -> {
			this.reads.merge(name, 1, Integer::sum);
			source.read(w, o);
		};
	}

	/** A graph over the synthetic engine, declared as the properties file spells it. */
	CustomUniforms build(List<String> problems, String... lines) {
		return build(engine(), problems, lines);
	}

	CustomUniforms build(UniformCatalog engine, List<String> problems, String... lines) {
		this.reads.clear();
		CustomUniforms.Builder builder = CustomUniforms.builder();
		for (String line : lines) {
			Matcher matcher = LINE.matcher(line);
			assertTrue(matcher.matches(), "not a declaration: " + line);
			builder.declare(matcher.group(3), matcher.group(2), matcher.group(4).trim(),
					matcher.group(1).equals("uniform"));
		}

		return builder.build(engine, problems);
	}

	/** The same, asserting that nothing was dropped. */
	CustomUniforms buildClean(String... lines) {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = build(problems, lines);
		assertEquals(List.of(), problems, "declarations dropped");

		return uniforms;
	}

	/**
	 * Steps the frame the way the engine does: the counter, the clock and the frame's duration
	 * move, then the graph is brought up to date.
	 */
	void frame(CustomUniforms uniforms, float dt) {
		int counter = (Integer) this.answers.getOrDefault("frameCounter", -1) + 1;
		float clock = (Float) this.answers.getOrDefault("frameTimeCounter", 0.0F) + dt;
		this.answers.put("frameCounter", counter);
		this.answers.put("frameTime", dt);
		this.answers.put("frameTimeCounter", clock);
		uniforms.update(this.world, dt);
	}

	/** One frame of one tenth of a second, the step nothing in these tests depends on. */
	void frame(CustomUniforms uniforms) {
		frame(uniforms, 0.1F);
	}

	Val read(CustomUniforms uniforms, String name) {
		UniformSource source = uniforms.source(name);
		assertNotNull(source, name + " has no source: it was dropped or never declared");
		source.read(this.world, this.scratch);

		return this.scratch;
	}

	float f(CustomUniforms uniforms, String name) {
		return read(uniforms, name).f(0);
	}

	int i(CustomUniforms uniforms, String name) {
		Val val = read(uniforms, name);
		assertTrue(val.integral(), name + " was written as a float, not as an int or a bool");

		return val.i(0);
	}

	boolean b(CustomUniforms uniforms, String name) {
		return i(uniforms, name) != 0;
	}

	float[] vec(CustomUniforms uniforms, String name, int size) {
		Val val = read(uniforms, name);
		assertEquals(size, val.rank(), name + " has the wrong rank");
		float[] out = new float[size];
		for (int k = 0; k < size; k++) {
			out[k] = val.f(k);
		}

		return out;
	}

	// One expression, one uniform, one frame: what most of the function tests need.

	float floatOf(String expression) {
		CustomUniforms uniforms = buildClean("uniform.float.r = " + expression);
		frame(uniforms);

		return f(uniforms, "r");
	}

	int intOf(String expression) {
		CustomUniforms uniforms = buildClean("uniform.int.r = " + expression);
		frame(uniforms);

		return i(uniforms, "r");
	}

	boolean boolOf(String expression) {
		CustomUniforms uniforms = buildClean("uniform.bool.r = " + expression);
		frame(uniforms);

		return b(uniforms, "r");
	}

	float[] vecOf(int size, String expression) {
		CustomUniforms uniforms = buildClean("uniform.vec" + size + ".r = " + expression);
		frame(uniforms);

		return vec(uniforms, "r", size);
	}

	/**
	 * The one line that says why a declaration was refused, and the assertion that it was refused
	 * and nothing else was said. A refusal is a problem string at build time; it never reaches a
	 * frame.
	 */
	String refusal(String type, String expression) {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = build(problems, "uniform." + type + ".r = " + expression);
		assertEquals(1, problems.size(), "expected one line about r: " + problems);
		assertNull(uniforms.source("r"), "a refused declaration must have no source");
		assertTrue(problems.getFirst().startsWith("r: "), problems.getFirst());

		return problems.getFirst();
	}

	/**
	 * Declares one expression that throws when it is evaluated, runs a frame, and returns what the
	 * graph said about the loss. The declaration must have been dropped by then.
	 */
	List<String> throwing(String type, String expression) {
		CustomUniforms uniforms = buildClean("uniform." + type + ".r = " + expression);
		frame(uniforms);
		assertNull(uniforms.source("r"), expression + " should have been dropped");
		List<String> drained = uniforms.drainProblems();
		assertTrue(!drained.isEmpty(), "and said so");

		return drained;
	}

	private static Object zero(Class<?> type) {
		if (type == int.class) {
			return 0;
		} else if (type == long.class) {
			return 0L;
		} else if (type == float.class) {
			return 0.0F;
		} else if (type == boolean.class) {
			return false;
		}

		return null;
	}
}
