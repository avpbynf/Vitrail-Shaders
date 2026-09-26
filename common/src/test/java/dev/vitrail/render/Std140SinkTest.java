package dev.vitrail.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vitrail.uniform.Std140Counter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.blaze3d.buffers.Std140Builder;
import org.joml.Matrix3f;
import org.junit.jupiter.api.Test;

/**
 * Holds the bytes a block member is written at to the offset the shader reads it at, which is the
 * offset {@link Std140Counter} sizes the block by.
 * <p>
 * The write goes through the game's own builder, and the game changed that builder under it: 26.3's
 * {@code putVec3} stops at twelve bytes where 26.2's padded itself to sixteen. A mat3 built out of
 * that call came out four bytes short on 26.3 and every member after it was read from the wrong
 * place, which reached the screen as Distant Horizons' land clipped away whole. The builder is the
 * one in the jar this module is compiled against, so the check holds for whichever game is built.
 */
class Std140SinkTest {

	@Test
	void placesTheMemberAfterAMat3WhereTheCounterDoes() {
		ByteBuffer buffer = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder());
		new Std140Sink(Std140Builder.intoBuffer(buffer)).putMat3(new Matrix3f()).putFloat(7.0F);

		Std140Counter counter = new Std140Counter();
		counter.putMat3(new Matrix3f()).putFloat(7.0F);

		assertEquals(52, counter.size(), "a mat3 is three columns of sixteen, then the float");
		assertEquals(counter.size(), buffer.position(), "bytes written against bytes counted");
		assertEquals(7.0F, buffer.getFloat(48), "the float after the mat3");
	}

	@Test
	void placesTheMemberAfterAVec3AtTwelve() {
		ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder());
		new Std140Sink(Std140Builder.intoBuffer(buffer)).putVec3(1.0F, 2.0F, 3.0F).putFloat(7.0F);

		assertEquals(16, buffer.position());
		assertEquals(7.0F, buffer.getFloat(12), "the float after the vec3");
	}
}
