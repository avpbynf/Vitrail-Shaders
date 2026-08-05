package dev.vitrail.pack.texture;

import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.target.TargetFormat;
import dev.vitrail.pack.target.TargetName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * What a pack supplies as a texture of its own, read out of {@code shaders.properties} and
 * checked against the files behind it.
 * <p>
 * The grammar is short and has one trap in it. What a value means is decided by HOW MANY words it
 * holds, not by what they say: one word is the path of an image, six a one dimensional blob, seven
 * a two dimensional one, eight a three dimensional one. Any other count is a line nothing can
 * honour, and it is dropped whole. Iris consumes a forged sampler name for it anyway, leaves the
 * type null and meets that null much later while translating; a directive nobody can read has to
 * cost nothing at all.
 * <p>
 * Every declaration is checked against its file, and a declaration whose file is missing,
 * unreadable, or SHORTER than the size it announces is refused rather than bound to black. That is
 * a deliberate difference with Iris, which logs and leaves the sampler on texture unit zero, so
 * that the shader reads whatever happens to be bound there. The image that comes out of it is
 * perfectly plausible and completely wrong, and this project would rather name the pack.
 */
public final class PackTextures {

	private static final String CUSTOM_PREFIX = "customTexture.";
	private static final String STAGE_PREFIX = "texture.";

	/** The word counts the format gives a meaning to. */
	private static final int PNG_TOKENS = 1;
	private static final int RAW_1D_TOKENS = 6;
	private static final int RAW_2D_TOKENS = 7;
	private static final int RAW_3D_TOKENS = 8;

	private final List<PackTexture> supplied;
	private final List<Refused> refused;
	private final List<String> notes;
	private final Map<TextureStage, Map<String, PackTexture>> overrides;
	private final Map<TextureStage, Set<String>> diverted;
	private final Map<String, PackTexture> named;

	/**
	 * A directive this engine will not honour, with the reason in one clause for the log.
	 *
	 * @param stage   present when the line was read far enough to know which name it was taking
	 *                over. That name is then taken over ANYWAY and left with nothing behind it,
	 *                which is not a nicety: Mellow points {@code texture.deferred.colortex3} at a
	 *                texture of the game, and letting the name fall back to colour target three
	 *                would have its deferred read the scene as a cloud texture. A line this could
	 *                not read that far takes nothing, and is as good as absent
	 * @param sampler the name being taken over, empty alongside an absent stage
	 */
	public record Refused(String key, String value, String reason, Optional<TextureStage> stage,
			String sampler) {

		private static Refused of(String key, String value, String reason) {
			return new Refused(key, value, reason, Optional.empty(), "");
		}

		@Override
		public String toString() {
			return this.key + "=" + this.value + ": " + this.reason;
		}
	}

	private PackTextures(List<PackTexture> supplied, List<Refused> refused, List<String> notes) {
		this.supplied = List.copyOf(supplied);
		this.refused = List.copyOf(refused);
		this.notes = List.copyOf(notes);

		Map<TextureStage, Map<String, PackTexture>> overrides = new EnumMap<>(TextureStage.class);
		Map<String, PackTexture> named = new LinkedHashMap<>();
		for (PackTexture texture : supplied) {
			texture.stage().ifPresentOrElse(
					stage -> overrides.computeIfAbsent(stage, _ -> new LinkedHashMap<>())
							.put(texture.sampler(), texture),
					() -> named.put(texture.sampler(), texture));
		}

		Map<TextureStage, Set<String>> diverted = new EnumMap<>(TextureStage.class);
		refused.forEach(one -> one.stage().ifPresent(stage ->
				diverted.computeIfAbsent(stage, _ -> new LinkedHashSet<>()).add(one.sampler())));

		this.overrides = Map.copyOf(overrides);
		this.diverted = Map.copyOf(diverted);
		this.named = Map.copyOf(named);
	}

	/**
	 * @param defines the settings as they stand, because the lines are read through the pack's own
	 *                conditionals and two packs put custom textures behind one
	 */
	public static PackTextures read(ShaderProperties properties, Map<String, String> defines,
			ShaderPackSource source) throws IOException {
		List<PackTexture> supplied = new ArrayList<>();
		List<Refused> refused = new ArrayList<>();
		List<String> notes = new ArrayList<>();

		for (Map.Entry<String, String> line : properties.customTextures(defines).entrySet()) {
			read(line.getKey(), line.getValue(), source, supplied, refused, notes);
		}

		return new PackTextures(supplied, refused, notes);
	}

	private static void read(String key, String value, ShaderPackSource source,
			List<PackTexture> supplied, List<Refused> refused, List<String> notes)
			throws IOException {
		Optional<TextureStage> stage;
		String sampler;

		if (key.startsWith(CUSTOM_PREFIX)) {
			stage = Optional.empty();
			// Dots and all: this name belongs to nobody but the pack, so nothing may be cut off it.
			sampler = key.substring(CUSTOM_PREFIX.length());
		} else {
			String rest = key.substring(STAGE_PREFIX.length());
			int dot = rest.indexOf('.');
			if (dot < 0) {
				refused.add(Refused.of(key, value, "names no stage to override the sampler in"));
				return;
			}

			stage = TextureStage.parse(rest.substring(0, dot));
			if (stage.isEmpty()) {
				refused.add(Refused.of(key, value,
						"names the stage " + rest.substring(0, dot) + ", which is not one of the seven"));
				return;
			}

			// Truncated at its first dot, as Iris truncates it. A sampler name has no dot in it, so
			// what follows one is whatever the pack thought it was adding, and it is not part of the
			// name being overridden.
			String tail = rest.substring(dot + 1);
			int inner = tail.indexOf('.');
			sampler = inner < 0 ? tail : tail.substring(0, inner);
		}

		if (sampler.isEmpty()) {
			refused.add(Refused.of(key, value, "names no sampler"));
			return;
		}

		String[] parts = value.trim().split("\\s+");
		if (parts.length != PNG_TOKENS && (parts.length < RAW_1D_TOKENS || parts.length > RAW_3D_TOKENS)) {
			// Nothing is consumed, a forged name least of all: the directive is as good as absent.
			refused.add(Refused.of(key, value, "holds " + parts.length
					+ " words, and the format gives a meaning to 1, 6, 7 and 8"));
			return;
		}

		Optional<PackTexture.Raw> raw = parts.length == PNG_TOKENS
				? Optional.empty()
				: rawOf(parts);
		if (parts.length != PNG_TOKENS && raw.isEmpty()) {
			refused.add(Refused.of(key, value, "declares a raw texture this cannot read"));
			return;
		}

		String path = parts[0];

		// Past this line the directive is readable and the name it takes over is settled, so every
		// refusal below carries the name with it and leaves that name with nothing behind it.
		//
		// A namespaced path names a texture the game owns and hands out through its own manager,
		// which is not something this engine reaches. Said before the file is looked for, because
		// looking for it inside the pack would report the wrong reason.
		if (path.indexOf(':') >= 0) {
			refused.add(new Refused(key, value, "points at " + path
					+ ", a resource of the game rather than a file of the pack", stage, sampler));
			return;
		}

		Optional<Path> file = source.file(path);
		if (file.isEmpty()) {
			refused.add(new Refused(key, value, "points at " + path + ", which the pack does not ship",
					stage, sampler));
			return;
		}

		long size = source.size(file.get());
		if (raw.isPresent()) {
			long wanted = raw.get().bytes();
			if (size < wanted) {
				refused.add(new Refused(key, value, path + " holds " + size + " bytes and the "
						+ "declaration asks for " + wanted, stage, sampler));
				return;
			}

			if (size > wanted) {
				notes.add(path + " holds " + size + " bytes for a declaration of " + wanted
						+ ", and the tail is not uploaded");
			}
		}

		// A raw blob is filtered and clamped unless the pack says otherwise and an image is neither,
		// which is OptiFine's rule rather than a taste: a blob carries a lookup table as often as an
		// image, and a table read past its edge or between its entries answers with something that
		// was never in it.
		String meta = meta(source, path);
		boolean sampled = raw.isPresent();

		supplied.add(new PackTexture(sampler, stage, path, raw,
				field(meta, "blur").orElse(sampled), field(meta, "clamp").orElse(sampled)));
	}

	/**
	 * The blob's shape, size and format, or empty when a word of it cannot be read.
	 * <p>
	 * {@code parts[1]} is only ever consulted at seven words, and that is the format's doing rather
	 * than a shortcut: six words is a 1D texture and eight a 3D one whatever the word says, so at
	 * those two lengths it is the count that decides and the word is decoration.
	 */
	private static Optional<PackTexture.Raw> rawOf(String[] parts) {
		PackTexture.Shape shape = switch (parts.length) {
			case RAW_1D_TOKENS -> PackTexture.Shape.TEXTURE_1D;
			case RAW_2D_TOKENS -> flat(parts[1]);
			default -> PackTexture.Shape.TEXTURE_3D;
		};

		if (shape == null) {
			return Optional.empty();
		}

		// The sizes always start at the fourth word and there are as many of them as the count
		// says: one more word than a 1D texture is one more axis.
		int dimensions = parts.length - RAW_1D_TOKENS + 1;
		int[] size = new int[3];
		try {
			for (int axis = 0; axis < dimensions; axis++) {
				size[axis] = Integer.parseInt(parts[3 + axis]);
			}
		} catch (NumberFormatException e) {
			return Optional.empty();
		}

		for (int axis = 0; axis < dimensions; axis++) {
			if (size[axis] <= 0) {
				return Optional.empty();
			}
		}

		Optional<PixelFormat> pixelFormat = PixelFormat.parse(parts[parts.length - 2]);
		Optional<PixelType> pixelType = PixelType.parse(parts[parts.length - 1]);
		if (pixelFormat.isEmpty() || pixelType.isEmpty()) {
			return Optional.empty();
		}

		TargetFormat.Resolution internal = TargetFormat.resolve(parts[2]);
		if (internal.reason() == TargetFormat.Reason.UNKNOWN) {
			return Optional.empty();
		}

		return Optional.of(new PackTexture.Raw(shape, internal, size[0], size[1], size[2],
				pixelFormat.get(), pixelType.get()));
	}

	/**
	 * The two shapes seven words can mean. A pack naming a 1D or a 3D texture at that length has
	 * written a size it does not have, and there is no honest way to guess which one it dropped.
	 */
	private static PackTexture.Shape flat(String word) {
		String name = word.trim().toUpperCase(Locale.ROOT);

		if (name.equals(PackTexture.Shape.TEXTURE_2D.name())) {
			return PackTexture.Shape.TEXTURE_2D;
		}

		return name.equals(PackTexture.Shape.TEXTURE_RECTANGLE.name())
				? PackTexture.Shape.TEXTURE_RECTANGLE
				: null;
	}

	/** The {@code .mcmeta} beside the file as one line, or nothing when the pack ships none. */
	private static String meta(ShaderPackSource source, String path) throws IOException {
		Optional<Path> meta = source.file(path + ".mcmeta");

		return meta.isEmpty() ? "" : String.join(" ", source.readLines(meta.get()));
	}

	/**
	 * One of the two fields a {@code .mcmeta} may carry, read by hand.
	 * <p>
	 * By hand rather than through a JSON reader because these files hold two booleans and nothing
	 * else in the whole corpus, and a dependency for that would be paid on every load. A file this
	 * cannot make sense of leaves the default standing, which is what a pack that ships no file at
	 * all gets.
	 */
	private static Optional<Boolean> field(String text, String name) {
		int at = text.indexOf('"' + name + '"');
		if (at < 0) {
			return Optional.empty();
		}

		int colon = text.indexOf(':', at);
		if (colon < 0) {
			return Optional.empty();
		}

		String tail = text.substring(colon + 1).stripLeading();
		if (tail.startsWith("true")) {
			return Optional.of(true);
		}

		return tail.startsWith("false") ? Optional.of(false) : Optional.empty();
	}

	/** Every declaration this engine will honour, in the order the pack wrote them. */
	public List<PackTexture> supplied() {
		return this.supplied;
	}

	/** Every declaration it will not, with the reason. Meant to be logged, one line each. */
	public List<Refused> refused() {
		return this.refused;
	}

	/** What was honoured with something worth saying about it. */
	public List<String> notes() {
		return this.notes;
	}

	/**
	 * What a name reads from in a program of that stage, if the pack supplies anything for it.
	 * <p>
	 * Empty is two different answers and the caller has to keep them apart: a name
	 * {@link #suppliedTo} does not carry is one the pack never touched, and a name it carries with
	 * nothing behind it is an override this engine could not honour, which reads black.
	 * <p>
	 * A stage override is asked first. Nothing in the corpus writes both forms for one name, and if
	 * one ever does, the form that names a stage is the more precise of the two.
	 */
	public Optional<PackTexture> resolve(TextureStage stage, String sampler) {
		Map<String, PackTexture> forStage = this.overrides.getOrDefault(stage, Map.of());
		for (String spelling : spellings(sampler)) {
			PackTexture found = forStage.get(spelling);
			if (found != null) {
				return Optional.of(found);
			}
		}

		return Optional.ofNullable(this.named.get(sampler));
	}

	/**
	 * Every name the pack takes over in a program of that stage, whether or not a file was found
	 * behind it, both spellings of a colour target included.
	 * <p>
	 * Whether or not, because the two failures are not the same. A name the pack takes over and
	 * this engine cannot serve reads black and is named in the log; letting it fall back to the
	 * colour target it shares a name with would put the scene where the pack asked for a lookup
	 * table, and that is a picture nobody would question. {@link #resolve} is what tells the two
	 * apart.
	 * <p>
	 * Both spellings because a colour target answers to two names and the override is written under
	 * one of them: Complementary writes {@code texture.gbuffers.gaux4} and its gbuffers may sample
	 * either word. Iris looks an override up under every name of the unit for the same reason.
	 */
	public Set<String> suppliedTo(TextureStage stage) {
		Set<String> names = new LinkedHashSet<>(this.named.keySet());
		this.overrides.getOrDefault(stage, Map.of()).keySet()
				.forEach(sampler -> names.addAll(spellings(sampler)));
		this.diverted.getOrDefault(stage, Set.of())
				.forEach(sampler -> names.addAll(spellings(sampler)));

		return names;
	}

	/** A colour target under both its names, anything else under its own. */
	private static List<String> spellings(String sampler) {
		OptionalInt index = TargetName.index(sampler);
		if (index.isEmpty()) {
			return List.of(sampler);
		}

		return TargetName.legacyAlias(index.getAsInt())
				.map(alias -> List.of(TargetName.canonical(index.getAsInt()), alias))
				.orElse(List.of(TargetName.canonical(index.getAsInt())));
	}
}
