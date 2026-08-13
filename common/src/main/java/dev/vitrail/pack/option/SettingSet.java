package dev.vitrail.pack.option;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The settings in force for one reading of a pack: what the pack declares, then what a profile
 * changes, then what the player changed.
 * <p>
 * Two tables of defines come out of this and they are deliberately not the same one.
 * {@code shaders.properties} is read with every setting already at its value, because it has to
 * be able to test them. A source file starts with only the engine's own symbols, and picks up
 * the pack's as it reads past their declarations, exactly as a preprocessor would: a file that
 * tests a setting above the line declaring it must see it undefined, because that is what the
 * compiler will see later.
 * <p>
 * Both tables answer for the settings the pack DECLARES and for no others, and {@link #unitDefines}
 * carries why. A chosen name the pack declares nowhere moves nothing at all. Naming it is the
 * caller's, not this class's, because only the caller knows which layer the name came off:
 * {@code EngineOptions.announceForced} says it word by word for {@code vitrail/options.txt}, where a
 * player types and a typo is worth a line, and {@code PackSession.stale} for the pack's own file.
 * A profile naming a word its own pack declares nowhere is dropped without a word, which is what
 * Iris does with it too.
 */
public final class SettingSet {

	private final Map<String, OptionValue> chosen;
	private final Map<String, String> engine;
	private final String variantName;

	private SettingSet(Map<String, OptionValue> chosen, Map<String, String> engine, String variantName) {
		this.chosen = Map.copyOf(chosen);
		this.engine = Map.copyOf(engine);
		this.variantName = variantName;
	}

	public static SettingSet resolve(Map<String, OptionValue> profile, Map<String, OptionValue> user,
			String variantName) {
		Map<String, OptionValue> chosen = new LinkedHashMap<>(profile);
		chosen.putAll(user);

		return new SettingSet(chosen, EngineDefines.table(EngineDefines.machine()), variantName);
	}

	public static SettingSet defaults() {
		return resolve(Map.of(), Map.of(), "default");
	}

	public Map<String, OptionValue> chosen() {
		return this.chosen;
	}

	public String variantName() {
		return this.variantName;
	}

	/** Everything defined, for reading {@code shaders.properties}, which may test any of it. */
	public Map<String, String> globalDefines(OptionIndex index) {
		Map<String, String> defines = new LinkedHashMap<>(this.engine);

		for (PackOption option : index.all()) {
			OptionValue value = this.chosen.get(option.name());
			if (value == null) {
				// Left as the pack ships it. A setting commented out is simply not defined.
				if (!option.defaultOff()) {
					defines.put(option.name(), option.defaultText());
				}

				continue;
			}

			if (!value.isBoolean()) {
				defines.put(option.name(), value.text());
			} else if (value.asBoolean()) {
				defines.put(option.name(), option.defaultText());
			}
		}

		return defines;
	}

	/**
	 * What a source file starts with, and it is the engine's own symbols alone. A choice is applied
	 * where the pack declares it and nowhere else, so a name the pack declares nowhere is applied
	 * nowhere.
	 * <p>
	 * Such a name is dropped rather than written into the head of each unit, which is what this
	 * engine used to do and what Iris has never done: {@code MutableOptionValues.addAll} walks the
	 * options the PACK declares and looks each one up in the values it was handed
	 * ({@code shaderpack/option/values/MutableOptionValues.java:49-97}), so a name no option carries
	 * is never read at all.
	 * <p>
	 * Writing it was worse than useless, and this was measured rather than reasoned. A settings name
	 * is an identifier and a pack uses identifiers for its own things: a build that did not yet know
	 * {@code hand} as a line of its own forced it as a setting of the pack, the header define landed
	 * on the local {@code float hand} of BSL's composite stages, and the whole pack was refused at
	 * the parse. Reserving that one word fixed that one word. Nothing reserves the next.
	 */
	public Map<String, String> unitDefines() {
		return this.engine;
	}
}
