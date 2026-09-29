package dev.vitrail.glsl;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vitrail.glsl.GlslTranslatorCases.Pair;
import dev.vitrail.glsl.GlslTranslatorCases.Single;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Says which rewrite families the corpus of {@link GlslTranslatorCases} reaches, so that a family
 * cannot be lost from it unnoticed.
 * <p>
 * {@link GlslTranslatorGoldenTest} holds every byte of every case, and a corpus that stopped
 * exercising a rewrite would keep passing it. This is the other half: each family the translator
 * performs is named with one line its output carries only when the family fired, in the golden that
 * is about it, and every counter the translation reports has to be non-zero in at least one golden.
 * The families were listed from reading {@link GlslTranslator}, pass by pass in the order
 * {@code rewrite} runs them.
 */
class GlslTranslatorFamiliesTest {

	/** One family, the golden that shows it, and a line of that golden which only it writes. */
	private record Family(String name, String golden, String marker) {

		@Override
		public String toString() {
			return this.name;
		}
	}

	private static Family family(String name, String golden, String marker) {
		return new Family(name, golden, marker);
	}

	static Stream<Arguments> families() {
		return Stream.of(
				family("legacy attribute", "legacy-vertex", "in vec4 of_Vertex;"),
				family("legacy matrix", "legacy-vertex", "vec4 position = of_ModelViewMatrix * of_Vertex;"),
				family("normal matrix", "legacy-vertex", "normal = normalize(of_NormalMatrix * of_Normal);"),
				family("texture matrix array", "legacy-vertex", "texcoord = (of_TextureMatrix[0] * of_MultiTexCoord0).xy;"),
				family("fog coordinate", "legacy-vertex", "out float of_FogFragCoord;"),
				family("fog struct", "legacy-fragment", "of_FogFragCoord * of_Fog.density"),
				family("front colour", "legacy-vertex", "vec4 of_FrontColor;"),
				family("vertex id", "legacy-vertex", "gl_VertexIndex == 0"),
				family("varying out", "legacy-vertex", "out vec2 texcoord;"),
				family("varying in", "legacy-fragment", "in vec2 texcoord;"),
				family("attribute", "attribute-and-reserved-names", "in vec4 mc_Entity;"),
				family("gl_FragData", "legacy-fragment", "ofFragData2 = vec4(lmcoord, 0.0, 1.0);"),
				family("gl_FragData dynamic", "frag-fragdata-dynamic", "gl_FragData[slot] = colour;"),
				family("gl_FragColor", "frag-fragcolor", "ofFragData0 = texture(tex, uv) * tint;"),
				family("output order call", "legacy-fragment", "void main() { ofOrderOutputs();"),
				family("ftransform", "attribute-and-reserved-names", "gl_Position = (of_ModelViewProjectionMatrix * of_Vertex);"),
				family("reserved names", "attribute-and-reserved-names", "tangent = at_tangent + vec4(ofSampler, ofImage);"),
				family("pack macro shims left alone", "pack-macros-shim", "#define texture2D ofTexture"),
				family("macro parameter kept", "pack-macros-shim", "#define ROTATE(texture, angle) mat2(ofReducedCos(texture)"),
				family("macro line continuation", "macro-continuation", "ofReducedSin(t) + ofReducedCos(t)"),
				family("post 120 builtin defined by pack", "shadowed-builtins", "colour = of_fma(of_Vertex.xyz"),
				family("extension hoisted", "version-and-extensions", "#extension GL_EXT_gpu_shader4 : enable"),
				family("absent extension macro hidden", "version-and-extensions", "#ifdef OF_ABSENT_GL_NV_gpu_shader5"),
				family("core profile names", "core-profile-fullscreen", "#define vaPosition Position"),
				family("redefined macro settled", "redefined-macros", "#define TAPS 8"),
				family("gl_PerVertex dropped", "gl-pervertex-redeclared", "texcoord = of_MultiTexCoord0.xy;"),
				family("interface block out", "interface-block-out", "of_Data_normal = vec3(0.0, 1.0, 0.0);"),
				family("interface block in", "interface-block-in", "vec3 n = of_Data_normal * float(of_Data_id);"),
				family("interface block left whole", "interface-block-in", "in Unreadable {"),
				family("trig reduced", "trig-reduced", "ofReducedSin(p.x + t)"),
				family("trig driver", "trig-driver", "sin(p.x + t)"),
				family("trig declared by pack", "trig-declared-by-pack", "wave = vec2(sin(of_Vertex.x), ofReducedCos(of_Vertex.y));"),
				family("goldberg hash", "goldberg-reduced", "return ofHash(p);"),
				family("goldberg hash refused", "goldberg-reduced", "fract(ofReducedSin(dot(p, vec2(12.9898, 78.233 * frameTimeCounter)))"),
				family("pack builtins on moltenvk", "pack-builtins-moltenvk", "uint low = ofPackUnorm4x8(c);"),
				family("pack builtins on drivers", "pack-builtins-driver", "uint low = packUnorm4x8(c);"),
				family("texture2D family renamed", "lookups-renamed", "vec4 e = textureGrad(colortex0, uv, vec2(0.0), vec2(0.0));"),
				family("lookups pinned", "lookups-pinned", "vec4 f = textureLodOffset(colortex0, uv, 0.0, ivec2(1, 0));"),
				family("chained sampler unpinned", "pin-composite", "sum += texture(colortex1, uv + vec2(float(i) / viewWidth, 0.0), 2.0).rgb;"),
				family("sampler parameter proven", "pin-sampler-parameters", "return textureLod(img, uv, 0.0).rgb;"),
				family("sampler parameter refused", "pin-sampler-parameters", "unpinnedParameterLookups = 1"),
				family("geometry program pins targets only", "pin-geometry-program", "vec4 albedo = texture(ofTexture, texcoord);"),
				family("shadow2D wrapped", "shadow-hardware-road", "sum += vec4(textureLod(shadowtex0, pos, 0.0)).x;"),
				family("comparison on the sampler", "shadow-hardware-road", "uniform sampler2DShadow shadowtex0;"),
				family("comparison in arithmetic", "shadow-arithmetic-road", "return ofShadowCompare(tex, pos) + ofShadowCompare(tex, pos, 2.0)"),
				family("soft compare armed", "shadow-arithmetic-armed", "sum += ofShadowCompare(shadowtex1HW, pos);"),
				family("comparison in compute", "shadow-compute", "float lit = ofShadowCompare(shadowtex0,"),
				family("shadow chain keeps a written level", "shadow-chain-both", "s += textureLod(shadowtex1, pos.xy, 2.0).r;"),
				family("depth window read", "depth-window", "float z = (of_DepthConv.z * gl_FragCoord.z + of_DepthConv.w);"),
				family("depth window vector", "depth-window", "vec3 screen = vec3(gl_FragCoord.xy, of_DepthConv.z"),
				family("depth write", "depth-window", "gl_FragDepth = (of_DepthConv.z * ( linearDepth(z) + d) + of_DepthConv.w);"),
				family("clip depth epilogue", "vertex-depth-epilogue", "gl_Position.z = of_DepthConv.x * gl_Position.z"),
				family("const demoted", "const-and-precision", " vec3 dir = normalize(vec3(1.0, 2.0, 3.0));"),
				family("const kept", "const-and-precision", "const vec3 aliased = ALIAS;"),
				family("precision dropped", "const-and-precision", " vec3 shade( vec3 n, const in vec3 l,  float k) {"),
				family("output lifted", "outputs-lifted", "layout(location = 2) out vec4 buf2;"),
				family("output left standing", "outputs-lifted", "layout(location = 3) out vec4 Skipped, Twin;"),
				family("alpha test", "alpha-cutout", "if (!(ofFragData0.a > 0.5)) { discard; }"),
				family("alpha test refused", "alpha-self-declared", "void main() { ofOrderOutputs(); ofPackMain(); ofCoverage = gl_FragCoord.z; }"),
				family("coverage", "alpha-cutout", "layout(location = 2) out float ofCoverage;"),
				family("coverage refused", "coverage-full", "void ofOrderOutputs() { ofFragData0; ofFragData1; ofFragData2; ofFragData3; ofFragData4; ofFragData5; ofFragData6; ofFragData7; }"),
				family("draw buffers", "draw-buffers-last-wins", "[0, 2, 11, 4, 5, 6, 7, 8]"),
				family("center depth", "center-depth-smooth", "texture(ofCenterDepthSmooth, vec2(0.5)).r"),
				family("center depth in a list", "center-depth-list", "float near;"),
				family("center depth left in a geometry pass", "center-depth-geometry", "float centerDepthSmooth;"),
				family("uniform block", "uniform-lifting", "layout(std140) uniform OfGlobals {"),
				family("uniform conflict", "uniform-lifting", "conflictNames = [conflict]"),
				family("uniform in a dead branch", "uniform-lifting", "uniform float fogDensity; // @dead"),
				family("image qualifiers", "compute-images", "layout(rgba8) writeonly uniform image2D colorimg0;"),
				family("storage blocks", "storage-blocks", "StorageBlock[name=bufferObject, binding=0]"),
				family("custom image view", "custom-image-view", "layout(r32ui) uniform uimage3D ofCustomImageView_r32ui_imgVoxels;"),
				family("volume flattened", "volume-flattened", "float a = ofTexture3D_colortex6(ofPackTexture_colortex6, uvw).r;"),
				family("volume left alone", "volume-left-alone", "volumesLeftAlone = 1"),
				family("attribute synthesized", "synthesized-attributes", "vec4 mc_Entity = vec4(0.0);"),
				family("terrain prologue", "terrain-vertex", "void ofSodiumVertex()"),
				family("entity mesh", "entity-vertex", "entityId = int(EntityIds[0]);"),
				family("game texture matrix", "game-transforms-entities", "texcoord = (of_GameTextureMatrix * of_MultiTexCoord0).xy;"),
				family("game model view", "game-transforms-glint", "vec4 a = (of_CameraBob * of_GameModelView) * of_Vertex;"),
				family("game model view ftransform", "game-transforms-glint", "gl_Position = (of_ProjectionMatrix * (of_CameraBob * of_GameModelView) * of_Vertex)"),
				family("distant texture matrix", "distant-horizons-vertex", "texcoord = (mat4(1.0) * of_MultiTexCoord0).xy;"),
				family("distant prologue", "distant-horizons-vertex", "void main() { ofDistantVertex();"),
				family("lines wrapper", "lines-vertex", "of_WidenLine(gl_Position, of_LineEnd);"),
				family("sky prologue", "sky-vertex", "#define of_Color (Color * of_PassColour)"),
				family("glint colour", "game-transforms-glint", "#define of_Color vec4(1.0, 1.0, 1.0, of_GlintAlpha)"),
				family("varyings both ways", "pair-varyings", "varyings = [of_FogFragCoord]"),
				family("input owed by the stage before", "pair-varyings", "void main() { owed = vec4(0); owedFlat = int(0);"),
				family("output withheld from the stage after", "pair-varyings", " vec3 withheld;"),
				family("input nobody writes dropped", "pair-varyings", "unprovided = {owed=vec4, owedFlat=flat int}"),
				family("overlay colour made", "pair-entity-overlay", "vec4 ofOverlayTexel = texelFetch(ofOverlay, UV1, 0);"),
				family("shared block name moved", "pair-shared-block", "ofOwn_sunVec = normalize("),
				family("matrix varying split", "pair-splits", "of_vmat_tbn_0 = tbn[0];"),
				family("struct varying split", "pair-splits", "of_vstruct_harmonics_sky = harmonics.sky;"),
				family("array varying split", "pair-splits", "of_varr_coefficients_0 = coefficients[0];"),
				family("varying through a macro word", "pair-alias-storage", "out vec3 of_vmat_tbn_0;"))
				.map(Arguments::of);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("families")
	void theCorpusExercisesEveryFamily(Family family) throws IOException {
		String golden = GlslTranslatorGoldenTest.golden(family.golden());

		assertTrue(golden.contains(family.marker()),
				family.name() + ": " + family.golden() + " no longer carries " + family.marker());
	}

	@Test
	void everyCounterOfTheNotesMovesInSomeCase() throws IOException {
		Set<String> moved = new HashSet<>();
		List<String> names = new ArrayList<>();
		for (Single one : GlslTranslatorCases.everySingle()) {
			names.add(one.name());
		}

		for (Pair pair : GlslTranslatorCases.everyPair()) {
			names.add(pair.name());
		}

		for (String name : names) {
			boolean inNotes = false;
			for (String line : GlslTranslatorGoldenTest.golden(name).lines().toList()) {
				if (line.startsWith("--- ")) {
					inNotes = line.equals("--- notes ---");
				} else if (inNotes) {
					int equals = line.indexOf(" = ");
					String value = line.substring(equals + 3);
					if (!value.equals("0") && !value.equals("[]")) {
						moved.add(line.substring(0, equals));
					}
				}
			}
		}

		Map<String, Boolean> counters = new LinkedHashMap<>();
		for (RecordComponent component : TranslatedUnit.Notes.class.getRecordComponents()) {
			counters.put(component.getName(), moved.contains(component.getName()));
		}

		List<String> still = counters.entrySet().stream().filter(entry -> !entry.getValue())
				.map(Map.Entry::getKey).toList();
		assertTrue(still.isEmpty(), "counters no case of the corpus moves: " + still);
	}
}
