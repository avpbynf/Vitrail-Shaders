package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Holds the translation cache's line feed to the bytes it fed before it encoded each line once:
 * every key a player's store holds was made by the old feed, and one byte of difference would
 * orphan all of them without a word.
 * <p>
 * The old feed is kept here verbatim as the reference, the character walk that counted the bytes
 * included, and the digest of the two is compared whole, length prefix and all. The inputs are the
 * ones where a count and an encoder can part: the edges of each UTF-8 width, a pair, a lone half of
 * one either way round, a half at the end of one line and its other half at the head of the next,
 * empty lines and no lines at all, and a seeded run over an alphabet of all of those.
 */
class TranslationCacheFeedLinesTest {

	/** Every width of UTF-8 at its edges, both halves of a pair, a letter, a tab and a break. */
	private static final char[] ALPHABET = {
			'a', 'Z', '\t', '\n', '\u007f', '\u0080', 'é', '߿', 'ࠀ', '中', '￿',
			'\uD83D', '\uDE00', '\uDBFF', '\uDC00',
	};

	@Test
	void feedsTheSameBytesAsTheFeedThatCountedByWalkingTheCharacters() throws Exception {
		List<List<String>> cases = new ArrayList<>();
		cases.add(List.of());
		cases.add(List.of(""));
		cases.add(List.of("", "", ""));
		cases.add(List.of("#version 460", "void main() {", "\tgl_FragData[0] = vec4(1.0);", "}"));
		cases.add(List.of("\u007f\u0080", "߿ࠀ", "￿"));
		cases.add(List.of("// café 中文", "vec3 x = vec3(0.0); // 😀"));
		cases.add(List.of("\uD83D", "\uDE00", "\uDE00\uD83D", "\uD83D😀", "a\uD83D"));
		cases.add(List.of("ends on a high half \uD83D", "\uDE00 opens on a low half"));
		cases.add(List.of("", "\uD83D", "", "\uDE00", ""));
		cases.add(List.of("a line\nwith a break inside", "xé中😀y\n"));
		cases.add(List.of("uniform sampler2D colortex0;".repeat(4_000)));

		Random random = new Random(0xFEED);
		for (int round = 0; round < 20_000; round++) {
			List<String> lines = new ArrayList<>();
			for (int line = random.nextInt(8); line > 0; line--) {
				char[] chars = new char[random.nextInt(12)];
				for (int at = 0; at < chars.length; at++) {
					chars[at] = ALPHABET[random.nextInt(ALPHABET.length)];
				}

				lines.add(new String(chars));
			}

			cases.add(lines);
		}

		Method feedLines = TranslationCache.class.getDeclaredMethod("feedLines", MessageDigest.class,
				List.class);
		feedLines.setAccessible(true);

		for (List<String> lines : cases) {
			MessageDigest now = sha256();
			feedLines.invoke(null, now, lines);
			MessageDigest before = sha256();
			feedLinesAsItWas(before, lines);

			assertArrayEquals(before.digest(), now.digest(), () -> "lines " + escaped(lines));
		}
	}

	/** The feed as it stood before each line was encoded once, kept as it was written. */
	private static void feedLinesAsItWas(MessageDigest digest, List<String> lines) {
		int length = Math.max(0, lines.size() - 1);
		for (String line : lines) {
			length += utf8Length(line);
		}

		digest.update(intBytes(length));
		for (int at = 0; at < lines.size(); at++) {
			if (at > 0) {
				digest.update((byte) '\n');
			}

			digest.update(lines.get(at).getBytes(StandardCharsets.UTF_8));
		}
	}

	/** How many bytes {@code getBytes(UTF_8)} yields, a lone surrogate counting as its replacement. */
	private static int utf8Length(String text) {
		int length = 0;
		for (int at = 0; at < text.length(); at++) {
			char c = text.charAt(at);
			if (c < 0x80) {
				length += 1;
			} else if (c < 0x800) {
				length += 2;
			} else if (Character.isHighSurrogate(c) && at + 1 < text.length()
					&& Character.isLowSurrogate(text.charAt(at + 1))) {
				length += 4;
				at++;
			} else if (Character.isSurrogate(c)) {
				length += 1;
			} else {
				length += 3;
			}
		}

		return length;
	}

	private static byte[] intBytes(int value) {
		return new byte[] {
				(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value,
		};
	}

	private static MessageDigest sha256() throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256");
	}

	private static String escaped(List<String> lines) {
		StringBuilder out = new StringBuilder("[");
		for (String line : lines) {
			if (out.length() > 1) {
				out.append(", ");
			}

			out.append('"');
			line.chars().forEach(c -> out.append(c < 0x20 || c > 0x7e
					? String.format(Locale.ROOT, "\\u%04x", c) : String.valueOf((char) c)));
			out.append('"');
		}

		return out.append(']').toString();
	}
}
