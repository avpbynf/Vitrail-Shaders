package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;
import dev.vitrail.glsl.GlslTranslator.FileScope;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The varyings this stage cannot hand over as the pack declared them, and what it hands over
 * instead.
 * <p>
 * {@code IntermediaryShaderModule.createFromSpirv} numbers a varying by the rank of its reflected
 * name, with no stride, so a name occupying several locations has the next one numbered onto its
 * second. A matrix is one such name, a struct is another and an array is a third, and all three
 * are answered the same way: the declaration comes out of the body, one varying per column, per
 * member or per element goes into the header, and the pack's own name is rebuilt as a local
 * around its {@code main}. The three cases share the numbering they exist for, the shape of the
 * rewrite and the wrapper they are copied in, which is why they are one class and not three.
 * <p>
 * What it can see is the token list, the unit's own liveness, the stage, and one reader handed to
 * it: a declaration at file scope is {@link GlslTranslator}'s to recognise, and reading one a
 * second way here would be a second answer to drift from the first.
 */
final class VaryingSplit {

	/**
	 * Prefix of the vectors a matrix varying is split into. Pack names never start with {@code of_},
	 * so a column cannot collide with a varying the pack already declared.
	 */
	private static final String MATRIX_COLUMN = "of_vmat_";

	/** The prefix of a varying standing for one member of a struct varying. */
	private static final String STRUCT_MEMBER = "of_vstruct_";

	/** The prefix of a varying standing for one element of an array varying. */
	private static final String ARRAY_ELEMENT = "of_varr_";

	/**
	 * A declarator's array suffix, one dimension sized by a number the pack wrote out: one to
	 * a few hundred elements, since no interface has that many locations and a zero is no array.
	 */
	private static final Pattern ARRAY_SUFFIX = Pattern.compile("\\[\\s*([1-9]\\d{0,2})\\s*\\]$");

	/**
	 * The member types a struct varying is split over: one location apiece, nothing nested, and
	 * nothing a varying may not be, which rules the booleans out.
	 */
	private static final Set<String> STRUCT_MEMBER_TYPES = Set.of(
			"float", "vec2", "vec3", "vec4", "int", "ivec2", "ivec3", "ivec4",
			"uint", "uvec2", "uvec3", "uvec4", "double", "dvec2", "dvec3", "dvec4");

	private final TokenStream tokens;
	private final ExpandedUnit unit;
	private final ProgramStage stage;
	private final Declarations declarations;

	/**
	 * Matrix varyings rewritten as one vector per column, so {@code createFromSpirv} can number
	 * them without overlap. Empty when the stage declared none.
	 */
	private final List<SplitMatrix> matrices = new ArrayList<>();

	/**
	 * Struct varyings rewritten as one varying per member, for the same numbering. Empty when the
	 * stage declared none, which is every stage of the corpus but Photon's.
	 */
	private final List<SplitStruct> structs = new ArrayList<>();

	/**
	 * Array varyings rewritten as one varying per element, for the same numbering again. Empty
	 * when the stage declared none.
	 */
	private final List<SplitArray> arrays = new ArrayList<>();

	VaryingSplit(TokenStream tokens, ExpandedUnit unit, ProgramStage stage,
			Declarations declarations) {
		this.tokens = tokens;
		this.unit = unit;
		this.stage = stage;
		this.declarations = declarations;
	}

	/**
	 * How this reads one declaration at file scope, which is the translator's own reader handed
	 * over rather than copied.
	 */
	@FunctionalInterface
	interface Declarations {

		/**
		 * The declaration the storage keyword at this index opens, or null where it opens
		 * something else.
		 *
		 * @param types the type names a declaration may be written under: the language's own, or
		 *              the struct types the unit defines
		 */
		FileScope read(int keyword, Set<String> types);
	}

	/** Every varying that has to be taken apart, in the order the numbering needs them split. */
	void split() {
		splitMatrixVaryings();
		splitStructVaryings();
		splitArrayVaryings();
	}

	/** Whether anything was split at all, and so whether the header owes a wrapper. */
	boolean any() {
		return !this.matrices.isEmpty() || !this.structs.isEmpty() || !this.arrays.isEmpty();
	}

	List<SplitMatrix> matrices() {
		return this.matrices;
	}

	List<SplitStruct> structs() {
		return this.structs;
	}

	List<SplitArray> arrays() {
		return this.arrays;
	}

	/**
	 * One matrix varying rewritten as one vector per column.
	 *
	 * @param input true when this stage reads the matrix ({@code in}), false when it writes it
	 */
	record SplitMatrix(String name, String matrixType, String columnType, int columns,
			String qualifier, boolean input) {
	}

	/**
	 * Turns each file-scope matrix {@code in} / {@code out} into one vector per column.
	 * <p>
	 * {@code IntermediaryShaderModule.createFromSpirv} numbers locations by the rank of each
	 * reflected variable, {@code 0..n-1} with no stride. A GLSL {@code mat3} is one variable and
	 * occupies three consecutive locations, so the next varying is numbered onto the second
	 * column and the rotation the pack stored is no longer orthonormal. AstraLex's night planet
	 * is the image of that: a billboard {@code xy / z} through a walked-on {@code mat3}.
	 * <p>
	 * OpenGL links by name and never asks. The workaround here is to take the declaration out of
	 * the body and emit the matrix as a local in the header, beside one vector per column: the pack
	 * body still writes and reads the original name, and the wrapper copies the columns. Arrays
	 * of matrices are left alone; no pack of the corpus writes one, and AstraLex's four matrices
	 * are not arrays.
	 */
	private void splitMatrixVaryings() {
		if (this.stage == ProgramStage.COMPUTE) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])) {
				continue;
			}

			boolean input = token.identifier("in");
			boolean output = token.identifier("out");
			if (!input && !output) {
				continue;
			}

			if (input && this.stage == ProgramStage.VERTEX) {
				continue;
			}

			FileScope declared = this.declarations.read(index, LegacyGlsl.TYPE_NAMES);
			if (declared == null) {
				continue;
			}

			MatrixColumns layout = matrixColumns(declared.type());
			if (layout == null) {
				continue;
			}

			boolean anyArray = declared.names().stream().anyMatch(name ->
					declared.declarators().getOrDefault(name, "").contains("["));
			if (anyArray) {
				continue;
			}

			this.tokens.blankRange(declared.start(), declared.end());

			for (String name : declared.names()) {
				this.matrices.add(new SplitMatrix(name, declared.type(), layout.columnType(),
						layout.columns(), declared.qualifier(), input));
			}
		}
	}

	/** Column count and vector type of a GLSL matrix, or null when the type is not a matrix. */
	record MatrixColumns(String columnType, int columns) {
	}

	/**
	 * {@code mat3} is three {@code vec3}, {@code mat4x3} is four {@code vec3}, {@code dmat2} is two
	 * {@code dvec2}. Anything else, including a vector, answers null.
	 */
	private static MatrixColumns matrixColumns(String type) {
		String prefix = "";
		String rest = type;
		if (rest.startsWith("dmat")) {
			prefix = "d";
			rest = rest.substring(1);
		}

		if (!rest.startsWith("mat")) {
			return null;
		}

		rest = rest.substring(3);
		int columns;
		int rows;
		int by = rest.indexOf('x');
		try {
			if (by < 0) {
				columns = Integer.parseInt(rest);
				rows = columns;
			} else {
				columns = Integer.parseInt(rest.substring(0, by));
				rows = Integer.parseInt(rest.substring(by + 1));
			}
		} catch (NumberFormatException ignored) {
			return null;
		}

		if (columns < 2 || columns > 4 || rows < 2 || rows > 4) {
			return null;
		}

		return new MatrixColumns(prefix + "vec" + rows, columns);
	}

	static String matrixColumnName(String matrix, int column) {
		return MATRIX_COLUMN + matrix + "_" + column;
	}

	/** One member of a struct a varying is declared under, in the order the struct lists it. */
	record StructMember(String type, String name) {
	}

	/**
	 * One struct varying rewritten as one varying per member.
	 *
	 * @param input true when this stage reads the struct ({@code in}), false when it writes it
	 */
	record SplitStruct(String name, String structType, List<StructMember> members,
			String qualifier, boolean input) {
	}

	/**
	 * Turns each file-scope struct {@code in} / {@code out} into one varying per member, for the
	 * reason {@link #splitMatrixVaryings} turns a matrix into its columns: a struct is one
	 * reflected variable occupying as many locations as it has members, and the rank the game
	 * numbers it by is one. Photon's {@code flat in OverworldFogParameters fog_params}, three
	 * {@code vec3} of fog coefficients, reached its water fragment stage wrong under that
	 * numbering, and the fog its reflections computed with them painted every distant lake red;
	 * handed over as three varyings, the same program draws the lake as the reference does.
	 * <p>
	 * The definition is read off the unit itself, since the type is the pack's own. Only a struct
	 * whose members are all scalars or vectors is split: a member that is a matrix, an array or a
	 * struct of its own would need the same treatment one level down, and no pack of the corpus
	 * writes one. Arrays of structs are left alone as arrays of matrices are.
	 */
	private void splitStructVaryings() {
		if (this.stage == ProgramStage.COMPUTE) {
			return;
		}

		Map<String, List<StructMember>> definitions = structDefinitions();
		if (definitions.isEmpty()) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])) {
				continue;
			}

			boolean input = token.identifier("in");
			boolean output = token.identifier("out");
			if (!input && !output) {
				continue;
			}

			if (input && this.stage == ProgramStage.VERTEX) {
				continue;
			}

			// Read against the struct types the unit defines: the plain reader only knows the
			// language's own types, which is why a struct varying was never a declaration to it.
			FileScope declared = this.declarations.read(index, definitions.keySet());
			if (declared == null) {
				continue;
			}

			List<StructMember> members = definitions.get(declared.type());

			boolean anyArray = declared.names().stream().anyMatch(name ->
					declared.declarators().getOrDefault(name, "").contains("["));
			if (anyArray) {
				continue;
			}

			this.tokens.blankRange(declared.start(), declared.end());

			for (String name : declared.names()) {
				this.structs.add(new SplitStruct(name, declared.type(), members,
						declared.qualifier(), input));
			}
		}

		// The definition moves to the header with the global that carries the pack's name: the
		// wrapper that rebuilds the struct stands in the header, above the body, and the type has
		// to exist there. The body's own definition goes blank so the type is not defined twice.
		Set<String> moved = new HashSet<>();
		for (SplitStruct split : this.structs) {
			if (!moved.add(split.structType())) {
				continue;
			}

			int[] definition = definitionRange(split.structType());
			if (definition != null) {
				this.tokens.blankRange(definition[0], definition[1]);
			}
		}
	}

	/**
	 * Every struct the unit defines on a live line whose members are all scalars or vectors, by
	 * type name. A struct with any other member is left out, so its varyings stay as they are.
	 */
	private Map<String, List<StructMember>> structDefinitions() {
		Map<String, List<StructMember>> definitions = new LinkedHashMap<>();
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("struct") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int name = this.tokens.significantAfter(index);
			int open = this.tokens.significantAfter(name);
			if (name < 0 || open < 0 || this.tokens.get(name).kind() != Kind.IDENTIFIER
					|| !this.tokens.get(open).operator("{")) {
				continue;
			}

			int close = this.tokens.matchingBracket(open);
			int semicolon = this.tokens.significantAfter(close);
			// A definition that declares an instance in the same breath, "struct T { ... } t;",
			// is left alone: taking it out of the body would take the instance with it.
			if (close < 0 || semicolon < 0 || !this.tokens.get(semicolon).operator(";")) {
				continue;
			}

			List<StructMember> members = structMembers(open + 1, close - 1);
			if (members != null && !members.isEmpty()) {
				definitions.putIfAbsent(this.tokens.get(name).text(), members);
			}
		}

		return definitions;
	}

	/**
	 * The members declared between a struct's braces, or null where one of them is not a scalar
	 * or a vector: a member with brackets, a matrix, a sampler or another struct.
	 */
	private List<StructMember> structMembers(int start, int end) {
		List<StructMember> members = new ArrayList<>();
		List<Integer> statement = new ArrayList<>();
		for (int scan : this.tokens.significantRange(start, end)) {
			Token token = this.tokens.get(scan);
			if (!token.operator(";")) {
				statement.add(scan);
				continue;
			}

			if (statement.size() < 2) {
				return null;
			}

			String type = this.tokens.get(statement.get(0)).text();
			if (!STRUCT_MEMBER_TYPES.contains(type)) {
				return null;
			}

			for (int part = 1; part < statement.size(); part++) {
				Token piece = this.tokens.get(statement.get(part));
				if (piece.operator(",")) {
					continue;
				}

				if (piece.kind() != Kind.IDENTIFIER) {
					return null;
				}

				members.add(new StructMember(type, piece.text()));
			}

			statement.clear();
		}

		return statement.isEmpty() ? members : null;
	}

	/**
	 * The first live definition of that struct, from its {@code struct} keyword to the semicolon
	 * closing it, or null where the unit holds none.
	 */
	private int[] definitionRange(String structType) {
		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("struct") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int name = this.tokens.significantAfter(index);
			int open = this.tokens.significantAfter(name);
			if (name < 0 || open < 0 || !this.tokens.get(name).identifier(structType)
					|| !this.tokens.get(open).operator("{")) {
				continue;
			}

			int close = this.tokens.matchingBracket(open);
			int semicolon = this.tokens.significantAfter(close);
			if (close >= 0 && semicolon >= 0 && this.tokens.get(semicolon).operator(";")) {
				return new int[] {index, semicolon};
			}
		}

		return null;
	}

	static String structMemberName(String struct, String member) {
		return STRUCT_MEMBER + struct + "_" + member;
	}

	/**
	 * One array varying rewritten as one varying per element.
	 *
	 * @param input true when this stage reads the array ({@code in}), false when it writes it
	 */
	record SplitArray(String name, String type, int size, String qualifier, boolean input) {
	}

	/**
	 * Turns each file-scope array {@code in} / {@code out} of scalars or vectors into one varying
	 * per element, for the reason the matrices and the structs are split: an array is one
	 * reflected variable over as many locations as it has elements, and one rank. Photon's
	 * {@code flat in vec3 sky_sh[9]} carries its sky harmonics into the deferred shading, and the
	 * two varyings after it were numbered onto its elements.
	 * <p>
	 * Only a single dimension sized by a number the pack wrote out is split: a size written as a
	 * name, a macro the unit still carries or a constant expression, is left alone with the
	 * declaration. A statement declaring an array beside a plain name is left alone too, so that
	 * the two are not pulled apart.
	 * <p>
	 * Only what the vertex stage writes and the fragment stage reads: a geometry or tessellation
	 * stage declares its per-vertex inputs as arrays that are no varying arrays, and a fragment
	 * stage's output array is a set of colour outputs, which the header numbers on its own.
	 */
	private void splitArrayVaryings() {
		boolean output = this.stage == ProgramStage.VERTEX;
		if (!output && this.stage != ProgramStage.FRAGMENT) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.directive() != null || !this.unit.isLive(lines[index])
					|| !token.identifier(output ? "out" : "in")) {
				continue;
			}

			FileScope declared = this.declarations.read(index, LegacyGlsl.TYPE_NAMES);
			if (declared == null || !STRUCT_MEMBER_TYPES.contains(declared.type())) {
				continue;
			}

			List<SplitArray> found = new ArrayList<>();
			for (String name : declared.names()) {
				String prefix = declared.type() + " " + name;
				String declarator = declared.declarators().getOrDefault(name, "");
				Matcher size = declarator.startsWith(prefix)
						? ARRAY_SUFFIX.matcher(declarator.substring(prefix.length()))
						: null;
				if (size == null || !size.matches()) {
					found.clear();
					break;
				}

				found.add(new SplitArray(name, declared.type(), Integer.parseInt(size.group(1)),
						declared.qualifier(), !output));
			}

			if (found.isEmpty()) {
				continue;
			}

			this.tokens.blankRange(declared.start(), declared.end());
			this.arrays.addAll(found);
		}
	}

	static String arrayElementName(String array, int element) {
		return ARRAY_ELEMENT + array + "_" + element;
	}

	/**
	 * Rebuilds each input matrix from its columns before the pack body runs, so a read of the
	 * original name still sees a {@code mat3}.
	 */
	String matrixPrologue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitMatrix split : this.matrices) {
			if (!split.input()) {
				continue;
			}

			assignments.append(split.name()).append(" = ").append(split.matrixType()).append('(');
			for (int column = 0; column < split.columns(); column++) {
				if (column > 0) {
					assignments.append(", ");
				}

				assignments.append(matrixColumnName(split.name(), column));
			}

			assignments.append("); ");
		}

		return assignments.toString();
	}

	/**
	 * Copies each output matrix onto its columns after the pack body ran, so the interface the
	 * next stage reads is the value the pack wrote.
	 */
	String matrixEpilogue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitMatrix split : this.matrices) {
			if (split.input()) {
				continue;
			}

			for (int column = 0; column < split.columns(); column++) {
				assignments.append(matrixColumnName(split.name(), column)).append(" = ")
						.append(split.name()).append('[').append(column).append("]; ");
			}
		}

		return assignments.toString();
	}

	/**
	 * Rebuilds each input struct from its members before the pack body runs, so a read of the
	 * original name still sees the struct. A constructor takes the members in declaration order,
	 * which is the order they were declared in as varyings.
	 */
	String structPrologue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitStruct split : this.structs) {
			if (!split.input()) {
				continue;
			}

			assignments.append(split.name()).append(" = ").append(split.structType()).append('(');
			for (int member = 0; member < split.members().size(); member++) {
				if (member > 0) {
					assignments.append(", ");
				}

				assignments.append(structMemberName(split.name(),
						split.members().get(member).name()));
			}

			assignments.append("); ");
		}

		return assignments.toString();
	}

	/**
	 * Copies each output struct onto its members after the pack body ran, so the interface the next
	 * stage reads is the value the pack wrote.
	 */
	String structEpilogue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitStruct split : this.structs) {
			if (split.input()) {
				continue;
			}

			for (StructMember member : split.members()) {
				assignments.append(structMemberName(split.name(), member.name()))
						.append(" = ")
						.append(split.name()).append('.').append(member.name()).append("; ");
			}
		}

		return assignments.toString();
	}

	/** Fills each input array from its elements before the pack body runs. */
	String arrayPrologue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitArray split : this.arrays) {
			if (!split.input()) {
				continue;
			}

			for (int element = 0; element < split.size(); element++) {
				assignments.append(split.name()).append('[').append(element).append("] = ")
						.append(arrayElementName(split.name(), element)).append("; ");
			}
		}

		return assignments.toString();
	}

	/** Copies each output array onto its elements after the pack body ran. */
	String arrayEpilogue() {
		StringBuilder assignments = new StringBuilder();
		for (SplitArray split : this.arrays) {
			if (split.input()) {
				continue;
			}

			for (int element = 0; element < split.size(); element++) {
				assignments.append(arrayElementName(split.name(), element)).append(" = ")
						.append(split.name()).append('[').append(element).append("]; ");
			}
		}

		return assignments.toString();
	}
}
