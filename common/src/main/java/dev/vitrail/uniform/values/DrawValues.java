package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * What a pass drawn over a quad gets instead of the fixed function state a program would have had.
 * <p>
 * None of these depends on the world, which is why they are the first thing the catalogue answers
 * and the only thing it answers before the frame state is filled in. A full screen pass has no
 * model view worth the name: the model view is the identity and the projection is the one that
 * carries the quad onto the screen, so their product is the projection.
 * <p>
 * The stand ins at the bottom are copied from Iris rather than left out. They exist so that a
 * program declaring {@code entityColor} outside a gbuffers pass compiles and reads something
 * harmless instead of failing to resolve; the real values arrive with the gbuffers, and at that
 * point three of them stop being block members at all and become the components of a flat integer
 * vector, which moves the layout. They are named here so that finding them again is not a search.
 */
public final class DrawValues {

	/**
	 * Carries the quad from (0,1) to clip space. Iris uses this one, so packs are written for it.
	 * <p>
	 * Its third column is entirely zero, so every vertex lands at {@code z_clip = 0}. That is
	 * valid in both volumes and means something different in each: under the reversed Z the game
	 * draws with it is the far plane, and under the zero to one convention it would be the near
	 * one. Nothing samples it, so neither reading matters, but it is not a matrix to reason about
	 * casually.
	 */
	public static final Matrix4f QUAD_PROJECTION = new Matrix4f().set(
			2.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 2.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 0.0F, 0.0F,
			-1.0F, -1.0F, 0.0F, 1.0F);

	/**
	 * The inverse of the part of {@link #QUAD_PROJECTION} that has one, written out rather than
	 * computed.
	 * <p>
	 * The quad projection is singular: its third column is zero, so its determinant is zero and
	 * asking JOML to invert it gives infinities and NaNs, which then travel into whatever the pack
	 * does with them. This is the inverse of the non-degenerate part, taking clip back to (0,1).
	 * The z is lost, which is inherent to a projection that throws z away, and is the reason this
	 * is a constant with a paragraph rather than a call to invert.
	 */
	public static final Matrix4f QUAD_PROJECTION_INVERSE = new Matrix4f().set(
			0.5F, 0.0F, 0.0F, 0.0F,
			0.0F, 0.5F, 0.0F, 0.0F,
			0.0F, 0.0F, 0.0F, 0.0F,
			0.5F, 0.5F, 0.0F, 1.0F);

	private static final Matrix4f IDENTITY = new Matrix4f();
	private static final Matrix3f NORMAL_IDENTITY = new Matrix3f();

	private DrawValues() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("of_ModelViewMatrix", UniformShape.MAT4, (_, out) -> out.set(IDENTITY));
		builder.add("of_ModelViewMatrixInverse", UniformShape.MAT4, (_, out) -> out.set(IDENTITY));
		builder.add("of_ModelViewProjectionMatrix", UniformShape.MAT4, (_, out) -> out.set(QUAD_PROJECTION));
		builder.add("of_ProjectionMatrix", UniformShape.MAT4, (_, out) -> out.set(QUAD_PROJECTION));
		builder.add("of_ProjectionMatrixInverse", UniformShape.MAT4, (_, out) -> out.set(QUAD_PROJECTION_INVERSE));
		builder.add("of_NormalMatrix", UniformShape.MAT3, (_, out) -> out.set(NORMAL_IDENTITY));

		// Eight identities, and here that is the whole answer rather than a stand in: the pack reads
		// gl_TextureMatrix[0] and expects the texture coordinates it was handed, which for a quad are
		// already the ones it wants, and Iris substitutes the identity for all eight of them in a
		// composite for the same reason (CompositeTransformer.java:43).
		//
		// It is the GEOMETRY table that has to answer differently, and it does: a quad carries no
		// light map, so unit one is the identity here and the light map's matrix there.
		builder.add("of_TextureMatrix", UniformShape.MAT4, (_, out) -> out.set(IDENTITY));

		builder.add("viewWidth", UniformShape.FLOAT, (world, out) -> out.set(world.viewWidth()));
		builder.add("viewHeight", UniformShape.FLOAT, (world, out) -> out.set(world.viewHeight()));
		builder.add("aspectRatio", UniformShape.FLOAT,
				(world, out) -> out.set(world.viewWidth() / Math.max(1.0F, world.viewHeight())));

		// How a vertex stage moves a legacy clip position into the volume the target really uses,
		// and how a fragment stage reads a depth back out of it. Answered whether or not the
		// translation emits the member yet, because the answer does not depend on that.
		builder.add("of_DepthConv", UniformShape.VEC4, (world, out) -> out.set(
				world.depthConvention().x(), world.depthConvention().y(),
				world.depthConvention().z(), world.depthConvention().w()));

		builder.add("pi", UniformShape.FLOAT, (_, out) -> out.set((float) Math.PI));
		builder.add("atlasSize", UniformShape.IVEC2,
				(world, out) -> out.set(world.atlasWidth(), world.atlasHeight()));
		builder.add("renderStage", UniformShape.INT, (world, out) -> out.set(world.renderStage()));
		builder.add("anisotropicFiltering", UniformShape.INT,
				(world, out) -> out.set((int) world.anisotropy()));
		builder.add("currentColorSpace", UniformShape.INT,
				(world, out) -> out.set(world.colorSpace()));
		builder.add("textureFilteringMode", UniformShape.INT,
				(world, out) -> out.set(world.textureFilteringMode()));
		builder.add("chunkFadeTimeInv", UniformShape.FLOAT,
				(world, out) -> out.set(world.chunkFadeTimeInv()));
		builder.add("ambientOcclusionLevel", UniformShape.FLOAT,
				(world, out) -> out.set(world.ambientOcclusionLevel()));
		builder.add("noiseTextureResolution", UniformShape.FLOAT,
				(world, out) -> out.set(world.noiseTextureResolution()));

		builder.add("entityColor", UniformShape.VEC4,
				(_, out) -> out.set(0.0F, 0.0F, 0.0F, 0.0F));
		// The three identifiers a pack tells one entity, block entity or held item apart by, and not
		// one of them is a value: nothing in this engine ever writes one, so each is the same number
		// on every draw and a pack branching on it takes one branch for the whole world. Iris reads
		// them off a vertex element of its own, an unsigned short triple it adds to its entity
		// format and hands the stages back as iris_entityInfo
		// (pipeline/transform/transformer/EntityPatcher.java:125-160); the game's
		// DefaultVertexFormat.ENTITY has no room for one and this engine decodes the game's format.
		//
		// The numbers are Iris's own uniform fallback, which is what it gives a program the element
		// never reached, for two of the three (uniforms/CommonUniforms.java:164-165). entityId is
		// zero where that fallback is -1 (uniforms/CommonUniforms.java:73), and which of the two a
		// program should read is genuinely open: nothing in Iris ever sets that fallback either, so
		// the answer a shader really takes there comes from the element, whose type cannot carry -1.
		//
		// UniformGaps names all three, so that the log says which of a program's values are these
		// rather than leaving them to count as supplied.
		builder.add("entityId", UniformShape.INT, (_, out) -> out.set(0));
		builder.add("blockEntityId", UniformShape.INT, (_, out) -> out.set(-1));
		builder.add("currentRenderedItemId", UniformShape.INT, (_, out) -> out.set(-1));
		builder.add("alphaTestRef", UniformShape.FLOAT, (_, out) -> out.set(0.0F));
	}
}
