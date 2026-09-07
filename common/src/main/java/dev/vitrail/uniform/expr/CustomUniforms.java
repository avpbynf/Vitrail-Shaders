package dev.vitrail.uniform.expr;

import dev.vitrail.uniform.expr.kroppeb.stareval.element.ExpressionElement;
import dev.vitrail.uniform.expr.kroppeb.stareval.expression.Expression;
import dev.vitrail.uniform.expr.kroppeb.stareval.expression.VariableExpression;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FunctionContext;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.FunctionReturn;
import dev.vitrail.uniform.expr.kroppeb.stareval.function.Type;
import dev.vitrail.uniform.expr.kroppeb.stareval.parser.Parser;
import dev.vitrail.uniform.expr.kroppeb.stareval.resolver.ExpressionResolver;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.UniformSource;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.WorldState;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The uniforms a pack declares for itself, resolved once at load and evaluated once a frame.
 * <p>
 * A {@code variable.} is written as an intermediate and a {@code uniform.} as a value the shader
 * reads. They share one namespace, they may refer to each other in any order, and they may read
 * any value the engine catalogue answers. That is the whole feature, and it is why it is written
 * as a graph rather than as a list: BSL's {@code shadowFade} is four other declarations deep, and
 * evaluating those out of order gives a plausible number rather than an error.
 * <p>
 * <strong>The two keywords decide nothing at the point a program is written, and that is the
 * reference's behaviour rather than a shortcut.</strong> Iris records the keyword on its
 * {@code Builder.Variable} and reads it in exactly one place, a list it builds and drops on the
 * spot ({@code uniforms/custom/CustomUniforms.java:64-67}); what actually reaches a program is
 * {@code assignTo} ({@code :189-202}), which walks every declaration that resolved and takes a
 * location for each one the program declares. So a pack may write {@code variable.} and still read
 * the value as a uniform, and one pack of the corpus does: E-LITE declares
 * {@code variable.float.hour_world} in its {@code shaders.properties} and declares
 * {@code uniform float hour_world} in {@code shaders/lib/oscilator_utils.glsl:9}.
 * <p>
 * Three rules decide what happens when a pack gets it wrong, and all three exist so that a
 * mistake stays named instead of turning into a permanently wrong image:
 * <ul>
 *     <li>a declaration that does not parse, or that reads a name nothing answers, is dropped and
 *     named. It is never evaluated as zero;</li>
 *     <li>everything that depended on it is dropped and named with it, rather than reading zero
 *     from the middle of the graph;</li>
 *     <li>a declaration that shadows a name the engine already answers is refused, so that a pack
 *     cannot quietly redefine what the engine means by {@code cameraPosition}.</li>
 * </ul>
 * <p>
 * The graph walk is adapted from {@code net.irisshaders.iris.uniforms.custom.CustomUniforms}, see
 * NOTICE. One deliberate difference: a cycle drops the uniforms it runs through and names them,
 * where Iris throws. A pack that writes a cycle in one line should lose that line, not the frame.
 */
public final class CustomUniforms implements FunctionContext, FrameClock {

	/** The six types a declaration may take. {@code mat4} is refused, as it is by OptiFine. */
	private static final Map<String, Type> TYPES = Map.of(
			"bool", Type.Boolean,
			"float", Type.Float,
			"int", Type.Int,
			"vec2", VectorType.VEC2,
			"vec3", VectorType.VEC3,
			"vec4", VectorType.VEC4);

	private final Map<String, Input> engineInputs = new LinkedHashMap<>();
	private final Map<String, Derived> declared = new LinkedHashMap<>();
	private final Map<Node, List<Node>> dependsOn = new LinkedHashMap<>();

	/**
	 * The names the pack wrote as {@code uniform.} rather than as {@code variable.}. It decides
	 * nothing about what a program may read, for the reason at the top of this class; what it still
	 * decides is which declarations {@link #prune} walks out from.
	 */
	private final Set<String> exposedNames = new LinkedHashSet<>();

	private final List<Node> order = new ArrayList<>();
	private final Set<Node> live = new LinkedHashSet<>();

	/** What was lost while a frame was being evaluated, waiting for somebody able to log it. */
	private final List<String> problems = new ArrayList<>();

	private WorldState world;
	private float deltaSeconds;

	private CustomUniforms() {
	}

	public static Builder builder() {
		return new BuilderImpl();
	}

	/**
	 * Brings every value up to date, in dependency order. Called once a frame, after the engine
	 * catalogue is current and before any program writes its block, and never once per program: a
	 * {@code smooth()} advances every time it is read, so reading it twice in one frame would make
	 * it fade at twice the speed on a pack that happens to use it in two passes.
	 * <p>
	 * An expression that throws while it is evaluated stops being evaluated, and so does everything
	 * that reads it; both are named, and both hold whatever they last stood at. That is a worse
	 * answer than a value and a much better one than the alternative, which is what happens today:
	 * the throw leaves here, reaches the draw call and switches the pack off for the rest of the
	 * session over one expression that only fails on one frame.
	 */
	public void update(WorldState world, float dt) {
		this.world = world;
		this.deltaSeconds = dt;

		List<Node> failed = null;
		for (Node node : this.order) {
			try {
				node.refresh();
			} catch (RuntimeException e) {
				if (failed == null) {
					failed = new ArrayList<>();
				}

				failed.add(node);
				this.problems.add(node.name + ": no longer evaluated, it threw on frame "
						+ world.frameCounter() + ", " + reason(e));
			}
		}

		if (failed != null) {
			drop(failed);
		}
	}

	/**
	 * One line per declaration lost since the last call, and nothing on any other frame. Drained
	 * rather than read, because the caller logs it and a line said twice is a line nobody reads.
	 */
	public List<String> drainProblems() {
		if (this.problems.isEmpty()) {
			return List.of();
		}

		List<String> drained = List.copyOf(this.problems);
		this.problems.clear();

		return drained;
	}

	/** Takes the named nodes out of the walk, and everything that reads them with them. */
	private void drop(List<Node> failed) {
		Set<Node> dead = new LinkedHashSet<>(failed);

		boolean growing = true;
		while (growing) {
			growing = false;
			for (Node node : this.order) {
				if (dead.contains(node)) {
					continue;
				}

				for (Node dependency : this.dependsOn.getOrDefault(node, List.of())) {
					if (dead.contains(dependency)) {
						dead.add(node);
						this.problems.add(node.name + ": no longer evaluated either, it reads "
								+ dependency.name);
						growing = true;
						break;
					}
				}
			}
		}

		this.live.removeAll(dead);
		this.order.removeAll(dead);
	}

	@Override
	public float deltaSeconds() {
		return this.deltaSeconds;
	}

	/** Null when the pack declares no such name, or when the declaration was dropped on the way. */
	public UniformSource source(String name) {
		Derived node = this.declared.get(name);
		if (node == null || !this.live.contains(node)) {
			return null;
		}

		return (_, out) -> node.writeInto(out);
	}

	/**
	 * The block shape the value takes. The engine catalogue does not know these names until they
	 * are layered onto it, so it is answered here.
	 */
	public UniformShape shape(String name) {
		Derived node = this.declared.get(name);

		return node == null ? null : Type.convert(node.type);
	}

	/**
	 * The names a program can read, which is every declaration that survived resolution and is
	 * still reached, in declaration order. Both keywords, for the reason at the top of this class.
	 */
	public Set<String> exposed() {
		Set<String> names = new LinkedHashSet<>();
		for (String name : this.declared.keySet()) {
			if (source(name) != null) {
				names.add(name);
			}
		}

		return Collections.unmodifiableSet(names);
	}

	/** The engine's table with the pack's own names on top of it, which is what a program reads. */
	public UniformCatalog layerOn(UniformCatalog engine) {
		UniformCatalog.Builder builder = UniformCatalog.builder(engine);
		for (String name : exposed()) {
			builder.add(name, shape(name), source(name));
		}

		return builder.build();
	}

	@Override
	public boolean hasVariable(String name) {
		return this.engineInputs.containsKey(name) || this.declared.containsKey(name);
	}

	@Override
	public Expression getVariable(String name) {
		Node node = this.engineInputs.get(name);
		if (node == null) {
			node = this.declared.get(name);
		}
		if (node == null) {
			throw new IllegalStateException("Unknown variable: " + name);
		}

		return node;
	}

	public interface Builder {

		/**
		 * Takes one declaration as the properties file wrote it.
		 *
		 * @param exposed true for a {@code uniform.} line, false for a {@code variable.} one. It
		 *                does not decide what a program may read, which is set out at the top of
		 *                this class; it decides which declarations the graph is walked out from
		 */
		Builder declare(String name, String type, String expression, boolean exposed);

		/**
		 * Resolves what was declared into a graph, in the order the values depend on each other.
		 *
		 * @param problems receives one line per declaration dropped, naming it and saying why
		 */
		CustomUniforms build(UniformCatalog engine, List<String> problems);
	}

	/** One declaration as the properties file wrote it, parsed but not yet resolved. */
	private record Declaration(String name, Type type, ExpressionElement tree, boolean exposed) {
	}

	private static final class BuilderImpl implements Builder {

		private final Map<String, Declaration> declarations = new LinkedHashMap<>();
		private final List<String> refused = new ArrayList<>();

		@Override
		public Builder declare(String name, String type, String expression, boolean exposed) {
			if (this.declarations.containsKey(name)) {
				// The first one wins, as it does in Iris. Two live declarations of one name mean
				// the conditionals around them were not read, which is a bug upstream of here.
				this.refused.add(name + ": declared more than once, the later one is ignored");
				return this;
			}

			Type declared = TYPES.get(type);
			if (declared == null) {
				this.refused.add(name + ": " + type + " is not a type a custom uniform may take");
				return this;
			}

			ExpressionElement tree;
			try {
				tree = Parser.parse(expression, ExprGrammar.options);
			} catch (Exception e) {
				this.refused.add(name + ": " + reason(e) + " (= " + expression + ")");
				return this;
			}

			this.declarations.put(name, new Declaration(name, declared, tree, exposed));

			return this;
		}

		@Override
		public CustomUniforms build(UniformCatalog engine, List<String> problems) {
			problems.addAll(this.refused);

			CustomUniforms uniforms = new CustomUniforms();
			uniforms.resolve(engine, this.declarations, problems);

			return uniforms;
		}
	}

	private void resolve(UniformCatalog engine, Map<String, Declaration> declarations,
			List<String> problems) {
		ExpressionResolver resolver = new ExpressionResolver(ExprFunctions.functions,
				name -> typeOf(engine, declarations, name));

		for (Declaration declaration : declarations.values()) {
			if (engine.source(declaration.name()) != null) {
				problems.add(declaration.name() + ": shadows a value the engine already answers");
				continue;
			}

			try {
				Expression expression = resolver.resolveExpression(declaration.type(), declaration.tree());
				this.declared.put(declaration.name(),
						new Derived(declaration.name(), declaration.type(), expression));
				if (declaration.exposed()) {
					this.exposedNames.add(declaration.name());
				}
			} catch (Exception e) {
				problems.add(declaration.name() + ": " + reason(e));
			}
		}

		sort(problems);
		prune();
	}

	/**
	 * What the resolver is told a name means. The engine is asked first, so that a declaration
	 * cannot change what a name means for the declarations around it either.
	 * <p>
	 * Asking is what creates the input, and that is the point: only the engine values a pack
	 * actually mentions are read every frame, a few dozen rather than the two hundred odd the
	 * catalogue holds.
	 */
	private Type typeOf(UniformCatalog engine, Map<String, Declaration> declarations, String name) {
		Input input = this.engineInputs.get(name);
		if (input != null) {
			return input.type;
		}

		UniformSource source = engine.source(name);
		UniformShape shape = engine.natural(name);
		if (source != null && shape != null) {
			Type type = Type.of(shape);
			if (type != null) {
				this.engineInputs.put(name, new Input(name, type, source));
				return type;
			}
		}

		Declaration declaration = declarations.get(name);

		return declaration == null ? null : declaration.type();
	}

	/**
	 * Orders the graph and drops what cannot be evaluated. A node whose dependency is missing is
	 * broken, so is everything that reaches it, and so is anything left standing in a cycle. All
	 * three are named rather than left to read zero.
	 */
	private void sort(List<String> problems) {
		Map<Node, List<Node>> requiredBy = new LinkedHashMap<>();
		Map<Node, Integer> remaining = new LinkedHashMap<>();
		Set<Node> broken = new LinkedHashSet<>();

		for (Node node : this.engineInputs.values()) {
			requiredBy.put(node, new ArrayList<>());
		}
		for (Node node : this.declared.values()) {
			requiredBy.put(node, new ArrayList<>());
		}

		FunctionReturn scratch = new FunctionReturn();
		for (Derived node : this.declared.values()) {
			Set<VariableExpression> variables = new LinkedHashSet<>();
			node.expression.listVariables(variables);

			List<Node> dependencies = new ArrayList<>();
			for (VariableExpression variable : variables) {
				if (variable.partialEval(this, scratch) instanceof Node dependency) {
					dependencies.add(dependency);
				} else {
					broken.add(node);
				}
			}

			if (dependencies.isEmpty()) {
				continue;
			}

			this.dependsOn.put(node, dependencies);
			remaining.put(node, dependencies.size());
			for (Node dependency : dependencies) {
				requiredBy.get(dependency).add(node);
			}
		}

		Deque<Node> free = new ArrayDeque<>();
		for (Node node : requiredBy.keySet()) {
			if (!remaining.containsKey(node)) {
				free.add(node);
			}
		}

		while (!free.isEmpty()) {
			Node node = free.removeLast();
			if (broken.contains(node)) {
				broken.addAll(requiredBy.get(node));
			} else {
				this.order.add(node);
			}

			for (Node dependent : requiredBy.get(node)) {
				if (remaining.merge(dependent, -1, Integer::sum) == 0) {
					remaining.remove(dependent);
					free.add(dependent);
				}
			}
		}

		for (Node node : broken) {
			problems.add(node.name + ": dropped, it or something it reads could not be resolved");
		}

		// Anything the walk never freed is in a cycle, or hangs off one.
		for (Node node : remaining.keySet()) {
			problems.add(node.name + ": dropped, it takes part in a circular reference");
		}
	}

	/**
	 * Keeps only what a {@code uniform.} declaration reaches. A pack that leaves a {@code variable.}
	 * behind after an edit should not pay for it every frame, and neither should one whose engine
	 * inputs are all read by declarations that were dropped.
	 * <p>
	 * <strong>This is the one place the keyword still decides anything, and it leaves a
	 * divergence.</strong> Iris keeps every declaration that resolved and drops one only in
	 * {@code optimise} ({@code uniforms/custom/CustomUniforms.java:241}), which counts the passes
	 * that have already claimed a location ({@code pipeline/IrisRenderingPipeline.java:477}) and so
	 * keeps whatever one of them asked for, whichever keyword wrote it. Nothing here can do the same:
	 * this table is built once, before a single program has been read, so it cannot tell a leftover
	 * declaration from one a program is about to name, and keeping every one means evaluating every
	 * one on every frame. What it costs is measured over the corpus rather than guessed: exactly one
	 * name is written as {@code variable.} and declared as a uniform by the pack's own GLSL, E-LITE's
	 * {@code hour_world}, and its {@code day_moment} reads it, so the walk reaches it and no pack of
	 * the corpus loses a value here.
	 */
	private void prune() {
		Deque<Node> pending = new ArrayDeque<>();
		for (String name : this.exposedNames) {
			Derived node = this.declared.get(name);
			if (node != null && this.order.contains(node)) {
				pending.add(node);
			}
		}

		while (!pending.isEmpty()) {
			Node node = pending.removeLast();
			if (this.live.add(node)) {
				pending.addAll(this.dependsOn.getOrDefault(node, List.of()));
			}
		}

		this.order.retainAll(this.live);
	}

	private static String reason(Throwable e) {
		String message = e.getMessage();

		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}

	/**
	 * One value in the graph, holding what it last evaluated to.
	 * <p>
	 * The value is held here rather than re-read from the evaluator on demand because the vector
	 * functions hand back a buffer they own and overwrite on their next call. Anything that has to
	 * outlive one call has to be copied out of it.
	 */
	private abstract static class Node implements VariableExpression {

		final String name;
		final Type type;
		final Object object;

		private boolean booleanValue;
		private int intValue;
		private float floatValue;

		Node(String name, Type type) {
			this.name = name;
			this.type = type;
			this.object = carrier(type);
		}

		// The same identity ladder as Type.convert, and safe for the same narrow reason: both kinds
		// of node are built from a fixed set of constants. A Derived takes its type from TYPES
		// above, which names six and no ivec or matrix among them; an Input takes Type.of(shape),
		// and that is what makes the last five branches here reachable at all. A type equal but not
		// identical would fall through to null and the uniform would carry nothing, silently.
		@SuppressWarnings("ReferenceEquality")
		private static Object carrier(Type type) {
			if (type == VectorType.VEC2) return new Vector2f();
			if (type == VectorType.VEC3) return new Vector3f();
			if (type == VectorType.VEC4) return new Vector4f();
			if (type == VectorType.I_VEC2) return new Vector2i();
			if (type == VectorType.I_VEC3) return new Vector3i();
			if (type == VectorType.I_VEC4) return new Vector4i();
			if (type == MatrixType.MAT3) return new Matrix3f();
			if (type == MatrixType.MAT4) return new Matrix4f();

			return null;
		}

		abstract void refresh();

		@Override
		@SuppressWarnings("ReferenceEquality")
		public final void evaluateTo(FunctionContext context, FunctionReturn functionReturn) {
			if (this.type == Type.Boolean) {
				functionReturn.booleanReturn = this.booleanValue;
			} else if (this.type == Type.Int) {
				functionReturn.intReturn = this.intValue;
			} else if (this.type == Type.Float) {
				functionReturn.floatReturn = this.floatValue;
			} else {
				functionReturn.objectReturn = this.object;
			}
		}

		@SuppressWarnings("ReferenceEquality")
		final void writeInto(Val out) {
			if (this.type == Type.Boolean) {
				out.set(this.booleanValue);
			} else if (this.type == Type.Int) {
				out.set(this.intValue);
			} else if (this.type == Type.Float) {
				out.set(this.floatValue);
			} else if (this.object instanceof Vector2f v) {
				out.set(v.x, v.y);
			} else if (this.object instanceof Vector3f v) {
				out.set(v.x, v.y, v.z);
			} else if (this.object instanceof Vector4f v) {
				out.set(v.x, v.y, v.z, v.w);
			}
		}

		final void setBoolean(boolean value) {
			this.booleanValue = value;
		}

		final void setInt(int value) {
			this.intValue = value;
		}

		final void setFloat(float value) {
			this.floatValue = value;
		}
	}

	/** A value the engine answers, read out of the catalogue into the graph once a frame. */
	private final class Input extends Node {

		private final UniformSource source;
		private final Val value = new Val();

		private Input(String name, Type type, UniformSource source) {
			super(name, type);
			this.source = source;
		}

		@Override
		@SuppressWarnings("ReferenceEquality")
		void refresh() {
			this.source.read(CustomUniforms.this.world, this.value);

			if (this.type == Type.Int) {
				setInt(this.value.i(0));
			} else if (this.type == Type.Float) {
				setFloat(this.value.f(0));
			} else if (this.object instanceof Vector2f v) {
				v.set(this.value.f(0), this.value.f(1));
			} else if (this.object instanceof Vector3f v) {
				v.set(this.value.f(0), this.value.f(1), this.value.f(2));
			} else if (this.object instanceof Vector4f v) {
				v.set(this.value.f(0), this.value.f(1), this.value.f(2), this.value.f(3));
			} else if (this.object instanceof Vector2i v) {
				v.set(this.value.i(0), this.value.i(1));
			} else if (this.object instanceof Vector3i v) {
				v.set(this.value.i(0), this.value.i(1), this.value.i(2));
			} else if (this.object instanceof Vector4i v) {
				v.set(this.value.i(0), this.value.i(1), this.value.i(2), this.value.i(3));
			} else if (this.object instanceof Matrix3f m) {
				m.set(this.value.mat3());
			} else if (this.object instanceof Matrix4f m) {
				m.set(this.value.mat4());
			}
		}
	}

	/** A value the pack computes, evaluated in dependency order. */
	private final class Derived extends Node {

		private final Expression expression;
		private final FunctionReturn held = new FunctionReturn();

		private Derived(String name, Type type, Expression expression) {
			super(name, type);
			this.expression = expression;
		}

		@Override
		@SuppressWarnings("ReferenceEquality")
		void refresh() {
			this.expression.evaluateTo(CustomUniforms.this, this.held);

			if (this.type == Type.Boolean) {
				setBoolean(this.held.booleanReturn);
			} else if (this.type == Type.Int) {
				setInt(this.held.intReturn);
			} else if (this.type == Type.Float) {
				setFloat(this.held.floatReturn);
			} else if (this.object instanceof Vector2f v) {
				v.set((Vector2f) this.held.objectReturn);
			} else if (this.object instanceof Vector3f v) {
				v.set((Vector3f) this.held.objectReturn);
			} else if (this.object instanceof Vector4f v) {
				v.set((Vector4f) this.held.objectReturn);
			}
		}
	}
}
