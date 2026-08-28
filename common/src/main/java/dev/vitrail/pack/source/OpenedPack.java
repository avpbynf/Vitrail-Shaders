package dev.vitrail.pack.source;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * A pack opened once, with the three answers every reading of it starts from already in hand.
 * <p>
 * <strong>This exists because those answers were being derived once per reading and there are a
 * dozen readings in one load.</strong> Every one of them opened the pack again, which for a zip
 * mounts the archive as a filesystem, and then walked every source file of it line by line to
 * rebuild the same {@link OptionIndex}. A dozen identical answers, each paid for in full, on the
 * thread that draws; that is the freeze a player sees after picking a pack. Carried instead, the
 * place, the values, the chain, the chunk programs and every shadow compute of a load share one.
 * <p>
 * The settings are part of it and not a fourth thing derived beside it, because the profile is
 * expanded out of the properties: two readings that resolved it apart would be two answers about
 * which branch of the pack compiles, and nothing would say which of them the picture came from.
 * <p>
 * <strong>Nothing taken from this may outlive {@link #close()}</strong>, which is
 * {@link ShaderPackSource}'s own rule and reaches everything held here: the index and the
 * properties hold strings and are safe, and the {@code Path} the source hands out is not.
 *
 * @param packPath what was opened, kept so that a line about it names the file the player sees
 *                 rather than the pack's own name, which is not always the same word
 */
public record OpenedPack(Path packPath, ShaderPackSource source, OptionIndex options,
		ShaderProperties properties, SettingSet settings) implements AutoCloseable {

	/**
	 * @param chosen  settings to override, by the name the pack declares them under
	 * @param profile a profile the pack declares, applied underneath {@code chosen} so that a single
	 *                setting can still be overridden on top of it, or the empty string
	 */
	public static OpenedPack open(Path packPath, Map<String, OptionValue> chosen, String profile)
			throws IOException {
		ShaderPackSource source = ShaderPackSource.open(packPath);
		try {
			OptionIndex options = OptionIndex.build(source);
			ShaderProperties properties = ShaderProperties.parse(source);
			Map<String, OptionValue> fromProfile =
					profile.isEmpty() ? Map.of() : properties.expandProfile(profile);
			SettingSet settings =
					SettingSet.resolve(fromProfile, chosen, profile.isEmpty() ? "chosen" : profile);

			return new OpenedPack(packPath, source, options, properties, settings);
		} catch (IOException | RuntimeException e) {
			// The archive is ours the moment it opened, so a failure below has to give it back.
			// Left to the caller it would leak a mounted filesystem per pack that cannot be read.
			source.close();
			throw e;
		}
	}

	@Override
	public void close() throws IOException {
		this.source.close();
	}
}
