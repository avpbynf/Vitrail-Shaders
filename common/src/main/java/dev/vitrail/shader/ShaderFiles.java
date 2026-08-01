package dev.vitrail.shader;

import dev.vitrail.Vitrail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * On-disk layout of the shader sources. Nothing is compiled from the jar: a starting pair
 * of files is written out the first time the game runs, and from then on the engine only
 * ever compiles what it reads back from disk. Editing a file and restarting is enough to
 * change what ends up on screen.
 */
public final class ShaderFiles {

	public static final String DIRECTORY_NAME = "vitrail";
	public static final String VERTEX_FILE = "overlay.vsh";
	public static final String FRAGMENT_FILE = "overlay.fsh";

	private static final String DEFAULT_VERTEX = """
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

	private static final String DEFAULT_FRAGMENT = """
			#version 330

			// --- settings ---
			// Thickness of the solid frame drawn against the edges of the window, in pixels.
			// 0.0 draws no frame.
			#define BORDER_PIXELS 6.0
			// Colour of that frame, linear RGB in 0.0 to 1.0.
			#define BORDER_COLOUR vec3(1.0, 0.0, 1.0)
			// Opacity of the tint laid over the rest of the screen, 0.0 to 1.0.
			#define TINT_OPACITY 0.15
			// --- end of settings ---

			// The block the game fills in for its own shaders. ScreenSize is the size of the
			// render target in pixels, which is what makes the frame below resolution aware.
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
				float edgeDistance = min(min(pixel.x, ScreenSize.x - pixel.x),
						min(pixel.y, ScreenSize.y - pixel.y));

				if (edgeDistance < BORDER_PIXELS) {
					fragColor = vec4(BORDER_COLOUR, 1.0);
				} else {
					fragColor = vec4(uv.x, 1.0 - uv.x, 0.65, TINT_OPACITY);
				}
			}
			""";

	private ShaderFiles() {
	}

	public static Path directory() {
		return Vitrail.platform().gameDirectory().resolve(DIRECTORY_NAME);
	}

	public static DiskShaderSource load() throws IOException {
		Path directory = directory();
		Files.createDirectories(directory);

		Path vertexFile = directory.resolve(VERTEX_FILE);
		Path fragmentFile = directory.resolve(FRAGMENT_FILE);
		String vertex = readOrCreate(vertexFile, DEFAULT_VERTEX);
		String fragment = readOrCreate(fragmentFile, DEFAULT_FRAGMENT);

		Vitrail.logger().info("Read {} ({} chars) and {} ({} chars) from {}",
				VERTEX_FILE, vertex.length(), FRAGMENT_FILE, fragment.length(), directory);

		return new DiskShaderSource(vertex, fragment);
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
}
