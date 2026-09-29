package dev.vitrail.pack.menu;

import dev.vitrail.pack.OptionPackFixture;
import dev.vitrail.pack.source.PackLang;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the menu of a small synthetic pack, read through the same source and properties readers a
 * load uses, for the tests of this package.
 */
final class MenuFixture {

	private MenuFixture() {
	}

	/** Writes {@code shaders.properties} and one source file into the directory and builds the menu. */
	static PackMenu menu(Path dir, String properties, String glsl) throws IOException {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("shaders.properties", properties);
		files.put("main.glsl", glsl);
		OptionPackFixture.write(dir, files);

		try (ShaderPackSource source = OptionPackFixture.open(dir)) {
			return PackMenu.build("test", source.options(), ShaderProperties.parse(source), PackLang.empty());
		}
	}
}
