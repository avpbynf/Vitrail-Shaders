package dev.vitrail.pack.model;

import java.util.Optional;

/**
 * How wide one value of a raw texture is, by the name a pack writes for it.
 * <p>
 * Two kinds of name live here and telling them apart is the whole reason the class exists. A
 * plain type gives the width of one CHANNEL, so a texel of it costs that width times the channel
 * count; a packed one gives the width of the WHOLE texel, channels and all, and multiplying it by
 * the channel count would ask a file for three times the bytes it holds and refuse a pack that is
 * perfectly well formed. Nothing in the corpus writes a packed type, which is exactly why the
 * distinction has to be written down rather than discovered later.
 */
public enum PixelType {

	BYTE(1, false),
	SHORT(2, false),
	INT(4, false),
	HALF_FLOAT(2, false),
	FLOAT(4, false),
	UNSIGNED_BYTE(1, false),
	UNSIGNED_SHORT(2, false),
	UNSIGNED_INT(4, false),
	UNSIGNED_BYTE_3_3_2(1, true),
	UNSIGNED_BYTE_2_3_3_REV(1, true),
	UNSIGNED_SHORT_5_6_5(2, true),
	UNSIGNED_SHORT_5_6_5_REV(2, true),
	UNSIGNED_SHORT_4_4_4_4(2, true),
	UNSIGNED_SHORT_4_4_4_4_REV(2, true),
	UNSIGNED_SHORT_5_5_5_1(2, true),
	UNSIGNED_SHORT_1_5_5_5_REV(2, true),
	UNSIGNED_INT_8_8_8_8(4, true),
	UNSIGNED_INT_8_8_8_8_REV(4, true),
	UNSIGNED_INT_10_10_10_2(4, true),
	UNSIGNED_INT_2_10_10_10_REV(4, true),
	UNSIGNED_INT_10F_11F_11F_REV(4, true),
	UNSIGNED_INT_5_9_9_9_REV(4, true);

	private final int bytes;
	private final boolean packed;

	PixelType(int bytes, boolean packed) {
		this.bytes = bytes;
		this.packed = packed;
	}

	/** Case insensitive: three packs of the corpus write these names in lower case. */
	public static Optional<PixelType> parse(String name) {
		for (PixelType type : values()) {
			if (type.name().equalsIgnoreCase(name.trim())) {
				return Optional.of(type);
			}
		}

		return Optional.empty();
	}

	/** What one texel costs, which a packed type answers for on its own. */
	public long bytesPerTexel(PixelFormat format) {
		return this.packed ? this.bytes : (long) this.bytes * format.components();
	}

	/** Bytes in one channel, which for a packed type is the whole word the channels share. */
	public int channelBytes() {
		return this.bytes;
	}
}
