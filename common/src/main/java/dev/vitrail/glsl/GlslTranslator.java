package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;
import dev.vitrail.pack.DrawBuffers;
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
import java.util.TreeMap;

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
 * <p>
 * The fragment outputs carry one rule that is not GLSL's and cannot be read off the language.
 * 26.2 does not keep the location a stage declares: {@code IntermediaryShaderModule.createFromSpirv}
 * asks SPIR-V reflection for the outputs and writes the rank of each one over its own location
 * decoration. The order reflection answers in is the order the compiler first met the names in, so
 * a stage that writes output one before output zero has the two swapped, and nothing says a word
 * about it. Everything below about outputs exists for that: they are all declared here, from zero
 * up with no gaps, and named once each in ascending order by a function ahead of anything the pack
 * wrote and called first thing in {@code main}. The rank is then the location and the rewrite is
 * the identity.
 */
public final class GlslTranslator {

	private static final String VERSION = "#version 460 core";

	/** The block name has to be declared to the pipeline by hand later, so it is fixed here. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/** The one varying the engine names itself, so the one both stages have to agree about. */
	private static final String FOG_COORD = "of_FogFragCoord";

	private static final String FOG_STRUCT =
			"struct OfFog { vec4 color; float density; float start; float end; float scale; };";

	/**
	 * How many outputs a fragment stage may declare. Not OptiFine's sixteen colour targets, which
	 * is the other question: a pipeline in 26.2 carries eight colour target states and no more,
	 * {@code ColorTargetState.MAX_COLOR_TARGETS}, and its builder holds them in an array of that
	 * length, so a ninth output has nowhere to land.
	 */
	private static final int MAX_FRAGMENT_OUTPUTS = 8;

	/** Names the ascending prologue, which nothing else in a pack is going to be called. */
	private static final String ORDER_OUTPUTS = "ofOrderOutputs";

	/** Matches the expander's own ceiling: nothing it produces should ever reach this. */
	private static final int MAX_SOURCE_CHARACTERS = 4_000_000;

	/**
	 * How far a declaration may reach for its semicolon. A pack that leaves one out would
	 * otherwise have the rest of the file scanned once per {@code uniform} it declares, which is
	 * quadratic: forty thousand such lines took twenty-seven seconds.
	 */
	private static final int MAX_STATEMENT_TOKENS = 4096;


	private final ExpandedUnit unit;
	private final ProgramStage stage;
	private final Map<String, String> engineDefines;

	/** A pass drawn over a quad rather than over the world takes its attributes differently. */
	private final boolean fullscreen;
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
	private final Map<String, String> samplers = new LinkedHashMap<>();

	/** Outputs the pack declares itself, by the location it asked for, moved into the header. */
	private final Map<Integer, Output> packOutputs = new TreeMap<>();

	private final Set<String> injectedNames = new HashSet<>();
	private final List<String> conflicts = new ArrayList<>();
	private final List<Integer> drawBuffers = new ArrayList<>();

	private Set<String> declaredAfter = Set.of();
	private Set<String> used = Set.of();

	private int maxFragmentOutput = -1;
	private boolean ordered;
	private int dynamicFragData;
	private int shadowCalls;
	private int unwrappedShadowCalls;
	private int strippedExtensions;

	private GlslTranslator(ExpandedUnit unit, ProgramStage stage, Map<String, String> engineDefines,
			boolean fullscreen) {
		this.unit = unit;
		this.stage = stage;
		this.engineDefines = engineDefines;
		this.fullscreen = fullscreen;

		// Tokens cost far more than the text they came from, roughly seventy bytes each, so a unit
		// the expander should never have produced has to be refused before it is read rather than
		// after. Running out of memory here throws an Error, and an Error goes straight past the
		// catch that is supposed to turn a bad pack into a report.
		String text = unit.text();
		if (text.length() > MAX_SOURCE_CHARACTERS) {
			throw new IllegalStateException(unit.entry() + " expands to " + text.length()
					+ " characters, past the " + MAX_SOURCE_CHARACTERS + " a unit is allowed");
		}

		this.tokens = new ArrayList<>(GlslLexer.lex(text));
	}

	/**
	 * Rewrites one stage on its own, agreeing with nothing but itself. Fine for measuring, and
	 * wrong for rendering: see {@link ProgramTranslator} for why the stages of one program have to
	 * be given a header together.
	 */
	public static TranslatedUnit translate(ExpandedUnit unit, ProgramStage stage) {
		Stage prepared = prepare(unit, stage, false);

		return prepared.render(prepared.uniforms(), prepared.samplers(), prepared.varyings());
	}

	/**
	 * Rewrites one stage and stops short of the header, so that a caller can settle it.
	 *
	 * @param fullscreen whether this program is drawn over a quad rather than over the world,
	 *                   which decides where its vertex inputs come from
	 */
	public static Stage prepare(ExpandedUnit unit, ProgramStage stage, boolean fullscreen) {
		GlslTranslator translator = new GlslTranslator(unit, stage,
				EngineDefines.table(EngineDefines.machine()), fullscreen);
		translator.rewrite();

		return new Stage(translator);
	}

	private void rewrite() {
		collectMacroNames();
		collectDeclarations();
		collectDrawBuffers();
		dropVersionAndExtensions();
		rewriteIdentifiers();
		dropPrecision();
		rewriteFragmentOutputs();
		liftFragmentOutputs();
		liftUniforms();
		orderFragmentOutputs();

		this.used = usedNames();
		this.declaredAfter = declaredUnderAType();
	}

	/**
	 * One stage rewritten, waiting for a header. What it asks for is separate from what it is
	 * given: a stage that asks for six uniforms may be handed twelve, because a sibling stage of
	 * the same program needs the other six and the block has to be the same on both sides.
	 */
	public static final class Stage {

		private final GlslTranslator translator;

		private Stage(GlslTranslator translator) {
			this.translator = translator;
		}

		public ProgramStage stage() {
			return this.translator.stage;
		}

		/** The block members this stage reads, fixed function state first. */
		public List<TranslatedUnit.Uniform> uniforms() {
			return this.translator.ownBlock();
		}

		public List<TranslatedUnit.Uniform> samplers() {
			return asUniforms(this.translator.samplers);
		}

		/** Varyings the engine names, which both sides of a program have to declare or neither. */
		public Set<String> varyings() {
			return this.translator.used.contains(FOG_COORD) ? Set.of(FOG_COORD) : Set.of();
		}

		/** Names this stage declares itself, and that a shared block must therefore not shadow. */
		public Set<String> declared() {
			return this.translator.declaredAfter;
		}

		/** Names this stage lifted into the block, where the block member is the real meaning. */
		public Set<String> lifted() {
			return this.translator.blockMembers.keySet();
		}

		public TranslatedUnit render(List<TranslatedUnit.Uniform> block,
				List<TranslatedUnit.Uniform> samplers, Set<String> varyings) {
			return render(block, samplers, varyings, Set.of());
		}

		public TranslatedUnit render(List<TranslatedUnit.Uniform> block,
				List<TranslatedUnit.Uniform> samplers, Set<String> varyings, Set<String> shadowed) {
			return this.translator.render(block, samplers, varyings, shadowed);
		}
	}

	private TranslatedUnit render(List<TranslatedUnit.Uniform> block,
			List<TranslatedUnit.Uniform> samplers, Set<String> varyings, Set<String> shadowed) {
		return new TranslatedUnit(this.unit.entry(), this.stage,
				header(block, varyings, shadowed) + body(shadowed) + "\n", notes(),
				List.copyOf(this.drawBuffers), List.copyOf(block), List.copyOf(samplers));
	}

	/**
	 * The rewritten text, with any name the caller flagged moved out of the way.
	 * <p>
	 * A shared block carries members some of its stages never asked for, and one of those stages
	 * may already use that name for a value of its own: Bliss works out {@code sunVec} in its
	 * vertex shader and takes it as a uniform in its fragment shader. Renaming is safe precisely
	 * where it is needed, because a stage that never declared the name as a uniform has only one
	 * meaning for it, its own.
	 */
	private String body(Set<String> shadowed) {
		if (shadowed.isEmpty()) {
			return GlslLexer.join(this.tokens);
		}

		StringBuilder text = new StringBuilder();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			boolean rename = token.kind() == Kind.IDENTIFIER
					&& shadowed.contains(token.text())
					&& !this.macroNamePositions.contains(index)
					&& (token.directive() == null
							|| !LegacyGlsl.OPAQUE_DIRECTIVES.contains(token.directive()));

			text.append(rename ? "ofOwn_" + token.text() : token.text());
		}

		return text.toString();
	}

	private List<TranslatedUnit.Uniform> ownBlock() {
		List<TranslatedUnit.Uniform> block = new ArrayList<>();

		for (Map.Entry<String, String> member : LegacyGlsl.FIXED_FUNCTION_MEMBERS.entrySet()) {
			if (this.used.contains(member.getKey())) {
				block.add(TranslatedUnit.Uniform.of(member.getKey(), member.getValue()));
			}
		}

		block.addAll(asUniforms(this.blockMembers));

		return block;
	}

	static List<TranslatedUnit.Uniform> asUniforms(Map<String, String> declarations) {
		return declarations.entrySet().stream()
				.map(entry -> TranslatedUnit.Uniform.of(entry.getKey(), entry.getValue()))
				.toList();
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
	 * Which colour attachments this program writes, bounded to what a fragment stage may declare.
	 * The rule itself is in {@link DrawBuffers}, next to the targets it decides the existence of;
	 * all that belongs here is the ceiling, since a target that has to exist and an output that
	 * can be declared are two different questions with two different answers.
	 * <p>
	 * The ceiling is on how many are kept and not on how far they count, which is the difference
	 * between the two questions. Entry {@code n} of this list is where output {@code n} lands, so
	 * an entry past the eighth belongs to an output that cannot exist; but the entries themselves
	 * name colour targets and Reverie names {@code colortex19}, which a bound of sixteen on the
	 * value used to drop, moving every attachment declared after it.
	 */
	private void collectDrawBuffers() {
		DrawBuffers.parse(this.unit).stream().limit(MAX_FRAGMENT_OUTPUTS).forEach(this.drawBuffers::add);
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
			if (shadow != null && callOpener(index) >= 0) {
				int close = matchingBracket(callOpener(index));
				if (close < 0) {
					// Unbalanced from here, usually because a macro opened the parenthesis. The
					// call is left alone and the compiler will say so, but silence here would
					// mean a lookup nobody wrapped and nobody counted.
					this.unwrappedShadowCalls++;
				}

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
				// mean declaring the lot, and writing the ones a pack never touches is not free,
				// so this is counted and left to fail loudly.
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

	/** One fragment output the pack declared for itself, once its declaration has been moved. */
	private record Output(String name, String type) {
	}

	/**
	 * Moves the outputs a pack declares for itself up into the header, next to the ones this
	 * translation declares, so that the whole set is written in one place and in one order.
	 * <p>
	 * Half the corpus takes this road rather than {@code gl_FragData}: two hundred and forty three
	 * fragment stages of the eight packs declare their own outputs, all of them with a location
	 * they wrote themselves, and none of them mixing the two ways. Reverie's translucent stages are
	 * where it costs something, since they name {@code Albedo}, {@code buf1}, {@code buf2} and
	 * {@code Shadow} and write the fourth before the second.
	 * <p>
	 * Only a live line is moved, for the reason {@link #liftUniforms} gives about uniforms: a
	 * declaration in a branch nobody takes would become unconditional on the way up. A declaration
	 * this refuses simply stays where it stands, which is what happens today, so the refusal costs
	 * the ordering and nothing else.
	 */
	private void liftFragmentOutputs() {
		if (this.stage != ProgramStage.FRAGMENT) {
			return;
		}

		int[] lines = lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("out") || token.directive() != null) {
				continue;
			}

			if (this.unit.isLive(lines[index]) && opensDeclaration(index)) {
				liftOutput(index);
			}
		}

		for (int location : this.packOutputs.keySet()) {
			this.maxFragmentOutput = Math.max(this.maxFragmentOutput, location);
		}
	}

	/**
	 * Whether this {@code out} opens a declaration rather than qualifying a function parameter.
	 * Told apart by what stands before it: a parameter follows the opening parenthesis or a comma,
	 * a declaration follows the end of the last one or a layout qualifier.
	 */
	private boolean opensDeclaration(int index) {
		int before = significantBefore(index);
		if (before < 0) {
			return true;
		}

		Token token = this.tokens.get(before);

		return token.operator(";") || token.operator("}") || token.operator(")");
	}

	private void liftOutput(int keyword) {
		int start = statementStart(keyword);
		int end = statementEnd(keyword);
		if (start < 0 || end < 0) {
			return;
		}

		List<Integer> parts = significantRange(start, end);
		int cursor = parts.indexOf(keyword);

		// A type, one declarator and the semicolon, and nothing else. A pack that declares two
		// outputs in one statement, or an array, or a type this cannot name, keeps its declaration
		// where it wrote it rather than having it guessed at.
		if (cursor < 0 || parts.size() != cursor + 4) {
			return;
		}

		String type = this.tokens.get(parts.get(cursor + 1)).text();
		Token name = this.tokens.get(parts.get(cursor + 2));
		if (!LegacyGlsl.TYPE_NAMES.contains(type) || name.kind() != Kind.IDENTIFIER
				|| !this.tokens.get(parts.get(cursor + 3)).operator(";")) {
			return;
		}

		// A location the pack spelled out as a number. Anything else, a macro or nothing at all,
		// leaves no way to say where the output belongs, and inventing one would be worse than the
		// disorder this exists to fix.
		int location = literalLocation(parts, cursor);
		if (location < 0 || location >= MAX_FRAGMENT_OUTPUTS || location <= this.maxFragmentOutput) {
			return;
		}

		if (this.packOutputs.putIfAbsent(location, new Output(name.text(), type)) == null) {
			blankRange(start, end);
		}
	}

	/** The {@code location = n} of a layout qualifier standing before this {@code out}, or -1. */
	private int literalLocation(List<Integer> parts, int keyword) {
		for (int at = 0; at + 2 < keyword; at++) {
			if (!this.tokens.get(parts.get(at)).identifier("location")) {
				continue;
			}

			Token number = this.tokens.get(parts.get(at + 2));
			String text = number.text();
			if (this.tokens.get(parts.get(at + 1)).operator("=") && number.kind() == Kind.NUMBER
					&& text.length() <= 2 && text.chars().allMatch(Character::isDigit)) {
				return Integer.parseInt(text);
			}
		}

		return -1;
	}

	/**
	 * Makes {@code main} name every output in ascending order before it does anything else, by
	 * calling the function the header declares for it.
	 * <p>
	 * The call goes into {@code main} rather than the naming itself because the order that decides
	 * is the order the compiler first meets the names in, and it walks the functions in the order
	 * they were written: a helper standing before {@code main} and writing one output would take
	 * the first rank whatever {@code main} does afterwards. The function is declared in the header
	 * and so stands before everything the pack wrote.
	 * <p>
	 * The brace is replaced rather than a token inserted, since inserting moves every index after
	 * it and this runs once the rest of the rewrite has settled.
	 */
	private void orderFragmentOutputs() {
		if (this.stage != ProgramStage.FRAGMENT || this.maxFragmentOutput < 0) {
			return;
		}

		int brace = mainBrace();
		if (brace >= 0) {
			inject(brace, "{ " + ORDER_OUTPUTS + "();");
			this.ordered = true;
		}
	}

	/**
	 * The brace opening the body of {@code main}, or -1 when the unit serves no such function.
	 * <p>
	 * A dead one is stepped over, for the reason {@link #liftFragmentOutputs} gives about
	 * declarations: a pack that writes one {@code main} per branch of an {@code #if} would
	 * otherwise have the prologue put in whichever came first in the file, and the branch the
	 * compiler actually sees would name its outputs in whatever order the pack wrote them in.
	 */
	private int mainBrace() {
		int[] lines = lineNumbers();
		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (!token.identifier("main") || token.directive() != null
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			int close = matchingBracket(callOpener(index));
			int brace = significantAfter(close);
			if (brace >= 0 && this.tokens.get(brace).operator("{")) {
				return brace;
			}
		}

		return -1;
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
		if (start < 0) {
			return;
		}

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

		// An opaque uniform is read the same way but keeps its declaration where it stands. It is
		// still recorded, because the engine has to name every sampler it binds.
		boolean opaque = LegacyGlsl.isOpaqueType(type);
		if (!readDeclarators(parts, cursor + 1, type, opaque ? this.samplers : this.blockMembers)) {
			return;
		}

		if (!opaque) {
			blankRange(start, end);
		}
	}

	/** Records each declarator of one declaration. False if none could be read. */
	private boolean readDeclarators(List<Integer> parts, int from, String type,
			Map<String, String> target) {
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

			record(target, token.text(), declaration.toString());
			any = true;

			if (cursor < parts.size() && this.tokens.get(parts.get(cursor)).operator(",")) {
				cursor++;
			}
		}

		return any;
	}

	private void record(Map<String, String> target, String name, String declaration) {
		String existing = target.putIfAbsent(name, declaration);
		if (existing != null && !existing.equals(declaration)) {
			this.conflicts.add(name);
		}
	}


	private String header(List<TranslatedUnit.Uniform> block, Set<String> varyings,
			Set<String> shadowed) {
		List<String> lines = new ArrayList<>();
		lines.add(VERSION);

		for (Map.Entry<String, String> define : this.engineDefines.entrySet()) {
			lines.add(define.getValue().isEmpty()
					? "#define " + define.getKey()
					: "#define " + define.getKey() + " " + define.getValue());
		}

		// The block is written in the order it was handed over, and nothing sorts it: a std140
		// buffer is filled by walking the members, so a different order is a different buffer.
		if (block.stream().anyMatch(member -> member.name().equals("of_Fog"))) {
			lines.add(FOG_STRUCT);
		}

		if (!block.isEmpty()) {
			lines.add("layout(std140) uniform " + UNIFORM_BLOCK + " {");
			for (TranslatedUnit.Uniform member : block) {
				lines.add("\t" + member.declaration() + ";");
			}

			lines.add("};");
		}

		// Attributes stay a matter for the stage that has them. Only a vertex shader has inputs
		// from a buffer, so there is no other side to agree with.
		if (this.stage == ProgramStage.VERTEX && this.fullscreen) {
			lines.addAll(LegacyGlsl.FULLSCREEN_ATTRIBUTES);
		} else if (this.stage == ProgramStage.VERTEX) {
			for (Map.Entry<String, String> attribute : LegacyGlsl.FIXED_ATTRIBUTES.entrySet()) {
				if (this.used.contains(attribute.getKey())) {
					lines.add("in " + attribute.getValue() + ";");
				}
			}

			for (Map.Entry<String, String> attribute : LegacyGlsl.ENGINE_ATTRIBUTES.entrySet()) {
				if (this.used.contains(attribute.getKey())
						&& !this.declaredNames.contains(attribute.getKey())) {
					lines.add("in " + attribute.getValue() + ";");
				}
			}
		}

		// Declared on both sides or on neither, whether this stage reads it or not. A varying the
		// vertex writes and the fragment never mentions is accepted in silence and shifts the
		// location of everything declared after it.
		if (varyings.contains(FOG_COORD)) {
			lines.add((this.stage == ProgramStage.VERTEX ? "out" : "in") + " float " + FOG_COORD + ";");
		}

		// From zero up with no gaps, because a location the game finds nothing declared at is not
		// left empty: it renumbers what is there and everything above the gap moves down one.
		for (int slot = 0; slot <= this.maxFragmentOutput; slot++) {
			Output output = this.packOutputs.get(slot);
			lines.add("layout(location = " + slot + ") out "
					+ (output == null ? "vec4" : output.type()) + " " + outputName(slot, shadowed) + ";");
		}

		if (this.ordered) {
			StringBuilder order = new StringBuilder("void " + ORDER_OUTPUTS + "() {");
			for (int slot = 0; slot <= this.maxFragmentOutput; slot++) {
				order.append(' ').append(outputName(slot, shadowed)).append(';');
			}

			lines.add(order.append(" }").toString());
		}

		return String.join("\n", lines) + "\n";
	}

	/** What output {@code slot} is called, which is the pack's own name when it declared one. */
	private String outputName(int slot, Set<String> shadowed) {
		Output output = this.packOutputs.get(slot);
		if (output == null) {
			return "ofFragData" + slot;
		}

		// Renamed here as well as in the body, since the declaration has moved up out of it and
		// the two halves of one name have to keep agreeing.
		return shadowed.contains(output.name()) ? "ofOwn_" + output.name() : output.name();
	}

	/**
	 * Names declared under a built-in type once the uniforms have been lifted out of the text. The
	 * outputs moved into the header count too: the body no longer shows them, and a shared block
	 * that carried the same name would end up declaring it twice.
	 */
	private Set<String> declaredUnderAType() {
		Set<String> names = new HashSet<>();
		this.packOutputs.values().forEach(output -> names.add(output.name()));

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || token.directive() != null) {
				continue;
			}

			int before = significantBefore(index);
			if (before >= 0 && LegacyGlsl.TYPE_NAMES.contains(this.tokens.get(before).text())) {
				names.add(token.text());
			}
		}

		return names;
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
				this.conflicts.size(), this.shadowCalls, this.unwrappedShadowCalls,
				this.strippedExtensions, List.copyOf(this.conflicts));
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

	/**
	 * Where the statement containing this token starts: just past whatever ended the last one.
	 * Returns -1 when no end to the previous statement is within reach, since blanking a range
	 * whose beginning was guessed would erase code that is none of our business.
	 */
	private int statementStart(int index) {
		int limit = Math.max(0, index - MAX_STATEMENT_TOKENS);
		int start = -1;

		for (int scan = index - 1; scan >= limit; scan--) {
			Token token = this.tokens.get(scan);
			boolean boundary = token.kind() == Kind.HASH || token.directive() != null
					|| token.operator(";") || token.operator("{") || token.operator("}");
			if (boundary) {
				start = scan + 1;
				break;
			}
		}

		if (start < 0) {
			// Reaching the first token is a real answer; running out of budget is not.
			if (limit > 0) {
				return -1;
			}

			start = 0;
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
		int last = Math.min(this.tokens.size(), index + MAX_STATEMENT_TOKENS);

		for (int scan = index; scan < last; scan++) {
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
