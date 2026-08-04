package dev.vitrail.pack.menu;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.PackOption;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every page a pack lays out, built once when the pack is read, in between twenty five and
 * eighty eight milliseconds on the corpus.
 * <p>
 * A token naming nothing leaves a blank and a line in {@link #warnings()} rather than throwing:
 * four of the corpus's three thousand slots name an option their own pack does not declare, and
 * a pack that ships one broken name is still a working pack.
 * <p>
 * Nothing here touches Minecraft and nothing here logs, which is what lets the whole model be
 * checked against the eight packs in ten seconds instead of a game session.
 */
public final class PackMenu {

	/** Iris's rule, and the one that decides most of our pages: three columns past this many. */
	private static final int WIDE_PAGE = 18;

	private static final MenuSlot BLANK = new MenuSlot.Blank();
	private static final MenuSlot PROFILES = new MenuSlot.Profiles();

	private final String packName;
	private final Map<String, MenuPage> pages;
	private final Map<String, MenuOption> options;
	private final Map<String, Map<String, String>> profiles;
	private final PackLang lang;
	private final List<String> warnings;

	private PackMenu(String packName, Map<String, MenuPage> pages, Map<String, MenuOption> options,
			Map<String, Map<String, String>> profiles, PackLang lang, List<String> warnings) {
		// Ordered rather than Map.copyOf: pages are walked in the order the pack writes them and
		// the profile selector cycles in the order the pack declares its profiles.
		this.packName = packName;
		this.pages = Collections.unmodifiableMap(new LinkedHashMap<>(pages));
		this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
		this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
		this.lang = lang;
		this.warnings = List.copyOf(warnings);
	}

	public static PackMenu build(String packName, OptionIndex index, ShaderProperties properties,
			PackLang lang) {
		Set<String> sliders = new HashSet<>(properties.sliders());
		Map<String, List<ShaderProperties.ScreenToken>> layout = properties.screenLayout();
		boolean hasProfiles = !properties.profiles().isEmpty();

		List<String> warnings = new ArrayList<>();
		Map<String, MenuOption> options = new LinkedHashMap<>();
		Map<String, MenuPage> pages = new LinkedHashMap<>();

		for (Map.Entry<String, List<ShaderProperties.ScreenToken>> page : layout.entrySet()) {
			String where = page.getKey().isEmpty() ? "screen" : "screen." + page.getKey();
			List<MenuSlot> slots = new ArrayList<>();

			for (ShaderProperties.ScreenToken token : page.getValue()) {
				switch (token) {
					case ShaderProperties.ScreenToken.Blank _ -> slots.add(BLANK);
					case ShaderProperties.ScreenToken.Name(String name) -> {
						PackOption declared = index.get(name).orElse(null);
						if (declared == null) {
							warnings.add(where + " names " + name
									+ ", which the pack does not declare");
							slots.add(BLANK);
						} else {
							slots.add(new MenuSlot.Option(options.computeIfAbsent(name,
									_ -> MenuOption.of(declared, sliders.contains(name)))));
						}
					}
					case ShaderProperties.ScreenToken.Link(String target) -> {
						boolean resolved = layout.containsKey(target);
						if (!resolved) {
							warnings.add(where + " links to " + target
									+ ", which the pack does not lay out");
						}

						slots.add(new MenuSlot.Link(target, resolved));
					}
					// A pack with nothing to choose from loses the token rather than keeping a
					// blank where the selector would have been.
					case ShaderProperties.ScreenToken.Profiles _ -> {
						if (hasProfiles) {
							slots.add(PROFILES);
						}
					}
					case ShaderProperties.ScreenToken.Rest _ -> {
						warnings.add(where + " asks for *, which no page shows yet");
						slots.add(BLANK);
					}
				}
			}

			pages.put(page.getKey(), new MenuPage(page.getKey(), slots,
					properties.columns(page.getKey()).orElse(slots.size() > WIDE_PAGE ? 3 : 2)));
		}

		if (!pages.containsKey("")) {
			warnings.add("The pack lays out no main screen");
			pages.put("", new MenuPage("", List.of(), 2));
		}

		Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
		for (String name : properties.profiles().keySet()) {
			profiles.put(name, expand(properties, name));
		}

		return new PackMenu(packName, pages, options, profiles, lang, warnings);
	}

	/** Opens the pack, reads the three of them, closes it. */
	public static PackMenu read(Path packPath, String languageCode) throws IOException {
		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			return build(source.packName(), OptionIndex.build(source), ShaderProperties.parse(source),
					PackLang.read(source, languageCode));
		}
	}

	private static Map<String, String> expand(ShaderProperties properties, String name) {
		Map<String, String> expanded = new LinkedHashMap<>();
		properties.expandProfile(name).forEach((option, value) -> expanded.put(option, text(value)));

		return Collections.unmodifiableMap(expanded);
	}

	/**
	 * A boolean reads as {@code on} or {@code off} here rather than as {@code true} or
	 * {@code false}, because those are the two values a toggle offers and a value has to be
	 * comparable to what the widget cycles through.
	 */
	private static String text(OptionValue value) {
		if (value.isBoolean()) {
			return value.asBoolean() ? "on" : "off";
		}

		return value.text();
	}

	public String packName() {
		return this.packName;
	}

	public MenuPage main() {
		return this.pages.get("");
	}

	public Optional<MenuPage> page(String name) {
		return Optional.ofNullable(this.pages.get(name));
	}

	/** Every page including the main one, in the order the pack declares them. */
	public Collection<MenuPage> pages() {
		return this.pages.values();
	}

	public Optional<MenuOption> option(String name) {
		return Optional.ofNullable(this.options.get(name));
	}

	/** How many distinct settings the pages place, a setting named twice counting once. */
	public int optionCount() {
		return this.options.size();
	}

	/** In declaration order, which is the order the selector walks through. */
	public List<String> profileNames() {
		return List.copyOf(this.profiles.keySet());
	}

	/** One profile's settings, chain already resolved, as text. */
	public Map<String, String> profile(String name) {
		return this.profiles.getOrDefault(name, Map.of());
	}

	public PackLang lang() {
		return this.lang;
	}

	/** What did not resolve, one line each, for the log and for the harness. */
	public List<String> warnings() {
		return this.warnings;
	}
}
