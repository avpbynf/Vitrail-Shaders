package dev.vitrail.pack.target;

import dev.vitrail.pack.source.IncludeExpander;

import java.util.ArrayList;
import java.util.List;

/**
 * Which colour attachments a program says it writes.
 * <p>
 * This follows Iris rather than anything reasoned out here, because Iris is what the packs are
 * written and tested against, and its rule is odd enough that guessing would get it wrong. Read
 * from {@code CommentDirectiveParser.findDirective} and {@code ProgramDirectives}: the
 * <em>last</em> occurrence in the file wins, the directive has to open a block comment or it is
 * not one at all, and if the last occurrence fails that test nothing falls back to an earlier
 * one. When both spellings appear, the later of the two wins.
 * <p>
 * The search runs on the lines a branch actually took and on no others. Iris hands it a source
 * the preprocessor has already been over, so a branch nobody takes is simply not there; here the
 * text is still whole and the difference is not academic, because the idiom the packs use is one
 * directive per branch. Reading them all takes the last one written rather than the one that
 * holds: Complementary's deferred1 would write a target list meant for a mod that is not
 * installed, and thirteen of BSL's world0 programs would claim attachments they never touch and
 * flip them afterwards.
 * <p>
 * Iris infers a single attachment zero when neither appears. That is a rendering decision, so it
 * is left to whoever binds these rather than made here: an empty list means the program said
 * nothing.
 * <p>
 * It lives in this package, away from the translator, because the two questions are not the
 * same one. A plan reads thirty one programs to find out which targets have to exist and
 * translates one of them, so the rule has to be readable without a token stream; and the bound
 * differs, since a target that must exist goes up to thirty two while a fragment stage may only
 * declare sixteen outputs.
 */
public final class DrawBuffers {

	private DrawBuffers() {
	}

	/**
	 * Every index the directive names, up to thirty two. The translator applies its own bound of
	 * sixteen on top; a target that has to exist is not the same question as an output that can
	 * be declared.
	 */
	public static List<Integer> parse(IncludeExpander.ExpandedUnit unit) {
		String text = liveText(unit);
		int drawBuffersAt = directiveStart(text, "DRAWBUFFERS");
		int renderTargetsAt = directiveStart(text, "RENDERTARGETS");

		if (drawBuffersAt < 0 && renderTargetsAt < 0) {
			return List.of();
		}

		boolean runTogether = drawBuffersAt > renderTargetsAt;
		String value = directiveValue(text, runTogether ? drawBuffersAt : renderTargetsAt,
				runTogether ? "DRAWBUFFERS" : "RENDERTARGETS");
		if (value == null) {
			return List.of();
		}

		List<Integer> slots = new ArrayList<>();

		// DRAWBUFFERS runs its indices together, so it cannot name an attachment past nine.
		// RENDERTARGETS separates them with commas and can.
		if (runTogether) {
			for (int i = 0; i < value.length(); i++) {
				char digit = value.charAt(i);
				if (digit >= '0' && digit <= '9') {
					slots.add(digit - '0');
				}
			}

			return List.copyOf(slots);
		}

		for (String part : value.split(",", -1)) {
			addRenderTarget(slots, part.trim());
		}

		return List.copyOf(slots);
	}

	/**
	 * The unit with every dead line blanked out. Blanked and not dropped, so that a directive keeps
	 * the column it was written in and nothing ends up glued to the line above it.
	 */
	private static String liveText(IncludeExpander.ExpandedUnit unit) {
		List<String> lines = unit.lines();
		StringBuilder text = new StringBuilder();

		for (int line = 0; line < lines.size(); line++) {
			if (line > 0) {
				text.append('\n');
			}

			if (unit.isLive(line)) {
				text.append(lines.get(line));
			}
		}

		return text.toString();
	}

	/** Where the winning occurrence of a directive begins, or -1 if the file has none usable. */
	private static int directiveStart(String text, String name) {
		int at = text.lastIndexOf(name + ":");
		if (at < 0) {
			return -1;
		}

		// The directive has to be the first thing in a block comment. A line comment does not
		// count, and neither does a directive with prose in front of it on the same line.
		return text.substring(0, at).stripTrailing().endsWith("/*") ? at : -1;
	}

	private static String directiveValue(String text, int at, String name) {
		String rest = text.substring(at + name.length() + 1);
		int close = rest.indexOf("*/");

		return close < 0 ? null : rest.substring(0, close).trim();
	}

	private static void addRenderTarget(List<Integer> slots, String text) {
		if (text.isEmpty() || text.length() > 2 || !text.chars().allMatch(Character::isDigit)) {
			return;
		}

		int slot = Integer.parseInt(text);
		if (slot < TargetName.MAX_TARGETS) {
			slots.add(slot);
		}
	}
}
