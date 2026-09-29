package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.target.PackDirectives.ShadowColour;
import dev.vitrail.pack.target.PackDirectives.ShadowDepth;
import dev.vitrail.pack.target.TargetDirectives;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.junit.jupiter.api.Test;

/**
 * Holds what the shadow map's constructor settles before any image exists: the size it clamps a
 * directive to, how many colour buffers a pack may reach, which of them are allocated, and the
 * clear colour each starts from.
 * <p>
 * None of it needs a device, and all of it decides what a pack's {@code shadowtex} and
 * {@code shadowcolor} lookups read: a map allocated at any other size than the pack declared is a
 * picture computed against an image that does not exist, and a buffer left out is a lookup that
 * reads white.
 */
class ShadowTargetsTest {
	// The game's own format and filter types are never named in this class: they live in a different
	// package in 26.3, and tests are not rewritten onto that name the way the shared sources are.

	private static final int MAX_COLOURS = PackDirectives.MAX_SHADOW_COLOURS;

	private static final List<ShadowDepth> NO_CHAIN = List.of(new ShadowDepth(false, false), new ShadowDepth(false, false));

	private static ShadowColour white() {
		return new ShadowColour(TargetFormat.defaultFormat(), true, new TargetDirectives.Colour(1.0F, 1.0F, 1.0F, 1.0F),
				false);
	}

	private static List<ShadowColour> defaults() {
		return IntStream.range(0, MAX_COLOURS).mapToObj(i -> white()).toList();
	}

	private static ShadowTargets targets(int resolution, Set<Integer> named, int ceiling) {
		return new ShadowTargets(resolution, defaults(), NO_CHAIN, named, ceiling);
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(ShadowTargets targets, String name) {
		try {
			Field field = ShadowTargets.class.getDeclaredField(name);
			field.setAccessible(true);

			return (T) field.get(targets);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("no field " + name + " on ShadowTargets", e);
		}
	}

	// ---- the size ----

	@Test
	void aSizeInsideWhatTheEngineAllocatesIsKeptAsItIsSquareAndWhateverItsFactors() {
		for (int resolution : new int[] {1, 2, 512, 1000, 1024, 2048, 3072, 16_384}) {
			assertEquals(resolution, targets(resolution, Set.of(), 2).resolution(), "size " + resolution);
		}
	}

	@Test
	void aSizeOutsideItIsClampedAndNotRefused() {
		assertEquals(1, targets(0, Set.of(), 2).resolution());
		assertEquals(1, targets(-5, Set.of(), 2).resolution());
		assertEquals(1, targets(Integer.MIN_VALUE, Set.of(), 2).resolution());
		assertEquals(16_384, targets(16_385, Set.of(), 2).resolution());
		assertEquals(16_384, targets(1_000_000, Set.of(), 2).resolution());
		assertEquals(16_384, targets(Integer.MAX_VALUE, Set.of(), 2).resolution());
	}

	// ---- how many buffers a pack may reach ----

	@Test
	void theCeilingIsHeldBetweenOneAndEightWhateverWasComputedElsewhere() {
		assertEquals(1, targets(1024, Set.of(), 0).colourCeiling());
		assertEquals(1, targets(1024, Set.of(), -3).colourCeiling());
		assertEquals(2, targets(1024, Set.of(), 2).colourCeiling());
		assertEquals(8, targets(1024, Set.of(), 8).colourCeiling());
		assertEquals(8, targets(1024, Set.of(), 9).colourCeiling());
		assertEquals(8, targets(1024, Set.of(), Integer.MAX_VALUE).colourCeiling());
	}

	// ---- which buffers are allocated ----

	private static List<Integer> live(Set<Integer> named, int ceiling) {
		return field(targets(1024, named, ceiling), "live");
	}

	@Test
	void bufferNoughtIsAlwaysAllocatedWhateverThePackNames() {
		assertEquals(List.of(0), live(Set.of(), 2), "a pack that names nothing");
		assertEquals(List.of(0), live(Set.of(0), 2));
		assertEquals(List.of(0, 1), live(Set.of(1), 2));
	}

	@Test
	void aPackNamingOnlyABufferAboveNoughtGetsNoughtAndThatOneWithHolesLeftOut() {
		assertEquals(List.of(0, 2), live(Set.of(2), 8), "shadowcolor2 alone: two images and not three");
		assertEquals(List.of(0, 1, 7), live(Set.of(1, 7), 8));
	}

	@Test
	void aBufferAtOrPastTheCeilingIsDroppedAndSoIsAnythingBelowNought() {
		assertEquals(List.of(0), live(Set.of(2), 2), "index two is past a ceiling of two");
		assertEquals(List.of(0, 1), live(Set.of(1, 2, 5), 2));
		assertEquals(List.of(0), live(Set.of(8), 8));
		assertEquals(List.of(0), live(Set.of(-1, 0), 8));
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), live(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9), 100),
				"and the ceiling is itself clamped to eight");
	}

	@Test
	void theAllocatedBuffersAreSortedWhateverOrderTheSetGivesThem() {
		Set<Integer> named = new HashSet<>(List.of(7, 3, 1, 5));

		assertEquals(List.of(0, 1, 3, 5, 7), live(named, 8));
	}

	// ---- the clear colour ----

	private static List<Vector4fc> clearColours(ShadowColour... first) {
		List<ShadowColour> asked = new ArrayList<>(List.of(first));
		while (asked.size() < MAX_COLOURS) {
			asked.add(white());
		}

		return field(new ShadowTargets(1024, asked, NO_CHAIN, Set.of(), 2), "clearColours");
	}

	private static ShadowColour with(String declared, float alpha, boolean declaresClearColour) {
		return new ShadowColour(TargetFormat.resolve(declared), true, new TargetDirectives.Colour(0.2F, 0.3F, 0.4F, alpha),
				declaresClearColour);
	}

	@Test
	void aFormatThatGainedAnAlphaChannelStartsOpaqueUnlessThePackNamedAClearColour() {
		// RGB8 is allocated as RGBA8, and in GL the three component texture always sampled alpha as one.
		List<Vector4fc> colours = clearColours(with("RGB8", 0.0F, false));

		assertEquals(1.0F, colours.get(0).w(), "alpha forced to one");
		assertEquals(0.2F, colours.get(0).x(), "the colour beside it is the pack's");
		assertEquals(0.3F, colours.get(0).y());
		assertEquals(0.4F, colours.get(0).z());
	}

	@Test
	void aPackThatNamedAClearColourKeepsTheAlphaItWrote() {
		List<Vector4fc> colours = clearColours(with("RGB8", 0.5F, true));

		assertEquals(0.5F, colours.get(0).w(), "forcing it would overrule a channel the pack was explicit about");
	}

	@Test
	void aFormatThatGainedNoAlphaKeepsTheClearAlphaEvenWhenTheColourWasNotDeclared() {
		List<Vector4fc> colours = clearColours(with("RGBA8", 0.0F, false), with("RGBA16F", 0.25F, false));

		assertEquals(0.0F, colours.get(0).w());
		assertEquals(0.25F, colours.get(1).w());
	}

	@Test
	void everyEntryOfTheAskedListHasAClearColourAndAFormat() {
		ShadowTargets targets = targets(1024, Set.of(), 2);

		List<Vector4fc> colours = field(targets, "clearColours");
		assertEquals(MAX_COLOURS, colours.size());
		for (int index = 0; index < MAX_COLOURS; index++) {
			assertEquals("RGBA8_UNORM", targets.format(index).name(), "the default format of buffer " + index);
			assertEquals(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector4f(colours.get(index)));
		}
	}

	@Test
	void theFormatIsWhatThePackDeclaredAfterPromotionToTheDevicesFormat() {
		List<ShadowColour> asked = new ArrayList<>(defaults());
		asked.set(0, with("RGB16F", 1.0F, false));
		asked.set(1, with("RG16F", 1.0F, false));
		ShadowTargets targets = new ShadowTargets(1024, asked, NO_CHAIN, Set.of(), 2);

		assertEquals("RGBA16_FLOAT", targets.format(0).name(), "RGB16F is promoted to four channels");
		assertEquals("RG16_FLOAT", targets.format(1).name());
	}

	// ---- how the depth pair is read ----

	@Test
	void eachDepthImageIsFilteredLinearUnlessThePackAskedForNearestOnThatImage() {
		ShadowTargets both = new ShadowTargets(1024, defaults(),
				List.of(new ShadowDepth(true, false), new ShadowDepth(true, false)), Set.of(), 2);
		assertEquals("NEAREST", both.depthFilter(false).name());
		assertEquals("NEAREST", both.depthFilter(true).name());

		ShadowTargets first = new ShadowTargets(1024, defaults(),
				List.of(new ShadowDepth(true, false), new ShadowDepth(false, false)), Set.of(), 2);
		assertEquals("NEAREST", first.depthFilter(false).name(), "shadowtex0");
		assertEquals("LINEAR", first.depthFilter(true).name(), "shadowtex1");

		assertEquals("LINEAR", targets(1024, Set.of(), 2).depthFilter(false).name(), "Iris's start");
	}

	@Test
	void aLookupNeverClimbsPastLevelNoughtBeforeTheChainHasBeenWritten() {
		// The directive alone would read undefined memory on a frame the fill was refused, so both have to
		// hold and nothing has been written yet.
		ShadowTargets chained = new ShadowTargets(1024, defaults(),
				List.of(new ShadowDepth(false, true), new ShadowDepth(false, true)), Set.of(), 2);

		assertFalse(chained.depthMipmapped(false));
		assertFalse(chained.depthMipmapped(true));
		assertFalse(targets(1024, Set.of(), 2).depthMipmapped(false));
	}

	// ---- before anything is allocated ----

	@Test
	void nothingIsHandedOutBeforeTheMapExists() {
		ShadowTargets targets = targets(1024, Set.of(0, 1), 2);

		assertNull(targets.depth());
		assertNull(targets.depthAttachment());
		assertNull(targets.colour(0));
		assertNull(targets.colour(1));
		assertNull(targets.colour(-1), "below the first name");
		assertNull(targets.colour(MAX_COLOURS), "past the last");
		assertFalse(targets.hasKept());
		assertTrue(targets.takeDepthClear().isEmpty());
		assertTrue(targets.takeColourClear(0).isEmpty());
	}

	@Test
	void deferringBeforeThereIsAMapRecordsNoClearBecauseThereIsNothingToEmpty() {
		ShadowTargets targets = targets(1024, Set.of(0, 1), 2);
		targets.defer();

		assertTrue(targets.takeDepthClear().isEmpty());
		assertTrue(targets.takeColourClear(0).isEmpty());
		assertTrue(targets.takeColourClear(1).isEmpty());
	}

	@Test
	void theListsAreCopiedSoTheCallerChangingItsOwnAfterwardsChangesNothing() {
		List<ShadowColour> asked = new ArrayList<>(defaults());
		List<ShadowDepth> depths = new ArrayList<>(NO_CHAIN);
		ShadowTargets targets = new ShadowTargets(1024, asked, depths, Set.of(), 2);
		asked.set(0, with("RGB16F", 1.0F, false));
		depths.set(0, new ShadowDepth(true, true));

		assertEquals("RGBA8_UNORM", targets.format(0).name());
		assertEquals("LINEAR", targets.depthFilter(false).name(), "the depth list is read on every lookup");
		List<ShadowColour> kept = field(targets, "asked");
		assertEquals(TargetFormat.RGBA8_UNORM, kept.get(0).format().used(), "and so is the asked list");
	}
}
