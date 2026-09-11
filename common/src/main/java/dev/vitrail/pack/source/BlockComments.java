package dev.vitrail.pack.source;

/** Where a pack's block comments stand from one line to the next, read as a compiler reads them. */
public final class BlockComments {

	private BlockComments() {
	}

	/**
	 * Whether a block comment is still open once this line has been read. A line comment closes at
	 * the end of the line and so ends the walk of it; inside a block, neither form opens anything
	 * and only the closing pair is looked for.
	 */
	public static boolean openAfter(String line, boolean commented) {
		for (int at = 0; at < line.length() - 1; at++) {
			if (commented) {
				if (line.charAt(at) == '*' && line.charAt(at + 1) == '/') {
					commented = false;
					at++;
				}
			} else if (line.charAt(at) == '/' && line.charAt(at + 1) == '/') {
				return false;
			} else if (line.charAt(at) == '/' && line.charAt(at + 1) == '*') {
				commented = true;
				at++;
			}
		}

		return commented;
	}
}
