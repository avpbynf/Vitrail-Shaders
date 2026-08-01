package dev.vitrail.shader;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * The GLSL the engine hands to the backend, held in memory exactly as it was read from
 * disk. The backend calls this back while compiling a pipeline, which is what lets files
 * outside the jar end up as SPIR-V without touching the resource pack machinery.
 * <p>
 * One identifier maps to one source per stage. The module cache is keyed on the identifier,
 * so two different sources filed under the same one would overwrite each other.
 */
public final class DiskShaderSource implements ShaderSource {

	private final Map<Identifier, String> vertexSources;
	private final Map<Identifier, String> fragmentSources;

	DiskShaderSource(Map<Identifier, String> vertexSources, Map<Identifier, String> fragmentSources) {
		this.vertexSources = Map.copyOf(vertexSources);
		this.fragmentSources = Map.copyOf(fragmentSources);
	}

	/**
	 * Returning null is how a missing unit is reported. The backend logs it and hands back
	 * a module that fails {@code isValid}, which is caught before anything is drawn.
	 */
	@Override
	public String get(Identifier id, ShaderType type) {
		return type == ShaderType.FRAGMENT ? this.fragmentSources.get(id) : this.vertexSources.get(id);
	}
}
