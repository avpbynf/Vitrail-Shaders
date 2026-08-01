package dev.vitrail.pack;

/**
 * What is known about a pack once it has been read.
 * <p>
 * Everything here is immutable and holds no {@code Path}. That is not tidiness: the pack may
 * have been a zip, and the archive is closed as soon as reading is done, which would turn any
 * surviving path into a {@code ClosedFileSystemException} on first use, far from the cause.
 */
public record LoadedPack(String packName, boolean fromZip, DimensionSet dimensions,
		OptionIndex options, ProgramSet programs, PackStats stats, ExpansionStats expansion,
		int expandedUnits, int caseInsensitiveHits, long loadMillis) {
}
