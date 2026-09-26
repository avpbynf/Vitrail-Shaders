package dev.vitrail.render;

import dev.vitrail.glsl.GeometryFold;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.Vitrail;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The geometry stage a pack ships between the two the engine builds every draw from.
 * <p>
 * <strong>The 26.3 half binds no geometry stage, on any device.</strong> On 26.2 the stage travelled
 * the game's compiler road beside the other two, through hooks on {@code GlslCompiler} and the
 * layout it built. 26.3 rewrote that road around a pipeline builder that names exactly a vertex
 * and a fragment stage, links them by location and writes a descriptor layout visible to those
 * two alone; carrying a third stage through it is its own piece of work and it is not done yet.
 * <p>
 * What is kept is the road a device without the {@code geometryShader} feature takes on 26.2,
 * which every Mac takes on either game: a stage that only hands each corner of a triangle on is
 * folded into the fragment stage after it, and any other program shipping a geometry stage is set
 * aside with a line naming it, rather than drawn with its middle stage missing. So iterationT's
 * terrain draws here as it does on a Mac, and its sun and moon stay with the game.
 */
public final class GeometryStage {

	/** Whether the device offered the feature, which this half reads only to say it in the log. */
	private static volatile boolean served;

	private GeometryStage() {
	}

	/** Answered once the device is created: whether it can run a geometry stage at all. */
	public static void serve(boolean answer) {
		served = answer;
	}

	/**
	 * Files a program's geometry stage where the full screen road would bind it. That road binds
	 * none on this game, so a program shipping one is set aside.
	 *
	 * @return false where the program ships a geometry stage and has to be set aside
	 */
	public static boolean note(RenderPipeline pipeline, String path, PackProgram.Loaded loaded) {
		if (loaded.program().stages().get(ProgramStage.GEOMETRY) == null) {
			return true;
		}

		Vitrail.logger().error("{} ships a geometry stage, which this engine does not bind on "
				+ "Minecraft 26.3 yet, so the program is set aside rather than drawn without it", path);

		return false;
	}

	/**
	 * The fragment stage a geometry program is to be compiled with: its own where it ships no
	 * geometry stage, the geometry stage folded into it where that stage only hands each corner on,
	 * and null where the program has to be set aside.
	 */
	public static @Nullable String fragment(RenderPipeline pipeline, String path,
			PackProgram.Loaded loaded) {
		String fragment = loaded.program().stages().get(ProgramStage.FRAGMENT).text();
		TranslatedUnit unit = loaded.program().stages().get(ProgramStage.GEOMETRY);
		if (unit == null) {
			return fragment;
		}

		GeometryFold.Result fold = fold(pipeline, path, unit.text(), fragment);
		if (!fold.folded()) {
			Vitrail.logger().error("{} ships a geometry stage, which this engine does not bind on "
					+ "Minecraft 26.3 yet{}, and the stage {}, so the program is set aside rather "
					+ "than drawn without it", path,
					served ? "" : " nor could this device run", fold.refusal());

			return null;
		}

		Vitrail.logger().info("{} ships a geometry stage; the stage only hands each corner on, so it "
				+ "is folded into the fragment stage", path);

		return fold.fragment();
	}

	/**
	 * Reads the geometry stage the way the compiler would, its defines applied, and folds it.
	 * The 26.2 road injected the pipeline's defines into the text; this game hands them to shaderc
	 * as macros, which is what is done here, so the text folded is the one either game compiles.
	 */
	private static GeometryFold.Result fold(RenderPipeline pipeline, String path, String geometry,
			String fragment) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		if (compiler == 0L) {
			return GeometryFold.Result.refused("could not be read, shaderc giving no compiler");
		}

		long options = Shaderc.shaderc_compile_options_initialize();
		ShaderDefines defines = pipeline.getShaderDefines();
		for (Map.Entry<String, String> macro : defines.values().entrySet()) {
			Shaderc.shaderc_compile_options_add_macro_definition(options, macro.getKey(),
					macro.getValue());
		}

		for (String flag : defines.flags()) {
			Shaderc.shaderc_compile_options_add_macro_definition(options, flag, "");
		}

		// On the heap rather than through the overloads taking text, which encode on the thread's
		// memory stack: a translated stage carries the pack's whole settings header and runs to tens
		// of kilobytes, against a stack of sixty-four.
		ByteBuffer source = MemoryUtil.memUTF8(geometry, false);
		ByteBuffer name = MemoryUtil.memUTF8(path);
		ByteBuffer entry = MemoryUtil.memUTF8("main");
		try {
			long result = Shaderc.shaderc_compile_into_preprocessed_text(compiler, source,
					Shaderc.shaderc_glsl_geometry_shader, name, entry, options);
			if (result == 0L) {
				return GeometryFold.Result.refused("could not be read, shaderc giving no result");
			}

			try {
				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
				if (Shaderc.shaderc_result_get_compilation_status(result)
						!= Shaderc.shaderc_compilation_status_success || bytes == null) {
					return GeometryFold.Result.refused("did not preprocess: "
							+ Shaderc.shaderc_result_get_error_message(result));
				}

				return GeometryFold.fold(StandardCharsets.UTF_8.decode(bytes).toString(), fragment);
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			MemoryUtil.memFree(entry);
			MemoryUtil.memFree(name);
			MemoryUtil.memFree(source);
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	/** Files a rebuilt variant beside its base. Nothing is filed on this game. */
	public static void noteBeside(RenderPipeline variant, RenderPipeline base) {
		// Nothing to carry: no geometry stage is bound, so no variant owes one.
	}

	/** Whether any program of the session filed a geometry stage, which none does on this game. */
	public static boolean noted() {
		return false;
	}

	/** Whether the calling thread is compiling a geometry stage, which none does on this game. */
	public static boolean compiling() {
		return false;
	}

	/** Called when the client shuts down. */
	public static void close() {
		// Holds nothing between loads.
	}
}
