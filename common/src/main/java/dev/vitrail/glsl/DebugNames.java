package dev.vitrail.glsl;

import java.util.BitSet;

/**
 * Drops the debug names a module carries for everything that is not a resource, an input or an
 * output, because on Apple hardware those names reach a C++ compiler that has its own meaning for
 * some of them.
 * <p>
 * <strong>What it costs a pack to keep them.</strong> MoltenVK does not hand SPIR-V to Metal: it
 * runs its own SPIRV-Cross and renders MSL, which is C++, and it takes the identifiers of that MSL
 * from the module's {@code OpName} strings. A pack's own names then land in Metal's namespace, and
 * Apple's compiler refuses the pipeline outright where one of them collides. Measured on an M4,
 * macOS Tahoe 26.6.1, MoltenVK 1.4.2, over the nine packs swept there: Photon dies on
 * {@code call to 'length_squared' is ambiguous}, its own helper against the one in Metal's standard
 * library, and again on {@code invalid parameter name: 'new' is a keyword}; Bliss dies on
 * {@code reference to 'bias' is ambiguous}, {@code metal::bias} being a type over there; BSL dies
 * on {@code expected unqualified-id}. Three packs of the nine carried a colliding name, the same
 * three carried it on the published 0.10.0-beta, so this has been shipping. Only BSL comes back
 * on the names alone: Bliss and Photon carry a second refusal underneath, unrelated to any name,
 * so they get past this pass and stop later.
 * <p>
 * <strong>Why dropping rather than renaming.</strong> A rename needs the list of what Metal
 * reserves, which is a C++ keyword list plus a standard library that grows with every macOS, so a
 * list is wrong the day after it is written. Dropping a name cannot collide with anything: the
 * driver's SPIRV-Cross mints {@code _123} for whatever has none, and no compiler has a meaning for
 * that. What is dropped is debug information, referenced by no instruction of the module.
 * <p>
 * <strong>What is KEPT, and why exactly this set.</strong> The names the game reads back are kept:
 * every variable in a storage class the reflection walks, and the type behind each of them, arrays
 * unwrapped, so that a uniform block's own name and its members' names survive. That type matters
 * as much as the variable: SPIRV-Cross reports an empty name where the {@code OpName} sits on the
 * block type rather than on the instance, which is the shape Complementary's
 * {@code buffer blockDataBuffer { } blockDataSSBO} has, and the engine reads the type's name back
 * for it ({@code render/ComputeShader.java:260-279}, the third fallback at the end of that
 * method). A resource whose name went would come back empty from both, and {@code rebind} then
 * throws rather than binding anything.
 * <p>
 * Everything else goes: functions, their parameters, module-scope globals a pack declares outside
 * a block, locals, shared variables of a compute, and the plain structs a pack passes between its
 * own functions. Not one of those is ever asked for by name from outside the module.
 * <p>
 * <strong>What it therefore does NOT cover.</strong> A pack that gives one of its UNIFORMS, its
 * samplers or its stage interface a name Metal owns keeps that collision, the name being one the
 * outside asks for. Nor does the kept set reach INSIDE a block: a struct a pack declares and then
 * uses as a member of a kept block loses its own member names, only the block's own members being
 * kept, and that is deliberate. What the reflection reads back of a block is the block and its
 * members; the fields of a struct standing in one of those members are read by the module alone. What says the corpus has none of those is the sweep itself and not a reading:
 * with this pass in, no pack of the nine is refused for a name any more, and the three that still
 * fail, Bliss, Reverie and Photon, fail on Metal's sampler slots. That one is a NUMBERING and not
 * a count: the refused stage names thirteen samplers while their binding numbers reach
 * twenty-three, and Metal has sixteen slots. A rename here would be a second subject, and a
 * harder one: it has to be carried through the binding side too, which keys on those very names.
 * <p>
 * <strong>It does not run when debug information was asked for.</strong> Somebody who set
 * {@code -Dvitrail.shaderDebugInfo=true} wants to read the module, and a module full of
 * {@code _123} is not readable; that state is already in the module cache's key, so the two never
 * serve each other's blobs.
 * <p>
 * The pass is a pure function of the words, like {@link LocalZeroes}, and the buffer dance lives at
 * the call site.
 */
public final class DebugNames {

	/**
	 * What the module cache hashes into its key for this pass: a change to what it drops changes
	 * the bytes handed to the driver, and a blob built by the old rule must never be served under
	 * the new one.
	 */
	public static final String VERSION = "names-1";

	private static final int HEADER_WORDS = 5;

	private static final int OP_NAME = 5;
	private static final int OP_MEMBER_NAME = 6;
	private static final int OP_TYPE_ARRAY = 28;
	private static final int OP_TYPE_RUNTIME_ARRAY = 29;
	private static final int OP_TYPE_POINTER = 32;
	private static final int OP_VARIABLE = 59;

	/**
	 * The storage classes whose names are read from outside the module: uniforms, samplers and the
	 * storage images a pack declares, all three {@code UniformConstant} or {@code Uniform} in what
	 * glslang emits, the storage blocks ({@code StorageBuffer}), the push constants, and the stage
	 * interface ({@code Input}, {@code Output}). {@code Image} is in the list for the sake of a
	 * module that uses that class rather than because glslang writes one.
	 * {@code Private}, {@code Function} and {@code Workgroup} are
	 * deliberately absent: a pack's globals, its locals and a compute's shared variables are
	 * nobody's business but the module's.
	 */
	private static final int[] KEPT_CLASSES = {0, 1, 2, 3, 9, 11, 12};

	private DebugNames() {
	}

	/**
	 * What one pass did to one module.
	 *
	 * @param words   the module, the same array when nothing was dropped
	 * @param dropped how many name instructions went
	 */
	public record Result(int[] words, int dropped) {

		public boolean changed() {
			return this.dropped > 0;
		}
	}

	/**
	 * Walks the module three times: once to find the ids whose names are read from outside, once to
	 * count what would go, and once to rebuild it without them. The first two are needed before
	 * the third because the debug section, where the names sit, comes BEFORE the types and
	 * variables it names, and because a module with nothing to drop must come back on its own array
	 * rather than on a copy of itself: this runs on every module the engine compiles.
	 * <p>
	 * A module that cannot be walked as SPIR-V comes back untouched, and it is the driver's place
	 * to refuse it. Past {@link LocalZeroes#readable}, an instruction that declares four words has
	 * four words, so the operand reads below need no bound of their own.
	 */
	public static Result strip(int[] words) {
		if (!LocalZeroes.readable(words)) {
			return new Result(words, 0);
		}

		BitSet kept = keptIds(words);
		int dropped = 0;
		int keptWords = HEADER_WORDS;
		for (int at = HEADER_WORDS; at < words.length; ) {
			int length = words[at] >>> 16;
			if (drops(words, at, kept)) {
				dropped++;
			} else {
				keptWords += length;
			}
			at += length;
		}

		if (dropped == 0) {
			return new Result(words, 0);
		}

		int[] out = new int[keptWords];
		System.arraycopy(words, 0, out, 0, HEADER_WORDS);
		int write = HEADER_WORDS;
		for (int at = HEADER_WORDS; at < words.length; ) {
			int length = words[at] >>> 16;
			if (!drops(words, at, kept)) {
				System.arraycopy(words, at, out, write, length);
				write += length;
			}
			at += length;
		}

		return new Result(out, dropped);
	}

	/** Whether the instruction standing here is a name of something the outside never asks for. */
	private static boolean drops(int[] words, int at, BitSet kept) {
		int opcode = words[at] & 0xFFFF;
		int length = words[at] >>> 16;
		// A name is three words at least, the opcode, its target and one word of string. Shorter
		// than that it is not one, whatever the opcode says, and it is kept: this pass drops what
		// it has understood and never what it has not.
		if (length < 3 || (opcode != OP_NAME && opcode != OP_MEMBER_NAME)) {
			return false;
		}

		int target = words[at + 1];

		return target >= 0 && !kept.get(target);
	}

	/**
	 * The ids whose names survive: each variable of a kept storage class, and the type it points
	 * at with its arrays unwrapped, which is where a block's member names hang.
	 */
	private static BitSet keptIds(int[] words) {
		BitSet kept = new BitSet();
		// Bound is the header's fourth word, so every id a well formed module uses fits under it.
		// Clamped at BOTH ends rather than trusted. Every id costs at least the two words of the
		// instruction that mints it, so a bound above the module's own length is not a bound, and
		// sizing the tables from it would allocate whatever a broken header happened to ask for. A
		// bound a module UNDERSTATES is left alone, and what that costs is written down rather than
		// guarded: an id past the tables is not followed, so the type name of a block declared past
		// the bound would go, and the module saying so is the broken one.
		int[] pointee = new int[Math.min(Math.max(words[3], 1), words.length)];
		int[] element = new int[pointee.length];
		for (int at = HEADER_WORDS; at < words.length; ) {
			int length = words[at] >>> 16;
			int opcode = words[at] & 0xFFFF;
			switch (opcode) {
				case OP_TYPE_POINTER -> {
					if (length >= 4 && inRange(words[at + 1], pointee)) {
						pointee[words[at + 1]] = words[at + 3];
					}
				}
				case OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY -> {
					if (length >= 3 && inRange(words[at + 1], element)) {
						element[words[at + 1]] = words[at + 2];
					}
				}
				case OP_VARIABLE -> {
					if (length >= 4 && isKept(words[at + 3])) {
						int id = words[at + 2];
						if (id >= 0) {
							kept.set(id);
						}
						int type = inRange(words[at + 1], pointee) ? pointee[words[at + 1]] : 0;
						// An array of arrays of a block is the deepest thing GLSL declares, so two
						// unwraps answer every real module. The loop allows eight and stops there
						// rather than trusting a module not to point an array at itself.
						for (int step = 0; step < 8 && type > 0; step++) {
							kept.set(type);
							int inner = inRange(type, element) ? element[type] : 0;
							if (inner <= 0) {
								break;
							}
							type = inner;
						}
					}
				}
				default -> {
				}
			}
			at += length;
		}

		return kept;
	}

	private static boolean inRange(int id, int[] table) {
		return id > 0 && id < table.length;
	}

	private static boolean isKept(int storageClass) {
		for (int kept : KEPT_CLASSES) {
			if (kept == storageClass) {
				return true;
			}
		}

		return false;
	}
}
