package dev.vitrail.pack.target;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The names a colour target answers to, and the index behind them. Also, {@link #bareName}: not
 * a target name at all, kept here because it is never called apart from one.
 * <p>
 * The eight names from before the format was numbered are not folklore. Five of the eight packs
 * sample a target through one of them, and not in a corner: Mellow reads {@code gaux1} in all of
 * its hundred and twenty seven programs, Complementary reads {@code gaux2} and {@code gaux4} in
 * ninety nine. A pack mixes the two spellings freely, on the same target: BSL declares
 * {@code gaux1Format} and samples {@code colortex4} elsewhere. So the alias has to be resolved
 * where a name is first read, not patched up at the end.
 * <p>
 * One pair is worth keeping apart in the head, because the two are one letter and one target
 * away from each other: {@code gdepth} is colortex1, a colour target, while {@code gdepthtex}
 * is depthtex0 and is not a colour target at all.
 */
public final class TargetName {

	/** Iris allows this many, and the corpus reaches colortex19. Sixteen is an OptiFine habit. */
	public static final int MAX_TARGETS = 32;

	private static final String PREFIX = "colortex";

	/** By position: index 0 is {@code gcolor}, index 7 is {@code gaux4}, and nothing past that. */
	private static final List<String> LEGACY = List.of(
			"gcolor", "gdepth", "gnormal", "composite", "gaux1", "gaux2", "gaux3", "gaux4");

	private TargetName() {
	}

	/** {@code colortex7} and {@code gaux4} both answer with 7. */
	public static OptionalInt index(String name) {
		int legacy = LEGACY.indexOf(name);
		if (legacy >= 0) {
			return OptionalInt.of(legacy);
		}

		if (!name.startsWith(PREFIX)) {
			return OptionalInt.empty();
		}

		String digits = name.substring(PREFIX.length());
		if (digits.isEmpty() || digits.length() > 2 || !digits.chars().allMatch(Character::isDigit)) {
			return OptionalInt.empty();
		}

		int index = Integer.parseInt(digits);

		return index < MAX_TARGETS ? OptionalInt.of(index) : OptionalInt.empty();
	}

	/** {@code colortex7} for 7. */
	public static String canonical(int index) {
		return PREFIX + index;
	}

	/** {@code gaux4} for 7, empty from 8 up. */
	public static Optional<String> legacyAlias(int index) {
		return index >= 0 && index < LEGACY.size() ? Optional.of(LEGACY.get(index)) : Optional.empty();
	}

	/** {@code gaux2Format} splits into index 5 and suffix {@code Format}. */
	public static Optional<Suffixed> split(String directiveName) {
		if (directiveName.startsWith(PREFIX)) {
			int cursor = PREFIX.length();
			while (cursor < directiveName.length() && Character.isDigit(directiveName.charAt(cursor))) {
				cursor++;
			}

			OptionalInt index = index(directiveName.substring(0, cursor));
			if (index.isEmpty() || cursor == directiveName.length()) {
				return Optional.empty();
			}

			return Optional.of(new Suffixed(index.getAsInt(), directiveName.substring(cursor)));
		}

		for (int index = 0; index < LEGACY.size(); index++) {
			String alias = LEGACY.get(index);
			if (directiveName.startsWith(alias) && directiveName.length() > alias.length()) {
				return Optional.of(new Suffixed(index, directiveName.substring(alias.length())));
			}
		}

		return Optional.empty();
	}

	public record Suffixed(int index, String suffix) {
	}

	/**
	 * A program is named here by itself, {@code composite1}, while the rest of the engine names it
	 * by where it lives, {@code world0/composite1}. Both are accepted, so that neither side has to
	 * remember which form the other one uses.
	 * <p>
	 * Not a target name, unlike the rest of this class: it strips a program's directory, not a
	 * buffer's alias. Kept here anyway because every reader that calls it also reads a target name
	 * in the same breath, matching a program's writes and samples against the targets above.
	 */
	public static String bareName(String program) {
		int slash = program.lastIndexOf('/');

		return slash < 0 ? program : program.substring(slash + 1);
	}
}
