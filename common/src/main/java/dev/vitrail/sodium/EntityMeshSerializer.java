package dev.vitrail.sodium;

import dev.vitrail.glsl.EntityVertex;
import dev.vitrail.render.EntityFrame;
import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.EntityMesh;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializer;
import net.caffeinemc.mods.sodium.api.vertex.serializer.VertexSerializerRegistry;

import org.lwjgl.system.MemoryUtil;

import java.util.Objects;

/**
 * What turns a cuboid Sodium wrote at the game's stride into a vertex of the mesh this engine binds,
 * identifiers included.
 * <p>
 * <strong>Every mob on screen comes through here and by no other road.</strong>
 * {@code mixin/features/render/entity/CubeMixin} cancels the game's own {@code compile} for every
 * cuboid of a {@code ModelPart} and writes it with {@code EntityRenderer.renderCuboid}, at
 * {@code EntityVertex.STRIDE} and into a buffer of its own, then hands the run over with
 * {@code VertexBufferWriter.push}. That push reserves room at the BUILDER's stride and copies the run
 * over raw whenever the two formats are the same object, and calls {@code copySlow} otherwise, which
 * asks {@code VertexSerializerRegistry} for the pair. A format apart is what makes that test fail;
 * this is what answers the question it then asks.
 * <p>
 * <strong>And it has to write the three appended elements itself, which is not a detail of this
 * class but the second half of why the mesh cannot simply be widened.</strong> Sodium never calls
 * {@code beginVertex} on that road, so {@code BufferBuilderMixin} never runs for a cuboid: without
 * this, no mob would carry an identifier, a sprite middle or a tangent whatever the stride agreed
 * on. Iris carries the same three in the same place
 * ({@code vertices/sodium/ModelToEntityVertexSerializer.java:66-83}).
 * <p>
 * <strong>Registering is not optional either.</strong> Left with no pair, the registry GENERATES a
 * serializer that copies the elements the two formats share
 * ({@code VertexSerializerRegistryImpl.createSerializer}), which is the six of them: the twenty bytes
 * after those would keep whatever the arena last held, and a pack would read one mob's identifier off
 * another's leavings and light it through a tangent that was never written.
 * <p>
 * The identifiers come from {@link EntityIdentifiers}, taken once for the whole run rather than per
 * vertex. That is right for the same reason Iris takes them once per run: a push is one submission's
 * geometry, so all of it was built while one entity was in hand.
 */
public final class EntityMeshSerializer implements VertexSerializer {

	/** What Sodium writes, which is the game's own entity vertex. */
	private static final int WRITTEN = DefaultVertexFormat.ENTITY.getVertexSize();

	/** What this engine binds, which is that plus the three elements. */
	private static final int CARRIED = EntityMesh.format().getVertexSize();

	/** Where the identifiers start inside one of those, taken off the format rather than counted. */
	private static final int IDENTIFIERS = carried(EntityVertex.IDENTIFIERS);

	/** And the middle of the sprite, and the tangent, which are the other two this engine appends. */
	private static final int MID_TEX_COORD = carried(EntityVertex.MID_TEX_COORD);

	private static final int TANGENT = carried(EntityVertex.TANGENT);

	/**
	 * Where the three the polygon is worked out from sit inside what Sodium wrote. Off the game's own
	 * format for the same reason as above: Sodium writes at the game's stride, and reading a position
	 * or a texture coordinate a fixed number of bytes in would be a literal to keep in step with a
	 * layout this engine does not own.
	 */
	private static final int POSITION = written("Position");

	private static final int TEX_COORD = written("UV0");

	private static final int NORMAL = written("Normal");

	private EntityMeshSerializer() {
	}

	/** Where one element of the mesh this engine binds starts inside a vertex of it. */
	private static int carried(String element) {
		return Objects.requireNonNull(EntityMesh.format().getElement(element),
				"The entity mesh was built without " + element).offset();
	}

	/** The same for the game's own format, which is what Sodium hands over. */
	private static int written(String element) {
		return Objects.requireNonNull(DefaultVertexFormat.ENTITY.getElement(element),
				"The game's entity format has no " + element).offset();
	}

	/**
	 * Names the pair to Sodium, once, and never again: the registry keys its cache on the two formats
	 * and both are built once for the run.
	 * <p>
	 * Called where the loader has a game to look at and Sodium is already up, which is the same place
	 * the pack is first read. Nothing here depends on a pack being loaded: the pair is a fact about
	 * two formats, and the answer to whether the mesh carries anything is
	 * {@code EntityMesh.carrying}'s alone.
	 */
	public static void register() {
		VertexSerializerRegistry.instance().registerSerializer(DefaultVertexFormat.ENTITY,
				EntityMesh.format(), new EntityMeshSerializer());
	}

	/**
	 * A run of Sodium's vertices into a run of this engine's, quad by quad.
	 * <p>
	 * <strong>Quad by quad because two of the three elements belong to the polygon</strong>, and this
	 * is the road where the four corners are simply four strides apart: Sodium wrote them into one
	 * arena and handed the whole run over at once. Iris walks it the same way,
	 * {@code vertices/sodium/ModelToEntityVertexSerializer.serialize}.
	 * <p>
	 * <strong>The normal is left exactly as Sodium wrote it</strong>, which is also Iris's answer on
	 * this road and not on the other: {@code EntityRenderer.renderCuboid} writes a face's own normal
	 * onto all four of its corners already, so there is nothing a quad could be asked that its
	 * corners do not agree on. The {@code BufferBuilder} road has no such promise and works one out.
	 * <p>
	 * A run that is not a whole number of quads keeps the stand-in on whatever is left over, rather
	 * than being dropped: Iris's loop copies {@code vertexCount >> 2} quads and never touches the
	 * remainder at all, which would leave those vertices at whatever the arena last held. It is the
	 * same stand-in {@code BufferBuilderMixin} writes for the same reason, nought for the sprite
	 * middle and {@code EntityFrame.FLAT} for the tangent, which are the two values
	 * {@code VertexPrologue} hands a mesh carrying neither. Nothing of the game writes a partial quad
	 * down this road, so it is a guard and not a case.
	 */
	@Override
	public void serialize(long written, long carried, int vertices) {
		short entity = (short) EntityIdentifiers.entity();
		short blockEntity = (short) EntityIdentifiers.blockEntity();
		short item = (short) EntityIdentifiers.item();

		int quads = vertices >> 2;
		long from = written;
		long into = carried;
		for (int quad = 0; quad < quads; quad++) {
			long second = from + WRITTEN;
			long third = second + WRITTEN;
			long fourth = third + WRITTEN;

			int normal = MemoryUtil.memGetInt(from + NORMAL);
			float midU = (MemoryUtil.memGetFloat(from + TEX_COORD)
					+ MemoryUtil.memGetFloat(second + TEX_COORD)
					+ MemoryUtil.memGetFloat(third + TEX_COORD)
					+ MemoryUtil.memGetFloat(fourth + TEX_COORD)) * 0.25F;
			float midV = (MemoryUtil.memGetFloat(from + TEX_COORD + 4L)
					+ MemoryUtil.memGetFloat(second + TEX_COORD + 4L)
					+ MemoryUtil.memGetFloat(third + TEX_COORD + 4L)
					+ MemoryUtil.memGetFloat(fourth + TEX_COORD + 4L)) * 0.25F;
			int tangent = EntityFrame.tangent(EntityFrame.unpack(normal, 0),
					EntityFrame.unpack(normal, 1), EntityFrame.unpack(normal, 2), false,
					MemoryUtil.memGetFloat(from + POSITION),
					MemoryUtil.memGetFloat(from + POSITION + 4L),
					MemoryUtil.memGetFloat(from + POSITION + 8L),
					MemoryUtil.memGetFloat(from + TEX_COORD),
					MemoryUtil.memGetFloat(from + TEX_COORD + 4L),
					MemoryUtil.memGetFloat(second + POSITION),
					MemoryUtil.memGetFloat(second + POSITION + 4L),
					MemoryUtil.memGetFloat(second + POSITION + 8L),
					MemoryUtil.memGetFloat(second + TEX_COORD),
					MemoryUtil.memGetFloat(second + TEX_COORD + 4L),
					MemoryUtil.memGetFloat(third + POSITION),
					MemoryUtil.memGetFloat(third + POSITION + 4L),
					MemoryUtil.memGetFloat(third + POSITION + 8L),
					MemoryUtil.memGetFloat(third + TEX_COORD),
					MemoryUtil.memGetFloat(third + TEX_COORD + 4L));

			for (int corner = 0; corner < 4; corner++) {
				copy(from, into, entity, blockEntity, item);
				MemoryUtil.memPutFloat(into + MID_TEX_COORD, midU);
				MemoryUtil.memPutFloat(into + MID_TEX_COORD + 4L, midV);
				MemoryUtil.memPutInt(into + TANGENT, tangent);
				from += WRITTEN;
				into += CARRIED;
			}
		}

		for (int vertex = quads << 2; vertex < vertices; vertex++) {
			copy(from, into, entity, blockEntity, item);
			MemoryUtil.memPutFloat(into + MID_TEX_COORD, 0.0F);
			MemoryUtil.memPutFloat(into + MID_TEX_COORD + 4L, 0.0F);
			MemoryUtil.memPutInt(into + TANGENT, EntityFrame.FLAT);
			from += WRITTEN;
			into += CARRIED;
		}
	}

	/**
	 * One vertex of Sodium's over one of this engine's, with the identifiers written after it.
	 * <p>
	 * Four shorts and not one long, for the reason {@code BufferBuilderMixin} writes four:
	 * {@code MemoryUtil} writes in the machine's own order, so a short at a time lands each lane
	 * where the format put it whichever way round the machine is. The fourth lane is a lane of the
	 * element like the other three and is written at nought rather than left.
	 */
	private static void copy(long from, long into, short entity, short blockEntity, short item) {
		MemoryUtil.memCopy(from, into, WRITTEN);
		MemoryUtil.memPutShort(into + IDENTIFIERS, entity);
		MemoryUtil.memPutShort(into + IDENTIFIERS + 2L, blockEntity);
		MemoryUtil.memPutShort(into + IDENTIFIERS + 4L, item);
		MemoryUtil.memPutShort(into + IDENTIFIERS + 6L, (short) 0);
	}
}
