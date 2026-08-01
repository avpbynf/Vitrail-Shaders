package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;
import dev.vitrail.pack.EngineDefines;
import dev.vitrail.pack.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.ProgramStage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one flattened pack unit into GLSL a Vulkan compiler will take.
 * <p>
 * The pack dialect is GLSL 120 with OptiFine's additions: fixed function state, {@code varying}
 * and {@code attribute}, texture lookups renamed twenty years ago, and plain uniforms declared
 * loose at file scope, which Vulkan does not allow at all. None of that needs the program to be
 * understood, only read, so the work here is a rewrite over a token stream rather than a compiler.
 * <p>
 * Two things are deliberately left undone, and both were done by the measuring prototype.
 * No {@code layout(binding = )} is emitted, and no {@code layout(location = )} except on fragment
 * outputs. The game's own compiler assigns both from SPIR-V reflection and rewrites them
 * afterwards, so a number chosen here is at best ignored and at worst collides with a number
 * chosen for another unit of the same pack. When the prototype was made to number them across a
 * whole pack rather than per file, colliding locations became its largest class of failure by
 * far. Fragment outputs keep theirs, because their order is what maps a write onto a colour
 * attachment and nothing else records that.
 * <p>
 * Conditionals are left standing. The unit that arrives here still carries every {@code #if} of
 * every file that was spliced into it, and the compiler will evaluate them again, so the engine's
 * own symbols are written into the header rather than assumed: if the compiler read them
 * differently from the expander, it would take a different branch and fail somewhere unrelated.
 */
public final class GlslTranslator {

	private static final String VERSION = "#version 460 core";

	/** The block name has to be declared to the pipeline by hand later, so it is fixed here. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	private static final String FOG_STRUCT =
			"struct OfFog { vec4 color; float density; float start; float end; float scale; };";

	/** OptiFine's colour attachments, so the highest index a fragment output can carry. */
	private static final int MAX_FRAGMENT_OUTPUTS = 16;

	private static final Pattern DRAW_BUFFERS =
			Pattern.compile("(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9][0-9,\\s]*)");

	private final ExpandedUnit unit;
	private final ProgramStage stage;
	private final Map<String, String> engineDefines;
	private final List<Token> tokens;

	/** Names the pack defines as macros. Their uses belong to the preprocessor, not to us. */
	private final Set<String> packMacros = new HashSet<>();

	/** Token positions that name a macro rather than use one, and so are never renamed. */
	private final Set<Integer> macroNamePositions = new HashSet<>();

	/** Names the unit declares under a built-in type, which is how a declaration is told apart. */
	private final Set<String> declaredNames = new HashSet<>();

	/** Built-ins added after GLSL 120 that this unit defines a function of its own for. */
	private final Set<String> shadowedBuiltins = new HashSet<>();

	private final Map<String, String> blockMembers = new LinkedHashMap<>();
	private final Set<String> injectedNames = new HashSet<>();
	private final List<String> conflicts = new ArrayList<>();
	private final List<Integer> drawBuffers = new ArrayList<>();

	private int maxFragmentOutput = -1;
	private int dynamicFragData;
	private int opaqueUniforms;
	private int shadowCalls;
	private int strippedExtensions;

	private GlslTranslator(ExpandedUnit unit, ProgramStage stage, Map<String, String> engineDefines) {
		this.unit = unit;
		this.stage = stage;
		this.engineDefines = engineDefines;
		this.tokens = new ArrayList<>(GlslLexer.lex(unit.text()));
	}

	public static TranslatedUnit translate(ExpandedUnit unit, ProgramStage stage) {
		return new GlslTranslator(unit, stage, EngineDefines.table(EngineDefines.DEFAULT_MC_VERSION))
				.run();
	}

	private TranslatedUnit run() {
		collectMacroNames();
		collectDeclarations();
		collectDrawBuffers();
		dropVersionAndExtensions();
		rewriteIdentifiers();
		dropPrecision();
		rewriteFragmentOutputs();
		liftUniforms();

		String body = GlslLexer.join(this.tokens);

		return new TranslatedUnit(this.unit.entry(), this.stage, header() + body + "\n", notes(),
				List.copyOf(this.drawBuffers));
	}


	private void collectMacroNames() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.HASH || !LegacyGlsl.NAMING_DIRECTIVES.contains(token.directive())) {
				continue;
			}

			int name = macroNameAfter(index);
			if (name < 0) {
				continue;
			}

			this.macroNamePositions.add(name);
			if (token.directive().equals("define")) {
				this.packMacros.add(this.tokens.get(name).text());
			}
		}
	}

	/**
	 * Records every name the unit declares under a built-in type, and among them the functions
	 * whose name GLSL has since taken for itself.
	 */
	private void collectDeclarations() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || token.directive() != null) {
				continue;
			}

			int before = significantBefore(index);
			if (before < 0 || !LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text())) {
				continue;
			}

			this.declaredNames.add(token.text());
			if (LegacyGlsl.POST_120_BUILTINS.contains(token.text()) && callOpener(index) >= 0) {
				this.shadowedBuiltins.add(token.text());
			}
		}
	}

	/**
	 * Which colour attachments this program writes. Packs declare it more than once, in different
	 * branches of the same {@code #if}, so only the first one on a line that was actually taken
	 * counts. Reading the first one found regardless gives the wrong list on real packs: BSL's
	 * nether composite writes four outputs and the first comment in its text names two.
	 * <p>
	 * Nothing in the translation depends on this. It is carried for the pass that binds them.
	 */
	private void collectDrawBuffers() {
		int[] lines = lineNumbers();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.COMMENT || !this.unit.isLive(lines[index])) {
				continue;
			}

			Matcher found = DRAW_BUFFERS.matcher(token.text());
			if (!found.find()) {
				continue;
			}

			// DRAWBUFFERS runs its indices together, so it cannot name an attachment past nine;
			// RENDERTARGETS separates them with commas and can.
			if (found.group(1).equals("DRAWBUFFERS")) {
				for (char digit : found.group(2).trim().toCharArray()) {
					if (digit >= '0' && digit <= '9') {
						this.drawBuffers.add(digit - '0');
					}
				}
			} else {
				for (String part : found.group(2).split(",")) {
					addRenderTarget(part.trim());
				}
			}

			return;
		}
	}

	private void addRenderTarget(String text) {
		if (text.isEmpty() || text.length() > 2) {
			return;
		}

		int slot = Integer.parseInt(text);
		if (slot < MAX_FRAGMENT_OUTPUTS) {
			this.drawBuffers.add(slot);
		}
	}

	/**
	 * Both directives go. The version is replaced by ours, and every extension the corpus uses is
	 * core in 4.60, so keeping them would at best say nothing. It would also often be an error:
	 * once includes are spliced in, an {@code #extension} line can land well past the first real
	 * token, and there it is rejected. They are counted so that an unexpected one shows up in the
	 * totals rather than vanishing.
	 */
	private void dropVersionAndExtensions() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.HASH) {
				continue;
			}

			if (token.directive().equals("extension")) {
				this.strippedExtensions++;
			} else if (!token.directive().equals("version")) {
				continue;
			}

			blankDirective(index);
		}
	}

	private void rewriteIdentifiers() {
		List<Integer> closings = new ArrayList<>();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER) {
				continue;
			}

			String directive = token.directive();
			if (directive != null
					&& (LegacyGlsl.OPAQUE_DIRECTIVES.contains(directive) || this.macroNamePositions.contains(index))) {
				continue;
			}

			String name = token.text();

			// A pack that shims a legacy name itself has already said what it means. Renaming its
			// uses would leave the macro behind, pointing at something no longer there.
			if (this.packMacros.contains(name)) {
				continue;
			}

			if (this.shadowedBuiltins.contains(name)) {
				replace(index, "of_" + name);
				continue;
			}

			String fixed = LegacyGlsl.FIXED_FUNCTION.get(name);
			if (fixed != null) {
				replace(index, fixed);
				continue;
			}

			if (name.equals("gl_VertexID") || name.equals("gl_InstanceID")) {
				replace(index, name.equals("gl_VertexID") ? "gl_VertexIndex" : "gl_InstanceIndex");
				continue;
			}

			if (name.equals("ftransform") && rewriteFtransform(index)) {
				continue;
			}

			String shadow = LegacyGlsl.SHADOW_FUNCTIONS.get(name);
			if (shadow != null) {
				int close = matchingBracket(callOpener(index));
				if (close >= 0) {
					// The wrap adds an opening parenthesis, so it has to add a closing one too.
					// Substituting the head alone is what left the prototype with eighty-six
					// units ending in "unexpected SEMICOLON, expecting RIGHT_PAREN".
					inject(index, "vec4(" + shadow);
					closings.add(close + 1);
					this.shadowCalls++;
					continue;
				}
			}

			String modern = LegacyGlsl.DEPRECATED_FUNCTIONS.get(name);
			if (modern != null && callOpener(index) >= 0) {
				replace(index, modern);
				continue;
			}

			if (directive != null) {
				continue;
			}

			if (name.equals("varying")) {
				// A geometry shader has varyings running both ways and the keyword cannot say
				// which. Every other stage is unambiguous, and the corpus has few enough geometry
				// programs that guessing wrong here is visible rather than silent.
				replace(index, this.stage == ProgramStage.VERTEX ? "out" : "in");
				continue;
			}

			if (name.equals("attribute")) {
				replace(index, "in");
				continue;
			}

			String reserved = LegacyGlsl.RESERVED_NAMES.get(name);
			if (reserved != null && callOpener(index) < 0) {
				replace(index, reserved);
			}
		}

		// Inserting shifts every index after it, so the last insertion is made first.
		closings.sort(Comparator.reverseOrder());
		for (int at : closings) {
			this.tokens.add(at, new Token(Kind.RAW, ")", null));
		}
	}

	private boolean rewriteFtransform(int index) {
		int open = callOpener(index);
		int close = matchingBracket(open);
		if (close < 0 || significantAfter(open) != close) {
			return false;
		}

		inject(index, "(of_ModelViewProjectionMatrix * of_Vertex)");
		this.injectedNames.add("of_ModelViewProjectionMatrix");
		this.injectedNames.add("of_Vertex");
		blank(open);
		blank(close);

		return true;
	}

	/**
	 * Precision qualifiers mean nothing on the desktop, but two declarations of one function that
	 * disagree about them are still a mismatch, which is a failure the packs hit and cannot see.
	 */
	private void dropPrecision() {
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || token.directive() != null) {
				continue;
			}

			if (LegacyGlsl.PRECISION_QUALIFIERS.contains(token.text())) {
				blank(index);
				continue;
			}

			if (!token.identifier("precision")) {
				continue;
			}

			int qualifier = significantAfter(index);
			if (qualifier >= 0 && LegacyGlsl.PRECISION_QUALIFIERS.contains(this.tokens.get(qualifier).text())) {
				int end = statementEnd(index);
				if (end >= 0) {
					blankRange(index, end);
				}
			}
		}
	}

	/**
	 * Counts writes from every branch, taken or not, which makes the number of outputs an upper
	 * bound rather than the truth. That is deliberate and it was measured: restricting the count
	 * to branches the expander took cost four units of Sildur's, where the only write to output
	 * zero sits on a line the expander read as dead and the compiler read as live. Declaring an
	 * output nothing writes costs a location; failing to declare one that is written costs the
	 * program. Which attachment each output actually reaches is in {@code drawBuffers}, not here.
	 */
	private void rewriteFragmentOutputs() {
		if (this.stage != ProgramStage.FRAGMENT) {
			return;
		}

		boolean fragColor = false;

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER) {
				continue;
			}

			if (token.identifier("gl_FragColor")) {
				inject(index, "ofFragData0");
				fragColor = true;
				continue;
			}

			if (!token.identifier("gl_FragData")) {
				continue;
			}

			int slot = literalIndexAfter(index);
			if (slot < 0) {
				// An index no one can read at translation time. Declaring an output for it would
				// mean declaring all sixteen, and writing the ones a pack never touches is not
				// free, so this is counted and left to fail loudly.
				this.dynamicFragData++;
				continue;
			}

			int open = significantAfter(index);
			int number = significantAfter(open);
			inject(index, "ofFragData" + slot);
			blank(open);
			blank(number);
			blank(significantAfter(number));
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, slot);
		}

		if (fragColor) {
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, 0);
		}
	}

	/** The subscript of {@code gl_FragData[n]} when it is a literal in range, otherwise -1. */
	private int literalIndexAfter(int index) {
		int open = significantAfter(index);
		if (open < 0 || !this.tokens.get(open).operator("[")) {
			return -1;
		}

		int number = significantAfter(open);
		if (number < 0 || this.tokens.get(number).kind() != Kind.NUMBER) {
			return -1;
		}

		int close = significantAfter(number);
		if (close < 0 || !this.tokens.get(close).operator("]")) {
			return -1;
		}

		String text = this.tokens.get(number).text();
		if (text.length() > 2 || !text.chars().allMatch(Character::isDigit)) {
			return -1;
		}

		int slot = Integer.parseInt(text);

		return slot < MAX_FRAGMENT_OUTPUTS ? slot : -1;
	}

	/**
	 * Moves every plain uniform into one block, because Vulkan takes no other kind. Samplers and
	 * images stay where they are: they are opaque, they are allowed loose, and they are the one
	 * thing here whose declaration the pack got right.
	 * <p>
	 * Brace depth is not consulted. A uniform is only legal at file scope, {@code uniform} is a
	 * reserved word so it can be nothing else, and a pack that opens a brace in one branch of an
	 * {@code #if} and closes it in another would put any depth count out by one for the rest of
	 * the file.
	 */
	private void liftUniforms() {
		int[] lines = lineNumbers();

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("uniform") || token.directive() != null) {
				continue;
			}

			// A declaration in a branch nobody takes stays where it is. Moving it to the header
			// would make it unconditional, and packs do declare a name as a uniform in one branch
			// and as an ordinary global in the other.
			if (this.unit.isLive(lines[index])) {
				liftOne(index);
			}
		}
	}

	private void liftOne(int keyword) {
		int end = statementEnd(keyword);
		if (end < 0) {
			// Either a uniform block, which is already legal, or a declaration with no semicolon,
			// which is a pack problem and not ours to paper over.
			return;
		}

		int start = statementStart(keyword);
		List<Integer> parts = significantRange(start, end);
		int cursor = parts.indexOf(keyword);
		if (cursor < 0) {
			return;
		}

		cursor++;
		while (cursor < parts.size() && isQualifier(this.tokens.get(parts.get(cursor)))) {
			cursor++;
		}

		if (cursor >= parts.size() || this.tokens.get(parts.get(cursor)).kind() != Kind.IDENTIFIER) {
			return;
		}

		String type = this.tokens.get(parts.get(cursor)).text();
		if (LegacyGlsl.isOpaqueType(type)) {
			this.opaqueUniforms++;
			return;
		}

		if (!readDeclarators(parts, cursor + 1, type)) {
			return;
		}

		blankRange(start, end);
	}

	/** Records each declarator of one declaration as a block member. False if none could be read. */
	private boolean readDeclarators(List<Integer> parts, int from, String type) {
		int cursor = from;
		boolean any = false;

		while (cursor < parts.size()) {
			Token token = this.tokens.get(parts.get(cursor));
			if (token.operator(";")) {
				break;
			}

			if (token.kind() != Kind.IDENTIFIER) {
				return false;
			}

			StringBuilder declaration = new StringBuilder(type).append(' ').append(token.text());
			cursor++;

			while (cursor < parts.size() && this.tokens.get(parts.get(cursor)).operator("[")) {
				int close = matchingBracket(parts.get(cursor));
				if (close < 0) {
					return false;
				}

				while (cursor < parts.size() && parts.get(cursor) <= close) {
					declaration.append(this.tokens.get(parts.get(cursor)).text());
					cursor++;
				}
			}

			// OptiFine tolerates an initialiser on a uniform and GLSL does not, so it is dropped.
			if (cursor < parts.size() && this.tokens.get(parts.get(cursor)).operator("=")) {
				while (cursor < parts.size()) {
					Token skipped = this.tokens.get(parts.get(cursor));
					if (skipped.operator(",") || skipped.operator(";")) {
						break;
					}

					cursor++;
				}
			}

			record(token.text(), declaration.toString());
			any = true;

			if (cursor < parts.size() && this.tokens.get(parts.get(cursor)).operator(",")) {
				cursor++;
			}
		}

		return any;
	}

	private void record(String name, String declaration) {
		String existing = this.blockMembers.putIfAbsent(name, declaration);
		if (existing != null && !existing.equals(declaration)) {
			this.conflicts.add(name);
		}
	}


	private String header() {
		Set<String> used = usedNames();
		List<String> lines = new ArrayList<>();
		lines.add(VERSION);

		for (Map.Entry<String, String> define : this.engineDefines.entrySet()) {
			lines.add(define.getValue().isEmpty()
					? "#define " + define.getKey()
					: "#define " + define.getKey() + " " + define.getValue());
		}

		List<String> members = new ArrayList<>();
		for (Map.Entry<String, String> member : LegacyGlsl.FIXED_FUNCTION_MEMBERS.entrySet()) {
			if (!used.contains(member.getKey())) {
				continue;
			}

			if (member.getKey().equals("of_Fog")) {
				lines.add(FOG_STRUCT);
			}

			members.add("\t" + member.getValue() + ";");
		}

		for (String declaration : this.blockMembers.values()) {
			members.add("\t" + declaration + ";");
		}

		if (!members.isEmpty()) {
			lines.add("layout(std140) uniform " + UNIFORM_BLOCK + " {");
			lines.addAll(members);
			lines.add("};");
		}

		if (this.stage == ProgramStage.VERTEX) {
			for (Map.Entry<String, String> attribute : LegacyGlsl.FIXED_ATTRIBUTES.entrySet()) {
				if (used.contains(attribute.getKey())) {
					lines.add("in " + attribute.getValue() + ";");
				}
			}
		}

		if (this.stage == ProgramStage.VERTEX) {
			for (Map.Entry<String, String> attribute : LegacyGlsl.ENGINE_ATTRIBUTES.entrySet()) {
				if (used.contains(attribute.getKey()) && !this.declaredNames.contains(attribute.getKey())) {
					lines.add("in " + attribute.getValue() + ";");
				}
			}
		}

		if (used.contains("of_FogFragCoord")) {
			lines.add((this.stage == ProgramStage.VERTEX ? "out" : "in") + " float of_FogFragCoord;");
		}

		for (int slot = 0; slot <= this.maxFragmentOutput; slot++) {
			lines.add("layout(location = " + slot + ") out vec4 ofFragData" + slot + ";");
		}

		return String.join("\n", lines) + "\n";
	}

	/**
	 * Every name the translated body mentions. Taken from the tokens rather than the text, so a
	 * name that only appears inside a comment does not have a uniform declared for it.
	 */
	private Set<String> usedNames() {
		Set<String> names = new HashSet<>(this.injectedNames);
		for (Token token : this.tokens) {
			if (token.kind() == Kind.IDENTIFIER) {
				names.add(token.text());
			}
		}

		return names;
	}

	private TranslatedUnit.Notes notes() {
		return new TranslatedUnit.Notes(this.maxFragmentOutput + 1, this.dynamicFragData,
				this.blockMembers.size(), this.opaqueUniforms, this.conflicts.size(),
				this.shadowCalls, this.strippedExtensions, List.copyOf(this.conflicts));
	}


	private void replace(int index, String text) {
		this.tokens.set(index, this.tokens.get(index).as(text));
	}

	/** Replaces a token with text of our own, which no later pass will match again. */
	private void inject(int index, String text) {
		this.tokens.set(index, new Token(Kind.RAW, text, this.tokens.get(index).directive()));
	}

	private void blank(int index) {
		if (index >= 0) {
			this.tokens.set(index, Token.BLANK);
		}
	}

	/** Empties a range but keeps its line breaks, so error messages still point at the right line. */
	private void blankRange(int start, int end) {
		for (int index = start; index <= end; index++) {
			if (this.tokens.get(index).kind() != Kind.NEWLINE) {
				this.tokens.set(index, Token.BLANK);
			}
		}
	}

	private void blankDirective(int hash) {
		for (int index = hash; index < this.tokens.size(); index++) {
			if (this.tokens.get(index).kind() == Kind.NEWLINE) {
				return;
			}

			this.tokens.set(index, Token.BLANK);
		}
	}

	/**
	 * The next token that is neither space nor comment. A line break ends the search inside a
	 * directive, since a directive is one line, and is stepped over everywhere else.
	 */
	private int significantAfter(int index) {
		if (index < 0) {
			return -1;
		}

		boolean directive = this.tokens.get(index).directive() != null;
		for (int scan = index + 1; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.trivia()) {
				continue;
			}

			if (token.kind() == Kind.NEWLINE) {
				if (directive) {
					return -1;
				}

				continue;
			}

			return scan;
		}

		return -1;
	}

	/**
	 * The identifier a directive declares or tests, which is the second one on its line. The first
	 * is the directive keyword itself.
	 */
	private int macroNameAfter(int hash) {
		boolean keywordSeen = false;

		for (int scan = hash + 1; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() == Kind.NEWLINE) {
				return -1;
			}

			if (token.trivia()) {
				continue;
			}

			if (!keywordSeen) {
				keywordSeen = true;
				continue;
			}

			return token.kind() == Kind.IDENTIFIER ? scan : -1;
		}

		return -1;
	}

	/** The previous token that is neither space, comment nor line break, staying out of directives. */
	private int significantBefore(int index) {
		for (int scan = index - 1; scan >= 0; scan--) {
			Token token = this.tokens.get(scan);
			if (token.trivia() || token.kind() == Kind.NEWLINE) {
				continue;
			}

			return token.directive() == null ? scan : -1;
		}

		return -1;
	}

	/**
	 * Which line of the expanded unit each token sits on. Recomputed rather than kept, because
	 * wrapping a shadow lookup inserts tokens and moves every index after it.
	 */
	private int[] lineNumbers() {
		int[] lines = new int[this.tokens.size()];
		int line = 0;

		for (int index = 0; index < this.tokens.size(); index++) {
			lines[index] = line;
			String text = this.tokens.get(index).text();
			for (int at = 0; at < text.length(); at++) {
				if (text.charAt(at) == '\n') {
					line++;
				}
			}
		}

		return lines;
	}

	/** The opening parenthesis of a call on the identifier at this index, or -1 if it is not one. */
	private int callOpener(int index) {
		int next = significantAfter(index);

		return next >= 0 && this.tokens.get(next).operator("(") ? next : -1;
	}

	private int matchingBracket(int open) {
		if (open < 0) {
			return -1;
		}

		String opening = this.tokens.get(open).text();
		String closing = opening.equals("(") ? ")" : "]";
		int depth = 0;

		for (int scan = open; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() != Kind.OPERATOR) {
				continue;
			}

			if (token.operator(opening)) {
				depth++;
			} else if (token.operator(closing)) {
				depth--;
				if (depth == 0) {
					return scan;
				}
			}
		}

		return -1;
	}

	/** Where the statement containing this token starts: just past whatever ended the last one. */
	private int statementStart(int index) {
		int start = 0;
		for (int scan = index - 1; scan >= 0; scan--) {
			Token token = this.tokens.get(scan);
			boolean boundary = token.kind() == Kind.HASH || token.directive() != null
					|| token.operator(";") || token.operator("{") || token.operator("}");
			if (boundary) {
				start = scan + 1;
				break;
			}
		}

		while (start < index) {
			Token token = this.tokens.get(start);
			if (!token.trivia() && token.kind() != Kind.NEWLINE) {
				break;
			}

			start++;
		}

		return start;
	}

	/** The semicolon closing this statement, or -1 if a brace opens first or none is found. */
	private int statementEnd(int index) {
		int depth = 0;

		for (int scan = index; scan < this.tokens.size(); scan++) {
			Token token = this.tokens.get(scan);
			if (token.kind() != Kind.OPERATOR || token.directive() != null) {
				continue;
			}

			String text = token.text();
			if (text.equals("(") || text.equals("[")) {
				depth++;
			} else if (text.equals(")") || text.equals("]")) {
				depth--;
			} else if (depth == 0 && text.equals("{")) {
				return -1;
			} else if (depth == 0 && text.equals(";")) {
				return scan;
			}
		}

		return -1;
	}

	private List<Integer> significantRange(int start, int end) {
		List<Integer> found = new ArrayList<>();
		for (int scan = start; scan <= end; scan++) {
			Token token = this.tokens.get(scan);
			if (!token.trivia() && token.kind() != Kind.NEWLINE) {
				found.add(scan);
			}
		}

		return found;
	}

	private static boolean isQualifier(Token token) {
		return token.kind() == Kind.IDENTIFIER
				&& (LegacyGlsl.MEMORY_QUALIFIERS.contains(token.text())
						|| LegacyGlsl.PRECISION_QUALIFIERS.contains(token.text()));
	}
}
