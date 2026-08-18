package dev.vitrail.sodium;

import dev.vitrail.glsl.EntityVertex;
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
 * <strong>And it has to write the identifiers itself, which is not a detail of this class but the
 * second half of why the mesh cannot simply be widened.</strong> Sodium never calls
 * {@code beginVertex} on that road, so {@code BufferBuilderMixin} never runs for a cuboid: without
 * this, no mob would carry the three whatever the stride agreed on. Iris carries the same three in
 * the same place ({@code vertices/sodium/ModelToEntityVertexSerializer.java:76-78}).
 * <p>
 * <strong>Registering is not optional either.</strong> Left with no pair, the registry GENERATES a
 * serializer that copies the elements the two formats share
 * ({@code VertexSerializerRegistryImpl.createSerializer}), which is the six of them: the eight bytes
 * after those would keep whatever the arena last held, and a pack would read one mob's identifier off
 * another's leavings.
 * <p>
 * The three come from {@link EntityIdentifiers}, taken once for the whole run rather than per vertex.
 * That is right for the same reason Iris takes them once per run: a push is one submission's
 * geometry, so all of it was built while one entity was in hand.
 */
public final class EntityMeshSerializer implements VertexSerializer {

	/** What Sodium writes, which is the game's own entity vertex. */
	private static final int WRITTEN = DefaultVertexFormat.ENTITY.getVertexSize();

	/** What this engine binds, which is that plus the element. */
	private static final int CARRIED = EntityMesh.format().getVertexSize();

	/** Where the element starts inside one of those, taken off the format rather than counted. */
	private static final int OFFSET = Objects.requireNonNull(
			EntityMesh.format().getElement(EntityVertex.IDENTIFIERS),
			"The entity mesh was built without " + EntityVertex.IDENTIFIERS).offset();

	private EntityMeshSerializer() {
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
	 * Four shorts and not one long, for the reason {@code BufferBuilderMixin} writes four:
	 * {@code MemoryUtil} writes in the machine's own order, so a short at a time lands each lane
	 * where the format put it whichever way round the machine is. The fourth lane is a lane of the
	 * element like the other three and is written at nought rather than left.
	 */
	@Override
	public void serialize(long written, long carried, int vertices) {
		short entity = (short) EntityIdentifiers.entity();
		short blockEntity = (short) EntityIdentifiers.blockEntity();
		short item = (short) EntityIdentifiers.item();

		long from = written;
		long into = carried;
		for (int vertex = 0; vertex < vertices; vertex++) {
			MemoryUtil.memCopy(from, into, WRITTEN);
			MemoryUtil.memPutShort(into + OFFSET, entity);
			MemoryUtil.memPutShort(into + OFFSET + 2L, blockEntity);
			MemoryUtil.memPutShort(into + OFFSET + 4L, item);
			MemoryUtil.memPutShort(into + OFFSET + 6L, (short) 0);
			from += WRITTEN;
			into += CARRIED;
		}
	}
}
