package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslTranslator.Stage;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ImageInformation;
import dev.vitrail.pack.model.PackTexture;
import dev.vitrail.pack.model.PixelFormat;
import dev.vitrail.pack.model.PixelType;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.model.TargetFormat;
import dev.vitrail.pack.option.EngineDefines;
import dev.vitrail.pack.source.ExpansionStats;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The inputs {@link GlslTranslator} is held to, and the one way they are run and written down.
 * <p>
 * The translator's output is kept on disk by the game, so what it emits for a given text is a
 * contract rather than an implementation detail: a change that moves one byte either invalidates
 * every cached program or, worse, serves a stale one. These cases are small stages written in the
 * shapes real OptiFine and Iris packs use, one or two rewrite families to a case, and
 * {@link #run(Single)} turns each into a snapshot that a test compares with the file kept beside it.
 * <p>
 * Everything a case can change is an argument of it. The four process-global switches the translator
 * reads are set from {@link Switches} before every run and put back after it, because the tests
 * that use this class share one JVM with tests that never heard of them.
 */
final class GlslTranslatorCases {

	private GlslTranslatorCases() {
	}

	/**
	 * The process-global state the translator reads, each set explicitly for a run.
	 *
	 * @param reduceTrig  {@link GlslTranslator#reduceTrig}
	 * @param softCompare {@link GlslTranslator#askSoftCompare}
	 * @param chainZero   {@link GlslTranslator#askShadowChains}, {@code shadowtex0}
	 * @param chainOne    the same, {@code shadowtex1}
	 * @param moltenVk    {@link VendorExtensions#serveMoltenVk}
	 */
	record Switches(boolean reduceTrig, boolean softCompare, boolean chainZero, boolean chainOne,
			boolean moltenVk) {

		/** What a process holds where nobody set anything, which is what every player runs under. */
		static final Switches DEFAULT = new Switches(true, false, false, false, false);

		Switches trig(boolean on) {
			return new Switches(on, this.softCompare, this.chainZero, this.chainOne, this.moltenVk);
		}

		Switches soft(boolean on) {
			return new Switches(this.reduceTrig, on, this.chainZero, this.chainOne, this.moltenVk);
		}

		Switches chains(boolean zero, boolean one) {
			return new Switches(this.reduceTrig, this.softCompare, zero, one, this.moltenVk);
		}

		Switches molten(boolean on) {
			return new Switches(this.reduceTrig, this.softCompare, this.chainZero, this.chainOne, on);
		}

		void apply() {
			GlslTranslator.reduceTrig(this.reduceTrig);
			GlslTranslator.askSoftCompare(this.softCompare);
			GlslTranslator.askShadowChains(this.chainZero, this.chainOne);
			VendorExtensions.serveMoltenVk(this.moltenVk);
		}

		/** Puts the process back on {@link #DEFAULT}, for the {@code finally} of whoever applied one. */
		static void restore() {
			DEFAULT.apply();
		}
	}

	/**
	 * Everything about a run that is not the text: the mesh the vertex stage is drawn from, the pass, the
	 * alpha test, the volumes and custom images the pack ships, and the process-global switches.
	 */
	record Setup(VertexInputs inputs, String program, AlphaTest alphaTest, boolean coverage,
			Map<String, VolumeAtlas> volumes, ImageInformation.Reading images, Switches switches) {

		static final Setup DEFAULT = new Setup(VertexInputs.WORLD, "", AlphaTest.OFF, false, Map.of(),
				ImageInformation.Reading.empty(), Switches.DEFAULT);

		Setup inputs(VertexInputs mesh) {
			return new Setup(mesh, this.program, this.alphaTest, this.coverage, this.volumes, this.images,
					this.switches);
		}

		Setup program(String pass) {
			return new Setup(this.inputs, pass, this.alphaTest, this.coverage, this.volumes, this.images,
					this.switches);
		}

		Setup alphaTest(AlphaTest test) {
			return new Setup(this.inputs, this.program, test, this.coverage, this.volumes, this.images,
					this.switches);
		}

		Setup covering() {
			return new Setup(this.inputs, this.program, this.alphaTest, true, this.volumes, this.images,
					this.switches);
		}

		Setup volumes(Map<String, VolumeAtlas> shipped) {
			return new Setup(this.inputs, this.program, this.alphaTest, this.coverage, shipped, this.images,
					this.switches);
		}

		Setup images(ImageInformation.Reading shipped) {
			return new Setup(this.inputs, this.program, this.alphaTest, this.coverage, this.volumes, shipped,
					this.switches);
		}

		Setup switches(Switches state) {
			return new Setup(this.inputs, this.program, this.alphaTest, this.coverage, this.volumes,
					this.images, state);
		}

		/** Puts the process-global state this setup wrote back where a run finds it by default. */
		static void restore() {
			Switches.restore();
			CustomImages.clear();
		}

		void apply() {
			this.switches.apply();
			CustomImages.install(this.images);
		}
	}

	/**
	 * One stage translated on its own, the way {@link GlslTranslator#prepare} is called for a
	 * program's only stage.
	 *
	 * @param source lines marked {@code // @dead} end in that comment and are given to the translator
	 *               as taken by no branch, which is what the expander would have said of them
	 */
	record Single(String name, ProgramStage stage, String source, Setup setup) {

		/** The name alone, which is what a test report shows instead of the text and the setup. */
		@Override
		public String toString() {
			return this.name;
		}

		Single named(String other) {
			return new Single(other, this.stage, this.source, this.setup);
		}

		Single withSource(String text) {
			return new Single(this.name, this.stage, text, this.setup);
		}

		Single on(VertexInputs mesh) {
			return new Single(this.name, this.stage, this.source, this.setup.inputs(mesh));
		}

		Single program(String pass) {
			return new Single(this.name, this.stage, this.source, this.setup.program(pass));
		}

		Single alpha(AlphaTest test) {
			return new Single(this.name, this.stage, this.source, this.setup.alphaTest(test));
		}

		Single covering() {
			return new Single(this.name, this.stage, this.source, this.setup.covering());
		}

		Single volumes(Map<String, VolumeAtlas> shipped) {
			return new Single(this.name, this.stage, this.source, this.setup.volumes(shipped));
		}

		Single images(ImageInformation.Reading shipped) {
			return new Single(this.name, this.stage, this.source, this.setup.images(shipped));
		}

		Single with(Switches state) {
			return new Single(this.name, this.stage, this.source, this.setup.switches(state));
		}
	}

	/** A vertex stage and a fragment stage of one program, settled together the way a pack is. */
	record Pair(String name, String vertex, String fragment, VertexInputs inputs, String program) {

		/** The name alone, for the same reason as {@link Single#toString}. */
		@Override
		public String toString() {
			return this.name;
		}
	}

	private static Single vertex(String name, String source) {
		return new Single(name, ProgramStage.VERTEX, source, Setup.DEFAULT);
	}

	private static Single fragment(String name, String source) {
		return new Single(name, ProgramStage.FRAGMENT, source, Setup.DEFAULT);
	}

	private static Single compute(String name, String source) {
		return new Single(name, ProgramStage.COMPUTE, source, Setup.DEFAULT);
	}

	/** The engine's own symbols, written into every header, which no case is about. */
	private static final String ENGINE_DEFINES = "#define <engine defines>\n";

	/**
	 * The game's own block of per draw transforms, which the header declares where a rewrite read it.
	 * Its members and their order are the game's and differ between the two games this tree builds
	 * for, and {@link GameTransformsTest} holds the declaration to the game it is built against, so
	 * what a golden says of it is that it is there.
	 */
	private static final String GAME_BLOCK = "<game transforms block>\n";

	/**
	 * The text of one translated stage as the golden holds it: the engine's symbol block, the same
	 * seventy lines in every file, and the game's transform block are each folded to a marker, and
	 * whatever the translation counted is printed after the text.
	 */
	private static String snapshot(ExpandedUnit unit, TranslatedUnit translated) {
		StringBuilder block = new StringBuilder();
		EngineDefines.table(EngineDefines.machine()).forEach((name, value) -> block.append("#define ")
				.append(name).append(value.isEmpty() ? "" : " " + value).append('\n'));
		String text = translated.text();
		int at = text.indexOf(block.toString());
		if (at < 0) {
			throw new IllegalStateException("the header of " + unit.entry()
					+ " does not carry the engine's symbol block as one run of lines");
		}

		StringBuilder out = new StringBuilder();
		out.append("--- ").append(translated.entry()).append(" (").append(translated.stage()).append(") ---\n");
		out.append(text, 0, at).append(ENGINE_DEFINES)
				.append(text.substring(at + block.length())
						.replace(String.join("\n", GameTransforms.BLOCK) + "\n", GAME_BLOCK));
		if (!text.endsWith("\n")) {
			out.append('\n');
		}

		out.append("--- notes ---\n");
		for (RecordComponent component : TranslatedUnit.Notes.class.getRecordComponents()) {
			try {
				out.append(component.getName()).append(" = ")
						.append(component.getAccessor().invoke(translated.notes())).append('\n');
			} catch (ReflectiveOperationException failure) {
				throw new IllegalStateException(failure);
			}
		}

		out.append("--- drawBuffers ---\n").append(translated.drawBuffers()).append('\n');
		out.append("--- block ---\n");
		translated.blockMembers().forEach(member -> out.append(member.declaration()).append('\n'));
		out.append("--- samplers ---\n");
		translated.samplers().forEach(sampler -> out.append(sampler.declaration()).append('\n'));

		return out.toString();
	}

	/** The unit an expander would hand over for this text, every line taken but the marked ones. */
	static ExpandedUnit unit(String entry, String source) {
		List<String> lines = source.lines().toList();
		BitSet live = new BitSet();
		for (int line = 0; line < lines.size(); line++) {
			live.set(line, !lines.get(line).contains("@dead"));
		}

		return new ExpandedUnit(entry, lines, "", ExpansionStats.NONE, live, Map.of());
	}

	/**
	 * Translates one stage on its own and writes it down. The switches of the case are set for the
	 * run and the process is put back on its defaults afterwards, whatever happens in between.
	 */
	static String run(Single one) {
		return snapshot(unit(one.name() + "." + one.stage().extension(), one.source()), translate(one));
	}

	/** What the translator makes of one stage under the setup of its case, before it is written down. */
	static TranslatedUnit translate(Single one) {
		ExpandedUnit unit = unit(one.name() + "." + one.stage().extension(), one.source());
		Setup setup = one.setup();
		try {
			setup.apply();
			Stage stage = GlslTranslator.prepare(unit, one.stage(), setup.inputs(), setup.inputs().elements(),
					setup.alphaTest(), setup.coverage(), setup.program(), setup.volumes());

			return stage.render(stage.uniforms(), stage.samplers(), stage.varyings());
		} finally {
			Setup.restore();
		}
	}

	/**
	 * Translates a vertex and a fragment stage the way {@code ProgramTranslator} does, step for step
	 * and with the disk cache left out: the drop of inputs nothing writes, the owed outputs, the
	 * withheld ones, the union of the block, the samplers and the engine's varyings, and the names
	 * of the shared block moved out from under a stage that already uses them.
	 */
	static String run(Pair pair) {
		ExpandedUnit vertexUnit = unit(pair.name() + ".vsh", pair.vertex());
		ExpandedUnit fragmentUnit = unit(pair.name() + ".fsh", pair.fragment());
		try {
			Setup.DEFAULT.apply();
			Map<ProgramStage, Stage> prepared = new LinkedHashMap<>();
			prepared.put(ProgramStage.VERTEX, GlslTranslator.prepare(vertexUnit, ProgramStage.VERTEX,
					pair.inputs(), pair.inputs().elements(), AlphaTest.OFF, false, pair.program(), Map.of()));
			prepared.put(ProgramStage.FRAGMENT, GlslTranslator.prepare(fragmentUnit, ProgramStage.FRAGMENT,
					pair.inputs(), pair.inputs().elements(), AlphaTest.OFF, false, pair.program(), Map.of()));

			Stage first = prepared.get(ProgramStage.VERTEX);
			Stage second = prepared.get(ProgramStage.FRAGMENT);
			second.dropUnprovidedInputs(new LinkedHashSet<>(first.provides()));
			Map<String, String> unprovided = second.unprovided();
			if (!unprovided.isEmpty()) {
				first.owe(unprovided);
			}

			first.withhold(second.requires());

			Set<String> varyings = new LinkedHashSet<>();
			prepared.values().forEach(stage -> varyings.addAll(stage.varyings()));
			prepared.values().forEach(stage -> stage.makesOverlayColour(varyings));

			Map<String, TranslatedUnit.Uniform> uniforms = new LinkedHashMap<>();
			Map<String, TranslatedUnit.Uniform> samplers = new LinkedHashMap<>();
			prepared.values().forEach(stage -> {
				stage.uniforms().forEach(uniform -> uniforms.putIfAbsent(uniform.name(), uniform));
				stage.samplers().forEach(sampler -> samplers.putIfAbsent(sampler.name(), sampler));
			});
			List<TranslatedUnit.Uniform> block = new ArrayList<>(uniforms.values());
			List<TranslatedUnit.Uniform> bound = new ArrayList<>(samplers.values());

			StringBuilder out = new StringBuilder();
			out.append("=== program ").append(pair.program().isEmpty() ? "(none)" : pair.program())
					.append(" on ").append(pair.inputs()).append(" ===\n");
			out.append("varyings = ").append(new TreeSet<>(varyings)).append('\n');
			out.append("provides = ").append(new TreeSet<>(first.provides())).append('\n');
			out.append("requires = ").append(new TreeSet<>(second.requires())).append('\n');
			out.append("unprovided = ").append(unprovided).append('\n');
			for (Map.Entry<ProgramStage, Stage> entry : prepared.entrySet()) {
				Set<String> shadowed = new LinkedHashSet<>();
				for (TranslatedUnit.Uniform member : block) {
					if (entry.getValue().declared().contains(member.name())
							&& !entry.getValue().lifted().contains(member.name())) {
						shadowed.add(member.name());
					}
				}

				ExpandedUnit unit = entry.getKey() == ProgramStage.VERTEX ? vertexUnit : fragmentUnit;
				out.append(snapshot(unit, entry.getValue().render(block, bound, varyings, shadowed)));
			}

			return out.toString();
		} finally {
			Setup.restore();
		}
	}

	/** A flat volume of 32 cubed bytes, which is what the two volume cases are handed. */
	private static Map<String, VolumeAtlas> shippedVolume(String name, boolean clamp) {
		return Map.of(name, VolumeAtlas.of(new PackTexture.Raw(PackTexture.Shape.TEXTURE_3D, null, 32, 32,
				32, PixelFormat.RGBA, PixelType.UNSIGNED_BYTE), clamp));
	}

	/** Two custom images of the pack, both stored as 32 bit floats, one of them also sampled as a volume. */
	private static ImageInformation.Reading customImages() {
		TargetFormat.Resolution floats = TargetFormat.resolve("R32F");

		return new ImageInformation.Reading(List.of(
				new ImageInformation("imgVoxels", Optional.of("samplerVoxels"), PackTexture.Shape.TEXTURE_3D,
						PixelFormat.RED, floats, PixelType.FLOAT, 64, 64, 64, false, false, 0.0F, 0.0F),
				new ImageInformation("imgPlain", Optional.empty(), PackTexture.Shape.TEXTURE_2D,
						PixelFormat.RED, floats, PixelType.FLOAT, 16, 16, 0, false, false, 0.0F, 0.0F)),
				List.of());
	}

	/**
	 * The cases whose output depends on how a {@code #version} line is read, or on how the parameters of
	 * a function-like macro are told from their uses: their goldens are kept apart from the rest, in a
	 * test of their own, because those two are the readings a change to the translator is expected to
	 * move. Regenerating what is listed here must not have to touch any other golden.
	 */
	private static final Set<String> FENCED = Set.of(
			"core-profile-fullscreen", "synthesized-attributes", "pair-modern-interface",
			"version-compatibility", "version-150-bare", "version-absent", "version-two-lines",
			"version-dead-last-line", "pack-macros-shim", "macro-continuation", "macro-parameters-reserved",
			"macro-parameters-after-block");

	/** Every stage on its own, except the fenced ones, in the order the goldens are listed. */
	static List<Single> singles() {
		return everySingle().stream().filter(one -> !FENCED.contains(one.name())).toList();
	}

	/** The stages on their own that depend on the reading of a version line or of a macro parameter. */
	static List<Single> fenced() {
		return everySingle().stream().filter(one -> FENCED.contains(one.name())).toList();
	}

	/** Every stage on its own, fenced or not. */
	static List<Single> everySingle() {
		List<Single> all = new ArrayList<>();

		// --- The fixed function names and the storage words ----------------------------------------

		all.add(vertex("legacy-vertex", """
				#version 120

				uniform mat4 gbufferModelViewInverse;
				uniform vec3 cameraPosition;
				uniform float frameTimeCounter;

				varying vec2 texcoord;
				varying vec2 lmcoord;
				varying vec4 glcolor;
				varying vec3 normal;

				void main() {
					vec4 position = gl_ModelViewMatrix * gl_Vertex;
					gl_Position = gl_ProjectionMatrix * position;
					texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
					lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
					glcolor = gl_Color;
					normal = normalize(gl_NormalMatrix * gl_Normal);
					gl_FogFragCoord = length(position.xyz);
					gl_FrontColor = gl_Color;
					vec4 clip = gl_ModelViewProjectionMatrix * gl_Vertex;
					if (clip.w < 0.0 && gl_VertexID == 0) gl_Position = clip;
				}
				"""));

		all.add(fragment("legacy-fragment", """
				#version 120

				uniform sampler2D texture;
				uniform sampler2D lightmap;
				uniform vec3 fogColor;

				varying vec2 texcoord;
				varying vec2 lmcoord;
				varying vec4 glcolor;
				varying vec3 normal;

				/* DRAWBUFFERS:012 */

				void main() {
					vec4 albedo = texture2D(texture, texcoord) * glcolor;
					albedo.rgb *= texture2D(lightmap, lmcoord).rgb;
					albedo.rgb = mix(albedo.rgb, fogColor, clamp(gl_FogFragCoord * gl_Fog.density, 0.0, 1.0));
					gl_FragData[0] = albedo;
					gl_FragData[1] = vec4(normal * 0.5 + 0.5, 1.0);
					gl_FragData[2] = vec4(lmcoord, 0.0, 1.0);
				}
				"""));

		all.add(fragment("frag-fragcolor", """
				#version 110

				uniform sampler2D tex;
				varying vec2 uv;
				varying vec4 tint;

				void main() {
					gl_FragColor = texture2D(tex, uv) * tint;
				}
				"""));

		all.add(fragment("frag-fragdata-dynamic", """
				#version 120

				uniform sampler2D colortex0;
				uniform int frameCounter;
				varying vec2 uv;

				void main() {
					int slot = frameCounter % 2;
					vec4 colour = texture2D(colortex0, uv);
					gl_FragData[slot] = colour;
					gl_FragData[3] = colour * 0.5;
					gl_FragData[9] = colour;
				}
				"""));

		all.add(vertex("attribute-and-reserved-names", """
				#version 120

				attribute vec4 mc_Entity;
				attribute vec4 at_tangent;
				uniform sampler2D texture;
				uniform vec3 sampler;
				uniform float image;
				varying vec4 tangent;
				varying float blockId;
				varying vec2 texcoord;

				void main() {
					gl_Position = ftransform();
					blockId = mc_Entity.x;
					tangent = at_tangent + vec4(sampler, image);
					texcoord = gl_MultiTexCoord0.st + texture2D(texture, gl_MultiTexCoord0.st).xy;
				}
				"""));

		all.add(vertex("pack-macros-shim", """
				#version 120

				#define texture2D texture
				#define gl_Vertex vec4(position, 1.0)
				#define ROTATE(texture, angle) mat2(cos(texture), sin(angle))
				#define SAMPLE_PREFIX textureGrad(texture,

				uniform sampler2D texture;
				attribute vec3 position;
				varying vec2 uv;

				void main() {
					vec2 r = ROTATE(1.0, 2.0) * vec2(1.0);
					uv = texture2D(texture, r).xy;
					gl_Position = gl_Vertex;
				}
				"""));

		all.add(vertex("shadowed-builtins", """
				#version 120

				float tanh(float x) {
					float e = exp(2.0 * x);
					return (e - 1.0) / (e + 1.0);
				}

				vec3 fma(vec3 a, vec3 b, vec3 c) {
					return a * b + c;
				}

				float round(float x) {
					return floor(x + 0.5);
				}

				varying vec3 colour;

				void main() {
					colour = fma(gl_Vertex.xyz, vec3(tanh(0.5)), vec3(round(gl_Color.r)));
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				"""));

		// --- Version, extensions, redefined macros, blocks ------------------------------------------

		all.add(fragment("version-and-extensions", """
				#version 330 compatibility
				#extension GL_ARB_shader_texture_lod : enable
				#extension GL_EXT_gpu_shader4 : require
				#extension GL_NV_gpu_shader5 : enable
				#extension GL_ARB_shading_language_packing : warn

				#ifdef GL_NV_gpu_shader5
					#define HALF float16_t // @dead
				#else
					#define HALF float
				#endif

				uniform sampler2D colortex0;
				varying vec2 uv;

				void main() {
					HALF a = HALF(texture2DLod(colortex0, uv, 0.0).r);
					gl_FragColor = vec4(a);
				}
				"""));

		all.add(vertex("core-profile-fullscreen", """
				#version 330 core

				in vec3 vaPosition;
				in vec2 vaUV0;
				in vec4 vaColor;
				out vec2 texcoord;

				void main() {
					texcoord = vaUV0;
					gl_Position = vec4(vaPosition.xy * 2.0 - 1.0, 0.0, 1.0);
				}
				""").on(VertexInputs.FULLSCREEN).program("composite"));

		all.add(vertex("redefined-macros", """
				#version 120

				#define ROUGH_FRESNEL 0.7
				#define WAVE_SPEED 1.0
				#define SHADOW_BIAS 0.05
				#define SHADOW_BIAS 0.08
				#define SAME_TWICE 3
				#define SAME_TWICE 3
				#define ROUGH_FRESNEL 0.2

				#define TAPS 4
				float taps() { return float(TAPS); }
				#define TAPS 8
				float fresnel(float x) { return ROUGH_FRESNEL * x + SHADOW_BIAS; }

				#define WAVE_SPEED 2.5
				#define UNUSED_OPTION 1
				#undef UNUSED_OPTION
				#define UNUSED_OPTION 2

				void main() {
					gl_Position = vec4(fresnel(WAVE_SPEED), 0.0, 0.0, 1.0);
				}
				"""));

		all.add(vertex("gl-pervertex-redeclared", """
				#version 450 core

				out gl_PerVertex {
					vec4 gl_Position;
				};
				out vec2 texcoord;

				void main() {
					texcoord = gl_MultiTexCoord0.xy;
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				"""));

		all.add(vertex("interface-block-out", """
				#version 330 core

				layout(location = 0) in vec3 Position;

				layout(location = 1) flat out Data {
					layout(location = 0) vec3 normal;
					layout(location = 1, component = 0) vec2 uv;
					smooth vec4 colour;
				#ifdef USE_WIND
					float wind[2], gust;
				#endif
					flat int id;
				} DataOut;

				out Plain {
					vec3 view;
				};

				void main() {
					DataOut.normal = vec3(0.0, 1.0, 0.0);
					DataOut.uv = Position.xy;
					DataOut.colour = vec4(1.0);
					DataOut.id = 3;
					view = Position;
					gl_Position = vec4(Position, 1.0) + vec4(DataOut.uv.x);
				}
				"""));

		all.add(fragment("interface-block-in", """
				#version 330 core

				flat in Data {
					layout(location = 0) vec3 normal;
					layout(location = 1, component = 0) vec2 uv;
					smooth vec4 colour;
					flat int id;
				} DataIn;

				in Plain {
					vec3 view;
				};

				in Unreadable {
					struct Inner { float a; } inner;
				} Unread;

				layout(location = 0) out vec4 fragColor;

				void main() {
					vec3 n = DataIn.normal * float(DataIn.id);
					fragColor = vec4(n + view, DataIn.colour.a) + vec4(DataIn.uv, 0.0, 0.0);
				}
				"""));

		// --- How the version line is read: only a fullscreen vertex stage shows it, through the names of
		// the core profile the quad answers for. What the last of several live version lines says is
		// pinned as it stands, and a dead one says nothing: it is the reading a change of the translator
		// is expected to move.

		String quad = """
				in vec3 vaPosition;
				in vec2 vaUV0;
				out vec2 texcoord;

				void main() {
					texcoord = vaUV0;
					gl_Position = vec4(vaPosition.xy * 2.0 - 1.0, 0.0, 1.0);
				}
				""";
		Single screen = vertex("version", quad).on(VertexInputs.FULLSCREEN).program("composite");
		all.add(screen.named("version-compatibility").withSource("#version 330 compatibility\n\n" + quad));
		all.add(screen.named("version-150-bare").withSource("#version 150\n\n" + quad));
		all.add(screen.named("version-absent").withSource(quad));
		all.add(screen.named("version-two-lines").withSource("#version 330 core\n\n#version 120\n\n" + quad));
		all.add(screen.named("version-dead-last-line").withSource(
				"#version 120\n\n#version 330 core // @dead\n\n" + quad));

		// --- Trigonometry, the goldberg hash, the packing builtins -----------------------------------

		Single trig = vertex("trig", """
				#version 120

				uniform float frameTimeCounter;
				varying vec3 wave;

				vec2 rotate(vec2 p, float a) {
					return vec2(cos(a) * p.x - sin(a) * p.y, sin(a) * p.x + cos(a) * p.y);
				}

				void main() {
					vec3 p = gl_Vertex.xyz;
					float t = frameTimeCounter * 0.5;
					wave = vec3(sin(p.x + t), cos(p.z * 2.0 - t), sin(length(p.xz)) * cos(t));
					p.xz = rotate(p.xz, t);
					gl_Position = gl_ModelViewProjectionMatrix * vec4(p, 1.0);
				}
				""");
		all.add(trig.named("trig-reduced"));
		all.add(trig.named("trig-driver").with(Switches.DEFAULT.trig(false)));

		all.add(vertex("trig-declared-by-pack", """
				#version 120

				float sin(float x) {
					return x - x * x * x / 6.0;
				}

				varying vec2 wave;

				void main() {
					wave = vec2(sin(gl_Vertex.x), cos(gl_Vertex.y));
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				"""));

		Single hash = fragment("goldberg", """
				#version 120

				uniform float frameTimeCounter;
				uniform sampler2D noisetex;
				varying vec2 texcoord;

				float hash12(vec2 p) {
					return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
				}

				float hash13(vec3 p) {
					return fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453123);
				}

				float grain(vec2 p) {
					return fract(sin(dot(p, vec2(12.9898, 78.233 * frameTimeCounter))) * 43758.5453);
				}

				float other(vec2 p) {
					return fract(sin(dot(p, vec2(1.0, 2.0))) * 100.0);
				}

				void main() {
					float n = hash12(texcoord) + hash13(vec3(texcoord, 1.0)) + grain(texcoord) + other(texcoord);
					gl_FragColor = vec4(vec3(n * 0.25), 1.0);
				}
				""");
		all.add(hash.named("goldberg-reduced"));
		all.add(hash.named("goldberg-driver").with(Switches.DEFAULT.trig(false)));

		Single packing = fragment("pack-builtins", """
				#version 400

				uniform sampler2D colortex0;
				uniform usampler2D colortex1;
				varying vec2 uv;

				layout(location = 0) out uvec4 encoded;

				void main() {
					vec4 c = texture(colortex0, uv);
					uint low = packUnorm4x8(c);
					uint high = packUnorm2x16(c.zw) + packSnorm4x8(c) + packSnorm2x16(c.xy);
					vec4 back = unpackUnorm4x8(texelFetch(colortex1, ivec2(0), 0).x);
					vec2 pair = unpackUnorm2x16(low) + unpackSnorm2x16(high) + unpackSnorm4x8(low).xy;
					encoded = uvec4(low, high, uint(back.x * 255.0), uint(pair.x));
				}
				""");
		all.add(packing.named("pack-builtins-driver"));
		all.add(packing.named("pack-builtins-moltenvk").with(Switches.DEFAULT.molten(true)));

		// --- Lookups ---------------------------------------------------------------------------------

		Single lookups = fragment("lookups", """
				#version 130

				uniform sampler2D colortex0;
				uniform sampler2D depthtex0;
				uniform sampler3D lut;
				uniform samplerCube sky;
				uniform sampler1D ramp;
				varying vec2 uv;

				void main() {
					vec4 a = texture2D(colortex0, uv);
					vec4 b = texture2DLod(colortex0, uv, 2.0);
					vec4 c = texture2DProj(colortex0, vec3(uv, 1.0));
					vec4 d = texture2DGrad(colortex0, uv, dFdx(uv), dFdy(uv));
					vec4 e = texture2DGradARB(colortex0, uv, vec2(0.0), vec2(0.0));
					vec4 f = texture2DOffset(colortex0, uv, ivec2(1, 0));
					vec4 g = texture2DLodOffset(colortex0, uv, 1.0, ivec2(0, 1));
					vec4 h = texelFetch2D(colortex0, ivec2(gl_FragCoord.xy), 0);
					vec4 i = textureCube(sky, vec3(uv, 1.0));
					vec4 j = texture1D(ramp, uv.x) + texture3D(lut, vec3(uv, 0.5)) + texture3DLod(lut, vec3(uv, 0.5), 1.0);
					vec4 k = texture2DProjLod(colortex0, vec3(uv, 1.0), 0.0) + texelFetch3D(lut, ivec3(1), 0);
					float depth = texture2D(depthtex0, uv).r;
					gl_FragColor = a + b + c + d + e + f + g + h + i + j + k + depth;
				}
				""");
		all.add(lookups.named("lookups-renamed"));
		all.add(lookups.named("lookups-pinned").program("composite"));

		all.add(fragment("pin-composite", """
				#version 120

				uniform sampler2D colortex0;
				uniform sampler2D colortex1;
				uniform sampler2D colortex4;
				uniform sampler2D depthtex1;
				uniform sampler2D noisetex;
				uniform sampler2DRect frameRect;
				uniform sampler2D customMask;
				uniform float viewWidth;
				uniform float viewHeight;
				varying vec2 texcoord;

				const bool colortex1MipmapEnabled = true;
				const bool colortex4MipmapEnabled = false;

				vec3 bloom(vec2 uv) {
					vec3 sum = vec3(0.0);
					for (int i = -2; i <= 2; i++) {
						sum += texture2D(colortex1, uv + vec2(float(i) / viewWidth, 0.0), 2.0).rgb;
						sum += texture2DLod(colortex1, uv, float(i)).rgb;
					}
					return sum / 5.0;
				}

				void main() {
					vec3 colour = texture2D(colortex0, texcoord).rgb;
					colour += texture2DGrad(colortex0, texcoord, vec2(0.001), vec2(0.001)).rgb;
					colour += textureOffset(colortex0, texcoord, ivec2(1, 1)).rgb;
					colour += textureGradOffset(colortex0, texcoord, vec2(0.0), vec2(0.0), ivec2(2, 0)).rgb;
					colour += textureLodOffset(colortex0, texcoord, 3.0, ivec2(0, 1)).rgb;
					colour += texture2D(colortex4, texcoord, -1.0).rgb;
					colour += texture2D(depthtex1, texcoord).rgb + texture2D(noisetex, texcoord * 8.0).rgb;
					colour += texture(frameRect, gl_FragCoord.xy).rgb + texture2D(customMask, texcoord).rgb;
					colour += bloom(texcoord) + textureProj(colortex0, vec3(texcoord, 1.0)).rgb;
					colour += textureGather(colortex0, texcoord, 1).rgb + texelFetch(colortex0, ivec2(3), 0).rgb;
					gl_FragData[0] = vec4(colour, 1.0);
				}
				""").program("composite"));

		all.add(fragment("pin-sampler-parameters", """
				#version 120

				uniform sampler2D colortex0;
				uniform sampler2D colortex3;
				uniform sampler2D depthtex0;
				uniform sampler2D gaux1;
				varying vec2 texcoord;

				vec3 tap(sampler2D img, vec2 uv) {
					return texture2D(img, uv).rgb;
				}

				vec3 chain(sampler2D img, vec2 uv) {
					return tap(img, uv) + texture2D(img, uv * 0.5).rgb;
				}

				float depthAt(sampler2D depth, vec2 uv) {
					return texture2D(depth, uv).r;
				}

				vec3 mixed(sampler2D img, vec2 uv) {
					return texture2D(img, uv).rgb;
				}

				vec3 rectTap(sampler2DRect img, vec2 uv) {
					return texture(img, uv).rgb;
				}

				void main() {
					vec3 a = chain(colortex0, texcoord) + tap(colortex3, texcoord);
					float d = depthAt(depthtex0, texcoord);
					vec3 b = mixed(colortex0, texcoord) + mixed(gaux1, texcoord * 2.0);
					gl_FragData[0] = vec4(a + b + vec3(d), 1.0);
				}
				""").program("gbuffers_terrain"));

		// DOCUMENTS CURRENT BEHAVIOUR, WHICH LOOKS LIKE A BUG: a sampler parameter spelled like one of the
		// reserved words is renamed by rewriteIdentifiers after collectSamplerParameters recorded it
		// under the old spelling, so pinLookupLevels cannot find it and its lookups are neither pinned nor
		// counted as left alone.
		all.add(fragment("pin-parameter-reserved-name", """
				#version 120

				uniform sampler2D colortex0;
				uniform sampler2D noisetex;
				varying vec2 texcoord;

				vec3 tap(sampler2D image, vec2 uv) {
					return texture2D(image, uv).rgb;
				}

				void main() {
					gl_FragData[0] = vec4(tap(colortex0, texcoord) + tap(noisetex, texcoord), 1.0);
				}
				""").program("composite"));

		all.add(fragment("pin-geometry-program", """
				#version 120

				uniform sampler2D texture;
				uniform sampler2D lightmap;
				uniform sampler2D normals;
				uniform sampler2D specular;
				uniform sampler2D colortex4;
				uniform sampler2D noisetex;
				uniform sampler2D depthtex1;
				uniform sampler2D shadowcolor0;
				varying vec2 texcoord;

				void main() {
					vec4 albedo = texture2D(texture, texcoord);
					vec3 n = texture2D(normals, texcoord).rgb + texture2D(specular, texcoord).rgb;
					vec3 l = texture2D(lightmap, texcoord).rgb;
					vec3 g = texture2D(colortex4, texcoord).rgb + texture2D(noisetex, texcoord).rgb;
					g += texture2D(depthtex1, texcoord).rgb + texture2D(shadowcolor0, texcoord).rgb;
					gl_FragData[0] = albedo * vec4(n + l + g, 1.0);
				}
				""").program("gbuffers_terrain"));

		// --- Comparison samplers and the shadow lookups ------------------------------------------------

		Single hardware = fragment("shadow-hardware", """
				#version 120

				uniform sampler2DShadow shadowtex0;
				uniform sampler2DShadow shadowtex1;
				uniform sampler2DShadow shadowtex1HW;
				uniform sampler2D colortex0;
				varying vec3 shadowPos;
				varying vec2 texcoord;

				float shadowFilter(vec3 pos) {
					float sum = 0.0;
					sum += shadow2D(shadowtex0, pos).x;
					sum += shadow2D(shadowtex1, pos + vec3(0.001, 0.0, 0.0)).r;
					sum += shadow2DLod(shadowtex0, pos, 0.0).x;
					sum += shadow2DProj(shadowtex0, vec4(pos, 1.0)).x;
					sum += texture(shadowtex1HW, pos);
					sum += textureLod(shadowtex0, pos, 1.0);
					sum += textureOffset(shadowtex1, pos, ivec2(1, 0));
					sum += textureProj(shadowtex0, vec4(pos, 1.0));
					return sum * 0.125;
				}

				void main() {
					gl_FragData[0] = texture2D(colortex0, texcoord) * shadowFilter(shadowPos);
				}
				""");
		all.add(hardware.named("shadow-hardware-road").program("composite"));
		all.add(hardware.named("shadow-arithmetic-armed").program("composite").with(Switches.DEFAULT.soft(true)));

		all.add(fragment("shadow-arithmetic-road", """
				#version 130

				uniform sampler2DShadow customShadow;
				uniform sampler2DArrayShadow shadowCascade;
				uniform sampler2D colortex0;
				varying vec3 shadowPos;
				varying vec2 texcoord;

				float compare(sampler2DShadow tex, vec3 pos) {
					return texture(tex, pos) + textureLod(tex, pos, 2.0) + shadow2D(tex, pos).x;
				}

				float plainCompare(sampler2D tex, vec3 pos) {
					return texture(tex, pos.xy).r;
				}

				void main() {
					float s = compare(customShadow, shadowPos);
					s += textureOffset(customShadow, shadowPos, ivec2(1, -1));
					s += textureLodOffset(customShadow, shadowPos, 0.0, ivec2(0, 1));
					s += textureProj(customShadow, vec4(shadowPos, 1.0));
					s += shadow2DProj(customShadow, vec4(shadowPos, 1.0)).x;
					s += textureGather(customShadow, shadowPos.xy, shadowPos.z).x;
					s += plainCompare(colortex0, shadowPos);
					gl_FragData[0] = texture2D(colortex0, texcoord) * s;
				}
				""").program("composite"));

		all.add(compute("shadow-compute", """
				#version 430

				layout(local_size_x = 8, local_size_y = 8) in;

				uniform sampler2DShadow shadowtex0;
				layout(rgba8) uniform writeonly image2D colorimg0;
				layout(r32f) uniform coherent image3D voxelVolume;
				uniform mat4 shadowProjection;

				void main() {
					ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
					float lit = textureLodOffset(shadowtex0, vec3(vec2(texel) / 64.0, 0.5), 0.0, ivec2(1, 0));
					imageStore(colorimg0, texel, vec4(lit));
					imageStore(voxelVolume, ivec3(texel, 0), vec4(lit));
				}
				""").program("shadowcomp"));

		Single chain = fragment("shadow-chain", """
				#version 130

				uniform sampler2DShadow shadowtex0;
				uniform sampler2D shadowtex1;
				uniform sampler2D shadow;
				varying vec3 pos;

				void main() {
					float s = textureLod(shadowtex0, pos, 3.0);
					s += textureLod(shadowtex1, pos.xy, 2.0).r;
					s += textureLodOffset(shadow, pos.xy, 1.0, ivec2(1, 1)).r;
					s += texture(shadowtex1, pos.xy).r;
					gl_FragData[0] = vec4(s);
				}
				""").program("composite");
		all.add(chain.named("shadow-chain-none"));
		all.add(chain.named("shadow-chain-zero").with(Switches.DEFAULT.chains(true, false)));
		all.add(chain.named("shadow-chain-both").with(Switches.DEFAULT.chains(true, true)));

		// --- Depth, outputs and the wrapped main -------------------------------------------------------

		all.add(fragment("depth-window", """
				#version 120

				uniform sampler2D depthtex0;
				uniform sampler2D colortex2;
				uniform float near;
				uniform float far;
				varying vec2 texcoord;

				float linearDepth(float d) {
					return (2.0 * near) / (far + near - d * (far - near));
				}

				void main() {
					float z = gl_FragCoord.z;
					vec3 screen = gl_FragCoord.xyz;
					vec2 pixel = gl_FragCoord.xy;
					vec2 tail = gl_FragCoord.zw;
					float w = gl_FragCoord.w;
					vec4 whole = gl_FragCoord;
					float d = texture2D(depthtex0, texcoord).r + texture2D(colortex2, texcoord).r;
					gl_FragDepth = linearDepth(z) + d;
					gl_FragDepth += 0.001;
					gl_FragData[0] = vec4(screen, pixel.x + w + whole.x + tail.x);
				}
				""").program("composite"));

		all.add(fragment("depth-in-dead-branch", """
				#version 120

				varying vec2 texcoord;
				uniform sampler2D colortex0;

				void main() {
					vec4 c = texture2D(colortex0, texcoord);
				#ifdef POM
					gl_FragDepth = gl_FragCoord.z - 0.1; // @dead
				#endif
					gl_FragData[0] = c;
				}
				""").program("gbuffers_terrain").alpha(AlphaTest.CUTOUT).covering());

		all.add(vertex("vertex-depth-epilogue", """
				#version 120

				uniform mat4 gbufferModelViewInverse;
				uniform mat4 gbufferProjectionInverse;
				attribute vec4 mc_Entity;
				varying vec2 texcoord;
				varying vec4 glcolor;

				void main() {
					texcoord = gl_MultiTexCoord0.xy;
					glcolor = gl_Color;
					vec4 pos = gl_ModelViewMatrix * gl_Vertex;
					gl_Position = gl_ProjectionMatrix * pos;
					gl_Position.z *= 0.999;
					if (mc_Entity.x == 10.0) gl_Position.xy += 0.001;
				}
				""").program("gbuffers_basic"));

		all.add(fragment("outputs-lifted", """
				#version 330 core

				uniform sampler2D colortex0;
				in vec2 texcoord;

				layout(location = 2) out f16vec4 buf2;
				layout(location = 0) out vec4 Albedo;
				layout(location = 1) out uvec2 Packed;
				out vec4 Loose;
				layout(location = 3) out vec4 Skipped, Twin;
				#ifdef EXTRA
				layout(location = 4) out vec4 Extra; // @dead
				#endif

				void main() {
					buf2 = f16vec4(1.0);
					Packed = uvec2(1u, 2u);
					Albedo = texture(colortex0, texcoord);
				}
				""").program("composite"));

		all.add(fragment("alpha-cutout", """
				#version 120

				uniform sampler2D texture;
				varying vec2 texcoord;
				varying vec4 glcolor;

				/* RENDERTARGETS: 0,1 */

				void main() {
					vec4 colour = texture2D(texture, texcoord) * glcolor;
					gl_FragData[1] = vec4(0.5);
					gl_FragData[0] = colour;
				}
				""").program("gbuffers_terrain").alpha(AlphaTest.CUTOUT).covering());

		all.add(fragment("alpha-self-declared", """
				#version 330 core

				uniform sampler2D gtexture;
				in vec2 texcoord;

				layout(location = 0) out vec4 outColor;
				layout(location = 1) out vec4 outNormal;

				void main() {
					outColor = texture(gtexture, texcoord);
					outNormal = vec4(0.5, 0.5, 1.0, 1.0);
				}
				""").program("gbuffers_terrain").alpha(AlphaTest.NON_ZERO).covering());

		all.add(fragment("coverage-full", """
				#version 120

				varying vec2 texcoord;
				uniform sampler2D colortex0;

				void main() {
					vec4 c = texture2D(colortex0, texcoord);
					gl_FragData[0] = c;
					gl_FragData[1] = c;
					gl_FragData[2] = c;
					gl_FragData[3] = c;
					gl_FragData[4] = c;
					gl_FragData[5] = c;
					gl_FragData[6] = c;
					gl_FragData[7] = c;
				}
				""").program("gbuffers_basic").covering());

		all.add(fragment("draw-buffers-last-wins", """
				#version 120

				varying vec2 texcoord;

				/* DRAWBUFFERS:01 */
				#ifdef ALT
				/* RENDERTARGETS: 3,4 */ // @dead
				#endif
				/* RENDERTARGETS: 0,2,11,4,5,6,7,8,9,10 */

				void main() {
					gl_FragData[0] = vec4(texcoord, 0.0, 1.0);
				}
				""").program("composite"));

		all.add(fragment("center-depth-smooth", """
				#version 120

				uniform sampler2D colortex0;
				uniform float centerDepthSmooth;
				uniform float near;
				uniform float far;
				varying vec2 texcoord;

				float focus() {
					return abs(texture2D(colortex0, texcoord).a - centerDepthSmooth);
				}

				void main() {
					gl_FragData[0] = vec4(focus() * (far - near) + centerDepthSmooth);
				}
				""").program("composite"));

		all.add(fragment("center-depth-list", """
				#version 120

				uniform float near, centerDepthSmooth, far;
				varying vec2 texcoord;

				void main() {
					gl_FragData[0] = vec4(centerDepthSmooth * (far - near));
				}
				""").program("composite"));

		all.add(fragment("center-depth-first", """
				#version 120

				uniform float centerDepthSmooth, far;
				varying vec2 texcoord;

				void main() {
					gl_FragData[0] = vec4(centerDepthSmooth * far);
				}
				""").program("composite"));

		all.add(fragment("center-depth-geometry", """
				#version 120

				uniform float centerDepthSmooth;
				varying vec2 texcoord;

				void main() {
					gl_FragData[0] = vec4(centerDepthSmooth);
				}
				""").program("gbuffers_basic"));

		// --- Constants, uniforms, storage ---------------------------------------------------------------

		all.add(fragment("const-and-precision", """
				#version 120
				precision highp float;
				precision mediump int;

				#define SUN_COLOUR vec3(1.0, 0.9, 0.8)
				#define SKY_COLOUR pow(vec3(0.3, 0.5, 0.9), vec3(2.2))
				#define ALIAS SUN_COLOUR
				#define FROM_SRGB(c) pow(c, vec3(2.2))

				uniform vec3 fogColor;
				uniform mat4 gbufferProjection;

				const float PI = 3.14159265;
				const vec3 up = vec3(0.0, 1.0, 0.0);
				const vec3 dir = normalize(vec3(1.0, 2.0, 3.0));
				const vec3 sun = SUN_COLOUR;
				const vec3 sky = SKY_COLOUR;
				const vec3 aliased = ALIAS;
				const vec3 linear = FROM_SRGB(vec3(0.5));
				const mat2 rot = mat2(cos(0.5), -sin(0.5), sin(0.5), cos(0.5));
				const highp float precise = 1.0;
				const int STEPS = 4;
				const vec3 fromUniform = fogColor * 2.0;
				float table[STEPS];

				mediump vec3 shade(highp vec3 n, const in vec3 l, lowp float k) {
					return n * max(dot(n, l), 0.0) * k;
				}

				void main() {
					gl_FragColor = vec4(shade(dir, up, PI), 1.0);
				}
				"""));

		all.add(fragment("uniform-lifting", """
				#version 120

				uniform float frameTimeCounter;
				uniform int frameCounter, isEyeInWater, entityId;
				uniform vec3 cameraPosition = vec3(0.0);
				uniform vec2 resolution[2];
				uniform mat4 gbufferModelView, gbufferProjection;
				uniform ivec2 atlasSize;
				uniform sampler2D colortex0, colortex1;
				uniform float conflict;
				uniform int conflict;
				#ifdef HAS_FOG
				uniform float fogDensity; // @dead
				#else
				float fogDensity = 0.5;
				#endif
				uniform vec4 entityColor;
				uniform float
					multiline,
					second;
				varying vec2 texcoord;

				void main() {
					vec4 c = texture2D(colortex0, texcoord) + texture2D(colortex1, texcoord);
					float f = frameTimeCounter + float(frameCounter + isEyeInWater + entityId) + multiline + second;
					gl_FragColor = c * f * fogDensity + entityColor + vec4(cameraPosition, float(atlasSize.x));
				}
				""").program("gbuffers_entities"));

		all.add(compute("compute-images", """
				#version 430

				layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;
				const ivec3 workGroups = ivec3(32, 32, 1);

				layout(rgba16f) uniform image2D colorimg1;
				layout(r32ui) uniform readonly uimage3D voxels;
				layout(rgba8) uniform writeonly image2D colorimg0;
				uniform coherent restrict volatile iimage2D counters;
				uniform sampler2D colortex0;
				uniform vec2 viewSize;

				void main() {
					ivec2 texel = ivec2(gl_GlobalInvocationID.xy);
					vec4 c = imageLoad(colorimg1, texel);
					imageStore(colorimg0, texel, c * texture(colortex0, vec2(texel) / viewSize));
				}
				"""));

		all.add(compute("storage-blocks", """
				#version 430

				layout(local_size_x = 8) in;

				layout(std430, binding = 0) buffer blockDataBuffer {
					int blockData[];
				} bufferObject;

				layout(std430, binding = 3) readonly buffer Lights { vec4 lights[]; };
				buffer Unbound { float scratch; };
				#ifdef OTHER
				buffer Dead { int dead; }; // @dead
				#endif

				void main() {
					bufferObject.blockData[gl_GlobalInvocationID.x] = int(lights[0].x + scratch);
				}
				"""));

		// --- Attributes and the meshes --------------------------------------------------------------------

		all.add(vertex("terrain-vertex", """
				#version 120

				uniform mat4 gbufferModelViewInverse;
				uniform vec3 cameraPosition;
				uniform vec3 chunkOffset;
				attribute vec4 mc_Entity;
				attribute vec4 mc_midTexCoord;
				attribute vec4 at_tangent;
				attribute vec3 at_midBlock;
				varying vec2 texcoord;
				varying vec2 lmcoord;
				varying vec4 glcolor;
				varying vec3 normal;
				varying float blockId;

				void main() {
					vec4 position = gl_ModelViewMatrix * gl_Vertex;
					vec3 world = (gbufferModelViewInverse * position).xyz + cameraPosition;
					texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
					lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
					glcolor = gl_Color;
					normal = gl_NormalMatrix * gl_Normal;
					blockId = mc_Entity.x + at_midBlock.y + mc_midTexCoord.x + at_tangent.w + chunkOffset.x;
					gl_Position = gl_ProjectionMatrix * position;
				}
				""").on(VertexInputs.TERRAIN).program("gbuffers_terrain"));

		all.add(vertex("synthesized-attributes", """
				#version 330 core

				in vec3 vaPosition;
				in vec4 mc_Entity;
				in vec4 at_tangent;
				in vec4 notAnAttribute;
				attribute vec4 mc_midTexCoord;
				attribute int blockEntityId;
				out vec4 tangent;

				void main() {
					tangent = at_tangent + mc_Entity + mc_midTexCoord + vec4(float(blockEntityId));
					gl_Position = vec4(vaPosition, 1.0);
				}
				""").on(VertexInputs.FULLSCREEN).program("composite"));

		all.add(vertex("entity-vertex", """
				#version 120

				uniform int entityId;
				uniform vec4 entityColor;
				attribute vec4 mc_Entity;
				varying vec2 texcoord;
				varying vec4 glcolor;

				void main() {
					texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
					glcolor = gl_Color * (entityId > 0 ? 1.0 : 0.5);
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex + entityColor.a * 0.0;
				}
				""").on(VertexInputs.ENTITY).program("gbuffers_entities"));

		all.add(vertex("game-transforms-glint", """
				#version 120

				uniform mat4 modelViewMatrix;
				uniform mat4 projectionMatrix;
				varying vec2 texcoord;
				varying vec4 glcolor;

				void main() {
					int unit = 1;
					texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
					vec2 other = (gl_TextureMatrix[unit] * gl_MultiTexCoord0).xy;
					vec4 a = gl_ModelViewMatrix * gl_Vertex;
					vec4 b = modelViewMatrix * gl_Vertex;
					vec4 c = gl_ModelViewProjectionMatrix * gl_Vertex;
					gl_Position = ftransform() + projectionMatrix * a + b + c + vec4(other, 0.0, 0.0);
					glcolor = gl_Color;
				}
				""").on(VertexInputs.GLINT).program("gbuffers_armor_glint"));

		all.add(vertex("game-transforms-entities", """
				#version 120

				varying vec2 texcoord;

				void main() {
					texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex + ftransform();
				}
				""").on(VertexInputs.ENTITY).program("gbuffers_entities"));

		all.add(vertex("distant-horizons-vertex", """
				#version 120

				attribute vec4 mc_Entity;
				varying vec2 texcoord;
				varying vec2 lmcoord;
				varying vec4 glcolor;

				void main() {
					texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
					lmcoord = (gl_TextureMatrix[2] * gl_MultiTexCoord2).xy + (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
					glcolor = gl_Color;
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				""").on(VertexInputs.DISTANT).program("dh_terrain"));

		all.add(vertex("lines-vertex", """
				#version 120

				uniform float viewWidth;
				varying vec4 glcolor;

				void main() {
					glcolor = gl_Color;
					gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
				}
				""").on(VertexInputs.LINES).program("gbuffers_line"));

		all.add(vertex("sky-vertex", """
				#version 120

				uniform vec3 skyColor;
				varying vec4 starColor;

				void main() {
					starColor = gl_Color * vec4(skyColor, 1.0);
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				""").on(VertexInputs.SKY).program("gbuffers_skybasic"));

		all.add(fragment("macro-continuation", """
				#version 120

				uniform sampler2D texture;
				uniform sampler2D colortex0;
				varying vec2 texcoord;

				#define SAMPLE_BOTH(uv) \\
					texture2D(texture, uv) * \\
					texture2D(colortex0, uv)
				#define WAVE(t) \\
					sin(t) + cos(t)

				void main() {
					gl_FragData[0] = SAMPLE_BOTH(texcoord) * WAVE(texcoord.x);
				}
				""").program("composite"));

		// The parameters of a function-like macro are told from the names they shadow, and the macro's
		// whole body is read with them: a parameter spelled like a reserved word must not be renamed.
		all.add(fragment("macro-parameters-reserved", """
				#version 120

				uniform sampler2D texture;
				varying vec2 uv;

				#define CALL(texture, uv) texture(uv)
				#define MIX(sampler, image) mix(sampler, image, 0.5)
				#define LOOK(tex) texture2D(tex, uv)

				void main() {
					vec4 a = LOOK(texture);
					vec4 b = MIX(a, vec4(1.0));
					gl_FragColor = b + CALL(sin, uv.x);
				}
				"""));

		// The same macro after an interface block, which puts tokens in ahead of it.
		all.add(fragment("macro-parameters-after-block", """
				#version 330 core

				in Data {
					vec3 normal;
					vec2 uv;
				} DataIn;

				uniform sampler2D texture;

				#define CALL(texture, uv) texture(uv)
				#define LOOK(sampler) texture2D(sampler, DataIn.uv)

				layout(location = 0) out vec4 fragColor;

				void main() {
					fragColor = LOOK(texture) + CALL(cos, DataIn.normal.x);
				}
				"""));

		// A custom image the pack shipped as floats and this program reads as unsigned integers: the
		// declaration is renamed to the typed view of it, and so is every mention.
		all.add(compute("custom-image-view", """
				#version 430

				layout(local_size_x = 8, local_size_y = 8) in;

				uniform uimage3D imgVoxels;
				uniform sampler3D samplerVoxels;
				uniform image2D imgPlain;
				#define VOXELS imgVoxels

				void main() {
					ivec3 at = ivec3(gl_GlobalInvocationID);
					imageStore(VOXELS, at, uvec4(1u));
					imageStore(imgPlain, at.xy, vec4(texelFetch(samplerVoxels, at, 0)));
				}
				""").images(customImages()));

		// --- Volumes ---------------------------------------------------------------------------------------

		all.add(fragment("volume-flattened", """
				#version 130

				uniform sampler3D colortex6;
				uniform sampler3D colortex7;
				uniform sampler2D colortex0;
				#define NOISE_LUT colortex6
				varying vec2 texcoord;

				void main() {
					vec3 uvw = vec3(texcoord, 0.25);
					float a = texture(colortex6, uvw).r;
					float b = textureLod(NOISE_LUT, uvw, 0.0).g;
					float c = texture3D(colortex7, uvw).b;
					gl_FragData[0] = vec4(a, b, c, texture2D(colortex0, texcoord).a);
				}
				""").program("composite").volumes(shippedVolume("colortex6", false)));

		all.add(fragment("volume-left-alone", """
				#version 130

				uniform sampler3D colortex6;
				varying vec2 texcoord;

				void main() {
					float a = texture(colortex6, vec3(texcoord, 0.25)).r;
					vec3 size = vec3(textureSize(colortex6, 0));
					gl_FragData[0] = vec4(a, size.xy, 1.0);
				}
				""").program("composite").volumes(shippedVolume("colortex6", true)));

		return List.copyOf(all);
	}

	/** A vertex stage that names most of what a mesh may answer for, whichever mesh it is drawn from. */
	private static final String SWEEP_VERTEX = """
			#version 120

			uniform vec3 cameraPosition;
			uniform int entityId;
			attribute vec4 mc_Entity;
			varying vec2 texcoord;
			varying vec2 lmcoord;
			varying vec4 glcolor;
			varying vec3 normal;

			void main() {
				texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
				lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
				glcolor = gl_Color * float(entityId + 1);
				normal = gl_NormalMatrix * gl_Normal;
				gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex + vec4(mc_Entity.x * cameraPosition.x);
			}
			""";

	/** The fragment stage of the same program, which reads the two engine varyings the mesh may carry. */
	private static final String SWEEP_FRAGMENT = """
			#version 120

			uniform sampler2D texture;
			uniform sampler2D lightmap;
			uniform vec4 entityColor;
			varying vec2 texcoord;
			varying vec2 lmcoord;
			varying vec4 glcolor;
			varying vec3 normal;

			void main() {
				vec4 c = texture2D(texture, texcoord) * glcolor * texture2D(lightmap, lmcoord);
				c.rgb = mix(c.rgb, entityColor.rgb, entityColor.a) * normal.y;
				gl_FragData[0] = c;
			}
			""";

	/** The pass each mesh is drawn for, as far as the passes that tell one mesh from another go. */
	private static String passOf(VertexInputs mesh) {
		return switch (mesh) {
			case FULLSCREEN -> "composite";
			case WORLD -> "";
			case TERRAIN, TERRAIN_SEPARATE_AO -> "gbuffers_terrain";
			case ENTITY, ENTITY_FULLBRIGHT -> "gbuffers_entities";
			case GLINT -> "gbuffers_armor_glint";
			case CRUMBLING -> "gbuffers_damagedblock";
			case MOVING_BLOCK -> "gbuffers_terrain";
			case LINES -> "gbuffers_line";
			case GLYPH -> "gbuffers_textured";
			case PARTICLE -> "gbuffers_textured";
			case SKY -> "gbuffers_skybasic";
			case DISTANT -> "dh_terrain";
			case CLOUDS -> "gbuffers_clouds";
		};
	}

	/**
	 * A line per mesh and stage: how long the translation is and the head of its SHA-256. The heads are
	 * the sodium, entity, sky and distant prologues, which are other classes' text and are pinned here
	 * for what the translator does around them and not read line by line.
	 */
	static String meshSweep() {
		StringBuilder out = new StringBuilder();
		for (VertexInputs mesh : VertexInputs.values()) {
			for (ProgramStage stage : List.of(ProgramStage.VERTEX, ProgramStage.FRAGMENT)) {
				Single one = new Single("sweep", stage,
						stage == ProgramStage.VERTEX ? SWEEP_VERTEX : SWEEP_FRAGMENT, Setup.DEFAULT)
						.on(mesh).program(passOf(mesh));
				String text = run(one);
				out.append(mesh).append(' ').append(stage).append(' ').append(text.length()).append(' ')
						.append(digest(text)).append('\n');
			}
		}

		return out.toString();
	}

	/**
	 * The line that runs the pack's main and then the alpha test, for each comparison the test can be:
	 * what {@link AlphaTest.Function} says in GLSL is written here and nowhere else.
	 */
	static String alphaSweep() {
		StringBuilder out = new StringBuilder();
		for (AlphaTest.Function function : AlphaTest.Function.values()) {
			Single one = fragment("alpha", "#version 120\nvarying vec2 uv;\nvoid main() { gl_FragData[0] = vec4(uv, 0.0, 1.0); }\n")
					.program("gbuffers_terrain").alpha(new AlphaTest(function, 0.25F));
			String mainLine = run(one).lines().filter(line -> line.startsWith("void main()")).findFirst()
					.orElse("(no wrapper)");
			out.append(function).append(": ").append(mainLine).append('\n');
		}

		return out.toString();
	}

	/** The first sixteen hex digits of the SHA-256 of a text, which is all a pin needs to say. */
	static String digest(String text) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (int at = 0; at < 8; at++) {
				hex.append(String.format(Locale.ROOT, "%02x", hash[at]));
			}

			return hex.toString();
		} catch (NoSuchAlgorithmException absent) {
			throw new IllegalStateException(absent);
		}
	}

	/** How many recombinations {@link #recombination} is asked for by the test that pins them. */
	static final int RECOMBINATIONS = 400;

	/**
	 * The blank line separated pieces of every stage the corpus holds, by stage. Each piece is a run of
	 * whole declarations or one function, so a text built out of them is broken in the ways real packs
	 * are (two mains, a name declared twice, a varying nobody writes) and not in the ways a random
	 * cut of characters would be.
	 */
	private static Map<ProgramStage, List<String>> chunks() {
		Map<ProgramStage, List<String>> found = new LinkedHashMap<>();
		for (Single one : everySingle()) {
			found.computeIfAbsent(one.stage(), stage -> new ArrayList<>()).addAll(pieces(one.source()));
		}

		for (Pair pair : everyPair()) {
			found.get(ProgramStage.VERTEX).addAll(pieces(pair.vertex()));
			found.get(ProgramStage.FRAGMENT).addAll(pieces(pair.fragment()));
		}

		return found;
	}

	/**
	 * The pieces of one source that a recombination may draw. Never one with a function-like macro in
	 * it, and never a {@code #version} line: how the parameters of a macro and how a version line are
	 * read are what the fenced goldens are about, and a text that carried them at random would put a
	 * change to either into the digest of a recombination instead of into the golden that names it.
	 */
	private static List<String> pieces(String source) {
		List<String> kept = new ArrayList<>();
		for (String piece : source.split("\n\n", -1)) {
			String bare = piece.lines().filter(line -> !line.startsWith("#version")).collect(Collectors.joining("\n"));
			if (!FUNCTION_LIKE_MACRO.matcher(bare).find() && !bare.isBlank()) {
				kept.add(bare);
			}
		}

		return kept;
	}

	/** A {@code #define} whose name is followed at once by an opening parenthesis. */
	private static final Pattern FUNCTION_LIKE_MACRO = Pattern.compile("(?m)^\\s*#\\s*define\\s+\\w+\\(");

	/**
	 * The {@code index}th text of a fixed pseudo-random series, each built out of three to eight
	 * pieces of the corpus in a random order under the setup of a random case of the same stage.
	 * Only the vertex and fragment stages are drawn: a compute unit has none of the pairing.
	 */
	static Single recombination(int index) {
		Map<ProgramStage, List<String>> chunks = chunks();
		Random random = new Random(scramble(0x5EED0000L + index));
		ProgramStage stage = random.nextBoolean() ? ProgramStage.VERTEX : ProgramStage.FRAGMENT;
		List<String> pieces = chunks.get(stage);
		int count = 3 + random.nextInt(6);
		StringBuilder source = new StringBuilder();
		for (int piece = 0; piece < count; piece++) {
			source.append(pieces.get(random.nextInt(pieces.size())).stripTrailing()).append("\n\n");
		}

		List<Single> donors = everySingle().stream().filter(one -> one.stage() == stage).toList();
		Setup donor = donors.get(random.nextInt(donors.size())).setup();
		Setup setup = Setup.DEFAULT.inputs(donor.inputs()).program(donor.program())
				.alphaTest(donor.alphaTest()).volumes(donor.volumes());

		return new Single("recombination-" + index, stage, source.toString(),
				donor.coverage() ? setup.covering() : setup);
	}

	/**
	 * A seed that has nothing in common with the one next to it: the first draws of {@link Random}
	 * are close for close seeds, which would make every text of a series begin alike.
	 */
	private static long scramble(long seed) {
		long z = seed + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;

		return z ^ (z >>> 31);
	}

	/**
	 * What the {@code index}th recombination translates to, as one line: its length and the head of its
	 * SHA-256, or the name of what was thrown, which is as much a part of the contract as the text.
	 */
	static String recombinationLine(int index) {
		Single one = recombination(index);
		try {
			String text = run(one);

			return index + " " + one.stage() + " " + text.length() + " " + digest(text);
		} catch (RuntimeException failure) {
			return index + " " + one.stage() + " threw " + failure.getClass().getName();
		}
	}

	/** The programs of two stages, except the fenced ones. */
	static List<Pair> pairs() {
		return everyPair().stream().filter(pair -> !FENCED.contains(pair.name())).toList();
	}

	/** The programs of two stages that depend on the reading of a version line. */
	static List<Pair> fencedPairs() {
		return everyPair().stream().filter(pair -> FENCED.contains(pair.name())).toList();
	}

	/** Programs of two stages, whose halves only mean something together, fenced or not. */
	static List<Pair> everyPair() {
		List<Pair> all = new ArrayList<>();

		all.add(new Pair("pair-varyings", """
				#version 120

				varying vec2 texcoord;
				varying vec4 glcolor;
				varying vec3 kept;
				varying vec3 withheld;
				varying float fog;

				void main() {
					texcoord = gl_MultiTexCoord0.xy;
					glcolor = gl_Color;
					kept = gl_Normal;
					withheld = gl_Vertex.xyz;
					fog = gl_FogFragCoord;
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				""", """
				#version 120

				uniform sampler2D texture;
				varying vec2 texcoord;
				varying vec4 glcolor;
				varying vec3 kept;
				varying vec3 unread;
				varying vec4 owed;
				flat varying int owedFlat;
				varying float fog;

				void main() {
					vec4 c = texture2D(texture, texcoord) * glcolor + vec4(kept + owed.xyz, fog);
					gl_FragData[0] = c + float(owedFlat);
				}
				""", VertexInputs.WORLD, "gbuffers_textured"));

		all.add(new Pair("pair-entity-overlay", """
				#version 120

				uniform int entityId;
				varying vec2 texcoord;
				varying vec4 glcolor;

				void main() {
					texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
					glcolor = gl_Color;
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				""", """
				#version 120

				uniform sampler2D texture;
				uniform vec4 entityColor;
				uniform int entityId;
				uniform int blockEntityId;
				varying vec2 texcoord;
				varying vec4 glcolor;

				void main() {
					vec4 c = texture2D(texture, texcoord) * glcolor;
					c.rgb = mix(c.rgb, entityColor.rgb, entityColor.a);
					gl_FragData[0] = c * float(entityId + blockEntityId);
				}
				""", VertexInputs.ENTITY, "gbuffers_entities"));

		all.add(new Pair("pair-shared-block", """
				#version 120

				uniform mat4 gbufferModelViewInverse;
				uniform vec3 sunPosition;
				varying vec3 sunVec;
				varying vec2 texcoord;

				void main() {
					texcoord = gl_MultiTexCoord0.xy;
					sunVec = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
					gl_Position = ftransform();
				}
				""", """
				#version 120

				uniform vec3 sunVec;
				uniform float frameTimeCounter;
				uniform sampler2D colortex0;
				varying vec2 texcoord;

				void main() {
					gl_FragData[0] = texture2D(colortex0, texcoord) * dot(sunVec, vec3(0.0, 1.0, 0.0)) * frameTimeCounter;
				}
				""", VertexInputs.FULLSCREEN, "composite"));

		all.add(new Pair("pair-splits", """
				#version 120

				struct Harmonics {
					vec3 sky;
					float amount;
				};

				varying mat3 tbn;
				varying Harmonics harmonics;
				varying vec3 coefficients[3];
				varying vec2 texcoord;

				void main() {
					vec3 t = normalize(gl_NormalMatrix * gl_Normal);
					tbn = mat3(t, cross(t, vec3(0.0, 1.0, 0.0)), vec3(0.0, 1.0, 0.0));
					harmonics = Harmonics(vec3(0.2, 0.4, 0.8), 0.5);
					coefficients[0] = t;
					coefficients[1] = t * 2.0;
					coefficients[2] = t * 3.0;
					texcoord = gl_MultiTexCoord0.xy;
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				""", """
				#version 120

				struct Harmonics {
					vec3 sky;
					float amount;
				};

				uniform sampler2D texture;
				varying mat3 tbn;
				varying Harmonics harmonics;
				varying vec3 coefficients[3];
				varying vec2 texcoord;

				void main() {
					vec3 n = tbn * vec3(texture2D(texture, texcoord).xy, 1.0);
					gl_FragData[0] = vec4(n * harmonics.amount + harmonics.sky + coefficients[1] + coefficients[2], 1.0);
				}
				""", VertexInputs.WORLD, "gbuffers_terrain"));

		all.add(new Pair("pair-alias-storage", """
				#version 120

				#ifdef FSH
				#define in_out in // @dead
				#else
				#define in_out out
				#endif

				in_out mat3 tbn;
				in_out vec2 uv;

				void main() {
					uv = gl_MultiTexCoord0.xy;
					tbn = mat3(1.0);
					gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
				}
				""", """
				#version 120

				#define FSH
				#ifdef FSH
				#define in_out in
				#else
				#define in_out out // @dead
				#endif

				uniform sampler2D texture;
				in_out mat3 tbn;
				in_out vec2 uv;

				void main() {
					gl_FragData[0] = vec4(tbn * vec3(texture2D(texture, uv).rg, 0.0), 1.0);
				}
				""", VertexInputs.WORLD, "gbuffers_water"));

		all.add(new Pair("pair-modern-interface", """
				#version 330 core

				in vec3 vaPosition;
				out vec2 uv;
				flat out int id;
				out vec3 dropped;

				void main() {
					uv = vaPosition.xy;
					id = 4;
					dropped = vaPosition;
					gl_Position = vec4(vaPosition, 1.0);
				}
				""", """
				#version 330 core

				in vec2 uv;
				flat in int id;
				in vec3 missing;
				layout(location = 0) out vec4 colour;

				void main() {
					colour = vec4(uv, float(id), 1.0) + vec4(missing, 0.0);
				}
				""", VertexInputs.FULLSCREEN, "composite"));

		return List.copyOf(all);
	}
}
