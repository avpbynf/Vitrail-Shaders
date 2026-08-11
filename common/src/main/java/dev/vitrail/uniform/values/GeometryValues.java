package dev.vitrail.uniform.values;

import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.UniformSource;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.WorldState;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.Set;

/**
 * The fixed function state of a pass drawn over the world rather than over a quad.
 * <p>
 * Seven names {@link DrawValues} already answers, layered over it, plus two this family alone has.
 * The seven are answered there with the stand ins a full screen pass needs: an identity model view,
 * the matrix that carries the quad to the screen, and eight identities where the texture matrices
 * were. Handing those to a terrain program would put every block of the world inside a unit cube at
 * the corner of the screen and every surface at the saturated corner of the light map, so the seven
 * are answered again here and the rest of the table is shared, which is the point of layering rather
 * than of a second catalogue.
 * <p>
 * What the matrices hold is the gbuffer pair, and it has to be that pair rather than anything
 * rebuilt.
 * Every pack of the corpus writes its clip position as some arrangement of
 * {@code gl_ProjectionMatrix}, {@code gbufferModelView} and {@code gl_ModelViewMatrix}, and BSL
 * writes {@code gbufferModelViewInverse * gl_ModelViewMatrix * gl_Vertex} on the way in: the two
 * cancel only if they are the same matrix, and a difference of one frame between them shows as a
 * world that lags the sky.
 * <p>
 * The projection is the published one, which is the OpenGL form. A vertex stage ends on the epilogue
 * that puts a clip depth back into the volume the target is rasterised in, so the pack is handed the
 * matrix it was written for at both ends.
 */
public final class GeometryValues {

	/**
	 * How far a chunk mesh's texture coordinate is pulled into its sprite, and where the two numbers
	 * come from.
	 * <p>
	 * Sodium's {@code UniformBufferManager.update} writes
	 * {@code 3.0517578E-5 - 1/atlasSize/subTexelPrecision} per axis, and its
	 * {@code GPULimits.getSubTexelPrecisionBits} is eight everywhere but macOS. The first term is one
	 * unit of the fifteen bit encoding the coordinate arrives in; the second takes off the sub texel
	 * the rasteriser is allowed to be wrong by. Copied rather than approximated: the whole point of
	 * the number is that it is smaller than a texel and larger than the error, and a value invented
	 * here would be one or the other by luck.
	 */
	private static final float SUB_TEXEL_OFFSET = 3.0517578E-5F;
	private static final float SUB_TEXEL_PRECISION = 256.0F;

	/**
	 * Which of the eight texture matrices are the light map's, in the fixed function pipeline the
	 * packs are written against.
	 * <p>
	 * Two and not one, because unit two is a second name for unit one and not a unit of its own. That
	 * is how the vertex side of the families the game hands over as a render type reads it - the
	 * entity prologue defines {@code of_MultiTexCoord2} as the same pair as
	 * {@code of_MultiTexCoord1} - and Iris substitutes the very same sixteen numbers for both
	 * matrices there, {@code VanillaCoreTransformer.java:87-90}.
	 * <p>
	 * <strong>It does not hold on the terrain, and that half is a divergence.</strong> Iris renames
	 * {@code gl_MultiTexCoord2} to unit one on its Sodium path and substitutes nothing at all for
	 * {@code gl_TextureMatrix[2]} there ({@code SodiumTransformer.java:39}), while this engine
	 * answers unit two with a matrix and hands the chunk prologue a {@code vec4(0.0)} for the
	 * attribute. A terrain program multiplying the two would read a flat 1/32 where Iris reads the
	 * real pair. <strong>Nothing rests on it</strong>: measured on the eight packs, ZERO files name
	 * {@code gl_MultiTexCoord2} and zero write {@code gl_TextureMatrix[2]}, against forty four that
	 * write {@code [1]}. It is written down rather than fixed because fixing it belongs to the chunk
	 * prologue, which is where the aliasing is missing.
	 */
	private static final Set<Integer> LIGHTMAP_UNITS = Set.of(1, 2);

	/**
	 * What {@code gl_TextureMatrix[1]} held when the game still had a fixed function pipeline, and
	 * therefore what a pack means by it.
	 * <p>
	 * The light map arrives on the vertex as the game stores it, a pair of levels from nought to two
	 * hundred and forty in steps of sixteen, and it is sampled out of a sixteen by sixteen texture
	 * filtered LINEAR. This scale puts a level on its own texel and this translation puts it on that
	 * texel's CENTRE: level {@code i} arrives at {@code 16i}, comes out at {@code i/16 + 1/32}, and
	 * the sixteenth lands at 0.969 rather than on the edge where the filter would mix it with its
	 * neighbour. Iris carries the same sixteen numbers,
	 * {@code uniforms/builtin/BuiltinReplacementUniforms.java:12}, and substitutes them for
	 * {@code gl_TextureMatrix[1]} on the terrain, {@code SodiumTransformer.java:32}, and on every
	 * family the game hands over as a render type, {@code VanillaTransformer.java:164}.
	 * <p>
	 * <strong>One family of Iris's gets the identity there instead, and it is a divergence this table
	 * cannot express</strong>: Distant Horizons geometry, {@code DHTerrainTransformer.java:24} and
	 * {@code DHGenericTransformer.java:24}, both replacing {@code gl_TextureMatrix[1]} with
	 * {@code mat4(1.0)}. A {@code dh_} program of a pack therefore reads this matrix here and an
	 * identity under Iris. What it costs is nothing today and the reason is not this file's doing:
	 * no Distant Horizons pass of this engine runs at all, so no {@code dh_} program is ever handed
	 * a block. The day one runs, this table has to be asked by family and not once for all of them.
	 * <p>
	 * The third axis is scaled and translated with the other two, which is Iris's matrix rather than a
	 * reading of what a light map needs: nothing samples a third coordinate out of a two dimensional
	 * texture, and a pack that writes the {@code .xy} of the product never sees it. Copied all the
	 * same, because a matrix a pack multiplies something else by is a matrix whose every column can
	 * be read.
	 */
	private static final Matrix4f LIGHTMAP_TEXTURE_MATRIX = new Matrix4f(
			1.0F / 256.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 1.0F / 256.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 1.0F / 256.0F, 0.0F,
			1.0F / 32.0F, 1.0F / 32.0F, 1.0F / 32.0F, 1.0F);

	/** The six units that are not the light map's, which the fixed function pipeline left alone. */
	private static final Matrix4f IDENTITY = new Matrix4f();

	private GeometryValues() {
	}

	/** One axis of the shrink, from the atlas that axis is measured on. */
	private static float shrink(int size) {
		return SUB_TEXEL_OFFSET - 1.0F / Math.max(1, size) / SUB_TEXEL_PRECISION;
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add("of_TexShrink", UniformShape.VEC2, (world, out) ->
				out.set(shrink(world.atlasWidth()), shrink(world.atlasHeight())));

		// The colour the game modulates a whole draw by, which for the sky is where its colour is:
		// the mesh of a sky disc carries a position and nothing else, and the mesh of the sunrise
		// band carries only the fade. White for every pass that has not set one.
		builder.add("of_PassColour", UniformShape.VEC4, (world, out) -> out.set(
				world.passColour().x(), world.passColour().y(), world.passColour().z(),
				world.passColour().w()));

		// The PASS's model view and not the frame's, and the two are one matrix for every pass but
		// the sky's and the four entity pieces the game nudges towards the viewer. What separates
		// them is written out in ViewSource.passModelView: the game
		// puts the sun where it is by pushing a rotation onto its own stack, and a pack reads that
		// rotation here while reading the camera under gbufferModelView, using both at once.
		builder.add("of_ModelViewMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.passModelView()));
		builder.add("of_ModelViewMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.passModelViewInverse()));
		builder.add("of_ProjectionMatrix", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferProjection()));
		builder.add("of_ProjectionMatrixInverse", UniformShape.MAT4,
				(world, out) -> out.set(world.gbufferProjectionInverse()));

		// Composed here rather than published, because nothing else in the engine reads it: a full
		// screen pass is handed the quad projection for it and a pack calling ftransform() is the
		// only reader. Left to right, so that the pack's own gl_ProjectionMatrix * gl_ModelViewMatrix
		// and its ftransform() are the same matrix.
		builder.add("of_ModelViewProjectionMatrix", UniformShape.MAT4, (world, out) ->
				out.set(new Matrix4f(world.gbufferProjection()).mul(world.passModelView())));

		// The inverse transpose of the model view's rotation. The level's model view is a pure
		// rotation, so this is its transpose, but it is computed rather than assumed: a shadow pass
		// or a pack directive could put a scale in it, and normalising a normal afterwards hides
		// the difference exactly until it does not.
		builder.add("of_NormalMatrix", UniformShape.MAT3, (world, out) ->
				out.set(new Matrix3f().set(world.passModelView()).invert().transpose()));

		// Six identities and the light map's twice, where the engine table answers eight identities.
		// That is the whole of the difference between a pass drawn over the world and one drawn over a
		// quad: a quad has no light map, and Iris replaces all eight of its texture matrices with the
		// identity for exactly that reason (CompositeTransformer.java:43).
		//
		// A read of the identity here is not a small error. The idiom every pack writes is
		// (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy, and with the identity that lands at 240 on
		// both axes, which the sampler clamps to the corner of the light map: full block light and
		// full sky light, on every surface, whatever the world is doing.
		builder.add("of_TextureMatrix", UniformShape.MAT4, new UniformSource() {

			@Override
			public void read(WorldState world, Val out) {
				out.set(IDENTITY);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				out.set(LIGHTMAP_UNITS.contains(element) ? LIGHTMAP_TEXTURE_MATRIX : IDENTITY);
			}
		});
	}
}
