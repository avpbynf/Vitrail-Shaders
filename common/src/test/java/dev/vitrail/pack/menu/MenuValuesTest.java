package dev.vitrail.pack.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds what a setting is worth in the three layers that must stay apart: applied, pending and
 * forced. Applied is what the pack was built with, pending what has been clicked, and forced is
 * {@code vitrail/options.txt}, which wins over both and cannot be edited here.
 */
class MenuValuesTest {

	/**
	 * BLOOM on, FOG off, QUALITY 2 of one to three, MODE Fast of Fast and Slow and shadowHardwareFiltering off
	 * are the five settings; STRENGTH is a declaration no page can place.
	 */
	private static final String GLSL = """
			#define BLOOM
			//#define FOG
			#define QUALITY 2 // [1 2 3]
			#define MODE Fast // [Fast Slow]
			#define STRENGTH 1.5
			const bool shadowHardwareFiltering = false;
			#ifdef BLOOM
			#endif
			#ifdef FOG
			#endif
			#ifdef shadowHardwareFiltering
			#endif
			""";

	/** Five profiles: two of the same size and a duplicate of LOW, to exercise the tie. */
	private static final String PROPERTIES = """
			screen=BLOOM FOG QUALITY MODE shadowHardwareFiltering <profile>
			profile.LOW=!BLOOM QUALITY=1
			profile.MID=BLOOM QUALITY=3
			profile.ALIAS=!BLOOM QUALITY=1
			profile.HIGH=BLOOM FOG QUALITY=3
			profile.PLAIN=QUALITY=2
			""";

	private static PackMenu menu(Path dir) throws IOException {
		return MenuFixture.menu(dir, PROPERTIES, GLSL);
	}

	private static Map<String, String> map(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}

		return map;
	}

	private static MenuValues values(PackMenu menu, Map<String, String> saved, Map<String, String> forced) {
		return MenuValues.of(menu, saved, forced);
	}

	// ---- the layers --------------------------------------------------------------------------

	@Test
	void nothingSetMeansTheDefaultOfThePack(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), Map.of(), Map.of());

		assertEquals("on", values.applied("BLOOM"));
		assertEquals("off", values.applied("FOG"));
		assertEquals("2", values.applied("QUALITY"));
		assertEquals("Fast", values.applied("MODE"));
		assertEquals("off", values.applied("shadowHardwareFiltering"));
		assertEquals("", values.applied("GHOST"), "a name nobody placed has no default");
		assertEquals("2", values.pending("QUALITY"));
		assertFalse(values.modified("QUALITY"));
		assertEquals(0, values.pendingCount());
	}

	@Test
	void appliedReadsForcedThenSavedThenTheForcedProfileThenTheDefault(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir);

		assertEquals("3", values(menu, map("QUALITY", "3"), Map.of()).applied("QUALITY"));
		assertEquals("1", values(menu, map("QUALITY", "3"), map("QUALITY", "1")).applied("QUALITY"));

		// A profile options.txt forces sits under the saved layer and over the default.
		MenuValues withProfile = values(menu, map("QUALITY", "3"), map("profile", "LOW"));
		assertEquals("off", withProfile.applied("BLOOM"));
		assertEquals("3", withProfile.applied("QUALITY"));
		assertEquals("off", withProfile.applied("FOG"));
		assertEquals("1", values(menu, Map.of(), map("profile", "LOW")).applied("QUALITY"));
	}

	@Test
	void aNameNobodyPlacedIsKeptAsWritten(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("OLD", "x", "OLD_TOGGLE", "true"), Map.of());

		assertEquals("x", values.applied("OLD"));
		assertEquals("true", values.applied("OLD_TOGGLE"), "only a toggle the pack places is respelt");
	}

	@Test
	void everyLayerIsRespeltInTheWordsAWidgetCyclesThrough(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir);

		MenuValues values = values(menu, map("BLOOM", "false", "FOG", "TRUE", "shadowHardwareFiltering", "true",
				"QUALITY", "false", "MODE", "true"), map("BLOOM", "true"));

		assertEquals("on", values.applied("BLOOM"), "forced comes first and is respelt");
		assertEquals("on", values.applied("FOG"));
		assertEquals("on", values.applied("shadowHardwareFiltering"));
		assertEquals("false", values.applied("QUALITY"), "a cycle is not respelt");
		assertEquals("true", values.applied("MODE"));
		assertEquals("off", values(menu, map("BLOOM", "false"), Map.of()).applied("BLOOM"));
	}

	@Test
	void aToggleWrittenAsAnythingElseIsKeptAsThatWord(@TempDir Path dir) throws IOException {
		assertEquals("Medium", values(menu(dir), map("BLOOM", "Medium"), Map.of()).applied("BLOOM"));
	}

	@Test
	void theMapsHandedInAreCopied(@TempDir Path dir) throws IOException {
		Map<String, String> saved = map("QUALITY", "3");
		Map<String, String> forced = map("MODE", "Slow");
		MenuValues values = values(menu(dir), saved, forced);

		saved.put("QUALITY", "1");
		forced.put("MODE", "Fast");

		assertEquals("3", values.applied("QUALITY"));
		assertEquals("Slow", values.applied("MODE"));
	}

	// ---- pending -----------------------------------------------------------------------------

	@Test
	void pendingReadsForcedThenPendingThenSavedThenTheProfileThenTheDefault(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir);
		MenuValues values = values(menu, map("QUALITY", "3"), map("MODE", "Slow", "profile", "LOW"));

		assertEquals("3", values.pending("QUALITY"));
		values.queue("QUALITY", "1");
		assertEquals("1", values.pending("QUALITY"));
		assertEquals("Slow", values.pending("MODE"));
		values.queue("MODE", "Fast");
		assertEquals("Slow", values.pending("MODE"), "the click is recorded and loses to options.txt");
		assertEquals("off", values.pending("BLOOM"), "the forced profile is under pending and over the default");
		assertEquals("off", values.pending("FOG"));
	}

	@Test
	void aClickIsModifiedOnlyWhereItDiffersFromWhatIsApplied(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("QUALITY", "3"), Map.of());

		values.queue("QUALITY", "3");
		assertFalse(values.modified("QUALITY"));
		values.queue("QUALITY", "1");
		assertTrue(values.modified("QUALITY"));
		values.queue("QUALITY", "3");
		assertFalse(values.modified("QUALITY"));
	}

	@Test
	void aClickIsRecordedInTheSpellingItIsGiven(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), Map.of(), Map.of());

		values.queue("BLOOM", "true");

		// The screen only ever queues what a toggle offers, so nothing respells a click.
		assertEquals("true", values.pending("BLOOM"));
		assertTrue(values.modified("BLOOM"));
	}

	@Test
	void importingAFileRespellsWhatItQueues(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), Map.of(), Map.of());

		values.queueAll(map("BLOOM", "false", "FOG", "true", "QUALITY", "3", "GHOST", "true"));

		assertEquals("off", values.pending("BLOOM"));
		assertEquals("on", values.pending("FOG"));
		assertEquals("3", values.pending("QUALITY"));
		assertEquals("true", values.pending("GHOST"));
	}

	@Test
	void pickingAProfileQueuesEveryValueItNamesAndNothingElse(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("MODE", "Slow"), Map.of());
		values.queue("QUALITY", "2");

		values.queueProfile("HIGH");

		assertEquals("on", values.pending("BLOOM"));
		assertEquals("on", values.pending("FOG"));
		assertEquals("3", values.pending("QUALITY"), "the profile overrides a click already waiting");
		assertEquals("Slow", values.pending("MODE"), "and leaves the settings it does not name where they were");
		assertEquals("HIGH", values.matchedProfile());

		values.queueProfile("NO_SUCH_PROFILE");
		assertEquals("3", values.pending("QUALITY"));
	}

	@Test
	void resetGoesBackToThePackDefaultNotToTheSavedOrTheProfileValue(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("QUALITY", "3"), map("profile", "LOW"));

		values.reset("QUALITY");

		assertEquals("2", values.pending("QUALITY"));
		assertTrue(values.modified("QUALITY"));
		assertEquals(1, values.pendingCount());
	}

	@Test
	void clearingWhatIsPendingBringsTheScreenBackToWhatIsApplied(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("QUALITY", "3"), Map.of());
		values.queue("QUALITY", "1");
		values.queue("FOG", "on");
		assertEquals(2, values.pendingCount());

		values.clearPending();

		assertEquals(0, values.pendingCount());
		assertEquals("3", values.pending("QUALITY"));
		assertEquals("off", values.pending("FOG"));
	}

	@Test
	void pendingCountsOnlyWhatDiffersAndAForcedNameNeverDoes(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("QUALITY", "3"), map("MODE", "Slow"));

		values.queue("QUALITY", "3");
		values.queue("BLOOM", "on");
		values.queue("MODE", "Fast");
		assertEquals(0, values.pendingCount());

		values.queue("FOG", "on");
		assertEquals(1, values.pendingCount());
	}

	// ---- what is written ---------------------------------------------------------------------

	@Test
	void whatIsWrittenIsOnlyWhatDiffersFromTheDefaultOfThePack(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("QUALITY", "3", "BLOOM", "false"), Map.of());
		values.queue("QUALITY", "2");
		values.queue("FOG", "on");
		values.queue("MODE", "Slow");

		Map<String, String> written = values.toSave();

		// QUALITY went back to its default so its line goes; BLOOM and FOG are switches, written true
		// and false because the reference reads nothing else.
		assertEquals(map("BLOOM", "false", "FOG", "true", "MODE", "Slow"), written);
		assertEquals(List.of("BLOOM", "FOG", "MODE"), List.copyOf(written.keySet()));
	}

	@Test
	void aNameThePackNoLongerPlacesIsKeptNotDropped(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("OLD", "x", "EMPTY", ""), Map.of());

		assertEquals(map("OLD", "x"), values.toSave());
	}

	@Test
	void aToggleThatHoldsAnyOtherWordIsWrittenFalse(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("BLOOM", "Medium"), Map.of());

		assertEquals(map("BLOOM", "false"), values.toSave());
	}

	@Test
	void aForcedNameIsWrittenAsItWasSavedOrNotAtAll(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("QUALITY", "3"), map("QUALITY", "1", "MODE", "Slow"));
		values.queue("QUALITY", "2");
		values.queue("MODE", "Slow");
		values.queue("FOG", "on");

		assertEquals(map("QUALITY", "3", "FOG", "true"), values.toSave());
	}

	@Test
	void theForcedProfileDecidesWhatIsDrawnAndNeverWhatIsWritten(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), Map.of(), map("profile", "LOW"));

		assertEquals("off", values.pending("BLOOM"));
		assertEquals(Map.of(), values.toSave());
		values.queue("QUALITY", "3");
		assertEquals(map("QUALITY", "3"), values.toSave());
	}

	@Test
	void writtenSpellsATogglesValueAsBooleanAndLeavesTheRestAlone(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir);

		assertEquals("true", MenuValues.written(menu, "BLOOM", "on"));
		assertEquals("false", MenuValues.written(menu, "BLOOM", "OFF"));
		assertEquals("false", MenuValues.written(menu, "BLOOM", "Medium"));
		assertEquals("true", MenuValues.written(menu, "shadowHardwareFiltering", "true"));
		assertEquals("3", MenuValues.written(menu, "QUALITY", "3"));
		assertEquals("on", MenuValues.written(menu, "QUALITY", "on"));
		assertEquals("on", MenuValues.written(menu, "GHOST", "on"));
	}

	// ---- profiles ----------------------------------------------------------------------------

	@Test
	void theProfileShownIsTheMostConstrainedOneWhoseValuesAllMatch(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir);
		assertEquals(List.of("HIGH", "LOW", "MID", "ALIAS", "PLAIN"), menu.profileNames());

		MenuValues values = values(menu, Map.of(), Map.of());
		// The defaults are BLOOM on, FOG off, QUALITY 2: only PLAIN names nothing else.
		assertEquals("PLAIN", values.matchedProfile());

		values.queueProfile("HIGH");
		assertEquals("HIGH", values.matchedProfile());

		values.queue("FOG", "off");
		assertEquals("MID", values.matchedProfile(), "HIGH no longer holds and MID names BLOOM and QUALITY 3");

		values.queue("QUALITY", "1");
		assertEquals("", values.matchedProfile(), "nothing holds, which the screen calls Custom");
	}

	@Test
	void twoProfilesOfTheSameSizeThatBothMatchAreSettledByTheOrderTheyAreTriedIn(@TempDir Path dir)
			throws IOException {
		MenuValues values = values(menu(dir), Map.of(), Map.of());

		values.queueProfile("ALIAS");

		assertEquals("LOW", values.matchedProfile());
	}

	@Test
	void anEmptyProfileNeverMatches(@TempDir Path dir) throws IOException {
		PackMenu menu = MenuFixture.menu(dir, "screen=BLOOM\nprofile.NOTHING=\n", GLSL);

		assertEquals(List.of("NOTHING"), menu.profileNames());
		assertEquals("", values(menu, Map.of(), Map.of()).matchedProfile());
	}

	@Test
	void theAppliedProfileIsReadFromWhatThePackWasBuiltWithNotFromWhatIsPending(@TempDir Path dir)
			throws IOException {
		MenuValues values = values(menu(dir), map("BLOOM", "false", "QUALITY", "1"), Map.of());
		values.queue("QUALITY", "3");

		assertEquals("LOW", values.matchedAppliedProfile());
		assertEquals("", values.matchedProfile());
	}

	@Test
	void aProfileOptionsTxtForcesIsTheProfileWhateverTheValuesAmountTo(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), Map.of(), map("profile", "HIGH"));

		assertEquals("HIGH", values.profile());
		assertEquals("PLAIN", values(menu(dir.resolve("again")), Map.of(), Map.of()).profile());
	}

	@Test
	void theProfileSentenceCountsWhatSitsOutsideTheMatchedProfile(@TempDir Path dir) throws IOException {
		PackMenu menu = menu(dir);

		assertEquals("Profile: LOW (+0 options changed by user)",
				values(menu, map("BLOOM", "false", "QUALITY", "1"), Map.of()).profileInfo());
		assertEquals("Profile: LOW (+1 option changed by user)",
				values(menu, map("BLOOM", "false", "QUALITY", "1", "FOG", "true"), Map.of()).profileInfo());
		assertEquals("Profile: LOW (+2 options changed by user)",
				values(menu, map("BLOOM", "false", "QUALITY", "1", "FOG", "true", "MODE", "Slow"), Map.of())
						.profileInfo());
		assertEquals("Profile: Custom (+2 options changed by user)",
				values(menu, map("BLOOM", "false", "QUALITY", "3"), Map.of()).profileInfo());
		// A profile that is the pack's own defaults changes nothing on its own.
		assertEquals("Profile: PLAIN (+1 option changed by user)",
				values(menu, map("MODE", "Slow"), Map.of()).profileInfo());
	}

	@Test
	void theProfileSentenceReadsWhatIsAppliedAndNotWhatIsPending(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("BLOOM", "false", "QUALITY", "1"), Map.of());
		values.queue("QUALITY", "3");
		values.queue("FOG", "on");

		assertEquals("Profile: LOW (+0 options changed by user)", values.profileInfo());
	}

	// ---- what options.txt holds down ---------------------------------------------------------

	@Test
	void forcedShownCountsWhatAScreenGreysOutAndTheProfileWhereThereIsASelector(@TempDir Path dir)
			throws IOException {
		PackMenu menu = menu(dir);
		MenuValues values = values(menu, Map.of(), map("QUALITY", "1", "profile", "LOW", "STRENGTH", "9", "GHOST",
				"1", "terrain", "off"));

		assertTrue(values.forced("QUALITY"));
		assertTrue(values.forced("STRENGTH"));
		assertFalse(values.forced("MODE"));
		// QUALITY is placed and the profile has a selector; STRENGTH is declared and placed nowhere.
		assertEquals(2, values.forcedShown());

		PackMenu noProfiles = MenuFixture.menu(dir.resolve("plain"), "screen=BLOOM QUALITY\n", GLSL);
		assertEquals(1, values(noProfiles, Map.of(), map("QUALITY", "1", "profile", "LOW")).forcedShown());
	}

	// ---- reading again -----------------------------------------------------------------------

	@Test
	void aRereadKeepsWhatWasClickedAndTakesTheNewMenuAndLayers(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), map("QUALITY", "3"), Map.of());
		values.queue("FOG", "on");

		PackMenu other = MenuFixture.menu(dir.resolve("other"), "screen=FOG QUALITY\n", GLSL);
		MenuValues again = values.reread(other, map("QUALITY", "1"), Map.of());

		assertEquals("1", again.applied("QUALITY"));
		assertEquals("on", again.pending("FOG"));
		assertEquals("3", values.applied("QUALITY"), "the old one is untouched");
	}

	@Test
	void aRebaseReplacesTheLayersUnderTheSameMenuAndKeepsWhatWasClicked(@TempDir Path dir) throws IOException {
		MenuValues values = values(menu(dir), Map.of(), map("QUALITY", "1"));
		values.queue("QUALITY", "3");
		assertEquals("1", values.pending("QUALITY"));

		values.rebase(map("MODE", "Slow"), Map.of());

		assertEquals("Slow", values.applied("MODE"));
		assertEquals("3", values.pending("QUALITY"), "the click comes back when the line goes away");
		assertEquals("2", values.applied("QUALITY"));
	}
}
