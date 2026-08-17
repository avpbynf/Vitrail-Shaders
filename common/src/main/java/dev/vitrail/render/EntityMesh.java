package dev.vitrail.render;

import dev.vitrail.glsl.EntityVertex;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/**
 * The game's own entity format with the three identifiers appended to it, and the reason it is that
 * format rather than one of ours.
 * <p>
 * <strong>The element is added to {@code DefaultVertexFormat.ENTITY} itself, and every other way of
 * doing it is wrong here.</strong> Iris puts its own object in the buffer builder's hand
 * ({@code mixin/vertices/MixinBufferBuilder.iris$extendFormat}) and gives its own pipelines that
 * object to bind; this engine hands entity geometry back to the game's pipelines on every road where
 * a pack serves nothing, the warm up that follows a load included, so the mesh and the game's own
 * binding have to be the same width or the game draws the world at the wrong stride. Two more
 * consequences make the same choice: {@code BufferBuilder} picks its fast write path for an entity
 * vertex by comparing the format by IDENTITY ({@code BufferBuilder:64}, {@code entityFormat =
 * format == DefaultVertexFormat.ENTITY}) and writes that path at literal offsets, and
 * {@code RenderPipelines} builds every entity pipeline out of the same field at class init.
 * Lengthening the object leaves all three agreeing with no one having to be told.
 * <p>
 * <strong>What it costs, and it is paid whether a pack is loaded or not:</strong> eight bytes on
 * every entity vertex the game builds, plus the four shorts {@code BufferBuilderMixin} writes into
 * them. Iris pays the same eight only while a pack is in use, because it may decide per buffer; the
 * decision here is taken in a class initialiser, long before a pack can be known, and it cannot be
 * moved afterwards without the mesh and the pipelines disagreeing for a frame. The alternative was
 * not free either: an identifier handed over as a uniform would break the batch at every change of
 * entity, which is a draw per mob rather than eight bytes per vertex.
 * <p>
 * The element goes LAST, after {@code Normal}, and that is what keeps the game's own entity shader
 * drawing through this format: an element a stage does not declare shifts the location of every
 * element after it and says nothing, so there has to be nothing after it.
 */
public final class EntityMesh {

	/**
	 * The name the game gives the overlay, and the one element of {@code DefaultVertexFormat} no
	 * format but the entity one carries. It is what tells the entity format apart from the fifteen
	 * others built in the same class initialiser, and it is a better question than counting them:
	 * a format is recognised by what it holds rather than by the order it was written in.
	 */
	private static final String OVERLAY = "UV1";

	private EntityMesh() {
	}

	/**
	 * Hands back the format the game just built, with the identifiers appended when that format is
	 * the entity one.
	 * <p>
	 * Rebuilt element by element from what it was handed rather than written out again, so that the
	 * six keep the offsets and the formats the game gave them whatever the game does to them next.
	 * Every element of that format is laid out end to end, so appending one after the last leaves
	 * the six exactly where they were.
	 */
	public static VertexFormat lengthen(VertexFormat built) {
		if (!built.contains(OVERLAY) || built.contains(EntityVertex.IDENTIFIERS)) {
			return built;
		}

		VertexFormat.Builder builder = VertexFormat.builder(built.getStepRate());
		for (VertexFormatElement element : built.getElements()) {
			builder.addAttribute(element.name(), element.format());
		}

		// Four lanes of which three are read, and unsigned: EntityVertex.IDENTIFIERS says why both.
		// Four is also the only width that fits, a vertex having to be a multiple of four bytes wide.
		return builder.addAttribute(EntityVertex.IDENTIFIERS, GpuFormat.RGBA16_UINT).build();
	}
}
