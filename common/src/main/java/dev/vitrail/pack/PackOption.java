package dev.vitrail.pack;

import java.util.List;

/**
 * One setting, as the pack declares it, with where it was found.
 * <p>
 * The kind does not follow from whether a list of values is written next to the declaration.
 * It follows from whether the declaration has a value at all: {@code #define BLOOM} is a
 * toggle, {@code #define BLOOM_STRENGTH 1.5} is a value even with no list beside it. Getting
 * that backwards changes the count on every pack.
 */
public record PackOption(String name, Kind kind, String defaultText, List<String> values,
		boolean defaultOff, String constType, String declaredIn, int line) {

	public enum Kind {
		/** A bare {@code #define NAME}, on or off and nothing else. */
		TOGGLE,
		/** A {@code #define NAME value}, whatever the value looks like. */
		VALUE,
		/** A {@code const int NAME = value;}, which the pack expects to be edited in place. */
		CONST
	}

	public PackOption {
		values = List.copyOf(values);
	}

	/** Whether the pack offered a list of allowed values in a trailing comment. */
	public boolean hasValueList() {
		return !this.values.isEmpty();
	}
}
