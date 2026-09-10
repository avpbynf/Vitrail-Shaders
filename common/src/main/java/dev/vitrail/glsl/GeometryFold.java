package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds a geometry stage that only hands each corner of its triangle on into the fragment stage
 * after it, for a device that cannot run a geometry stage at all.
 * <p>
 * Vulkan makes {@code geometryShader} an optional feature and Metal has no such stage, so on a Mac
 * every program shipping a {@code .gsh} was set aside and drawn by the game. What iterationT writes
 * there needs no third stage: its terrain copies each of the three corners from the {@code v_} names
 * its vertex stage writes onto the names its fragment stage reads. Such a stage is taken out of the
 * pipeline, the fragment stage declares the vertex stage's names as its inputs, and the names it read
 * are filled from them at the head of its {@code main}. The picture is the same one: a varying copied
 * corner by corner and interpolated after the copy is that varying interpolated.
 * <p>
 * <strong>The shape is read strictly, and any stage that does something else is refused</strong>,
 * which is what it was before: triangles in, a strip of three vertices out, and a
 * {@code main} made of one loop over the three corners whose body writes {@code gl_Position} and each
 * output from the same corner of one input, then {@code EmitVertex}, then {@code EndPrimitive}.
 * The text read is the stage after the preprocessor, which {@code render/GeometryStage} runs first,
 * so a branch the settings do not take is not there to be matched.
 * <p>
 * <strong>One value worked out from the whole triangle is served, because a pixel can answer
 * it.</strong> iterationT sizes the texture of a block face before its loop, as the largest change of
 * the texture coordinate per unit of view space length over the triangle's three edges, rounds that
 * to a power of two, and hands it on flat ({@code Lib/Programs/Gbuffers/Terrain_GS.glsl:36-45}). Its
 * fragment stage reads it as the size of one sprite of the atlas, to keep its anisotropic taps inside
 * the sprite and to pick their level ({@code Terrain_FS.glsl:57-74}), so a value that differed would
 * be taps read from the neighbouring sprite. The fragment stage has the same quantity from its
 * screen space derivatives: both varyings are affine over one triangle, so what the pixel step moves
 * the first by is its gradient against the second applied to what it moves the second by, exactly and
 * not to first order, and the length of that gradient is the largest change over any direction of the
 * plane. The edges reach that largest change wherever one of them runs along the gradient, which a
 * block face always has: each triangle of a quad carries the two edges its sprite is laid along. On a
 * triangle with no such edge the pixel's answer is the larger of the two, and the rounding to a power
 * of two absorbs the difference except near the half way point between two powers. A triangle seen
 * exactly edge on has no gradient to speak of, and is guarded against a division by nought rather
 * than answered. Only that one form is served: a triangle-wide value written any other way is
 * refused, as iterationT's sky is, which tells the moon from the sun by the spread of the texture
 * coordinates over the whole quad, and no pixel can see a spread.
 * <p>
 * <strong>What the fragment stage works out there has to mean what it meant in the stage.</strong>
 * The two varyings of a rate are interpolated plainly, as the fragment stage declares them where
 * it reads them: a flat one has no derivative, a noperspective one is not affine over the triangle
 * alongside a plain one, and a centroid one is sampled away from where its derivative is taken. A
 * statement before the loop reads only rates, outputs already worked out, built-in functions and
 * uniforms other than textures that the fragment stage declares under the same name and type, a
 * texture being read at its base level in the geometry stage and at a level of the pixel's
 * choosing in the fragment stage. Its text is carried over as written: a name only the geometry
 * stage declares would not compile in the fragment stage, or would read that stage's own symbol
 * of the same name without a word.
 * <p>
 * No Minecraft class is named here, so the fold runs off game against the corpus like the rest of
 * the translation.
 */
public final class GeometryFold {

	/** The helper the fold writes into the fragment stage, overloaded per pair of types it needs. */
	private static final String RATE = "ofEdgeRate";

	/** The names the helper spells, which the fragment stage must not already use for a macro. */
	private static final List<String> RATE_NAMES =
			List.of(RATE, "ofAx", "ofAy", "ofBx", "ofBy", "ofXx", "ofXy", "ofYy");

	/** What may stand in front of a varying's storage qualifier. */
	private static final Set<String> QUALIFIERS = Set.of("flat", "smooth", "noperspective",
			"centroid", "highp", "mediump", "lowp");

	/** The types the changing varying of a rate may have. */
	private static final Set<String> RATE_OF = Set.of("float", "vec2", "vec3", "vec4");

	/** The types the varying a rate is measured against may have, which has to span a plane. */
	private static final Set<String> RATE_OVER = Set.of("vec2", "vec3", "vec4");

	/**
	 * The tokens of one edge term, {@code abs(A[a] - A[b]) / distance(B[a], B[b])}, with null where a
	 * name or a corner stands.
	 */
	private static final String[] EDGE = {"abs", "(", null, "[", null, "]", "-", null, "[", null, "]",
		")", "/", "distance", "(", null, "[", null, "]", ",", null, "[", null, "]", ")"};

	/** {@code max(max(edge, edge), edge)}, counted in tokens. */
	private static final int RATE_TOKENS = 4 + 3 * EDGE.length + 4;

	private GeometryFold() {
	}

	/**
	 * What one fold answered: the fragment stage to compile in place of the two, or the clause saying
	 * why the geometry stage cannot be folded. Exactly one of the two is null.
	 *
	 * @param fragment the rewritten fragment stage
	 * @param refusal  what the stage does that the fold does not serve, worded to follow "the stage"
	 */
	public record Result(String fragment, String refusal) {

		public static Result refused(String refusal) {
			return new Result(null, refusal);
		}

		public boolean folded() {
			return this.fragment != null;
		}
	}

	/**
	 * Folds a geometry stage into the fragment stage after it.
	 *
	 * @param geometry the geometry stage as the preprocessor left it, every directive settled
	 * @param fragment the translated fragment stage, directives standing, as the pipeline compiles it
	 */
	public static Result fold(String geometry, String fragment) {
		try {
			return new Result(rewrite(new Stage(new Code(geometry)), new Code(fragment)), null);
		} catch (Refused refused) {
			return Result.refused(refused.getMessage());
		}
	}

	/** The one way out of a reading that meets something it does not serve. */
	private static final class Refused extends Exception {

		private Refused(String clause) {
			super(clause, null, false, false);
		}
	}

	/** A varying of the geometry stage, with what was written in front of its storage qualifier. */
	private record Varying(String type, List<String> qualifiers) {
	}

	/**
	 * A local holding the largest change of one input per unit length of another over the triangle.
	 *
	 * @param type     the local's type, which is the changing input's
	 * @param of       the input that changes
	 * @param over     the input it is measured against
	 * @param overType that input's type
	 */
	private record Rate(String type, String of, String over, String overType) {
	}

	/** One edge term of a rate, with the two corners it spans. */
	private record Edge(String of, String over, int first, int second) {
	}

	/**
	 * One statement at file scope, from its first token to the semicolon that ends it, or to the
	 * closing brace of a function, whose opening brace {@code body} is. Minus one for a declaration.
	 */
	private record Statement(int start, int end, int body) {
	}

	/** A replacement of the significant tokens {@code from} to {@code to}, both included. */
	private record Edit(int from, int to, String text) {
	}

	/**
	 * The tokens of a text the compiler reads, each with where it stands in the whole stream: spaces,
	 * comments and every token on a preprocessor line are stepped over, and kept for the join back.
	 */
	private static final class Code {

		private final List<GlslLexer.Token> all;

		private final List<Integer> at = new ArrayList<>();

		private Code(String source) {
			this.all = GlslLexer.lex(source);
			for (int index = 0; index < this.all.size(); index++) {
				GlslLexer.Token token = this.all.get(index);
				if (token.directive() == null && !token.trivia()
						&& token.kind() != GlslLexer.Kind.NEWLINE) {
					this.at.add(index);
				}
			}
		}

		private int size() {
			return this.at.size();
		}

		private String text(int index) {
			return (index >= 0 && index < this.at.size()) ? this.all.get(this.at.get(index)).text() : "";
		}

		private boolean is(int index, String text) {
			return text(index).equals(text);
		}

		private boolean identifier(int index) {
			return index >= 0 && index < this.at.size()
					&& this.all.get(this.at.get(index)).kind() == GlslLexer.Kind.IDENTIFIER;
		}

		/** The index of the bracket closing the one at {@code open}, or minus one. */
		private int closing(int open) {
			String opening = text(open);
			String close = switch (opening) {
				case "(" -> ")";
				case "[" -> "]";
				case "{" -> "}";
				default -> "";
			};
			int depth = 0;
			for (int index = open; index < this.at.size(); index++) {
				if (is(index, opening)) {
					depth++;
				} else if (is(index, close)) {
					depth--;
					if (depth == 0) {
						return index;
					}
				}
			}

			return -1;
		}

		/** The tokens {@code from} to {@code to}, the second excluded, joined by single spaces. */
		private String join(int from, int to) {
			StringBuilder text = new StringBuilder();
			for (int index = from; index < to; index++) {
				if (index > from) {
					text.append(' ');
				}

				text.append(text(index));
			}

			return text.toString();
		}

		/** The tokens {@code from} to {@code to}, the second excluded, as they were written. */
		private String source(int from, int to) {
			StringBuilder text = new StringBuilder();
			for (int index = this.at.get(from); index < this.at.get(to); index++) {
				text.append(this.all.get(index).text());
			}

			return text.toString();
		}

		/**
		 * The uniforms declared at file scope, by name to type: loose ones and the members of a block
		 * without an instance name, each written as a bare type and name. Any other shape is left out,
		 * so a read of it is refused.
		 */
		private Map<String, String> uniforms() throws Refused {
			Map<String, String> uniforms = new HashMap<>();
			for (Statement statement : fileScope()) {
				int uniform = statement.start();
				while (uniform < statement.end() && !is(uniform, "uniform") && !is(uniform, "{")) {
					uniform++;
				}

				if (statement.body() >= 0 || !is(uniform, "uniform")) {
					continue;
				}

				if (identifier(uniform + 1) && is(uniform + 2, "{")) {
					int close = closing(uniform + 2);
					if (close + 1 != statement.end()) {
						continue;
					}

					int member = uniform + 3;
					while (member < close) {
						if (identifier(member) && identifier(member + 1) && is(member + 2, ";")) {
							uniforms.put(text(member + 1), text(member));
						}

						while (member < close && !is(member, ";")) {
							member++;
						}

						member++;
					}
				} else if (identifier(uniform + 1) && identifier(uniform + 2)
						&& uniform + 3 == statement.end()) {
					uniforms.put(text(uniform + 2), text(uniform + 1));
				}
			}

			return uniforms;
		}

		/**
		 * The statements at file scope. A brace after a closing parenthesis opens a function body and
		 * ends the statement at its closing brace; any other brace is a block the statement goes on
		 * past, a uniform block among them.
		 */
		private List<Statement> fileScope() throws Refused {
			List<Statement> statements = new ArrayList<>();
			int start = 0;
			int index = 0;
			while (index < size()) {
				String text = text(index);
				if (text.equals("(") || text.equals("[") || text.equals("{")) {
					int close = closing(index);
					if (close < 0) {
						throw new Refused("leaves a bracket open");
					}

					if (text.equals("{") && index > start && is(index - 1, ")")) {
						statements.add(new Statement(start, close, index));
						start = close + 1;
					}

					index = close + 1;
					continue;
				}

				if (text.equals(";")) {
					if (index > start) {
						statements.add(new Statement(start, index, -1));
					}

					start = index + 1;
				}

				index++;
			}

			return statements;
		}
	}

	/** The geometry stage, read into what the fold needs of it. */
	private static final class Stage {

		private final Code code;

		private final Map<String, Varying> inputs = new LinkedHashMap<>();

		private final Map<String, Varying> outputs = new LinkedHashMap<>();

		/** Each output the loop writes, to the input it copies. */
		private final Map<String, String> copies = new LinkedHashMap<>();

		/** Each output written before the loop, once for the whole triangle. */
		private final Set<String> wide = new LinkedHashSet<>();

		/** The locals holding a rate, by name. */
		private final Map<String, Rate> rates = new LinkedHashMap<>();

		/** What the fragment stage works out before anything else, in the order the stage wrote it. */
		private final List<String> prologue = new ArrayList<>();

		/** The stage's uniforms by name to type, which a statement before the loop may read. */
		private final Map<String, String> uniforms;

		/** The uniforms read before the loop, which the fragment stage has to declare alike. */
		private final Map<String, String> reads = new LinkedHashMap<>();

		/** The functions and structures the stage declares, which the fragment stage may lack. */
		private final Set<String> declared = new HashSet<>();

		private Stage(Code code) throws Refused {
			this.code = code;
			this.uniforms = code.uniforms();
			boolean triangles = false;
			int vertices = -1;
			int body = -1;
			int end = -1;
			for (Statement statement : code.fileScope()) {
				int start = statement.start();
				if (!code.is(start + 1, "main")) {
					declare(statement);
				}

				if (statement.body() >= 0) {
					if (code.is(start + 1, "main")) {
						if (body >= 0 || !code.is(start, "void") || !code.is(start + 2, "(")
								|| !code.is(start + 3, ")") || statement.body() != start + 4) {
							throw new Refused("declares its main in a shape the fold does not read");
						}

						body = statement.body() + 1;
						end = statement.end();
					}

					continue;
				}

				if (code.is(start, "layout")) {
					int close = code.closing(start + 1);
					if (!code.is(start + 1, "(") || close < 0 || close >= statement.end()) {
						throw new Refused("writes a layout the fold does not read");
					}

					String storage = code.join(close + 1, statement.end());
					if (storage.equals("in")) {
						if (!code.join(start + 2, close).equals("triangles")) {
							throw new Refused("takes something other than triangles in");
						}

						triangles = true;
					} else if (storage.equals("out")) {
						vertices = strip(code.join(start + 2, close));
					} else if (storage.startsWith("in ") || storage.startsWith("out ")
							|| storage.contains(" in ") || storage.contains(" out ")) {
						throw new Refused("places a varying by layout");
					}

					continue;
				}

				varying(statement);
			}

			if (!triangles) {
				throw new Refused("takes something other than triangles in");
			}

			if (vertices != 3) {
				throw new Refused("emits a strip of other than the three corners it takes in");
			}

			if (body < 0) {
				throw new Refused("has no main the fold can find");
			}

			main(body, end);
		}

		/** The vertex count of an output layout, or minus one for anything but a triangle strip. */
		private static int strip(String layout) {
			boolean strip = false;
			int vertices = -1;
			for (String argument : layout.split(",", -1)) {
				String written = argument.trim();
				if (written.equals("triangle_strip")) {
					strip = true;
				} else if (written.matches("max_vertices = [0-9]{1,9}")) {
					vertices = Integer.parseInt(written.substring(written.lastIndexOf(' ') + 1));
				} else {
					return -1;
				}
			}

			return strip ? vertices : -1;
		}

		/**
		 * Records the name of a function or a structure a file scope statement declares: the name in
		 * front of its first parenthesis when nothing is assigned or opened before it, or the name after
		 * {@code struct}.
		 */
		private void declare(Statement statement) {
			int start = statement.start();
			if (this.code.is(start, "struct")) {
				this.declared.add(this.code.text(start + 1));
				return;
			}

			for (int index = start; index < statement.end(); index++) {
				if (this.code.is(index, "=") || this.code.is(index, "{")) {
					return;
				}

				if (this.code.is(index, "(")) {
					if (this.code.identifier(index - 1)) {
						this.declared.add(this.code.text(index - 1));
					}

					return;
				}
			}
		}

		/** Whether a varying is interpolated plainly, which a derivative of it assumes. */
		private static boolean plain(Varying varying) {
			List<String> qualifiers = varying.qualifiers();
			return !qualifiers.contains("flat") && !qualifiers.contains("noperspective")
					&& !qualifiers.contains("centroid");
		}

		/** Records an input or an output declared at file scope, and passes over everything else. */
		private void varying(Statement statement) throws Refused {
			int start = statement.start();
			int end = statement.end();
			int storage = -1;
			for (int index = start; index < end; index++) {
				if (this.code.is(index, "(")) {
					return;
				}

				if (storage < 0 && (this.code.is(index, "in") || this.code.is(index, "out"))) {
					storage = index;
				}
			}

			if (storage < 0) {
				return;
			}

			List<String> qualifiers = new ArrayList<>();
			for (int index = start; index < storage; index++) {
				if (!QUALIFIERS.contains(this.code.text(index))) {
					throw new Refused("declares a varying in a shape the fold does not read");
				}

				qualifiers.add(this.code.text(index));
			}

			int name = storage + 2;
			if (!this.code.identifier(storage + 1) || !this.code.identifier(name)) {
				throw new Refused("declares a varying in a shape the fold does not read");
			}

			boolean input = this.code.is(storage, "in");
			boolean unsized = this.code.is(name + 2, "]") && name + 3 == end;
			boolean sized = this.code.is(name + 2, "3") && this.code.is(name + 3, "]")
					&& name + 4 == end;
			boolean shaped = input
					? this.code.is(name + 1, "[") && (unsized || sized)
					: name + 1 == end;
			if (!shaped) {
				throw new Refused("declares a varying in a shape the fold does not read");
			}

			Varying varying = new Varying(this.code.text(storage + 1), List.copyOf(qualifiers));
			if ((input ? this.inputs : this.outputs).put(this.code.text(name), varying) != null) {
				throw new Refused("declares " + this.code.text(name) + " twice");
			}
		}

		/** Reads the body of main, from the token after its brace to its closing brace. */
		private void main(int body, int end) throws Refused {
			int index = body;
			while (!this.code.is(index, "for")) {
				if (index >= end) {
					throw new Refused("never loops over its three corners");
				}

				int semicolon = statementEnd(index, end);
				before(index, semicolon);
				index = semicolon + 1;
			}

			index = loop(index, end);
			if (!this.code.is(index, "EndPrimitive") || !this.code.is(index + 1, "(")
					|| !this.code.is(index + 2, ")") || !this.code.is(index + 3, ";")
					|| index + 4 != end) {
				throw new Refused("does more after its loop than end the primitive");
			}

			for (String output : this.outputs.keySet()) {
				if (!this.copies.containsKey(output) && !this.wide.contains(output)) {
					throw new Refused("never writes " + output);
				}
			}
		}

		/** The semicolon ending the statement at {@code from}, which may not open a block. */
		private int statementEnd(int from, int end) throws Refused {
			for (int index = from; index < end; index++) {
				if (this.code.is(index, "(") || this.code.is(index, "[")) {
					index = this.code.closing(index);
					if (index < 0) {
						break;
					}
				} else if (this.code.is(index, ";")) {
					return index;
				} else if (this.code.is(index, "{")) {
					break;
				}
			}

			throw new Refused("does more before its loop than work out a value for the whole triangle");
		}

		/**
		 * One statement before the loop, which works out a value for the whole triangle: a local
		 * holding a rate, or an output assigned from locals, from outputs already written this way and
		 * from anything that is not a corner.
		 */
		private void before(int from, int semicolon) throws Refused {
			if (this.code.identifier(from) && this.code.identifier(from + 1)
					&& this.code.is(from + 2, "=")) {
				String type = this.code.text(from);
				String name = this.code.text(from + 1);
				Rate rate = rate(from + 3, semicolon);
				if (rate == null || !rate.type().equals(type) || this.inputs.containsKey(name)
						|| this.outputs.containsKey(name) || this.rates.containsKey(name)) {
					throw new Refused("works a value out of the whole triangle that a pixel cannot");
				}

				this.rates.put(name, rate);
				this.prologue.add(type + " " + name + " = " + RATE + "(dFdx(" + rate.of() + "), dFdy("
						+ rate.of() + "), dFdx(" + rate.over() + "), dFdy(" + rate.over() + "));");

				return;
			}

			String target = this.code.text(from);
			if (!this.outputs.containsKey(target) || !this.code.is(from + 1, "=")
					|| this.code.is(from + 2, "=")) {
				throw new Refused("does more before its loop than work out a value for the whole triangle");
			}

			for (int index = from + 2; index < semicolon; index++) {
				if (!this.code.identifier(index) || this.code.is(index - 1, ".")) {
					continue;
				}

				String name = this.code.text(index);
				if (this.code.is(index + 1, "(")) {
					if (this.declared.contains(name)) {
						throw new Refused("calls " + name + ", a function of its own, before its loop");
					}

					continue;
				}

				if (name.equals("true") || name.equals("false") || this.rates.containsKey(name)
						|| this.wide.contains(name)) {
					continue;
				}

				String type = this.uniforms.get(name);
				if (type == null || type.contains("sampler") || type.contains("image")) {
					throw new Refused("works a value out of the whole triangle that a pixel cannot");
				}

				this.reads.put(name, type);
			}

			this.wide.add(target);
			this.prologue.add(this.code.source(from, semicolon) + ";");
		}

		/** The rate an expression computes, or null when it is not {@code max(max(edge, edge), edge)}. */
		private Rate rate(int from, int to) {
			if (to - from != RATE_TOKENS || !this.code.is(from, "max") || !this.code.is(from + 1, "(")
					|| !this.code.is(from + 2, "max") || !this.code.is(from + 3, "(")) {
				return null;
			}

			int second = from + 4 + EDGE.length + 1;
			int third = second + EDGE.length + 2;
			if (!this.code.is(second - 1, ",") || !this.code.is(third - 2, ")")
					|| !this.code.is(third - 1, ",") || !this.code.is(to - 1, ")")) {
				return null;
			}

			Edge[] edges = {edge(from + 4), edge(second), edge(third)};
			int spanned = 0;
			for (Edge edge : edges) {
				if (edge == null || !edge.of().equals(edges[0].of())
						|| !edge.over().equals(edges[0].over())) {
					return null;
				}

				int pair = Math.min(edge.first(), edge.second()) * 3
						+ Math.max(edge.first(), edge.second());
				spanned |= 1 << pair;
			}

			Varying of = this.inputs.get(edges[0].of());
			Varying over = this.inputs.get(edges[0].over());
			// The three edges of the triangle, each once: corners 0 and 1, 0 and 2, 1 and 2.
			int triangle = (1 << 1) | (1 << 2) | (1 << 5);
			if (spanned != triangle || of == null || over == null || !plain(of) || !plain(over)
					|| edges[0].of().equals(edges[0].over()) || !RATE_OF.contains(of.type())
					|| !RATE_OVER.contains(over.type())) {
				return null;
			}

			return new Rate(of.type(), edges[0].of(), edges[0].over(), over.type());
		}

		/** The edge term starting at {@code at}, or null. */
		private Edge edge(int at) {
			for (int index = 0; index < EDGE.length; index++) {
				if (EDGE[index] != null && !this.code.is(at + index, EDGE[index])) {
					return null;
				}
			}

			String of = this.code.text(at + 2);
			String over = this.code.text(at + 15);
			String first = this.code.text(at + 4);
			String second = this.code.text(at + 9);
			if (!of.equals(this.code.text(at + 7)) || !over.equals(this.code.text(at + 20))
					|| !first.equals(this.code.text(at + 17))
					|| !second.equals(this.code.text(at + 22))) {
				return null;
			}

			int a = corner(first);
			int b = corner(second);

			if (a < 0 || b < 0 || a == b) {
				return null;
			}

			return new Edge(of, over, a, b);
		}

		private static int corner(String written) {
			return switch (written) {
				case "0" -> 0;
				case "1" -> 1;
				case "2" -> 2;
				default -> -1;
			};
		}

		/**
		 * The loop over the three corners, {@code for (int i = 0; i < 3; i++)}, and the index of the
		 * token after its closing brace.
		 */
		private int loop(int at, int end) throws Refused {
			String counter = this.code.text(at + 3);
			int step = at + 11;
			boolean head = this.code.is(at + 1, "(") && this.code.is(at + 2, "int")
					&& this.code.identifier(at + 3) && this.code.is(at + 4, "=")
					&& this.code.is(at + 5, "0") && this.code.is(at + 6, ";")
					&& this.code.is(at + 7, counter) && this.code.is(at + 8, "<")
					&& this.code.is(at + 9, "3") && this.code.is(at + 10, ";");
			boolean increments = this.code.join(step, step + 3).equals(counter + " + +")
					|| this.code.join(step, step + 3).equals("+ + " + counter);
			int close = this.code.closing(step + 4);
			if (!head || !increments || !this.code.is(step + 3, ")") || !this.code.is(step + 4, "{")
					|| close < 0 || close >= end) {
				throw new Refused("loops over something other than its three corners");
			}

			boolean position = false;
			int index = step + 5;
			while (index < close) {
				if (this.code.is(index, "EmitVertex") && this.code.is(index + 1, "(")
						&& this.code.is(index + 2, ")") && this.code.is(index + 3, ";")
						&& index + 4 == close && position) {
					return close + 1;
				}

				if (!position && this.code.join(index, index + 9)
						.equals("gl_Position = gl_in [ " + counter + " ] . gl_Position ;")) {
					position = true;
					index += 9;
					continue;
				}

				String output = this.code.text(index);
				String input = this.code.text(index + 2);
				if (this.outputs.containsKey(output) && this.inputs.containsKey(input)
						&& this.code.join(index + 1, index + 7)
								.equals("= " + input + " [ " + counter + " ] ;")) {
					if (this.copies.containsKey(output) || this.wide.contains(output)
							|| this.copies.containsValue(input)) {
						throw new Refused("writes " + output + " or reads " + input
								+ " more than once");
					}

					if (!this.outputs.get(output).type().equals(this.inputs.get(input).type())) {
						throw new Refused("copies " + input + " into a varying of another type");
					}

					this.copies.put(output, input);
					index += 7;
					continue;
				}

				break;
			}

			throw new Refused("does something other than copy each corner in its loop");
		}
	}

	/**
	 * The fragment stage with the geometry stage folded into it.
	 * <p>
	 * An input the loop copies keeps its declaration under the vertex stage's name, qualifiers and
	 * all, and its own name becomes a plain global filled at the head of {@code main}; an output
	 * written for the whole triangle loses its declaration as an input and becomes a global worked
	 * out there too. Every input of the geometry stage nothing copies is still declared, under its
	 * own name: the game numbers the fragment stage's inputs over the vertex stage's outputs and
	 * counts only the names the fragment declares, so one left out would move every location after
	 * it onto its neighbour without a word.
	 */
	private static String rewrite(Stage stage, Code code) throws Refused {
		Set<String> spelled = new HashSet<>();
		for (GlslLexer.Token token : code.all) {
			if (token.kind() == GlslLexer.Kind.IDENTIFIER) {
				spelled.add(token.text());
			}
		}

		for (String name : stage.inputs.keySet()) {
			if (spelled.contains(name)) {
				throw new Refused("hands on " + name + ", a name the fragment stage already spells");
			}
		}

		for (String name : RATE_NAMES) {
			if (spelled.contains(name)) {
				throw new Refused("would be folded under " + name
						+ ", a name the fragment stage already spells");
			}
		}

		Map<String, Statement> declared = new HashMap<>();
		Set<String> twice = new HashSet<>();
		int main = -1;
		for (Statement statement : code.fileScope()) {
			int start = statement.start();
			if (statement.body() >= 0) {
				if (code.join(start, statement.body() + 1).equals("void main ( ) {")) {
					if (main >= 0) {
						throw new Refused("meets a fragment stage with two main functions");
					}

					main = start;
				}

				continue;
			}

			int storage = start;
			while (storage < statement.end() && QUALIFIERS.contains(code.text(storage))) {
				storage++;
			}

			if (code.is(storage, "in") && code.identifier(storage + 1)
					&& code.identifier(storage + 2) && storage + 3 == statement.end()
					&& declared.put(code.text(storage + 2), statement) != null) {
				twice.add(code.text(storage + 2));
			}
		}

		if (main < 0) {
			throw new Refused("meets a fragment stage whose main the fold cannot find");
		}

		Map<String, String> uniforms = code.uniforms();
		for (Map.Entry<String, String> read : stage.reads.entrySet()) {
			if (!read.getValue().equals(uniforms.get(read.getKey()))) {
				throw new Refused("reads " + read.getKey() + " before its loop, which the fragment stage "
						+ "does not declare as the same uniform");
			}
		}

		List<Edit> edits = new ArrayList<>();
		StringBuilder copies = new StringBuilder();
		for (Map.Entry<String, Varying> output : stage.outputs.entrySet()) {
			String name = output.getKey();
			Statement statement = declared.get(name);
			if (statement == null || twice.contains(name)) {
				throw new Refused("hands on " + name
						+ ", which the fragment stage does not declare once as a plain input");
			}

			int semicolon = statement.end();
			String type = code.text(semicolon - 2);
			if (!type.equals(output.getValue().type())) {
				throw new Refused("hands on " + name + " as a type the fragment stage does not read");
			}

			String input = stage.copies.get(name);
			if (input == null) {
				edits.add(new Edit(statement.start(), semicolon, type + " " + name + ";"));
			} else {
				edits.add(new Edit(semicolon - 1, semicolon - 1, input));
				edits.add(new Edit(semicolon, semicolon, "; " + type + " " + name + ";"));
				copies.append(name).append(" = ").append(input).append("; ");
			}
		}

		// A copied input is interpolated under the fragment stage's declaration of the name it is
		// copied to, so that declaration is the one a rate over it has to find plain.
		for (Rate rate : stage.rates.values()) {
			for (Map.Entry<String, String> copy : stage.copies.entrySet()) {
				if (!copy.getValue().equals(rate.of()) && !copy.getValue().equals(rate.over())) {
					continue;
				}

				for (int index = declared.get(copy.getKey()).start(); QUALIFIERS.contains(code.text(index));
						index++) {
					String qualifier = code.text(index);
					if (qualifier.equals("flat") || qualifier.equals("noperspective")
							|| qualifier.equals("centroid")) {
						throw new Refused("measures " + copy.getValue() + " across the triangle, which the "
								+ "fragment stage reads " + qualifier);
					}
				}
			}
		}

		StringBuilder head = new StringBuilder("\n");
		for (Map.Entry<String, Varying> input : stage.inputs.entrySet()) {
			if (stage.copies.containsValue(input.getKey())) {
				continue;
			}

			String type = input.getValue().type();
			List<String> qualifiers = input.getValue().qualifiers();
			// An integer reaching a fragment stage has to be flat, which a stage between the two
			// was free not to say.
			if (!qualifiers.contains("flat") && (type.startsWith("int") || type.startsWith("uint")
					|| type.startsWith("ivec") || type.startsWith("uvec"))) {
				head.append("flat ");
			}

			qualifiers.forEach(qualifier -> head.append(qualifier).append(' '));
			head.append("in ").append(type).append(' ').append(input.getKey()).append(";\n");
		}

		Map<String, Rate> signatures = new LinkedHashMap<>();
		for (Rate rate : stage.rates.values()) {
			signatures.putIfAbsent(rate.type() + " " + rate.overType(), rate);
		}

		for (Rate rate : signatures.values()) {
			// The length of the gradient of the first varying over the plane the second one spans,
			// from how far one pixel step moves each: the step pair's Gram matrix inverted, as a
			// quadratic form of the first varying's two steps.
			String of = rate.type();
			String over = rate.overType();
			head.append(of).append(' ').append(RATE).append('(').append(of).append(" ofAx, ")
					.append(of).append(" ofAy, ").append(over).append(" ofBx, ").append(over)
					.append(" ofBy) { float ofXx = dot(ofBx, ofBx); float ofXy = dot(ofBx, ofBy); "
							+ "float ofYy = dot(ofBy, ofBy); return sqrt(max((ofYy * ofAx * ofAx "
							+ "- 2.0 * ofXy * ofAx * ofAy + ofXx * ofAy * ofAy) / max(ofXx * ofYy "
							+ "- ofXy * ofXy, 1e-30), ")
					.append(of).append("(0.0))); }\n");
		}

		edits.add(new Edit(main, main, head + "void"));
		edits.add(new Edit(main + 4, main + 4,
				"{ { " + copies + String.join(" ", stage.prologue) + " }"));
		edits.sort(Comparator.comparingInt(Edit::from));

		StringBuilder text = new StringBuilder();
		int next = 0;
		int last = -1;
		for (Edit edit : edits) {
			if (edit.from() <= last) {
				throw new Refused("meets a fragment stage it cannot rewrite in one reading");
			}

			int from = code.at.get(edit.from());
			for (int index = next; index < from; index++) {
				text.append(code.all.get(index).text());
			}

			text.append(edit.text());
			next = code.at.get(edit.to()) + 1;
			last = edit.to();
		}

		for (int index = next; index < code.all.size(); index++) {
			text.append(code.all.get(index).text());
		}

		return text.toString();
	}
}
