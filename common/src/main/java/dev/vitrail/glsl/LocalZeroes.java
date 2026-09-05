package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives every variable a compiled module can read before writing the zero it reads under Iris.
 * <p>
 * <strong>Why a pass on the SPIR-V and not a rule for packs.</strong> GLSL leaves a variable
 * declared without an initialiser undefined until it is written, so a pack that reads one first
 * has a defect, and one that shows nowhere on the platform it was written on: measured against
 * Iris at the same spot, on the same machine, the pack reads zero there. Compiled through shaderc
 * the {@code OpVariable} stays bare, and what the Vulkan driver hands back changed with the face
 * being drawn and with every edit to the shader's text, which is what a register left over from
 * the previous work looks like. Complementary Unbound's {@code GetComplexLightVolume} accumulates
 * into a {@code vec4} it never zeroes when its corner leak fix is on, and that was a hand black on
 * one face and black lines along every far shore, both gone with that one variable started at
 * zero, and the face then reading the byte Iris reads. The pack is wrong and the picture Iris
 * shows is the one packs were tuned against, so this engine reproduces the zero.
 * <p>
 * <strong>Why here and not in the translated text.</strong> An initialiser written into the GLSL
 * has to know the type of each variable, arrays and structs included, and has to find every
 * declaration in every scope of every unit the pack ships. The SPIR-V already carries both: a
 * variable is one instruction naming its pointer type, and {@code OpConstantNull} zeroes every
 * type a bare variable of a pack has, the opaque handles being the ones it refuses and the ones
 * no GLSL declares bare.
 * <p>
 * <strong>Only the variables that need it, and that was measured.</strong> Zeroing every bare
 * variable of a module cost eight percent of the frame on Complementary Unbound at its Ultra
 * profile, on a bench where the two states ran in one launch a pack reload apart: twelve hundred
 * locals in one fragment stage, most of them the compiler's own temporaries, plus a hundred
 * globals, and the driver did not fold those stores away. So the pass works out which variables
 * some path can read before any store reaches them, by the ordinary definite-assignment walk over
 * each function's blocks, and only those get the zero. Calls are summarised rather than feared:
 * glslang hands every argument over as a pointer to a temporary, so a function is first asked
 * which of its parameters and which globals it may read before writing, and which it writes on
 * every path that returns, and a call site reads and defines accordingly. The summaries start at
 * the most conservative answer and the module is walked again until none moves, which GLSL's ban
 * on recursion makes finite: the call graph is a directed acyclic graph, the functions are walked
 * in the order the module lists them, and each round settles at least one more level of callers
 * above their callees. A global is judged from the entry point, since that is where an invocation
 * starts.
 * <p>
 * <strong>Where the walk is conservative and where it is blind.</strong> A store through an access
 * chain writes one component and so defines nothing, and a callee this module does not define
 * reads everything and defines nothing: both err towards the zero. A pointer the walk cannot
 * trace to a variable, which would take a pointer chosen by {@code OpPhi} or {@code OpSelect} or
 * handed back by a call, is left alone; glslang emits none of those for GLSL, which has no pointer
 * values.
 * <p>
 * <strong>The optimised road.</strong> A module shaderc optimised before this pass saw it has had
 * its variables rewritten into values, and a read of one never written is then an {@code OpUndef}
 * rather than a bare variable. Those become the null constant of their type as well, so a
 * compute compiled at the performance level reads its zero like a fragment compiled at none.
 */
public final class LocalZeroes {

	/**
	 * What the module cache hashes into its key for this pass: any change to what the pass emits
	 * for a given module bumps it, so that a blob zeroed by the old walk is never served under the
	 * new one, with nothing in the log to say why.
	 */
	public static final String VERSION = "locals-2";

	/**
	 * What one pass did to one module.
	 *
	 * @param variables the bare variables given an initialiser
	 * @param undefs    the undefined values given a constant instead
	 */
	public record Result(int[] words, int variables, int undefs) {

		/** Whether the module came back changed at all. */
		public boolean changed() {
			return this.variables > 0 || this.undefs > 0;
		}
	}

	private static final int MAGIC = 0x07230203;

	private static final int HEADER_WORDS = 5;

	private static final int OP_UNDEF = 1;
	private static final int OP_ENTRY_POINT = 15;
	private static final int OP_TYPE_FIRST = 19;
	private static final int OP_TYPE_LAST = 39;
	private static final int OP_TYPE_IMAGE = 25;
	private static final int OP_TYPE_SAMPLER = 26;
	private static final int OP_TYPE_SAMPLED_IMAGE = 27;
	private static final int OP_TYPE_ARRAY = 28;
	private static final int OP_TYPE_RUNTIME_ARRAY = 29;
	private static final int OP_TYPE_STRUCT = 30;
	private static final int OP_TYPE_OPAQUE = 31;
	private static final int OP_TYPE_POINTER = 32;
	private static final int OP_TYPE_FORWARD_POINTER = 39;
	private static final int OP_CONSTANT_NULL = 46;
	private static final int OP_FUNCTION = 54;
	private static final int OP_FUNCTION_PARAMETER = 55;
	private static final int OP_FUNCTION_END = 56;
	private static final int OP_FUNCTION_CALL = 57;
	private static final int OP_VARIABLE = 59;
	private static final int OP_LOAD = 61;
	private static final int OP_STORE = 62;
	private static final int OP_COPY_MEMORY = 63;
	private static final int OP_ACCESS_CHAIN = 65;
	private static final int OP_IN_BOUNDS_ACCESS_CHAIN = 66;
	private static final int OP_PTR_ACCESS_CHAIN = 67;
	private static final int OP_COPY_OBJECT = 83;
	private static final int OP_LABEL = 248;
	private static final int OP_BRANCH = 249;
	private static final int OP_BRANCH_CONDITIONAL = 250;
	private static final int OP_SWITCH = 251;
	private static final int OP_KILL = 252;
	private static final int OP_RETURN = 253;
	private static final int OP_RETURN_VALUE = 254;
	private static final int OP_UNREACHABLE = 255;
	private static final int OP_TERMINATE_INVOCATION = 4416;

	private static final int STORAGE_PRIVATE = 6;
	private static final int STORAGE_FUNCTION = 7;

	/** The instruction header of an OpConstantNull, three words long. */
	private static final int CONSTANT_NULL_HEAD = (3 << 16) | OP_CONSTANT_NULL;

	/** The instruction header of an OpVariable that carries an initialiser, five words long. */
	private static final int VARIABLE_WITH_INITIALISER_HEAD = (5 << 16) | OP_VARIABLE;

	/**
	 * How many rounds the summaries get before the pass stops refining them. Walked in the
	 * module's order, a chain of callers each listed before its callee settles one level a round,
	 * and no pack's call graph is anywhere near this deep; a module that has not settled by then
	 * keeps its conservative summaries and zeroes more, never less.
	 */
	private static final int MOST_ROUNDS = 32;

	private LocalZeroes() {
	}

	/**
	 * The module with every variable some path reads before writing initialised to zero, and
	 * every undefined value replaced by the zero of its type.
	 *
	 * @param words the module in the platform's word order, the magic number first
	 * @return the same array when nothing needed changing or the module could not be read as
	 *         SPIR-V, a new one otherwise
	 */
	public static Result apply(int[] words) {
		if (words.length < HEADER_WORDS || words[0] != MAGIC || !wellFormed(words)) {
			return new Result(words, 0, 0);
		}

		Types types = Types.read(words);

		// The bare globals, in declaration order, which is the index every function knows them by
		// behind its own parameters; the entry points; the undefined values, wherever they stand;
		// then each function.
		List<Integer> entryPoints = new ArrayList<>();
		List<Integer> privateIds = new ArrayList<>();
		List<Integer> privateAt = new ArrayList<>();
		// An undefined value's instruction position, and the type whose null it becomes.
		Map<Integer, Integer> undefAt = new LinkedHashMap<>();
		int at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			int opcode = words[at] & 0xFFFF;
			if (opcode == OP_ENTRY_POINT && count >= 3) {
				entryPoints.add(words[at + 2]);
			} else if (opcode == OP_VARIABLE && count == 4 && words[at + 3] == STORAGE_PRIVATE
					&& types.zeroablePointee(words[at + 1]) != null) {
				privateIds.add(words[at + 2]);
				privateAt.add(at);
			} else if (opcode == OP_UNDEF && count == 3 && types.zeroable(words[at + 1])) {
				undefAt.put(at, words[at + 1]);
			}

			at += count;
		}

		List<Function> functions = new ArrayList<>();
		at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			if ((words[at] & 0xFFFF) == OP_FUNCTION) {
				Function function = Function.read(words, at, types, privateIds);
				functions.add(function);
				at = function.end();
				continue;
			}

			at += count;
		}

		// The variables to zero, keyed by the position of their instruction.
		Map<Integer, Integer> zeroAt = new LinkedHashMap<>();
		readFirst(words, functions, entryPoints, privateAt, types, zeroAt);

		if (zeroAt.isEmpty() && undefAt.isEmpty()) {
			return new Result(words, 0, 0);
		}

		// One null constant per pointee type, in first-use order so the output is deterministic.
		// An undefined value keeps its own id, and its constant is declared where a new one would
		// be: behind the type, which stands before every use of the value wherever the value was.
		Map<Integer, Integer> nulls = new LinkedHashMap<>();
		Map<Integer, List<Integer>> undefsByType = new LinkedHashMap<>();
		int bound = words[3];
		for (int pointee : zeroAt.values()) {
			if (!nulls.containsKey(pointee)) {
				nulls.put(pointee, bound++);
			}
		}

		for (Map.Entry<Integer, Integer> undef : undefAt.entrySet()) {
			undefsByType.computeIfAbsent(undef.getValue(), _ -> new ArrayList<>())
					.add(words[undef.getKey() + 2]);
		}

		int[] out = new int[words.length + 3 * nulls.size() + zeroAt.size()];
		System.arraycopy(words, 0, out, 0, HEADER_WORDS);
		out[3] = bound;
		int to = HEADER_WORDS;
		at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			int opcode = words[at] & 0xFFFF;
			Integer pointee = zeroAt.get(at);
			if (pointee != null) {
				out[to] = VARIABLE_WITH_INITIALISER_HEAD;
				out[to + 1] = words[at + 1];
				out[to + 2] = words[at + 2];
				out[to + 3] = words[at + 3];
				out[to + 4] = nulls.get(pointee);
				to += 5;
				at += count;
				continue;
			}

			if (undefAt.containsKey(at)) {
				// Declared behind its type instead, under the same id.
				at += count;
				continue;
			}

			System.arraycopy(words, at, out, to, count);
			to += count;
			at += count;

			// Right behind the type it zeroes: that is after the type and before any pointer to it,
			// so before any variable of it, wherever the module declares those. A struct or an
			// array is a type like any other here, which is what a textual initialiser could not
			// have said in one line. A forward pointer declaration names no type of its own.
			if (opcode >= OP_TYPE_FIRST && opcode <= OP_TYPE_LAST && opcode != OP_TYPE_FORWARD_POINTER
					&& count >= 2) {
				int type = words[at - count + 1];
				Integer zero = nulls.get(type);
				if (zero != null) {
					out[to] = CONSTANT_NULL_HEAD;
					out[to + 1] = type;
					out[to + 2] = zero;
					to += 3;
				}

				for (int id : undefsByType.getOrDefault(type, List.of())) {
					out[to] = CONSTANT_NULL_HEAD;
					out[to + 1] = type;
					out[to + 2] = id;
					to += 3;
				}
			}
		}

		return new Result(out, zeroAt.size(), undefAt.size());
	}

	/** Whether every instruction's word count keeps inside the module. */
	private static boolean wellFormed(int[] words) {
		int at = HEADER_WORDS;
		while (at < words.length) {
			int count = words[at] >>> 16;
			if (count == 0 || at + count > words.length) {
				return false;
			}

			at += count;
		}

		return true;
	}

	/**
	 * The type declarations of a module, which all stand before its first function: each type's
	 * opcode and the types it is made of.
	 */
	private record Types(Map<Integer, Integer> opcodes, Map<Integer, int[]> members,
			Map<Integer, Integer> pointees) {

		static Types read(int[] words) {
			Map<Integer, Integer> opcodes = new HashMap<>();
			Map<Integer, int[]> members = new HashMap<>();
			Map<Integer, Integer> pointees = new HashMap<>();
			int at = HEADER_WORDS;
			while (at < words.length) {
				int count = words[at] >>> 16;
				int opcode = words[at] & 0xFFFF;
				// A forward pointer declaration's first operand is the pointer type it announces,
				// not a result id of its own.
				if (opcode >= OP_TYPE_FIRST && opcode <= OP_TYPE_LAST
						&& opcode != OP_TYPE_FORWARD_POINTER && count >= 2) {
					int id = words[at + 1];
					opcodes.put(id, opcode);
					if (opcode == OP_TYPE_POINTER && count >= 4) {
						pointees.put(id, words[at + 3]);
					} else if (opcode == OP_TYPE_ARRAY && count >= 3) {
						members.put(id, new int[] {words[at + 2]});
					} else if (opcode == OP_TYPE_STRUCT) {
						int[] made = new int[count - 2];
						System.arraycopy(words, at + 2, made, 0, made.length);
						members.put(id, made);
					}
				}

				at += count;
			}

			return new Types(opcodes, members, pointees);
		}

		/**
		 * The pointee a null constant would name for a variable of this pointer type, or null when
		 * that pointee is not one {@code OpConstantNull} may name.
		 */
		Integer zeroablePointee(int pointerType) {
			Integer pointee = this.pointees.get(pointerType);

			return pointee != null && zeroable(pointee) ? pointee : null;
		}

		/**
		 * Whether {@code OpConstantNull} may name this type: anything but an opaque handle, a
		 * runtime array, a pointer, or a composite holding one of those at any depth, none of
		 * which a bare variable of a translated pack ever has.
		 */
		boolean zeroable(int type) {
			Integer opcode = this.opcodes.get(type);
			if (opcode == null
					|| opcode == OP_TYPE_IMAGE
					|| opcode == OP_TYPE_SAMPLER
					|| opcode == OP_TYPE_SAMPLED_IMAGE
					|| opcode == OP_TYPE_RUNTIME_ARRAY
					|| opcode == OP_TYPE_OPAQUE
					|| opcode == OP_TYPE_POINTER) {
				return false;
			}

			for (int member : this.members.getOrDefault(type, new int[0])) {
				if (!zeroable(member)) {
					return false;
				}
			}

			return true;
		}
	}

	/** One basic block of a function: where its instructions start and end, and where it goes. */
	private record Block(int start, int end, List<Integer> successors, boolean returns) {
	}

	/**
	 * What a call site may assume of a function, over its parameters and then the module's bare
	 * globals: which it can read before it writes them, and which it has written on every path
	 * that returns.
	 */
	private record Summary(BitSet readsFirst, BitSet defines) {

		/** The summary of a function nothing is known about: reads everything, defines nothing. */
		static Summary unknown(int shared) {
			BitSet reads = new BitSet(shared);
			reads.set(0, shared);

			return new Summary(reads, new BitSet(shared));
		}
	}

	/**
	 * One function, read once: its blocks, and the pointers the walk tracks under one index each,
	 * which are its parameters first, then the module's bare globals, then its own bare locals.
	 * Aliases are the access chains and copies made of a tracked pointer, which name the same
	 * variable.
	 *
	 * @param shared  how many indices the parameters and the globals take, which is what a summary
	 *                speaks about
	 * @param localAt the instruction position of each tracked local, by its index less
	 *                {@code shared}
	 */
	private record Function(int id, int end, int parameters, int shared, List<Block> blocks,
			Map<Integer, Integer> labelToBlock, Map<Integer, Integer> index,
			List<Integer> localAt, Map<Integer, Integer> aliases) {

		static Function read(int[] words, int start, Types types, List<Integer> privateIds) {
			int id = words[start + 2];
			List<Block> blocks = new ArrayList<>();
			Map<Integer, Integer> labelToBlock = new HashMap<>();
			Map<Integer, Integer> index = new LinkedHashMap<>();
			List<Integer> localAt = new ArrayList<>();
			Map<Integer, Integer> aliases = new HashMap<>();
			int parameters = 0;
			int at = start + (words[start] >>> 16);
			while (at < words.length && (words[at] & 0xFFFF) == OP_FUNCTION_PARAMETER) {
				// Every parameter takes an index, opaque ones included, so that a call site's
				// argument positions and the summary's bits line up.
				if (types.zeroablePointee(words[at + 1]) != null) {
					index.put(words[at + 2], parameters);
				}

				parameters++;
				at += words[at] >>> 16;
			}

			for (int k = 0; k < privateIds.size(); k++) {
				index.put(privateIds.get(k), parameters + k);
			}

			int shared = parameters + privateIds.size();
			int blockStart = -1;
			while (at < words.length) {
				int count = words[at] >>> 16;
				int opcode = words[at] & 0xFFFF;
				if (opcode == OP_FUNCTION_END) {
					at += count;
					break;
				}

				if (opcode == OP_LABEL) {
					blockStart = at;
					labelToBlock.put(words[at + 1], blocks.size());
				} else if (opcode == OP_VARIABLE && count == 4 && words[at + 3] == STORAGE_FUNCTION) {
					if (types.zeroablePointee(words[at + 1]) != null) {
						index.put(words[at + 2], shared + localAt.size());
						localAt.add(at);
					}
				} else if (opcode == OP_ACCESS_CHAIN || opcode == OP_IN_BOUNDS_ACCESS_CHAIN
						|| opcode == OP_PTR_ACCESS_CHAIN || opcode == OP_COPY_OBJECT) {
					// In the module's order, which is the order a definition precedes its uses in
					// the structured control flow glslang emits.
					Integer base = base(words[at + 3], index, aliases);
					if (base != null) {
						aliases.put(words[at + 2], base);
					}
				} else if (terminator(opcode)) {
					blocks.add(new Block(blockStart, at + count, successors(words, at),
							opcode == OP_RETURN || opcode == OP_RETURN_VALUE));
					blockStart = -1;
				}

				at += count;
			}

			return new Function(id, at, parameters, shared, blocks, labelToBlock, index, localAt,
					aliases);
		}

		int tracked() {
			return this.shared + this.localAt.size();
		}
	}

	/**
	 * Puts into {@code zeroAt} every bare variable of the module that some path reads before a
	 * store reaches it: a local by the walk of its function, a global by the walk of each entry
	 * point, the functions' summaries refined together until none moves.
	 */
	private static void readFirst(int[] words, List<Function> functions, List<Integer> entryPoints,
			List<Integer> privateAt, Types types, Map<Integer, Integer> zeroAt) {
		Map<Integer, Function> byId = new HashMap<>();
		Map<Integer, Summary> summaries = new HashMap<>();
		for (Function function : functions) {
			byId.put(function.id(), function);
			summaries.put(function.id(), Summary.unknown(function.shared()));
		}

		Map<Integer, BitSet> needs = new HashMap<>();
		for (int round = 0; round < MOST_ROUNDS; round++) {
			boolean moved = false;
			for (Function function : functions) {
				BitSet read = new BitSet(function.tracked());
				Summary summary = walk(words, function, byId, summaries, read);
				needs.put(function.id(), read);
				if (!summary.equals(summaries.get(function.id()))) {
					summaries.put(function.id(), summary);
					moved = true;
				}
			}

			if (!moved) {
				break;
			}
		}

		for (Function function : functions) {
			BitSet read = needs.get(function.id());
			boolean root = entryPoints.contains(function.id());
			for (int index = read.nextSetBit(0); index >= 0; index = read.nextSetBit(index + 1)) {
				int position;
				if (index >= function.shared()) {
					position = function.localAt().get(index - function.shared());
				} else if (root && index >= function.parameters()) {
					position = privateAt.get(index - function.parameters());
				} else {
					continue;
				}

				zeroAt.put(position, types.zeroablePointee(words[position + 1]));
			}
		}
	}

	/**
	 * The must-analysis over one function: what is definitely stored on every path into each
	 * block, the entry starting empty and every other block full, the sets only ever shrinking.
	 * Marks in {@code read} every tracked pointer some block reads while it is not yet stored, and
	 * returns what the function's parameters and the globals look like from a call site.
	 */
	private static Summary walk(int[] words, Function function, Map<Integer, Function> byId,
			Map<Integer, Summary> summaries, BitSet read) {
		int tracked = function.tracked();
		List<Block> blocks = function.blocks();
		if (blocks.isEmpty()) {
			return Summary.unknown(function.shared());
		}

		List<List<Integer>> predecessors = new ArrayList<>();
		for (int index = 0; index < blocks.size(); index++) {
			predecessors.add(new ArrayList<>());
		}

		for (int index = 0; index < blocks.size(); index++) {
			for (int label : blocks.get(index).successors()) {
				Integer target = function.labelToBlock().get(label);
				if (target != null) {
					predecessors.get(target).add(index);
				}
			}
		}

		BitSet[] in = new BitSet[blocks.size()];
		BitSet[] out = new BitSet[blocks.size()];
		for (int index = 0; index < blocks.size(); index++) {
			in[index] = new BitSet(tracked);
			if (index > 0) {
				in[index].set(0, tracked);
			}

			out[index] = transfer(words, function, blocks.get(index), in[index], byId, summaries,
					null);
		}

		boolean moved = true;
		while (moved) {
			moved = false;
			for (int index = 1; index < blocks.size(); index++) {
				List<Integer> preds = predecessors.get(index);
				if (preds.isEmpty()) {
					continue;
				}

				BitSet meet = new BitSet(tracked);
				meet.set(0, tracked);
				for (int pred : preds) {
					meet.and(out[pred]);
				}

				if (!meet.equals(in[index])) {
					in[index] = meet;
					out[index] = transfer(words, function, blocks.get(index), meet, byId, summaries,
							null);
					moved = true;
				}
			}
		}

		// What is defined on every path that returns: a path that kills never hands anything back,
		// so it does not weaken the meet. A function with no returning block defines everything.
		BitSet defined = new BitSet(tracked);
		defined.set(0, tracked);
		for (int index = 0; index < blocks.size(); index++) {
			transfer(words, function, blocks.get(index), in[index], byId, summaries, read);
			if (blocks.get(index).returns()) {
				defined.and(out[index]);
			}
		}

		return new Summary(read.get(0, function.shared()), defined.get(0, function.shared()));
	}

	/**
	 * Runs one block from a set of definitely stored pointers and returns what is definitely
	 * stored at its end. With {@code read} given, also marks every pointer the block reads while
	 * it is not yet stored.
	 */
	private static BitSet transfer(int[] words, Function function, Block block, BitSet in,
			Map<Integer, Function> byId, Map<Integer, Summary> summaries, BitSet read) {
		BitSet defined = (BitSet) in.clone();
		Map<Integer, Integer> index = function.index();
		Map<Integer, Integer> aliases = function.aliases();
		int at = block.start();
		while (at < block.end()) {
			int count = words[at] >>> 16;
			int opcode = words[at] & 0xFFFF;
			switch (opcode) {
				case OP_STORE -> define(words[at + 1], defined, index);
				case OP_LOAD -> use(words[at + 3], defined, index, aliases, read);
				case OP_COPY_MEMORY -> {
					use(words[at + 2], defined, index, aliases, read);
					define(words[at + 1], defined, index);
				}
				case OP_FUNCTION_CALL -> call(words, at, function, byId, summaries, defined, read);
				default -> {
					// Nothing else in what glslang emits for GLSL reads or writes a variable
					// through its pointer.
				}
			}

			at += count;
		}

		return defined;
	}

	/**
	 * A call: each argument the callee may read first is read here, each one it writes on every
	 * path is defined here, and the globals go the same way under the callee's own indices.
	 */
	private static void call(int[] words, int at, Function caller, Map<Integer, Function> byId,
			Map<Integer, Summary> summaries, BitSet defined, BitSet read) {
		int count = words[at] >>> 16;
		Function callee = byId.get(words[at + 3]);
		Summary summary = summaries.get(words[at + 3]);
		Map<Integer, Integer> index = caller.index();
		Map<Integer, Integer> aliases = caller.aliases();
		for (int argument = 0; argument + 4 < count; argument++) {
			int pointer = words[at + 4 + argument];
			if (summary == null || summary.readsFirst().get(argument)) {
				use(pointer, defined, index, aliases, read);
			}

			if (summary != null && summary.defines().get(argument)) {
				define(pointer, defined, index);
			}
		}

		if (callee == null || summary == null) {
			// A callee this module does not define reads every global and defines none.
			for (int k = caller.parameters(); k < caller.shared(); k++) {
				if (!defined.get(k) && read != null) {
					read.set(k);
				}
			}

			return;
		}

		int globals = caller.shared() - caller.parameters();
		for (int k = 0; k < globals; k++) {
			int here = caller.parameters() + k;
			int there = callee.parameters() + k;
			if (summary.readsFirst().get(there) && !defined.get(here) && read != null) {
				read.set(here);
			}

			if (summary.defines().get(there)) {
				defined.set(here);
			}
		}
	}

	/** A store through the variable's own pointer defines it whole; one through a chain does not. */
	private static void define(int pointer, BitSet defined, Map<Integer, Integer> index) {
		Integer whole = index.get(pointer);
		if (whole != null) {
			defined.set(whole);
		}
	}

	/** A read of a pointer: the tracked variable behind it, if not yet stored, needs its zero. */
	private static void use(int pointer, BitSet defined, Map<Integer, Integer> index,
			Map<Integer, Integer> aliases, BitSet read) {
		Integer base = base(pointer, index, aliases);
		if (base != null && !defined.get(base) && read != null) {
			read.set(base);
		}
	}

	/** The index of the tracked pointer an id names, directly or through an alias. */
	private static Integer base(int pointer, Map<Integer, Integer> index,
			Map<Integer, Integer> aliases) {
		Integer direct = index.get(pointer);

		return direct != null ? direct : aliases.get(pointer);
	}

	private static boolean terminator(int opcode) {
		return opcode == OP_BRANCH || opcode == OP_BRANCH_CONDITIONAL || opcode == OP_SWITCH
				|| opcode == OP_KILL || opcode == OP_RETURN || opcode == OP_RETURN_VALUE
				|| opcode == OP_UNREACHABLE || opcode == OP_TERMINATE_INVOCATION;
	}

	/** The labels a terminator can go to. A switch's literals are read as single words. */
	private static List<Integer> successors(int[] words, int at) {
		int count = words[at] >>> 16;
		int opcode = words[at] & 0xFFFF;
		List<Integer> targets = new ArrayList<>();
		if (opcode == OP_BRANCH) {
			targets.add(words[at + 1]);
		} else if (opcode == OP_BRANCH_CONDITIONAL) {
			targets.add(words[at + 2]);
			targets.add(words[at + 3]);
		} else if (opcode == OP_SWITCH) {
			targets.add(words[at + 2]);
			for (int label = at + 4; label < at + count; label += 2) {
				targets.add(words[label]);
			}
		}

		return targets;
	}
}
