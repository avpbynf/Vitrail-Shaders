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
 * caller's, not this class's, because only the caller knows which layer the name came off, and the
 * three layers are not answered alike. {@code EngineOptions.announceForced} says it word by word for
 * {@code vitrail/options.txt}, where a player types and a typo is worth a line.
 * {@code PackSession.stale} reports the pack's own file against the MENU, unless
 * {@code vitrail/options.txt} names the same word and the line above has it: so a name declared
 * nowhere is in that line, along with names that are merely off a page and still apply.
 * <p>
 * A profile naming a word its own pack declares nowhere is dropped without a word, and Iris is
 * nearly as quiet. Four of the six forms its parser takes name a setting
 * ({@code shaderpack/option/ProfileSet.java:57-82}), and it looks only ONE of the four up before
 * using it: the bare positive, warned as {@code Invalid pack option} at {@code :78-81}, and looked
 * up in the boolean half of the index rather than in the whole of it. {@code !NAME},
 * {@code NAME=value} and {@code NAME:value} go through unchecked at {@code :70-77}. Measured over
 * the eight pack corpus, the checked form is 17 tokens of the 440 those four carry, against 402 for
 * {@code NAME=value}, 21 for {@code !NAME} and none at all for {@code NAME:value}. Here nothing is
 * looked up on any form {@code ShaderProperties.expandProfile} takes, a bare token becoming an on
 * without a lookup. What that difference costs is a pack author's own typo staying invisible in one
 * form of four; what it costs the picture is nothing, both engines applying the word nowhere.
 * <p>
 * A second and unrelated gap sits in the same method and is NOT this one: two of the six forms have
 * no branch here at all. {@code NAME:value} falls through to the bare case and turns on an option
 * whose name carries the colon, where Iris splits it; {@code !program.NAME} falls through to the
 * negation and turns an option named {@code program.NAME} off, where Iris disables the program. No
 * pack of the corpus writes either, and neither is this lot's to fix.
 */
public final class SettingSet {

	/**
	 * How much of the shadow map the pack asks for is actually drawn, as a percentage on each
	 * axis, a hundred meaning the pack's own number untouched.
	 * <p>
	 * <strong>Pushed by the engine rather than passed in, on the shape of
	 * {@link EngineDefines#machine(EngineDefines.Environment)} and for its reason:</strong>
	 * {@link #resolve} is reached from six places that translate, and threading one number
	 * through all six is six chances to miss one and read a pack at two different sizes in one
	 * load. It stays a plain number with a default that does nothing, so the pack corpus runs
	 * off-game without anybody setting it.
	 */
	private static volatile int shadowMapScale = 100;

	private final Map<String, OptionValue> chosen;
	private final Map<String, String> engine;
	private final String variantName;
	private final int scale;

	private SettingSet(Map<String, OptionValue> chosen, Map<String, String> engine, String variantName,
			int scale) {
		this.chosen = Map.copyOf(chosen);
		this.engine = Map.copyOf(engine);
		this.variantName = variantName;
		this.scale = scale;
	}

	public static void shadowMapScale(int percent) {
		shadowMapScale = percent;
	}

	public static SettingSet resolve(Map<String, OptionValue> profile, Map<String, OptionValue> user,
			String variantName) {
		Map<String, OptionValue> chosen = new LinkedHashMap<>(profile);
		chosen.putAll(user);

		// Taken once here rather than read where it is used, so that every unit of one reading
		// is expanded at the same size even if the player moves the slider while a pack loads.
		return new SettingSet(chosen, EngineDefines.table(EngineDefines.machine()), variantName,
			shadowMapScale);
	}

	/** The percentage this reading expands at, for the one declaration it touches. */
	public int scale() {
		return this.scale;
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

	/**
	 * Everything defined, for reading {@code shaders.properties}, which may test any of it.
	 * Any setting, that is: a constant {@link OptionIndex#offers} refuses stays out of this
	 * table the way it stays out of the reference's, so a conditional testing its name reads it
	 * exactly as it reads a name the pack never declared. And a boolean constant enters only
	 * while it is TRUE, because that is how the reference's preprocessor defines its booleans
	 * ({@code PropertiesPreprocessor.getBooleanValues}): an {@code #ifdef} on one declared
	 * false has to read false, whatever text the declaration carries.
	 */
	public Map<String, String> globalDefines(OptionIndex index) {
		Map<String, String> defines = new LinkedHashMap<>(this.engine);

		for (PackOption option : index.all()) {
			if (option.kind() == PackOption.Kind.CONST) {
				if (!index.offers(option)) {
					continue;
				}

				if ("bool".equals(option.constType())) {
					OptionValue held = this.chosen.get(option.name());
					boolean on = held == null ? "true".equals(option.defaultText())
							: held.isBoolean() && held.asBoolean();
					if (on) {
						defines.put(option.name(), "true");
					}

					continue;
				}
			}

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
	 * Such a name is dropped rather than written into the head of each unit, which is not what Iris
	 * does either: {@code MutableOptionValues.addAll} walks the options the PACK declares and looks
	 * each one up in the values it was handed
	 * ({@code shaderpack/option/values/MutableOptionValues.java:49-97}), so a name no option carries
	 * is never read at all.
	 * <p>
	 * Writing it is worse than useless, and that was measured rather than reasoned. A settings name
	 * is an identifier and a pack uses identifiers for its own things: a build that did not yet know
	 * {@code hand} as a line of its own forced it as a setting of the pack, the header define landed
	 * on the local {@code float hand} of BSL's composite stages, and the whole pack was refused at
	 * the parse. Reserving that one word fixed that one word. Nothing reserves the next.
	 */
	public Map<String, String> unitDefines() {
		return this.engine;
	}
}
