package dev.vitrail.glsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moves the shared variables of a compute stage into a storage buffer, for a Metal kernel that
 * could not hold them in threadgroup memory.
 * <p>
 * <strong>What goes wrong without it.</strong> SPIRV-Cross declares a GLSL {@code shared} variable,
 * workgroup storage in SPIR-V, as {@code threadgroup} memory of the Metal kernel, and Metal refuses a
 * kernel holding more than 32768 bytes of it as the pipeline is built:
 * {@code Threadgroup memory size (36864) exceeds the maximum threadgroup memory allowed (32768)}.
 * That is Photon's sky light, {@code shared vec3 shared_memory[256][9]} at sixteen bytes a
 * {@code float3}, and without it everything the sky lights renders dark. Vulkan reports no such
 * limit, so the figure is the one Metal's refusal names and not an answer asked of the device.
 * <p>
 * <strong>Why a buffer is the same memory, and when it is not.</strong> A shared variable is one
 * copy for the invocations of one local work group, and a buffer is one copy for every invocation of
 * the dispatch. The two agree when the dispatch is a single work group, which the caller asks before
 * using the text. A shared variable starts undefined, and what the last dispatch left in the buffer
 * is one reading of that.
 * <p>
 * <strong>The barriers have to name the buffer.</strong> SPIRV-Cross writes {@code barrier()} as
 * {@code threadgroup_barrier(mem_flags::mem_threadgroup)} whatever memory the stage touches, and that
 * flag orders writes to threadgroup memory and to nothing else, so a reduction reading what the other
 * invocations wrote into a buffer would race. The moved text therefore puts
 * {@code memoryBarrierBuffer()} in front of every {@code barrier()}, which SPIRV-Cross writes as
 * {@code mem_device} below MSL 3.2 and as an {@code atomic_thread_fence} over device memory from
 * 3.2, and declares the block {@code coherent}, which 3.2 receives as {@code coherent device}, the
 * qualifier that fence is written against.
 * <p>
 * Off until {@code VulkanBackendMixin} says the driver is MoltenVK, which is what the harness gets.
 */
public final class SharedMemory {

	/** What a Metal kernel may hold in threadgroup memory, as its refusal names it. */
	public static final long THREADGROUP_BYTES = 32768L;

	/** The block the moved variables are declared in, and the name the dispatch binds a buffer to. */
	public static final String BLOCK = "OfSharedMemory";

	/**
	 * Written after the version line, so that every barrier of the stage names the buffer. One
	 * expression and not two statements: {@code if (x) barrier();} would otherwise fence the buffer
	 * in the branch and wait for every thread outside it.
	 */
	private static final String BARRIER = "#define barrier() (memoryBarrierBuffer(), barrier())";

	private static final Pattern WORD = Pattern.compile("\\bshared\\b");

	private static final Pattern TOKEN = Pattern.compile("[A-Za-z_]\\w*|\\d+[uU]?|\\S");

	private static final Pattern NAME = Pattern.compile("[A-Za-z_]\\w*");

	private static final Pattern COUNT = Pattern.compile("(\\d+)[uU]?");

	private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b.*$");

	/** What a shared declaration may carry beside the word, none of which changes its layout. */
	private static final Set<String> QUALIFIERS = Set.of("shared", "highp", "mediump", "lowp", "precise");

	private static final Map<String, Layout> TYPES = types();

	private static volatile boolean moltenVk;

	private SharedMemory() {
	}

	/**
	 * What was read of a stage's shared declarations.
	 *
	 * @param threadgroupBytes what they take as Metal threadgroup memory, laid out as SPIRV-Cross
	 *                         lays them out, or -1 when one of them could not be sized
	 * @param bufferBytes      what the block holding them takes under std430
	 * @param moved            the stage with them moved into that block, or null when they fit
	 * @param unread           the declaration that could not be sized, or null
	 */
	public record Reading(long threadgroupBytes, long bufferBytes, String moved, String unread) {

		/** Whether Metal refuses that much threadgroup memory. */
		public boolean over() {
			return this.threadgroupBytes > THREADGROUP_BYTES;
		}
	}

	/** Set once by {@code VulkanBackendMixin}, at device creation. */
	public static void serve(boolean moltenVkDriver) {
		moltenVk = moltenVkDriver;
	}

	/** Whether the driver is MoltenVK, the one driver whose threadgroup memory is capped. */
	public static boolean moltenVk() {
		return moltenVk;
	}

	/** Whether the text says {@code shared} at all, asked before paying for a preprocessing. */
	public static boolean mentioned(String text) {
		return WORD.matcher(text).find();
	}

	/**
	 * Sizes the shared declarations of a preprocessed compute stage and, past what Metal allows,
	 * moves them into one buffer block.
	 * <p>
	 * Preprocessed because the text a translation hands out still carries every {@code #if} of the
	 * pack and its macros: a declaration in a dead branch would be counted, and an array sized by a
	 * setting could not be. Only what shaderc would compile is left here. Only declarations of
	 * scalars, vectors and matrices of floats and integers, and arrays of them counted in literals,
	 * are sized; anything else is named unread and the stage is left as it stands.
	 */
	public static Reading read(String preprocessed) {
		String scanned = withoutDirectives(preprocessed);
		List<int[]> spans = new ArrayList<>();
		StringBuilder members = new StringBuilder();
		long threadgroup = 0L;
		long offset = 0L;
		long widest = 4L;
		int depth = 0;
		int start = 0;
		for (int i = 0; i < scanned.length(); i++) {
			char c = scanned.charAt(i);
			if (c == '{') {
				depth++;
				continue;
			}

			if (c == '}') {
				depth = Math.max(0, depth - 1);
				if (depth == 0) {
					start = i + 1;
				}

				continue;
			}

			if (c != ';' || depth != 0) {
				continue;
			}

			String statement = scanned.substring(start, i);
			int from = start + (statement.length() - statement.stripLeading().length());
			start = i + 1;
			List<String> tokens = tokens(statement);
			if (!declaresShared(tokens)) {
				continue;
			}

			Declaration declaration = declaration(tokens);
			// A directive inside the statement would be lost with the span it sits in.
			if (declaration == null || preprocessed.substring(from, i).indexOf('#') >= 0) {
				return new Reading(-1L, 0L, null, statement.strip());
			}

			Layout layout = declaration.layout();
			for (long count : declaration.counts()) {
				long metal = layout.stride() * Math.max(1L, count);
				threadgroup += metal;
				offset = aligned(offset, layout.align()) + (count == 0L ? layout.size() : metal);
				widest = Math.max(widest, layout.align());
			}

			members.append(declaration.member()).append(' ');
			spans.add(new int[] {from, i + 1});
		}

		long bytes = aligned(offset, widest);
		if (threadgroup <= THREADGROUP_BYTES) {
			return new Reading(threadgroup, bytes, null, null);
		}

		return new Reading(threadgroup, bytes, moved(preprocessed, spans, members.toString()), null);
	}

	/** The first declaration becomes the block, the others blanks, and the barrier is redefined. */
	private static String moved(String preprocessed, List<int[]> spans, String members) {
		StringBuilder text = new StringBuilder(preprocessed.length() + 256);
		int copied = 0;
		for (int k = 0; k < spans.size(); k++) {
			int[] span = spans.get(k);
			text.append(preprocessed, copied, span[0]);
			text.append(k == 0
					? "layout(std430) coherent buffer " + BLOCK + " { " + members + "};"
					: blank(preprocessed.substring(span[0], span[1])));
			copied = span[1];
		}

		text.append(preprocessed, copied, preprocessed.length());
		Matcher version = VERSION.matcher(text);
		if (version.find()) {
			text.insert(version.end(), "\n" + BARRIER);
		} else {
			text.insert(0, BARRIER + "\n");
		}

		return text.toString();
	}

	private record Layout(long size, long align, long stride) {
	}

	/** @param counts each declarator's element count, or 0 for one that is not an array */
	private record Declaration(String member, Layout layout, List<Long> counts) {
	}

	/**
	 * The std430 size and alignment of each type, and the stride of an array of it, which is also
	 * what Metal gives one of them: a {@code float3} takes sixteen bytes there as a {@code vec3}
	 * element does here, and a matrix is its columns.
	 */
	private static Map<String, Layout> types() {
		Map<String, Layout> table = new HashMap<>();
		for (String scalar : List.of("float", "int", "uint")) {
			table.put(scalar, new Layout(4L, 4L, 4L));
		}

		for (int n = 2; n <= 4; n++) {
			long align = n == 2 ? 8L : 16L;
			for (String prefix : List.of("vec", "ivec", "uvec")) {
				table.put(prefix + n, new Layout(4L * n, align, align));
			}

			for (int rows = 2; rows <= 4; rows++) {
				long column = rows == 2 ? 8L : 16L;
				Layout matrix = new Layout(n * column, column, n * column);
				table.put("mat" + n + "x" + rows, matrix);
				if (rows == n) {
					table.put("mat" + n, matrix);
				}
			}
		}

		return Map.copyOf(table);
	}

	private static boolean declaresShared(List<String> tokens) {
		int parentheses = 0;
		for (String token : tokens) {
			if ("(".equals(token)) {
				parentheses++;
			} else if (")".equals(token)) {
				parentheses--;
			} else if (parentheses == 0 && "shared".equals(token)) {
				return true;
			}
		}

		return false;
	}

	/** One declaration read, or null for anything other than a sizable type and literal counts. */
	private static Declaration declaration(List<String> tokens) {
		int at = 0;
		while (at < tokens.size() && QUALIFIERS.contains(tokens.get(at))) {
			at++;
		}

		Layout layout = at < tokens.size() ? TYPES.get(tokens.get(at)) : null;
		if (layout == null) {
			return null;
		}

		at++;
		List<Long> counts = new ArrayList<>();
		try {
			long typeCount = 0L;
			while (at < tokens.size() && "[".equals(tokens.get(at))) {
				long[] dimension = dimension(tokens, at);
				if (dimension == null) {
					return null;
				}

				typeCount = Math.multiplyExact(Math.max(1L, typeCount), dimension[0]);
				at = (int) dimension[1];
			}

			while (true) {
				if (at >= tokens.size() || !NAME.matcher(tokens.get(at)).matches()
						|| TYPES.containsKey(tokens.get(at)) || QUALIFIERS.contains(tokens.get(at))) {
					return null;
				}

				at++;
				long count = typeCount;
				while (at < tokens.size() && "[".equals(tokens.get(at))) {
					long[] dimension = dimension(tokens, at);
					if (dimension == null) {
						return null;
					}

					count = Math.multiplyExact(Math.max(1L, count), dimension[0]);
					at = (int) dimension[1];
				}

				counts.add(count);
				if (at == tokens.size()) {
					break;
				}

				if (!",".equals(tokens.get(at))) {
					return null;
				}

				at++;
			}
		} catch (ArithmeticException wide) {
			return null;
		}

		List<String> member = new ArrayList<>();
		for (String token : tokens) {
			if (!"shared".equals(token)) {
				member.add(token);
			}
		}

		return new Declaration(String.join(" ", member) + ";", layout, List.copyOf(counts));
	}

	/**
	 * The count of the bracket opening at that token and the token past its close, or null for
	 * anything but a literal or a product of literals. I Like Vanilla writes its floodfill cache as
	 * {@code [10 * 10 * 10]}, which the preprocessor leaves as it is.
	 */
	private static long[] dimension(List<String> tokens, int at) {
		long product = 1L;
		int next = at + 1;
		while (next < tokens.size()) {
			Matcher count = COUNT.matcher(tokens.get(next));
			if (!count.matches()) {
				return null;
			}

			try {
				product = Math.multiplyExact(product, Long.parseLong(count.group(1)));
			} catch (NumberFormatException wide) {
				return null;
			}

			next++;
			if (next < tokens.size() && "]".equals(tokens.get(next))) {
				return product > 0L ? new long[] {product, next + 1L} : null;
			}

			if (next >= tokens.size() || !"*".equals(tokens.get(next))) {
				return null;
			}

			next++;
		}

		return null;
	}

	private static List<String> tokens(String statement) {
		List<String> tokens = new ArrayList<>();
		Matcher matcher = TOKEN.matcher(statement);
		while (matcher.find()) {
			tokens.add(matcher.group());
		}

		return tokens;
	}

	/** The text with each directive line blanked, lengths and line breaks kept. */
	private static String withoutDirectives(String text) {
		StringBuilder scanned = new StringBuilder(text.length());
		for (String line : text.split("\n", -1)) {
			if (scanned.length() > 0) {
				scanned.append('\n');
			}

			scanned.append(line.stripLeading().startsWith("#") ? " ".repeat(line.length()) : line);
		}

		return scanned.toString();
	}

	private static String blank(String text) {
		StringBuilder blank = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			blank.append(text.charAt(i) == '\n' ? '\n' : ' ');
		}

		return blank.toString();
	}

	private static long aligned(long offset, long align) {
		return (offset + align - 1L) / align * align;
	}
}
