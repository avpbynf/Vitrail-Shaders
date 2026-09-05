package dev.vitrail.pack.model;

import dev.vitrail.pack.model.TargetFormat;

import java.util.Locale;
import java.util.Optional;

/**
 * One texture a pack supplies with a file of its own, as it declared it and once the file behind
 * it has been found.
 * <p>
 * Two families of directive land here and they do different things, which is why the stage is
 * carried rather than folded away. {@code customTexture.NAME} brings a name that means nothing to
 * anybody else, and every program declaring it is bound to that file. {@code texture.STAGE.NAME}
 * brings no name at all: it REPLACES what an existing one is bound to, and only in the programs
 * of that stage, so the same {@code colortex3} is a colour target in one half of the frame and a
 * lookup table in the other.
 *
 * @param path  where the file sits, as the pack wrote it. A leading slash means relative to
 *              {@code shaders/} and is not part of the name. A colon in it means the pack named a
 *              resource of the game instead, and then there is no file of the pack behind this at
 *              all; see {@link #gameResource()}
 * @param raw   empty for an image the loader decodes, present for a blob uploaded as written
 * @param blur  whether the sampler filters linearly. A raw blob defaults to true and a PNG to
 *              false, which is OptiFine's rule and not a taste; a {@code .mcmeta} beside the file
 *              overrides it
 * @param clamp whether the sampler clamps rather than repeats, under the same defaults
 */
public record PackTexture(String sampler, Optional<TextureStage> stage, String path,
		Optional<Raw> raw, boolean blur, boolean clamp) {

	/** The shapes a raw blob can be given, spelled as a pack spells them. */
	public enum Shape { TEXTURE_1D, TEXTURE_2D, TEXTURE_3D, TEXTURE_RECTANGLE }

	/**
	 * What a blob is, since nothing in it says so.
	 *
	 * @param sizeY zero for a 1D texture, {@code sizeZ} zero for anything but a 3D one, which is
	 *              how the format writes them and what {@link #bytes()} allows for
	 */
	public record Raw(Shape shape, TargetFormat.Resolution internalFormat, int sizeX, int sizeY,
			int sizeZ, PixelFormat pixelFormat, PixelType pixelType) {

		/**
		 * How many bytes the file has to hold for the declaration to be true.
		 * <p>
		 * This is the only thing that tells a whole blob from a truncated one, and nothing else
		 * ever will: a short file uploads without a word and reads as noise, which is precisely
		 * what most of these files hold anyway.
		 */
		public long bytes() {
			return (long) this.sizeX * Math.max(this.sizeY, 1) * Math.max(this.sizeZ, 1)
					* this.pixelType.bytesPerTexel(this.pixelFormat);
		}
	}

	/** Whether the file is an image to decode rather than a blob to upload as written. */
	public boolean png() {
		return this.raw.isEmpty();
	}

	/**
	 * Whether the pack named a texture the GAME owns rather than a file of its own, in which case
	 * the path is an identifier the game's resource manager answers for and nothing here opens it.
	 * <p>
	 * The sign is a colon in the word, which is Iris's rule rather than a guess
	 * ({@code ShaderPack.readTexture}): a path of the pack is written relative to {@code shaders/}
	 * and never carries one.
	 */
	public boolean gameResource() {
		return gameResource(this.path);
	}

	public static boolean gameResource(String path) {
		return path.indexOf(':') >= 0;
	}

	/** One line for the log, saying which name is being moved and where it now reads from. */
	public String describe() {
		return this.stage.map(stage -> stage.name().toLowerCase(Locale.ROOT) + " " + this.sampler
						+ " overridden by ")
				.orElse(this.sampler + " supplied by ") + this.path
				+ this.raw.map(raw -> " (" + raw.shape() + " " + raw.internalFormat().declared() + " "
						+ raw.sizeX() + "x" + raw.sizeY() + "x" + raw.sizeZ() + ")").orElse("");
	}
}
