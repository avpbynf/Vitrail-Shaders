package dev.vitrail.uniform.expr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.values.FrameSmoothed;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Holds custom uniforms against the engine's own table, the one a pack is really given, with the
 * world answering from a map: the names a pack writes most (the clock, the sun, the weather, the
 * player's flags) read as the numbers the world says.
 * <p>
 * This is the seam the off-game harness uses. What each engine value means is pinned by the
 * catalogue's own tests; here it is only the values a custom expression is built from, their types
 * (a flag is an int, the sun angle a float, the eye brightness an ivec2) and that they arrive in
 * the frame they were read.
 */
class CustomUniformsEngineTest {

	private final ExprRig rig = new ExprRig();
	private final UniformCatalog engine = UniformCatalog.engine();

	@BeforeEach
	void forgetWhatTheLastTestSmoothed() {
		FrameSmoothed.forgetAll();
	}

	private CustomUniforms build(String... lines) {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(this.engine, problems, lines);
		assertEquals(List.of(), problems, "declarations dropped");

		return uniforms;
	}

	@Test
	void readsTheClocksTheWorldKeeps() {
		CustomUniforms uniforms = build(
				"uniform.float.t = frameTimeCounter * 2.0",
				"uniform.int.n = frameCounter % 4",
				"uniform.float.dt = frameTime");

		float[] clock = new float[4];
		int[] counter = new int[5];
		for (int frame = 0; frame < 5; frame++) {
			this.rig.frame(uniforms, 0.25F);
			counter[frame] = this.rig.i(uniforms, "n");
			if (frame < 4) {
				clock[frame] = this.rig.f(uniforms, "t");
			}
		}

		assertArrayEquals(new int[] {0, 1, 2, 3, 0}, counter, "the frame counter, modulo four");
		assertArrayEquals(new float[] {0.5F, 1.0F, 1.5F, 2.0F}, clock, "twice the accumulated 0.25 seconds");
		assertEquals(0.25F, this.rig.f(uniforms, "dt"));
	}

	@Test
	void readsTheSunAngleAsAFractionOfTheDay() {
		CustomUniforms uniforms = build(
				"uniform.float.a = sunAngle",
				"uniform.float.height = sin(sunAngle * 6.2831855)");

		// The engine adds a quarter turn to the game's angle, wraps once, and divides by 360.
		this.rig.answers.put("sunAngleDegrees", 0.0F);
		this.rig.frame(uniforms);
		assertEquals(0.25F, this.rig.f(uniforms, "a"), 1.0E-6F, "(0 + 90) / 360");
		assertEquals(1.0F, this.rig.f(uniforms, "height"), 1.0E-6F, "sin of a quarter turn");

		this.rig.answers.put("sunAngleDegrees", -90.0F);
		this.rig.frame(uniforms);
		assertEquals(0.0F, this.rig.f(uniforms, "a"), 1.0E-6F, "(-90 + 90) / 360");
		assertEquals(0.0F, this.rig.f(uniforms, "height"), 1.0E-6F);

		this.rig.answers.put("sunAngleDegrees", -100.0F);
		this.rig.frame(uniforms);
		assertEquals(350.0F / 360.0F, this.rig.f(uniforms, "a"), 1.0E-6F, "-10 wraps once to 350");

		this.rig.answers.put("sunAngleDegrees", 300.0F);
		this.rig.frame(uniforms);
		assertEquals(30.0F / 360.0F, this.rig.f(uniforms, "a"), 1.0E-6F, "390 wraps once to 30");
	}

	@Test
	void readsTheTimeOfDayAsAnInt() {
		// worldTime reaches the expression as an int, so the division is the language's float one.
		CustomUniforms uniforms = build(
				"variable.float.hour = frac(worldTime / 24000.0) * 24.0",
				"uniform.float.night = if(hour > 18.0 || hour < 6.0, 1.0, 0.0)");

		this.rig.answers.put("worldTime", 6000L);
		this.rig.frame(uniforms);
		assertEquals(6.0F, this.rig.f(uniforms, "hour"), 1.0E-4F);
		assertEquals(0.0F, this.rig.f(uniforms, "night"), "6.0 is not below 6");

		this.rig.answers.put("worldTime", 18000L);
		this.rig.frame(uniforms);
		assertEquals(18.0F, this.rig.f(uniforms, "hour"), 1.0E-4F);
		assertEquals(0.0F, this.rig.f(uniforms, "night"), "18.0 is not above 18");

		this.rig.answers.put("worldTime", 20000L);
		this.rig.frame(uniforms);
		assertEquals(20.0F, this.rig.f(uniforms, "hour"), 1.0E-3F);
		assertEquals(1.0F, this.rig.f(uniforms, "night"));

		this.rig.answers.put("worldTime", 25000L);
		this.rig.frame(uniforms);
		assertEquals(1.0F, this.rig.f(uniforms, "hour"), 1.0E-3F, "past a day it wraps: 25000 is 1000");
		assertEquals(1.0F, this.rig.f(uniforms, "night"));
	}

	@Test
	void aPlayerFlagIsAnIntThatFitsAnIf() {
		// Body Camera's one declaration, and the reason the resolver has an int-to-bool cast.
		CustomUniforms uniforms = build(
				"uniform.float.sneak = if(is_sneaking, 1.0, 0.0)",
				"uniform.float.hurt = if(is_hurt, 1.0, 0.0)",
				"uniform.bool.both = is_sneaking && is_hurt",
				"uniform.bool.either = is_sneaking || is_hurt",
				"uniform.int.asInt = is_hurt + 10");

		this.rig.frame(uniforms);
		assertEquals(0.0F, this.rig.f(uniforms, "sneak"));
		assertEquals(0.0F, this.rig.f(uniforms, "hurt"));
		assertEquals(10, this.rig.i(uniforms, "asInt"));

		this.rig.answers.put("sneaking", true);
		this.rig.frame(uniforms);
		assertEquals(1.0F, this.rig.f(uniforms, "sneak"));
		assertEquals(0.0F, this.rig.f(uniforms, "hurt"));
		assertTrue(!this.rig.b(uniforms, "both"));
		assertTrue(this.rig.b(uniforms, "either"));

		this.rig.answers.put("hurt", true);
		this.rig.frame(uniforms);
		assertTrue(this.rig.b(uniforms, "both"));
		assertEquals(11, this.rig.i(uniforms, "asInt"), "a bool set as an int is 1");
	}

	@Test
	void readsAnIvecThroughItsComponents() {
		this.rig.answers.put("eyeBrightnessHalfLife", 10.0F);
		this.rig.answers.put("eyeBrightnessSky", 240);
		this.rig.answers.put("eyeBrightnessBlock", 120);
		CustomUniforms uniforms = build(
				"uniform.float.sky = eyeBrightnessSmooth.y / 240.0",
				"uniform.float.block = eyeBrightness.x / 240.0");

		this.rig.frame(uniforms);

		assertEquals(1.0F, this.rig.f(uniforms, "sky"), "the first frame is taken as it comes");
		assertEquals(0.5F, this.rig.f(uniforms, "block"));
	}

	@Test
	void readsAVec3ThroughItsComponentsAndDoesArithmeticOnIt() {
		this.rig.answers.put("cameraPosition", new Vector3d(10.0, 64.0, -30.0));
		CustomUniforms uniforms = build(
				"uniform.float.alt = eyeAltitude",
				"uniform.float.horizontal = cameraPosition.x + cameraPosition.z",
				"uniform.vec3.moved = cameraPosition * vec3(1.0, 0.5, 1.0)");

		this.rig.frame(uniforms);

		assertEquals(64.0F, this.rig.f(uniforms, "alt"));
		assertEquals(-20.0F, this.rig.f(uniforms, "horizontal"));
		assertArrayEquals(new float[] {10.0F, 32.0F, -30.0F}, this.rig.vec(uniforms, "moved", 3));
	}

	@Test
	void buildsTheChainAPackWritesFromTheSkyAndTheWeather() {
		// Four declarations deep, in the order a pack might write them, the last one first.
		CustomUniforms uniforms = build(
				"uniform.float.shadowFade = mix(0.0, 1.0, dayFactor) * (1.0 - rainStrength)",
				"variable.float.dayFactor = clamp(sunHeight * 4.0 + 0.5, 0.0, 1.0)",
				"variable.float.sunHeight = sin(sunAngle * 6.2831855)");

		this.rig.answers.put("sunAngleDegrees", 0.0F);
		this.rig.answers.put("rainStrength", 0.25F);
		this.rig.frame(uniforms);
		assertEquals(0.75F, this.rig.f(uniforms, "shadowFade"), 1.0E-6F, "noon, a quarter rained on: 1 * 0.75");

		this.rig.answers.put("sunAngleDegrees", -90.0F);
		this.rig.answers.put("rainStrength", 0.0F);
		this.rig.frame(uniforms);
		assertEquals(0.5F, this.rig.f(uniforms, "shadowFade"), 1.0E-6F, "sunrise: sin 0 = 0, so 0.5");

		this.rig.answers.put("sunAngleDegrees", 90.0F);
		this.rig.frame(uniforms);
		assertEquals(0.5F, this.rig.f(uniforms, "shadowFade"), 1.0E-5F, "sunset: sin pi is nearly 0");
	}

	@Test
	void aPackCannotRedefineWhatTheEngineAnswers() {
		List<String> problems = new ArrayList<>();
		CustomUniforms uniforms = this.rig.build(this.engine, problems,
				"uniform.float.sunAngle = 0.0",
				"uniform.float.frameTimeCounter = 0.0",
				"uniform.vec3.cameraPosition = vec3(0.0, 0.0, 0.0)",
				"uniform.int.is_hurt = 1",
				"uniform.float.isSneaking = if(is_sneaking, 1.0, 0.0)");

		assertEquals(4, problems.size(), problems.toString());
		for (String name : new String[] {"sunAngle", "frameTimeCounter", "cameraPosition", "is_hurt"}) {
			assertTrue(problems.stream().anyMatch(line -> line.startsWith(name + ": shadows")), name);
		}
		assertNotNull(uniforms.source("isSneaking"), "isSneaking is the pack's own name, not the engine's");
	}

	@Test
	void theStructAndTheMatricesAreNotValuesAnExpressionMayReadByName() {
		List<String> problems = new ArrayList<>();
		this.rig.build(this.engine, problems, "uniform.float.a = of_Fog");
		assertEquals("a: Unknown variable: of_Fog", problems.getFirst(), "a struct has no expression type");
	}

	@Test
	void anEngineMatrixGivesItsColumnsToAnExpression() {
		this.rig.answers.put("gbufferModelView", new org.joml.Matrix4f().translation(1.0F, 2.0F, 3.0F));
		CustomUniforms uniforms = build(
				"uniform.vec4.col = gbufferModelView.w",
				"uniform.float.y = gbufferModelView.w.y");

		this.rig.frame(uniforms);

		assertArrayEquals(new float[] {1.0F, 2.0F, 3.0F, 1.0F}, this.rig.vec(uniforms, "col", 4),
				"the translation is the fourth column");
		assertEquals(2.0F, this.rig.f(uniforms, "y"));
	}
}
