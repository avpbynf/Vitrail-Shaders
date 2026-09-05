package dev.vitrail.pack.model;

import java.util.List;
import java.util.Optional;

/**
 * One {@code bufferObject.N} directive, as Iris reads it and before anything is allocated.
 * <p>
 * The grammar is Iris's, {@code ShaderProperties} around the {@code bufferObject.} handler: an
 * index, a size in bytes, an optional block name, or the four-word relative form. Twelve is the
 * ceiling Iris enforces. Nothing here touches a device.
 *
 * @see <a href="https://github.com/IrisShaders/Iris">Iris ShaderStorageInfo, LGPL-3.0</a>
 */
public record BufferObject(int index, long size, boolean relative, float scaleX, float scaleY,
		Optional<String> name) {

	/** Iris refuses an index past twelve; those slots are reserved. */
	public static final int LIMIT = 12;

	/**
	 * What {@link dev.vitrail.pack.source.ShaderProperties#bufferObjects} answered, including the
	 * lines that were live and could not be read.
	 */
	public record Reading(List<BufferObject> buffers, List<String> dropped) {

		public Reading {
			buffers = List.copyOf(buffers);
			dropped = List.copyOf(dropped);
		}

		public static Reading empty() {
			return new Reading(List.of(), List.of());
		}

		public boolean hasIndex(int index) {
			for (BufferObject buffer : this.buffers) {
				if (buffer.index() == index) {
					return true;
				}
			}

			return false;
		}

		public boolean hasName(String name) {
			for (BufferObject buffer : this.buffers) {
				if (buffer.name().filter(name::equals).isPresent()) {
					return true;
				}
			}

			return false;
		}
	}

	/** One clause for the log, saying the index, the optional name and the size. */
	public String describe() {
		String size = this.relative
				? this.size + " bytes per pixel at " + this.scaleX + "x" + this.scaleY
						+ " of the screen"
				: this.size + " bytes";

		return this.index
				+ this.name.map(name -> " as " + name).orElse("")
				+ " " + size;
	}
}
