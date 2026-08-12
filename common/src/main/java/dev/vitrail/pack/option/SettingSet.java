package dev.vitrail.pack.option;

import java.util.LinkedHashMap;
import java.util.List;
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
 * Both tables answer for the settings the pack DECLARES and for no others. A chosen name the pack
 * declares nowhere moves nothing at all; {@link #undeclared} is what names it, so that the load can
 * say so rather than let a value be lost without a word.
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
	 */
	public Map<String, String> unitDefines() {
		return this.engine;
	}

	/**
	 * The chosen names this pack declares nowhere, in the order they were chosen, for the caller
	 * that has to say what it is dropping.
	 * <p>
	 * They are dropped rather than written into the head of each unit, which is what this engine
	 * used to do and what Iris has never done: {@code MutableOptionValues.addAll} walks the options
	 * the PACK declares and looks each one up in the values it was handed
	 * ({@code shaderpack/option/values/MutableOptionValues.java:49-90}), so a name no option carries
	 * is never read at all.
	 * <p>
	 * Writing it was worse than useless. A settings name is an identifier, and a pack uses
	 * identifiers for its own things: {@code hand=on} defines {@code hand} to nothing, BSL's
	 * composite stages declare a local {@code float hand}, and the whole pack then fails to compile
	 * on a word its author never offered as a setting.
	 */
	public List<String> undeclared(OptionIndex index) {
		return this.chosen.keySet().stream().filter(name -> !index.contains(name)).toList();
	}
}
