package dev.vitrail.render;

import dev.vitrail.glsl.DistantVertex;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.List;
import java.util.Map;

/**
 * The vertex format Distant Horizons filled its buffers with, declared here so that a program of
 * the pack can be bound over them.
 * <p>
 * <strong>It is DH's layout and not a format of this engine's</strong>, which is the whole
 * difference from {@code EntityMesh}: nothing here is appended, nothing is chosen, and the buffers
 * were written before this engine saw them. What is decided is only which of the elements a stage
 * declares, and {@link DistantVertex} says why that is not a saving but a requirement.
 * <p>
 * <strong>A format with a hole in it keeps the offsets of the elements around the hole</strong>, and
 * that is what the stride form of {@code addAttribute} is for: told a stride wider than the element,
 * the builder leaves the difference unclaimed and puts the next element where DH really wrote it. A
 * hole closed up instead would read the following element out of the wrong bytes, which is a wrong
 * normal and a wrong material rather than a compile failure. The vertex size is DH's as well, the
 * last element's stride carrying whatever is left, so the sixth element nobody declares is paid for
 * by the padding at the end.
 */
public final class DistantMesh {

	/** Where DH writes each element, in bytes from the start of the vertex. */
	private static final Map<String, Integer> OFFSETS = Map.of(
			DistantVertex.POSITION, 0,
			DistantVertex.META, 6,
			DistantVertex.COLOUR, 8,
			DistantVertex.MATERIAL, 12,
			DistantVertex.NORMAL, 13);

	/** What each element is, which is DH's answer and no reading of ours. */
	private static final Map<String, GpuFormat> FORMATS = Map.of(
			DistantVertex.POSITION, GpuFormat.RGB16_UINT,
			DistantVertex.META, GpuFormat.R16_UINT,
			DistantVertex.COLOUR, GpuFormat.RGBA8_UNORM,
			DistantVertex.MATERIAL, GpuFormat.R8_UINT,
			DistantVertex.NORMAL, GpuFormat.R8_UINT);

	/**
	 * Sixteen bytes: the five elements above and a texture tile at fourteen that nothing declares.
	 * Checked against what DH's own buffers hold rather than trusted, {@code dh/DhLods} dividing a
	 * buffer's length by the vertices DH says are in it.
	 */
	public static final int STRIDE = 16;

	private DistantMesh() {
	}

	/**
	 * The format to bind for a stage declaring those elements, in DH's own order.
	 *
	 * @param carried the elements really declared, which is {@code DistantVertex.carried} over the
	 *                union of what the pack's far terrain programs read
	 */
	static VertexFormat format(List<String> carried) {
		// Step rate nought, which is DH's own answer (BlazeVertexFormatBuilder) and the only one that
		// makes sense: a step rate turns the binding into a per instance one, and there are no
		// instances here.
		VertexFormat.Builder builder = VertexFormat.builder(0);
		for (int index = 0; index < carried.size(); index++) {
			String element = carried.get(index);
			int at = OFFSETS.get(element);
			// How far to the next element the stage declares, or to the end of the vertex for the
			// last of them. That difference is the element's own width plus every byte DH wrote that
			// this stage does not read.
			int next = index + 1 < carried.size() ? OFFSETS.get(carried.get(index + 1)) : STRIDE;
			builder.addAttribute(element, next - at, FORMATS.get(element));
		}

		return builder.build();
	}
}
