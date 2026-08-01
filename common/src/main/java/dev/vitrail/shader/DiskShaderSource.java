package dev.vitrail.shader;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

/**
 * The GLSL the engine hands to the backend, held in memory exactly as it was read from
 * disk. The backend calls this back while compiling a pipeline, which is what lets a file
 * outside the jar end up as SPIR-V without touching the resource pack machinery.
 */
public final class DiskShaderSource implements ShaderSource {

	/** Both stages live under one identifier; the stage is picked by {@link ShaderType}. */
	public static final Identifier SHADER_ID = Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "overlay");

	private final String vertex;
	private final String fragment;

	DiskShaderSource(String vertex, String fragment) {
		this.vertex = vertex;
		this.fragment = fragment;
	}

	@Override
	public String get(Identifier id, ShaderType type) {
		if (!SHADER_ID.equals(id)) {
			return null;
		}

		return type == ShaderType.FRAGMENT ? this.fragment : this.vertex;
	}
}
