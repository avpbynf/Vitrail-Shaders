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
	 * What a source file starts with: the engine's symbols, plus only those choices the pack
	 * declares nowhere. Those have no declaration to rewrite, so the header is the only place
	 * they can be said.
	 */
	public Map<String, String> unitDefines(OptionIndex index) {
		Map<String, String> defines = new LinkedHashMap<>(this.engine);
		defines.putAll(headerDefines(index));

		return defines;
	}

	public Map<String, String> headerDefines(OptionIndex index) {
		Map<String, String> defines = new LinkedHashMap<>();

		for (Map.Entry<String, OptionValue> entry : this.chosen.entrySet()) {
			if (index.contains(entry.getKey())) {
				continue;
			}

			OptionValue value = entry.getValue();
			if (!value.isBoolean()) {
				defines.put(entry.getKey(), value.text());
			} else if (value.asBoolean()) {
				defines.put(entry.getKey(), "");
			}
		}

		return defines;
	}
}
