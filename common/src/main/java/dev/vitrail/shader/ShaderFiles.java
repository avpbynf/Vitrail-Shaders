package dev.vitrail.shader;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk layout of the shader sources. Nothing is compiled from the jar: a starting set of
 * files is written out the first time the game runs, and from then on the engine only ever
 * compiles what it reads back from disk. Editing a file and restarting is enough to change
 * what ends up on screen.
 * <p>
 * The four units here are a hand-written stand-in for the program chain a pack will
 * eventually declare. One vertex stage is shared by all three passes, so the fragment
 * stages have to agree with it on varyings. They are paired by name, not by order, and the
 * two ways of getting it wrong are not equally kind: a name the vertex stage never outputs
 * is refused outright, while a fragment that leaves one out shifts the locations of the
 * ones after it and says nothing.
 */
public final class ShaderFiles {

	public static final String DIRECTORY_NAME = "vitrail";

	public static final Identifier SCREEN = shaderId("screen");
	public static final Identifier FIRST = shaderId("pass1");
	public static final Identifier SECOND = shaderId("pass2");
	public static final Identifier COMPOSE = shaderId("compose");

	private static final String SCREEN_VERTEX = """
			#version 330

			out vec2 texCoord;

			void main() {
				// One oversized triangle covering the screen. There is no vertex buffer:
				// the corner is derived from the vertex index alone.
				vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
				texCoord = corner;
				gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	private static final String FIRST_FRAGMENT = """
			#version 330

			// --- settings ---
			// Side of the square marks the two passes stamp into the image, in pixels.
			#define MARK_PIXELS 64.0
			// --- end of settings ---

			// The name of a sampler has to be spelled the same way here, in the bind group
			// layout the pipeline declares, and in the call that binds the texture. There is
			// deliberately no layout(binding) or layout(set): the game assigns those itself
			// while compiling, and rewrites them afterwards.
			uniform sampler2D VitrailSceneSampler;

			// The block the game fills in for its own shaders. ScreenSize is the size of the
			// target in pixels, which is what makes the marks below resolution aware.
			layout(std140) uniform Globals {
				ivec3 CameraBlockPos;
				vec3 CameraOffset;
				vec2 ScreenSize;
				float GlintAlpha;
				float GameTime;
				int MenuBlurRadius;
				int UseRgss;
			};

			in vec2 texCoord;

			out vec4 fragColor;

			void main() {
				vec2 uv = clamp(texCoord, 0.0, 1.0);
				vec2 pixel = uv * ScreenSize;
				vec4 scene = texture(VitrailSceneSampler, uv);

				// Pure magenta survives a round trip through an RGBA8 target untouched, so
				// the next pass can recognise it by an exact comparison.
				if (pixel.x < MARK_PIXELS && pixel.y < MARK_PIXELS) {
					fragColor = vec4(1.0, 0.0, 1.0, 1.0);
				} else {
					fragColor = vec4(scene.rgb, 1.0);
				}
			}
			""";

	private static final String SECOND_FRAGMENT = """
			#version 330

			// --- settings ---
			#define MARK_PIXELS 64.0
			// --- end of settings ---

			uniform sampler2D VitrailFirstSampler;

			layout(std140) uniform Globals {
				ivec3 CameraBlockPos;
				vec3 CameraOffset;
				vec2 ScreenSize;
				float GlintAlpha;
				float GameTime;
				int MenuBlurRadius;
				int UseRgss;
			};

			in vec2 texCoord;

			out vec4 fragColor;

			void main() {
				vec2 uv = clamp(texCoord, 0.0, 1.0);
				vec2 pixel = uv * ScreenSize;
				vec4 previous = texture(VitrailFirstSampler, uv);

				if (previous.r > 0.9 && previous.g < 0.1 && previous.b > 0.9) {
					// Green is written nowhere else in the chain. It can only appear if this
					// pass really read back what the previous one wrote, which is the whole
					// point of the exercise.
					fragColor = vec4(0.0, 1.0, 0.0, 1.0);
				} else if (pixel.x > ScreenSize.x - MARK_PIXELS && pixel.y < MARK_PIXELS) {
					// A mark of its own, in the opposite corner, so that a chain stopping one
					// pass short is visible rather than merely suspected.
					fragColor = vec4(0.0, 1.0, 1.0, 1.0);
				} else {
					fragColor = previous;
				}
			}
			""";

	private static final String COMPOSE_FRAGMENT = """
			#version 330

			uniform sampler2D VitrailSecondSampler;

			in vec2 texCoord;

			out vec4 fragColor;

			void main() {
				fragColor = vec4(texture(VitrailSecondSampler, clamp(texCoord, 0.0, 1.0)).rgb, 1.0);
			}
			""";

	private static final List<Unit> UNITS = List.of(
			new Unit(SCREEN, ShaderType.VERTEX, "screen.vsh", SCREEN_VERTEX),
			new Unit(FIRST, ShaderType.FRAGMENT, "pass1.fsh", FIRST_FRAGMENT),
			new Unit(SECOND, ShaderType.FRAGMENT, "pass2.fsh", SECOND_FRAGMENT),
			new Unit(COMPOSE, ShaderType.FRAGMENT, "compose.fsh", COMPOSE_FRAGMENT));

	private ShaderFiles() {
	}

	public static Path directory() {
		return Vitrail.platform().gameDirectory().resolve(DIRECTORY_NAME);
	}

	public static DiskShaderSource load() throws IOException {
		Path directory = directory();
		Files.createDirectories(directory);

		Map<Identifier, String> vertexSources = new HashMap<>();
		Map<Identifier, String> fragmentSources = new HashMap<>();
		List<String> read = new ArrayList<>();

		for (Unit unit : UNITS) {
			String source = readOrCreate(directory.resolve(unit.fileName()), unit.defaultSource());
			if (unit.type() == ShaderType.FRAGMENT) {
				fragmentSources.put(unit.id(), source);
			} else {
				vertexSources.put(unit.id(), source);
			}

			read.add(unit.fileName() + " (" + source.length() + " chars)");
		}

		Vitrail.logger().info("Read {} from {}", String.join(", ", read), directory);

		return new DiskShaderSource(vertexSources, fragmentSources);
	}

	private static String readOrCreate(Path file, String starting) throws IOException {
		if (!Files.isRegularFile(file)) {
			Files.writeString(file, starting, StandardCharsets.UTF_8);
			Vitrail.logger().info("Wrote a starting {}", file);
		}

		return stripByteOrderMark(Files.readString(file, StandardCharsets.UTF_8));
	}

	// A byte order mark left behind by a Windows editor pushes #version off the first line,
	// and the compiler then rejects the file with a message that points nowhere useful.
	private static String stripByteOrderMark(String source) {
		return !source.isEmpty() && source.charAt(0) == 0xFEFF ? source.substring(1) : source;
	}

	private static Identifier shaderId(String name) {
		return Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, name);
	}

	private record Unit(Identifier id, ShaderType type, String fileName, String defaultSource) {
	}
}
