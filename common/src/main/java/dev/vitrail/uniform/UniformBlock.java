package dev.vitrail.uniform;

import dev.vitrail.glsl.TranslatedUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * One program's uniform block, walked in the order the translation fixed.
 * <p>
 * The order is the layout, which is why it is carried out of the translation rather than worked
 * out again here. Members are resolved once at construction into a list of what has to be written,
 * and both the size and the write walk that same list through the same code, so a member cannot be
 * measured one way and written another.
 * <p>
 * A name nothing supplies is written as zeroes rather than skipped. Skipping would shift every
 * member after it and quietly corrupt the ones that <em>are</em> supplied; writing zeroes keeps the
 * layout and loses only that value. Those names are collected so the gap can be said out loud
 * instead of being discovered as a wrong image.
 * <p>
 * Arrays are the trap, and twice over. {@code gl_TextureMatrix} is declared
 * {@code mat4 of_TextureMatrix[8]}, five hundred and twelve bytes, and the arity lives only in the
 * declaration text: the type is {@code mat4} either way. Read it as a single matrix and everything
 * after it, including every uniform the pack declared for itself, lands four hundred and forty eight
 * bytes early. A hundred and ninety four files of the corpus read that name. The second half of the
 * trap is quieter: the source is asked per ELEMENT and not per member, because that same name holds
 * eight different matrices and one value written eight times is a wrong number rather than a wrong
 * layout.
 */
public final class UniformBlock {

	private final List<Member> members;
	private final List<String> unanswered;
	private final int size;

	/** Held rather than allocated per member: a block has up to two hundred of them, every frame. */
	private final Val carrier = new Val();

	/**
	 * @param array whether the declaration carried brackets at all, which is not the same question as
	 *              whether {@code elements} is one: an array of a single element still pays the
	 *              sixteen byte stride, and a bare member does not
	 */
	private record Member(String name, UniformShape shape, int elements, boolean array,
			UniformSource source) {
	}

	public UniformBlock(List<TranslatedUnit.Uniform> members, UniformCatalog catalog) {
		List<Member> resolved = new ArrayList<>(members.size());
		List<String> missing = new ArrayList<>();

		for (TranslatedUnit.Uniform member : members) {
			UniformShape shape = UniformShape.of(member.type());
			if (shape == null) {
				throw new IllegalStateException(
						"Cannot size " + member.declaration() + ": nothing here knows the type " + member.type());
			}

			int declared = arrayLength(member.declaration());
			if (declared < 0) {
				throw new IllegalStateException(
						"Cannot size " + member.declaration() + ": the array length is not a literal");
			}

			boolean array = declared > 0;

			UniformSource source = catalog.source(member.name());
			// The fog struct is the one shape that cannot be coerced from anything else, so a
			// source that does not hold one answers this name no better than nothing does. Settled
			// here, once, rather than discovered per frame.
			if (source != null && (shape == UniformShape.FOG) != (catalog.natural(member.name()) == UniformShape.FOG)) {
				source = null;
			}

			if (source == null) {
				missing.add(member.name());
			}

			resolved.add(new Member(member.name(), shape, array ? declared : 1, array, source));
		}

		this.members = List.copyOf(resolved);
		this.unanswered = List.copyOf(missing);
		this.size = walk(new Std140Counter(), null).size();
	}

	public int size() {
		return this.size;
	}

	/** Names the block declares that nothing answers, in declaration order. */
	public List<String> unanswered() {
		return this.unanswered;
	}

	public void write(UniformSink sink, WorldState world) {
		walk(sink, world);
	}

	/**
	 * The one walk. A null world means write zeroes throughout, which is how the block is measured:
	 * sizing by a second pass over the members is exactly how the two halves come to disagree.
	 */
	private <T extends UniformSink> T walk(T sink, WorldState world) {
		for (Member member : this.members) {
			boolean supplied = member.source() != null && world != null;

			sink.member(member.name(), member.elements(), supplied);

			for (int element = 0; element < member.elements(); element++) {
				// Every element of an array starts on a sixteen byte boundary in std140, which for
				// anything smaller than a vec4 is not what putting them back to back gives. It is the
				// FIRST element that carries the rule, so a one element array pays it too.
				if (member.array()) {
					sink.align(16);
				}

				if (supplied) {
					// Asked once per element and not once per member. Almost every source answers
					// them all alike and pays a call it did not need; the one that does not is
					// gl_TextureMatrix, whose unit one is the light map's and is not the identity the
					// others are. Reading the member once wrote that one value eight times.
					member.source().read(world, this.carrier, element);
					UniformCoercion.write(sink, member.shape(), this.carrier);
				} else {
					member.shape().zero(sink);
				}
			}

			// And once more AFTER the last element, because an array's size is its stride times its
			// length rather than where its last element happens to stop. Without this a vec3[2]
			// would end at twenty eight and the member behind it would start there; the compiler
			// puts that member at thirty two. Only the padding after the last element is missing
			// from the loop above, since every other element is aligned on its way in.
			if (member.array()) {
				sink.align(16);
			}
		}

		return sink;
	}

	/**
	 * How many elements a declaration asks for: 0 when it is not an array at all, -1 when a length is
	 * not a literal, and the product of every dimension otherwise.
	 * <p>
	 * Every group of brackets counts. GLSL writes {@code float x[2][3]} for six floats, and reading
	 * only the first group writes two of them and leaves everything behind them in the block four
	 * elements early. Nought and one are told apart for the other half of the same rule: an array of
	 * one element still has the sixteen byte stride an array element has, so {@code float x[1]} is
	 * sixteen bytes where {@code float x} is four.
	 */
	static int arrayLength(String declaration) {
		int open = declaration.indexOf('[');
		if (open < 0) {
			return 0;
		}

		int elements = 1;
		while (open >= 0) {
			int close = declaration.indexOf(']', open);
			if (close < 0) {
				return -1;
			}

			int length;
			try {
				length = Integer.parseInt(declaration.substring(open + 1, close).trim());
			} catch (NumberFormatException e) {
				return -1;
			}

			// An unsized or empty dimension is refused rather than taken for one element: a block
			// measured a member short is every member after it landing where nothing reads it.
			if (length < 1) {
				return -1;
			}

			elements *= length;
			open = declaration.indexOf('[', close + 1);
		}

		return elements;
	}
}
