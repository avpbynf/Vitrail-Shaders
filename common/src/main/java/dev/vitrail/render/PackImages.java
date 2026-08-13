package dev.vitrail.render;

import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.texture.PackTexture;
import dev.vitrail.pack.texture.PackTextures;
import dev.vitrail.pack.texture.TextureStage;
import dev.vitrail.pack.texture.VolumeAtlas;
import dev.vitrail.uniform.NoiseTexture;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The textures a pack ships as files of its own, read and decoded once, ready to be uploaded.
 * <p>
 * Nothing here touches the device: it opens files, decodes images and lays volumes out flat, all of
 * which happens while the client is still starting up. {@link ColorTargets} allocates and uploads
 * what comes out of it, and answers for it at draw time. Keeping the two apart is what lets the
 * reading be measured against the eight packs without starting a game, which is where the atlas was
 * proved right texel by texel.
 * <p>
 * One API of the game is named, in {@link #gameResource}, because a pack may name a texture the
 * game owns instead of shipping one; it is asked for defensively so that a measurement taken
 * outside a client loses that texture rather than the whole reading.
 * <p>
 * A declaration that cannot be honoured is, with one exception named in {@link PackTextures}, kept
 * and left with nothing behind it rather than dropped. {@link PackTextures#suppliedTo} still
 * carries the name, so the sampler reads one black pixel rather than falling back to the colour
 * target it shares a name with. That fall back is the failure worth a class of its own:
 * Complementary points {@code texture.deferred.colortex3} at a cloud and water lookup table, and
 * letting the name go back to colour target three would have its deferred read the scene as that
 * table, which is a picture nobody would question.
 */
final class PackImages {

	/**
	 * Past this the log says so once. Body Camera's lookup table alone reaches it: fifty nine
	 * kilobytes in the zip, four thousand and ninety six square once decoded, sixty four megabytes
	 * in memory. The ceiling on the file says nothing whatever about that.
	 */
	private static final long LOUD_BYTES = 32L * 1024L * 1024L;

	/**
	 * The most a resource of the game may hold before it is refused, which is the ceiling a file of
	 * the pack gets as well: a pack naming a file the player's resource packs ship has chosen it as
	 * directly as one it ships itself. What the decode costs past this is bounded by the dimensions
	 * in the header rather than by the length, and {@link NoiseTexture} does that half.
	 */
	private static final int MAX_RESOURCE_BYTES = 8 * 1024 * 1024;

	/**
	 * One texture of the pack, decoded into the bytes that will be uploaded.
	 *
	 * @param rgba  four bytes a texel, in the order the encoder wants them, which is the order
	 *              {@link NoiseTexture} already produces for the noise image
	 * @param shape one clause for the log, saying what was read and how big it came out
	 */
	@SuppressWarnings("ArrayRecordComponent")
	record Image(PackTexture texture, int width, int height, byte[] rgba, String shape) {

		long bytes() {
			return this.rgba.length;
		}
	}

	private final PackTextures declared;

	/** In the order the pack declared them, which is the order the log reads best in. */
	private final List<Image> images;

	private final Map<PackTexture, Image> byTexture;

	/** The volumes by the pack's own name, for a lookup that arrives under a forged one. */
	private final Map<String, Image> volumes;

	private final List<String> notes;

	private PackImages(PackTextures declared, List<Image> images, Map<PackTexture, Image> byTexture,
			Map<String, Image> volumes, List<String> notes) {
		this.declared = declared;
		this.images = List.copyOf(images);
		this.byTexture = Map.copyOf(byTexture);
		this.volumes = Map.copyOf(volumes);
		this.notes = List.copyOf(notes);
	}

	/** What a pack declaring nothing comes to, and what stands in before one is read. */
	static PackImages none() {
		return new PackImages(PackTextures.empty(), List.of(), Map.of(), Map.of(), List.of());
	}

	/**
	 * Reads every file the pack names, inside an opening the caller already holds.
	 *
	 * @param defines the settings as they stand, because two packs put a custom texture behind one
	 */
	static PackImages read(ShaderProperties properties, Map<String, String> defines,
			ShaderPackSource source) throws IOException {
		PackTextures declared = PackTextures.read(properties, defines, source);
		Map<String, VolumeAtlas> flat = declared.volumes();
		List<Image> images = new ArrayList<>();
		Map<PackTexture, Image> byTexture = new LinkedHashMap<>();
		Map<String, Image> volumes = new LinkedHashMap<>();
		List<String> notes = new ArrayList<>(declared.notes());

		declared.refused().forEach(refused -> notes.add("Dropped " + refused));

		for (PackTexture texture : declared.supplied()) {
			VolumeAtlas atlas = flat.get(texture.sampler());
			Image image = decode(texture, atlas, source, notes);
			if (image == null) {
				continue;
			}

			images.add(image);
			byTexture.put(texture, image);
			if (atlas != null) {
				// The FIRST declaration under that name, which is the one whose layout was printed
				// into every shader: PackTextures keeps the first too. A pack writing two files for
				// one name on two stages would otherwise have the last file spread over the first
				// one's tiles, and what comes out of that still looks like noise.
				if (volumes.putIfAbsent(texture.sampler(), image) != null) {
					notes.add(texture.path() + " is a second volume under the name "
							+ texture.sampler() + ", and the shaders read the first");
				}
			}
		}

		return new PackImages(declared, images, byTexture, volumes, notes);
	}

	/**
	 * One texture, decoded. Null when it cannot be, with the reason in the notes: a name nothing
	 * could be read for reads black rather than reading whatever that name used to mean.
	 */
	private static Image decode(PackTexture texture, VolumeAtlas atlas, ShaderPackSource source,
			List<String> notes) {
		if (texture.gameResource()) {
			return gameResource(texture, notes);
		}

		Optional<Path> file = source.file(texture.path());
		if (file.isEmpty()) {
			// Looked for once already when the directive was read, so reaching this means the pack
			// moved under us between the two openings.
			notes.add(texture.path() + " is no longer in the pack, so " + texture.sampler()
					+ " reads one black pixel");

			return null;
		}

		try {
			byte[] bytes = source.bytes(file.get());
			if (atlas != null) {
				PackTexture.Raw raw = texture.raw().orElseThrow();

				return new Image(texture, atlas.atlasWidth(), atlas.atlasHeight(),
						atlas.spread(bytes), raw.sizeX() + "x" + raw.sizeY() + "x" + raw.sizeZ()
								+ " laid out flat as " + atlas.atlasWidth() + "x"
								+ atlas.atlasHeight());
			}

			// Any other blob means what its own format says it means, and nothing here turns one of
			// those into an image. The corpus ships none; a pack that starts to has to be named
			// rather than served something plausible.
			if (!texture.png()) {
				notes.add(texture.path() + " is a raw " + texture.raw().orElseThrow().shape()
						+ ", which this engine only lays out flat for a volume, so "
						+ texture.sampler() + " reads one black pixel");

				return null;
			}

			NoiseTexture.Image decoded = NoiseTexture.decode(bytes);

			return new Image(texture, decoded.width(), decoded.height(), decoded.rgba(),
					decoded.width() + "x" + decoded.height());
		} catch (IOException | RuntimeException e) {
			notes.add(texture.path() + " could not be read: " + e.getMessage() + ", so "
					+ texture.sampler() + " reads one black pixel");

			return null;
		}
	}

	/**
	 * A texture the GAME owns, named by the pack as {@code namespace:path} and read out of the
	 * resource packs the client has loaded rather than out of the shader pack. Null when the client
	 * has nothing under that name, with the reason in the notes.
	 * <p>
	 * The one place in this class that names an API of the game, and it is asked defensively for
	 * the reason the rest of the class avoids it: outside a running client there is no client to
	 * ask, and reading a pack has to keep working there.
	 * <p>
	 * Read once here, where Iris re-asks the texture manager at every bind, so a resource pack
	 * swapped under a running client is not followed until the shader pack is read again. Two cases
	 * Iris serves this cannot serve at all, and both are named rather than given something
	 * plausible. An ATLAS, which is stitched at runtime and is no file of any resource pack. And a
	 * normal or specular map reached from a texture NAME, which is a different door from the one
	 * {@link PbrAtlases} opens: the maps that follow the atlases are built there, off the sprites
	 * the game stitched, where this method is asked for a path a pack wrote in its own properties.
	 */
	private static Image gameResource(PackTexture texture, List<String> notes) {
		String path = texture.path();

		// Split rather than cut at the first colon: a name carrying more than one keeps its first
		// two parts and drops the rest, which is what Iris makes of such a name.
		// Limit 0, the one-argument reading. It matters which one: tryBuild accepts an empty path,
		// so a name written 'minecraft:' would build an identifier under any limit that kept the
		// empty term, and the black pixel below would be blamed on a file no resource pack ships
		// rather than on a name this client cannot be asked for.
		String[] parts = path.split(":", 0);
		Identifier location = parts.length < 2 ? null : Identifier.tryBuild(parts[0], parts[1]);
		Minecraft client = Minecraft.getInstance();
		if (location == null || client == null) {
			notes.add(path + " is not a resource this client can be asked for, so "
					+ texture.sampler() + " reads one black pixel");

			return null;
		}

		Optional<Resource> resource = client.getResourceManager().getResource(location);
		if (resource.isEmpty()) {
			notes.add(path + " is not a file any loaded resource pack ships, so " + texture.sampler()
					+ " reads one black pixel");

			return null;
		}

		try (InputStream stream = resource.get().open()) {
			byte[] bytes = stream.readNBytes(MAX_RESOURCE_BYTES + 1);
			if (bytes.length > MAX_RESOURCE_BYTES) {
				notes.add(path + " holds more than the " + MAX_RESOURCE_BYTES
						+ " bytes a texture is read under, so " + texture.sampler()
						+ " reads one black pixel");

				return null;
			}

			NoiseTexture.Image decoded = NoiseTexture.decode(bytes);

			return new Image(texture, decoded.width(), decoded.height(), decoded.rgba(),
					decoded.width() + "x" + decoded.height());
		} catch (IOException | RuntimeException e) {
			notes.add(path + " could not be read: " + e.getMessage() + ", so " + texture.sampler()
					+ " reads one black pixel");

			return null;
		}
	}

	/** Every image that was read, in the order the pack declared them. */
	List<Image> images() {
		return this.images;
	}

	/**
	 * Which image a sampler reads, or null when the pack takes the name over and nothing could be
	 * put behind it.
	 * <p>
	 * A forged name is answered without consulting the stage, and that is the one place the stage is
	 * ignored on purpose. The translation moves a volume's declaration in every program that carries
	 * it, whatever stage the directive named, because the declaration is what the backend refuses;
	 * the same file therefore has to answer wherever that declaration ended up. It invents nothing:
	 * the pack named exactly one file for that identifier.
	 */
	Image find(TextureStage stage, String sampler) {
		String name = SamplerPlan.behind(sampler);
		if (!name.equals(sampler)) {
			return this.volumes.get(name);
		}

		// No stage means a program no family claims, which is a program nothing runs. Answered
		// rather than looked up: a stage override is written for one of the seven and there is no
		// honest way to guess which one an eighth would have been.
		return stage == null
				? null
				: this.declared.resolve(stage, name).map(this.byTexture::get).orElse(null);
	}

	/** One line per declaration this engine could not honour. Said once, when the pack is read. */
	List<String> notes() {
		return this.notes;
	}

	/**
	 * What the log says about a texture that IS served, once it is known how big it came out.
	 * <p>
	 * A volume says where its wrapping is done, because that is the one thing a reader could not
	 * work out from the sampler: the atlas itself is bound clamped, and the repeat lives in the
	 * helper the translation printed.
	 */
	static String describe(Image image) {
		PackTexture texture = image.texture();
		boolean flat = texture.raw()
				.filter(raw -> raw.shape() == PackTexture.Shape.TEXTURE_3D)
				.isPresent();

		return texture.stage().map(stage -> stage.name().toLowerCase(Locale.ROOT))
				.orElse("every stage") + " " + texture.sampler() + " reads " + texture.path()
				+ ", " + image.shape() + ", " + (texture.blur() ? "linear" : "nearest") + " and "
				+ (texture.clamp() ? "clamped" : "repeating")
				+ (flat ? ", the repeat done by the shader" : "");
	}

	/**
	 * What the whole set costs once uploaded, which is not what it costs on disk and is the only one
	 * of the two worth watching. Body Camera ships a 59 kilobyte lookup table that decodes to
	 * 4096 by 4096, sixty four megabytes, and the ceiling on the file says nothing about that.
	 */
	long bytes() {
		long total = 0L;
		for (Image image : this.images) {
			total += image.bytes();
		}

		return total;
	}

	/** Whether the set is heavy enough to be worth a line of its own in the log. */
	boolean loud() {
		return bytes() > LOUD_BYTES;
	}
}
