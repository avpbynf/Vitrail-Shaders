package dev.vitrail.mixin;

import dev.vitrail.glsl.EntityVertex;
import dev.vitrail.render.EntityFrame;
import dev.vitrail.render.EntityIdentifiers;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.PrimitiveTopology;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Writes the three elements this engine appends onto every vertex of the entity mesh that does not
 * come from Sodium, since nothing in the game will.
 * <p>
 * <strong>The builder knows seven elements and none of ours is one of them.</strong>
 * {@code BufferBuilder} holds its elements in an array indexed by a semantic id, seven names long
 * ({@code BufferBuilder:39}), and {@code beginElement} takes that id rather than an element; an
 * eighth is filled by nobody and, for the same reason, missed by nobody either, its
 * {@code endLastVertex} weighing the same seven. So the bytes are reserved with the vertex and left
 * as whatever the arena last held there, which is why this is not optional: the pack's stage reads
 * them.
 * <p>
 * <strong>On {@code beginVertex} and not on {@code addVertex}, because there are two roads into a
 * vertex and only one of them is the second.</strong> The game writes an entity vertex through a
 * fast path of its own, eleven arguments and literal offsets ({@code BufferBuilder:297-306}), and
 * everything else through the eleven setters. Both begin the vertex here, and this is also where the
 * pointer to write at is handed out.
 * <p>
 * <strong>Two of the three belong to the POLYGON, and a polygon is only finished one vertex
 * late.</strong> The middle of the sprite is the mean of the corners' texture coordinates and the
 * tangent comes off three corners at once, so neither can be written where its own vertex begins:
 * the corners after it do not exist yet, and the corner in hand is nothing but a reserved stretch of
 * arena. So each vertex leaves its offset behind, and the polygon is filled in at the first moment
 * its last corner is known to be whole, which is the next {@code beginVertex} or {@code build}.
 * <p>
 * <strong>Those two callers and not {@code endLastVertex}, which is where Iris does the same
 * work</strong> ({@code mixin/vertices/MixinBufferBuilder.iris$beforeNext}) and which both of them
 * call. Sodium's {@code push} writes a whole run without ever beginning a vertex and leaves
 * {@code vertexPointer} on the last of them, so a hook there counts a corner belonging to no polygon
 * of this builder's; Iris carries a {@code skipEndVertexOnce} flag for exactly that, and taking the
 * callers instead leaves nothing to skip. A run pushed between two polygons is then simply invisible
 * here, which is right: {@link dev.vitrail.sodium.EntityMeshSerializer} has already filled it.
 * <p>
 * <strong>Offsets and not pointers.</strong> {@code ByteBufferBuilder.reserve} grows the arena with
 * a {@code realloc} when the next vertex would not fit, and every pointer handed out before that
 * moves with it. {@link ByteBufferBuilderAccessor} is where the base is asked for again.
 * <p>
 * Four shorts and not one long for the identifiers: {@code MemoryUtil} writes in the machine's own
 * order, so a short at a time lands each lane where the format put it whichever way round the
 * machine is, where a long would swap them on a big endian one. The fourth lane is written too, at
 * nought: it is a lane of the element like the other three and leaving it would hand a stage that
 * reads the whole element whatever the arena held.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {

	@Shadow
	@Final
	private VertexFormat format;

	@Shadow
	@Final
	private ByteBufferBuilder buffer;

	/**
	 * Where the identifiers start inside a vertex of this builder, or minus one when this builder is
	 * not building the entity mesh. Taken once in the constructor, the format of a builder being
	 * final, and it is the gate for all of this: the five offsets below are elements of the same
	 * format and stand or fall with it.
	 */
	@Unique
	private int vitrail$identifiers;

	@Unique
	private int vitrail$midTexCoord;

	@Unique
	private int vitrail$tangent;

	@Unique
	private int vitrail$position;

	@Unique
	private int vitrail$texCoord;

	@Unique
	private int vitrail$normal;

	/**
	 * How many corners a polygon of this builder has, or nought where its topology draws none that
	 * can be walked: a strip and a fan share corners with their neighbours, so there is no place a
	 * polygon begins. Those keep the stand-in every vertex is written with. Iris reads the same two
	 * topologies and no others.
	 */
	@Unique
	private int vitrail$corners;

	/** Where the corners of the polygon in hand begin, from the arena's own base. */
	@Unique
	private long @Nullable [] vitrail$corner;

	/** How many of those are written and whole. */
	@Unique
	private int vitrail$written;

	/** Those corners read back, five floats each: the position, then the texture coordinate. */
	@Unique
	private float @Nullable [] vitrail$read;

	/** The normal a quad's tangent is measured against, which is worked out rather than read. */
	@Unique
	private float @Nullable [] vitrail$face;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$findElements(ByteBufferBuilder buffer, PrimitiveTopology topology,
			VertexFormat format, CallbackInfo callback) {
		this.vitrail$identifiers = -1;
		// Asked on its own and ahead of the others, because asking is a WALK. A format keeps its
		// elements in an array map sixteen slots wide (VertexFormat's own elements field, a fastutil
		// Object2ObjectArrayMap), so a name is compared against every element the format has until it
		// matches or runs out, and a name that is not there is that walk paid in full. This is the one
		// name no format but this engine's carries, and the game builds one of these per render type
		// change, so the five below are five walks an ordinary builder no longer takes.
		int identifiers = vitrail$offsetOf(EntityVertex.IDENTIFIERS);
		if (identifiers < 0) {
			return;
		}

		int midTexCoord = vitrail$offsetOf(EntityVertex.MID_TEX_COORD);
		int tangent = vitrail$offsetOf(EntityVertex.TANGENT);
		int position = vitrail$offsetOf("Position");
		int texCoord = vitrail$offsetOf("UV0");
		int normal = vitrail$offsetOf("Normal");
		// Six or none, and never some of them: what this builder is is one question, and a format
		// carrying the first without the rest is not a state this engine can build. Answering it the
		// way a builder of any other format is answered is what keeps a constructor the game runs
		// thousands of times a frame from being a place anything can be thrown out of.
		if (midTexCoord < 0 || tangent < 0 || position < 0 || texCoord < 0 || normal < 0) {
			return;
		}

		this.vitrail$identifiers = identifiers;
		this.vitrail$midTexCoord = midTexCoord;
		this.vitrail$tangent = tangent;
		this.vitrail$position = position;
		this.vitrail$texCoord = texCoord;
		this.vitrail$normal = normal;
		this.vitrail$corners = switch (topology) {
			case QUADS -> 4;
			case TRIANGLES -> 3;
			default -> 0;
		};
		this.vitrail$corner = new long[4];
		this.vitrail$read = new float[20];
		this.vitrail$face = new float[3];
	}

	/**
	 * The identifiers, and the stand-in for the two the polygon owes.
	 * <p>
	 * The stand-in is written on every vertex and not only where a polygon will never come: what
	 * fills the two in runs one vertex late, so a buffer whose last polygon is a corner short would
	 * hand the pack whatever the arena held there. Nought for the middle of the sprite and an axis
	 * for the tangent, which are the two values {@code VertexPrologue} hands a mesh carrying neither,
	 * and the axis is not a nicety: a tangent of nought normalises to a NaN that reaches the colour.
	 */
	@Inject(method = "beginVertex", at = @At("RETURN"), require = 1)
	private void vitrail$writeVertex(CallbackInfoReturnable<Long> callback) {
		if (this.vitrail$identifiers < 0) {
			return;
		}

		// Before anything of this vertex is recorded, and it has to be here: the vertex that just
		// ended is the last corner of the polygon in hand, so this is the first moment all of it is
		// written.
		vitrail$fill();

		long pointer = callback.getReturnValueJ();
		long identifiers = pointer + this.vitrail$identifiers;
		MemoryUtil.memPutShort(identifiers, (short) EntityIdentifiers.entity());
		MemoryUtil.memPutShort(identifiers + 2L, (short) EntityIdentifiers.blockEntity());
		MemoryUtil.memPutShort(identifiers + 4L, (short) EntityIdentifiers.item());
		MemoryUtil.memPutShort(identifiers + 6L, (short) 0);
		MemoryUtil.memPutFloat(pointer + this.vitrail$midTexCoord, 0.0F);
		MemoryUtil.memPutFloat(pointer + this.vitrail$midTexCoord + 4L, 0.0F);
		MemoryUtil.memPutInt(pointer + this.vitrail$tangent, EntityFrame.FLAT);

		long[] corner = this.vitrail$corner;
		if (corner != null && this.vitrail$corners > 0) {
			corner[this.vitrail$written++] =
					pointer - ((ByteBufferBuilderAccessor) this.buffer).vitrail$pointer();
		}
	}

	/**
	 * The last polygon of the buffer, which no {@code beginVertex} follows.
	 * <p>
	 * At the head, ahead of {@code endLastVertex} and of the mesh being taken: a caller has written
	 * its last vertex whole by the time it asks for the mesh, and everything past this point only
	 * reads.
	 */
	@Inject(method = "build", at = @At("HEAD"), require = 1)
	private void vitrail$finishPolygon(CallbackInfoReturnable<MeshData> callback) {
		if (this.vitrail$identifiers >= 0) {
			vitrail$fill();
		}
	}

	/**
	 * The middle of the sprite and the tangent onto every corner of the polygon in hand, once all of
	 * its corners are written. Silent where there is no whole polygon to fill, which is every vertex
	 * but the one that follows a polygon's last.
	 */
	@Unique
	private void vitrail$fill() {
		long[] corner = this.vitrail$corner;
		float[] read = this.vitrail$read;
		float[] face = this.vitrail$face;
		int corners = this.vitrail$corners;
		if (corner == null || read == null || face == null || corners == 0
				|| this.vitrail$written < corners) {
			return;
		}

		this.vitrail$written = 0;
		long base = ((ByteBufferBuilderAccessor) this.buffer).vitrail$pointer();
		float midU = 0.0F;
		float midV = 0.0F;
		for (int at = 0; at < corners; at++) {
			long vertex = base + corner[at];
			int into = at * 5;
			read[into] = MemoryUtil.memGetFloat(vertex + this.vitrail$position);
			read[into + 1] = MemoryUtil.memGetFloat(vertex + this.vitrail$position + 4L);
			read[into + 2] = MemoryUtil.memGetFloat(vertex + this.vitrail$position + 8L);
			read[into + 3] = MemoryUtil.memGetFloat(vertex + this.vitrail$texCoord);
			read[into + 4] = MemoryUtil.memGetFloat(vertex + this.vitrail$texCoord + 4L);
			midU += read[into + 3];
			midV += read[into + 4];
		}

		midU /= corners;
		midV /= corners;

		// A quad is read flat and a triangle is not, which is Iris's own split
		// (MixinBufferBuilder.fillExtendedData) and its own reason: it took the face normal off the
		// triangle branch deliberately, its note at NormalHelper's call site saying that was "to
		// enable smooth shaded triangles". So a quad gets one tangent for its four corners, measured
		// against the normal its own corners give it; a triangle keeps the normal each corner was
		// given and takes the tangent that normal's own plane gives it.
		if (corners == 4) {
			vitrail$fillQuad(base, corner, read, face, midU, midV);

			return;
		}

		for (int at = 0; at < corners; at++) {
			long vertex = base + corner[at];
			int normal = MemoryUtil.memGetInt(vertex + this.vitrail$normal);
			vitrail$write(vertex, midU, midV, EntityFrame.tangent(EntityFrame.unpack(normal, 0),
					EntityFrame.unpack(normal, 1), EntityFrame.unpack(normal, 2), true,
					read[0], read[1], read[2], read[3], read[4],
					read[5], read[6], read[7], read[8], read[9],
					read[10], read[11], read[12], read[13], read[14]));
		}
	}

	/**
	 * The same for a quad, whose tangent is measured against a normal taken from its own corners
	 * rather than read off one of them, exactly as Iris measures it
	 * ({@code MixinBufferBuilder.fillExtendedData}, {@code NormalHelper.computeFaceNormal} then
	 * {@code computeTangent} on what it gives).
	 * <p>
	 * <strong>The normal itself is left as the game wrote it, and Iris writes its own back.</strong>
	 * A divergence, and what it works around is that this engine has no moment to hang the write-back
	 * on. Iris does it only inside the level render ({@code MixinBufferBuilder.java:244},
	 * {@code recalculateNormal = ImmediateState.isRenderingLevel}, under a comment at {@code :243}
	 * naming an item batching mod), and outside it the question never arises because it does not
	 * extend the format there at all ({@code MixinBufferBuilder.java:98} and
	 * {@code MixinRenderPipeline.java:26}, both on the same flag). This engine settles its format
	 * once and binds it wherever the game declares the entity one,
	 * {@link dev.vitrail.render.EntityMesh#settle()} saying why it cannot be read live and
	 * {@link dev.vitrail.mixin.RenderPipelineMixin} doing the binding, so a write-back here would
	 * reach geometry Iris never touches, an item drawn into an inventory screen among it.
	 * <p>
	 * <strong>What it costs is the handedness and not the direction.</strong> The corners are not
	 * flattened onto this normal's plane here, so it reaches {@code EntityFrame.tangent} for one
	 * thing only, the sign in the fourth component. A quad whose corners were given a normal that is
	 * not the face's therefore keeps a tangent pointing the right way and may get that sign turned
	 * over, which is a bump lighting as a dent. No entity geometry of the game reaches this road in
	 * that state: a {@code ModelPart} cuboid does not come this way at all, Sodium's
	 * {@code CubeMixin} taking it, and a baked quad carries one normal over its four corners.
	 * <p>
	 * A quad of no area falls back on the normal of its first corner: corners lying on a line have no
	 * normal to give, and normalising nought is a NaN that would travel into the colour through the
	 * whole tangent frame. {@code EntityFrame.faceNormal} is what refuses, and Iris has no such
	 * branch: {@code NormalHelper.computeFaceNormalManual:85-91} normalises whatever the cross
	 * product gave. It costs a degenerate quad a tangent measured against a corner normal rather than
	 * a NaN, and the game draws no such quad here.
	 */
	@Unique
	private void vitrail$fillQuad(long base, long[] corner, float[] read, float[] face, float midU,
			float midV) {
		if (!EntityFrame.faceNormal(face, read[0], read[1], read[2], read[5], read[6], read[7],
				read[10], read[11], read[12], read[15], read[16], read[17])) {
			int given = MemoryUtil.memGetInt(base + corner[0] + this.vitrail$normal);
			face[0] = EntityFrame.unpack(given, 0);
			face[1] = EntityFrame.unpack(given, 1);
			face[2] = EntityFrame.unpack(given, 2);
		}

		int tangent = EntityFrame.tangent(face[0], face[1], face[2], false,
				read[0], read[1], read[2], read[3], read[4],
				read[5], read[6], read[7], read[8], read[9],
				read[10], read[11], read[12], read[13], read[14]);
		for (int at = 0; at < 4; at++) {
			vitrail$write(base + corner[at], midU, midV, tangent);
		}
	}

	/** One corner's share of what the polygon worked out. */
	@Unique
	private void vitrail$write(long vertex, float midU, float midV, int tangent) {
		MemoryUtil.memPutFloat(vertex + this.vitrail$midTexCoord, midU);
		MemoryUtil.memPutFloat(vertex + this.vitrail$midTexCoord + 4L, midV);
		MemoryUtil.memPutInt(vertex + this.vitrail$tangent, tangent);
	}

	/** Where one element starts inside a vertex of this builder, or minus one where it has none. */
	@Unique
	private int vitrail$offsetOf(String element) {
		VertexFormatElement found = this.format.getElement(element);

		return found == null ? -1 : found.offset();
	}
}
