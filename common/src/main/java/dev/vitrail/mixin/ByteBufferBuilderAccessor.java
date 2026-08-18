package dev.vitrail.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where the arena a {@code BufferBuilder} writes into currently begins.
 * <p>
 * <strong>It moves, and that is the whole reason this exists.</strong> {@code reserve} grows the
 * arena with a {@code realloc} whenever the next vertex would not fit, and every pointer handed out
 * before that moves with it. {@code BufferBuilderMixin} has to look back at the corners of a polygon
 * once its last one is written, so it keeps them as offsets from this and asks for it again at the
 * moment it reads them. Iris keeps the same base for the same reason, through an accessor of its own
 * ({@code vertices/MojangBufferAccessor}).
 * <p>
 * The field is private with no getter beside it, so an accessor is the only road to it.
 */
@Mixin(ByteBufferBuilder.class)
public interface ByteBufferBuilderAccessor {

	@Accessor("pointer")
	long vitrail$pointer();
}
