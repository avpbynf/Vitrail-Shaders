package dev.vitrail.pack.target;

import java.util.Set;

/**
 * Which sampler declarations this backend can build a pipeline for, decided on the type alone.
 * <p>
 * The rule belongs to the game and is not ours to bend. {@code GlslCompiler.addToBindGroup} walks
 * every sampler the SPIR-V reflection hands back and throws
 * {@code Sampled texture (X) must have type of SpvDim2D or SpvDimCube} for anything whose
 * dimensionality is neither of those two, so one {@code sampler3D} anywhere in a program stops that
 * program's whole pipeline from being built.
 * <p>
 * Two things measured in 26.2 make the declaration alone enough, and both are worth knowing before
 * anyone tries to be cleverer about it. {@code IntermediaryShaderModule.createFromSpirv} asks for
 * the module's whole resource list rather than the resources the entry point reaches, and the
 * compiler is set to optimisation level 0, so a sampler declared in a shared include and never read
 * is refused exactly like one sampled on every pixel. Asking whether the body uses it would buy
 * nothing, and it could not be answered from a pack's text anyway: brace depth is not countable
 * there, packs opening a brace in one branch of an {@code #if} and closing it in another.
 * <p>
 * The name is no evidence at all. Mellow writes {@code uniform sampler3D colortex6} in a shared
 * include, and a check made on the name would call that a colour target and hand it a real 2D view.
 */
public final class SamplerTypes {

	/** What a sampler type is spelled with, once the integer and unsigned prefixes are off. */
	private static final String SAMPLER = "sampler";

	/**
	 * Everything whose dimensionality is SpvDim2D or SpvDimCube, which is the whole of what the
	 * check looks at: the shadow, array and multisample spellings carry the same two dimensions and
	 * pass it. {@code 2DRect} is not one of them however it reads, it is SpvDimRect.
	 */
	private static final Set<String> BINDABLE = Set.of(
			"2D", "2DShadow", "2DArray", "2DArrayShadow", "2DMS", "2DMSArray",
			"Cube", "CubeShadow", "CubeArray", "CubeArrayShadow");

	private SamplerTypes() {
	}

	/** Whether a declaration of this type stops the program that carries it from being built. */
	public static boolean refused(String type) {
		String shape = shapeOf(type);

		return shape != null && !BINDABLE.contains(shape);
	}

	/**
	 * What follows the word sampler, {@code 3D} for {@code usampler3D}, or null when the type names
	 * no sampler. Storage images are a resource of their own and are not answered for here.
	 */
	public static String shapeOf(String type) {
		String name = type;
		if (name.startsWith("i") || name.startsWith("u")) {
			name = name.substring(1);
		}

		return name.startsWith(SAMPLER) ? name.substring(SAMPLER.length()) : null;
	}
}
