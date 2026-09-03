package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * What a pass drawn over a quad gets instead of the fixed function state a program would have had.
 * <p>
 * The matrices depend on nothing, which is why they are the first thing the catalogue answers
 * and can be answered before the frame state is filled in. A full screen pass has no
 * model view worth the name: the model view is the identity and the projection is the one that
 * carries the quad onto the screen, so their product is the projection.
 * <p>
 * The values at the bottom are copied from Iris rather than left out, and they are copied whole:
 * outside a gbuffers pass they are what Iris supplies as well, so a composite declaring
 * {@code entityColor} both compiles and reads the number it would read there. What differs is what
 * happens INSIDE a gbuffers pass drawn from the entity mesh, and it differs in the mesh rather than
 * in the table: Iris stops answering four of these from a uniform at all and answers them from the
 * mesh. This engine now answers all four the same way, the overlay colour out of the element the
 * game's own format already carries and the three identifiers out of the element the entity mesh
 * appends, so inside such a pass the name reaches a table on neither engine.
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

		// Iris's own answer for a pass whose mesh has no overlay, which is what this table serves:
		// a composite, the terrain, the sky (uniforms/CommonUniforms.java:163). Where the mesh DOES
		// carry the overlay the name never reaches a table at all, here or there - the vertex stage
		// fetches the texel and hands the colour on as a varying, glsl/GlslTranslator.overlayPrologue
		// against pipeline/transform/transformer/EntityPatcher.java:39-56.
		builder.add("entityColor", UniformShape.VEC4,
				(_, out) -> out.set(0.0F, 0.0F, 0.0F, 0.0F));
		// The three identifiers a pack tells one entity, block entity or held item apart by, and the
		// numbers are Iris's own: it registers blockEntityId and currentRenderedItemId at -1 once
		// and for the session (uniforms/CommonUniforms.java:164-165), and entityId comes off a field
		// its render dispatcher sets to the entity being submitted and back to zero at the end of
		// every submit (mixin/entity_render_context/MixinEntityRenderDispatcher.java:82 and :89), so
		// zero is what a pass drawn between two entities reads. The -1 that field starts at
		// (uniforms/CapturedRenderingState.java:23) is the value before the first entity of the
		// session and is not what a pack meets.
		//
		// So these three are the right answer wherever Iris hands the uniform over, which is every
		// pass whose mesh has no overlay: the composites, and the terrain and the sky with them.
		// Where the mesh DOES carry them the name never reaches a table at all, here or there, the
		// vertex stage handing each on out of a lane of the element the mesh carries,
		// glsl/GlslTranslator.identifierPrologue against EntityPatcher.java:124-204.
		//
		// One case Iris keeps the uniform for is not about the mesh at all, and it is not live here:
		// the lightning (uniforms/CommonUniforms.java:72-73), whose shard puts the live identifier
		// in the field around the draw (layer/LightningRenderStateShard.java:19-21). Served, our
		// lightning would read zero there, and what a pack loses is the branch it writes on that one
		// identifier. No program of the pack serves the lightning here, the game drawing it and the
		// feature layer carrying it in, so today it costs the image nothing.
		builder.add("entityId", UniformShape.INT, (_, out) -> out.set(0));
		builder.add("blockEntityId", UniformShape.INT, (_, out) -> out.set(-1));
		builder.add("currentRenderedItemId", UniformShape.INT, (_, out) -> out.set(-1));
		// What the pass's own program discards at, and in a pass drawn over a quad the reference
		// held when the chain wrote its one block for the frame: ViewSource.passAlphaTest says
		// what Iris hands a composite instead, and why the difference reaches no pack.
		builder.add("alphaTestRef", UniformShape.FLOAT, (world, out) -> out.set(world.passAlphaTest()));
	}
}
