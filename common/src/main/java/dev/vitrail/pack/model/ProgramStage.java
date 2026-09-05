package dev.vitrail.pack.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Which stage of the pipeline a file is, decided by its extension alone.
 * <p>
 * The extension is the whole rule, and that is what keeps a shared body from being mistaken
 * for an entry point. A pack is free to call a {@code .glsl} file {@code composite1.glsl}, and
 * several do; it is still an include and never a program.
 */
public enum ProgramStage {

	VERTEX("vsh"),
	FRAGMENT("fsh"),
	GEOMETRY("gsh"),
	COMPUTE("csh"),
	TESSELLATION_CONTROL("tcs"),
	TESSELLATION_EVALUATION("tes");

	private final String extension;

	ProgramStage(String extension) {
		this.extension = extension;
	}

	public String extension() {
		return this.extension;
	}

	public static Optional<ProgramStage> fromExtension(String extension) {
		String lower = extension.toLowerCase(Locale.ROOT);
		for (ProgramStage stage : values()) {
			if (stage.extension.equals(lower)) {
				return Optional.of(stage);
			}
		}

		return Optional.empty();
	}

	public static Optional<ProgramStage> ofFile(String fileName) {
		int dot = fileName.lastIndexOf('.');

		return dot < 0 ? Optional.empty() : fromExtension(fileName.substring(dot + 1));
	}
}
