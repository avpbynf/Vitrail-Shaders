package dev.vitrail.mixin;

import dev.vitrail.glsl.EntityVertex;
import dev.vitrail.render.EntityIdentifiers;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.PrimitiveTopology;
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
 * Writes the three identifiers onto every vertex of the entity mesh, since nothing in the game will.
 * <p>
 * <strong>The builder knows seven elements and ours is not one of them.</strong>
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
 * Four shorts and not one long: {@code MemoryUtil} writes in the machine's own order, so a short at
 * a time lands each lane where the format put it whichever way round the machine is, where a long
 * would swap them on a big endian one. The fourth lane is written too, at nought: it is a lane of
 * the element like the other three and leaving it would hand a stage that reads the whole element
 * whatever the arena held.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderMixin {

	@Shadow
	@Final
	private VertexFormat format;

	/**
	 * Where the element starts inside a vertex of this builder, or minus one when this builder is not
	 * building the entity mesh. Taken once in the constructor, the format of a builder being final.
	 */
	@Unique
	private int vitrail$offset;

	@Inject(method = "<init>", at = @At("RETURN"), require = 1)
	private void vitrail$findElement(ByteBufferBuilder buffer, PrimitiveTopology topology,
			VertexFormat format, CallbackInfo callback) {
		VertexFormatElement element = this.format.getElement(EntityVertex.IDENTIFIERS);
		this.vitrail$offset = element == null ? -1 : element.offset();
	}

	@Inject(method = "beginVertex", at = @At("RETURN"), require = 1)
	private void vitrail$writeIdentifiers(CallbackInfoReturnable<Long> callback) {
		if (this.vitrail$offset < 0) {
			return;
		}

		long pointer = callback.getReturnValueJ() + this.vitrail$offset;
		MemoryUtil.memPutShort(pointer, (short) EntityIdentifiers.entity());
		MemoryUtil.memPutShort(pointer + 2L, (short) EntityIdentifiers.blockEntity());
		MemoryUtil.memPutShort(pointer + 4L, (short) EntityIdentifiers.item());
		MemoryUtil.memPutShort(pointer + 6L, (short) 0);
	}
}
