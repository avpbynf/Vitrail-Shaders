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
	 * The reading that runs on the thread that draws, which is the chain's, and the one reading
	 * offered the opening a previous load left standing. {@link KeptPack} says what has to answer
	 * the same before that offer is made, and what it empties before making it.
	 * <p>
	 * <strong>Nothing else may ask for it.</strong> The memos inside a {@link ShaderPackSource}
	 * belong to one thread, and this is the only reading whose thread is known and whose readings
	 * cannot overlap.
	 */
	public static OpenedPack openKept(Path packPath, Map<String, OptionValue> chosen, String profile)
			throws IOException {
		return KeptPack.open(packPath, chosen, profile);
	}

	/**
	 * Lets go of whatever {@link #openKept} left standing, which a load that has decided to draw no
	 * pack at all owes: those roads never reach the opening, so nothing else would ever replace it.
	 */
	public static void forgetKept() {
		KeptPack.forget();
	}

	/**
	 * @param chosen  settings to override, by the name the pack declares them under
	 * @param profile a profile the pack declares, applied underneath {@code chosen} so that a single
	 *                setting can still be overridden on top of it, or the empty string
	 */
	public static OpenedPack open(Path packPath, Map<String, OptionValue> chosen, String profile)
			throws IOException {
		ShaderPackSource source = ShaderPackSource.open(packPath);
		try {
			OptionIndex options = source.options();
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

	/**
	 * Gives the archive back, unless this is the opening {@link KeptPack} is holding for the next
	 * load, in which case what a reader worked out of it goes and the archive stays.
	 * <p>
	 * Emptied HERE and not when the next load takes the opening: a load's conclusions have no
	 * business outliving the load, and dropping them at the next hit would leave them alive for as
	 * long as no hit came, which is for the rest of the session when none does. The rule above
	 * stands as it was, and is what makes this safe: nothing taken from an opening may be used
	 * after its {@code close}, whether or not that close reached the archive.
	 */
	@Override
	public void close() throws IOException {
		if (KeptPack.holds(this)) {
			this.source.forgetDerived();

			return;
		}

		this.source.close();
	}
}
