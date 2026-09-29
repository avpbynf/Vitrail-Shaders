package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.vitrail.glsl.GlslTranslatorCases.Setup;
import dev.vitrail.glsl.GlslTranslatorCases.Switches;
import dev.vitrail.pack.model.ProgramStage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Holds the four process-global switches of {@link GlslTranslator} and the two counters that ride on
 * them: what {@link GlslTranslator#emissionSwitches} says of each state, what
 * {@link GlslTranslator#trigSites} counts, and that a run of the corpus puts every switch back.
 * <p>
 * The switches are one set for the process, read by translations that may be running on a worker
 * while a pack loads, and tests share a JVM, so each test here puts them back on their defaults in
 * {@link #restore} whatever it did.
 */
class GlslTranslatorSwitchesTest {

	/** What a process says of itself where nobody armed anything. */
	private static final String DEFAULT =
			"trig-reduced compare-on-sampler shadow-chain-00 custom-views-v1:";

	/** Three calls to a builtin sine or cosine, one of them inside a helper the pack wrote. */
	private static final String THREE_SITES = """
			#version 120
			varying vec2 wave;
			float twist(float a) { return sin(a); }
			void main() {
				wave = vec2(cos(gl_Vertex.x), twist(gl_Vertex.y) + sin(gl_Vertex.z));
				gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
			}
			""";

	@AfterEach
	void restore() {
		Setup.restore();
	}

	@Test
	void theDefaultsAreWhatEveryPlayerRunsUnder() {
		assertEquals(DEFAULT, GlslTranslator.emissionSwitches());
	}

	@Test
	void everySwitchIsInTheWordTheCacheKeysOn() {
		Switches.DEFAULT.trig(false).apply();
		assertEquals("trig-driver compare-on-sampler shadow-chain-00 custom-views-v1:",
				GlslTranslator.emissionSwitches());

		Switches.DEFAULT.soft(true).apply();
		assertEquals("trig-reduced compare-in-shader shadow-chain-00 custom-views-v1:",
				GlslTranslator.emissionSwitches());

		Switches.DEFAULT.chains(true, false).apply();
		assertEquals("trig-reduced compare-on-sampler shadow-chain-10 custom-views-v1:",
				GlslTranslator.emissionSwitches());

		Switches.DEFAULT.chains(false, true).apply();
		assertEquals("trig-reduced compare-on-sampler shadow-chain-01 custom-views-v1:",
				GlslTranslator.emissionSwitches());

		Switches.DEFAULT.chains(true, true).apply();
		assertEquals("trig-reduced compare-on-sampler shadow-chain-11 custom-views-v1:",
				GlslTranslator.emissionSwitches());
	}

	@Test
	void aRunOfTheCorpusPutsEverySwitchBack() {
		for (GlslTranslatorCases.Single one : GlslTranslatorCases.everySingle()) {
			GlslTranslatorCases.run(one);
			assertEquals(DEFAULT, GlslTranslator.emissionSwitches(), "after " + one.name());
		}
	}

	@Test
	void trigSitesCountEveryCallWhetherOrNotItWasSubstituted() {
		for (boolean on : new boolean[] {true, false}) {
			GlslTranslator.reduceTrig(on);
			assertEquals(0, GlslTranslator.trigSites(), "just after the switch was set, on " + on);

			GlslTranslator.translate(GlslTranslatorCases.unit("sites.vsh", THREE_SITES), ProgramStage.VERTEX);
			assertEquals(3, GlslTranslator.trigSites(), "after one unit, on " + on);

			GlslTranslator.translate(GlslTranslatorCases.unit("sites.vsh", THREE_SITES), ProgramStage.VERTEX);
			assertEquals(6, GlslTranslator.trigSites(), "the tally adds up over units, on " + on);
		}
	}

	@Test
	void settingTheSwitchEmptiesTheTallyEvenToTheSameValue() {
		GlslTranslator.reduceTrig(true);
		GlslTranslator.translate(GlslTranslatorCases.unit("sites.vsh", THREE_SITES), ProgramStage.VERTEX);
		assertNotEquals(0, GlslTranslator.trigSites());

		GlslTranslator.reduceTrig(true);
		assertEquals(0, GlslTranslator.trigSites());
	}

	@Test
	void aGoldbergHashComesOffTheTallyItsSineWasCountedIn() {
		GlslTranslator.reduceTrig(true);
		GlslTranslator.translate(GlslTranslatorCases.unit("hash.fsh", """
				#version 120
				varying vec2 uv;
				void main() {
					float h = fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453);
					float k = fract(sin(dot(uv, vec2(1.0, 2.0))) * 100.0);
					gl_FragColor = vec4(h + k + cos(uv.x));
				}
				"""), ProgramStage.FRAGMENT);

		// The idiom is erased, so it is not a call either helper takes: two sites are left of three.
		assertEquals(2, GlslTranslator.trigSites());
	}
}
