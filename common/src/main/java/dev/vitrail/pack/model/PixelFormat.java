package dev.vitrail.pack.model;

import java.util.Optional;

/**
 * How many channels a byte of a raw texture belongs to, by the name a pack writes for it.
 * <p>
 * The names are the GL enumerants a pack has always written, and the only thing read off them
 * here is the channel count: it is what turns a declared size into the number of bytes the file
 * has to hold, which is the one check that tells a truncated blob from a whole one. Nothing here
 * decides what the device is handed; that belongs beside the device.
 */
public enum PixelFormat {

	RED(1),
	RG(2),
	RGB(3),
	BGR(3),
	RGBA(4),
	BGRA(4),
	RED_INTEGER(1),
	RG_INTEGER(2),
	RGB_INTEGER(3),
	BGR_INTEGER(3),
	RGBA_INTEGER(4),
	BGRA_INTEGER(4);

	private final int components;

	PixelFormat(int components) {
		this.components = components;
	}

	/** Case insensitive: three packs of the corpus write these names in lower case. */
	public static Optional<PixelFormat> parse(String name) {
		for (PixelFormat format : values()) {
			if (format.name().equalsIgnoreCase(name.trim())) {
				return Optional.of(format);
			}
		}

		return Optional.empty();
	}

	public int components() {
		return this.components;
	}
}
