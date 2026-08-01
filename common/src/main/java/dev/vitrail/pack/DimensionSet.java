package dev.vitrail.pack;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The directories a program may live in: the root of {@code shaders/}, and one per dimension.
 * <p>
 * A dimension directory is not always named after a number. A pack may map its own folder names
 * to dimensions in {@code dimension.properties}, and folders declared that way have to be read
 * from it: guessing from the name alone loses them, and their programs then look missing while
 * sitting in plain sight.
 */
public final class DimensionSet {

	/** The conventional names, used when the pack declares nothing. */
	private static final Pattern CONVENTIONAL = Pattern.compile("^world-?\\d+$");

	private static final Pattern DECLARATION = Pattern.compile("^\\s*dimension\\.([A-Za-z0-9_-]+)\\s*=.*$");

	private static final String PROPERTIES = "dimension.properties";

	private final List<String> names;
	private final boolean declared;

	private DimensionSet(List<String> names, boolean declared) {
		this.names = List.copyOf(names);
		this.declared = declared;
	}

	public static DimensionSet discover(ShaderPackSource source) throws IOException {
		Set<String> found = new LinkedHashSet<>();
		boolean declared = false;

		Optional<java.nio.file.Path> properties = source.file(PROPERTIES);
		if (properties.isPresent()) {
			for (String line : source.readLines(properties.get())) {
				Matcher declaration = DECLARATION.matcher(line);
				if (declaration.matches()) {
					found.add(declaration.group(1));
					declared = true;
				}
			}
		}

		for (String directory : source.topLevelDirectories()) {
			if (CONVENTIONAL.matcher(directory).matches()) {
				found.add(directory);
			}
		}

		return new DimensionSet(found.stream().sorted().toList(), declared);
	}

	public boolean isDimensionDirectory(String name) {
		return this.names.contains(name);
	}

	public List<String> names() {
		return this.names;
	}

	public boolean hasDimensionProperties() {
		return this.declared;
	}
}
