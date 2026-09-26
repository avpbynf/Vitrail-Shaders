package dev.vitrail.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.PointerBuffer;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * The pass 26.3 draws the whole level through, made one this engine can step out of.
 * <p>
 * <strong>Why it exists.</strong> On 26.2 every phase of the level opened a render pass of its own:
 * the opaque terrain, the solid features, the translucents, the clouds, the weather. The engine
 * sent the families a pack serves into passes on the pack's targets by rewriting those openings,
 * and ran its own stages, the deferred passes among them, in the gaps between two phases, where no
 * pass was open. 26.3 opens ONE pass on the main target for all of it and hands it to every phase,
 * NeoForge's stage events included, so there is no gap: a pass the engine opens there, a clear or a
 * copy it records, is refused, the game's pass being still open.
 * <p>
 * <strong>What it does.</strong> The game's pass is replaced by this one when the level opens it.
 * Every call is forwarded to a real pass, opened on the same attachments with the same load, and
 * {@link #suspendCurrent} closes that real pass whenever the engine needs the gap 26.2 had: a pass
 * of its own being opened, a clear, a copy, a compute. The next call the game makes on this pass
 * opens a real one again, with the default uniforms bound, the debug groups pushed again and the
 * scissor set again, which is all the state a phase relies on across draws: the pipeline, the
 * buffers and the uniforms of a draw are set by the draw. So the game draws as though it held one
 * pass, and the engine gets the gaps back.
 * <p>
 * It never clears: the pass the level opens loads what the frame drew before it, and so does every
 * pass opened again after a gap.
 * <p>
 * <strong>The engine's own feature storages are drawn through one as well</strong>, the hand's and
 * the shadow casters' ({@code GameRender.renderAllFeatures}), for the reason the level's is: the
 * entity door opens a pass of its own for a run of draws the pack serves, and the pass the draws
 * were handed has to step aside for it. One may be opened while another is current, the level's
 * being the one under it: the newer is current until it closes, and the one it opened over is
 * current again after, its real pass having been closed when the newer one's opened and opening
 * again at its own next call.
 */
public final class LevelPass implements RenderPass {

	/**
	 * The pass of this kind opened last and not yet closed: the level's while the level is being
	 * drawn, one of the engine's own feature passes while it draws a storage of its own, and null
	 * at every other instant.
	 */
	private static @Nullable LevelPass current;

	private final @Nullable LevelPass under;
	private final GpuTextureView colour;
	private final @Nullable GpuTextureView depth;
	private final Supplier<RenderPass> opener;
	private final List<Supplier<String>> groups = new ArrayList<>();
	private @Nullable RenderPass real;
	private int @Nullable [] scissor;
	private boolean closed;

	private LevelPass(@Nullable LevelPass under, RenderPass first, GpuTextureView colour,
			@Nullable GpuTextureView depth, Supplier<RenderPass> opener) {
		this.under = under;
		this.real = first;
		this.colour = colour;
		this.depth = depth;
		this.opener = opener;
	}

	/**
	 * Replaces the pass the level, or the engine for a storage of its own, has just opened.
	 * {@code opener} opens a real pass on the same attachments with nothing emptied, which is what
	 * the level's own opening does.
	 *
	 * @param colour the colour image the pass was opened on
	 * @param depth  the depth image it was opened on, or null where it has none
	 */
	public static RenderPass open(RenderPass first, GpuTextureView colour,
			@Nullable GpuTextureView depth, Supplier<RenderPass> opener) {
		LevelPass pass = new LevelPass(current, first, colour, depth, opener);
		current = pass;
		return pass;
	}

	/**
	 * The colour image this pass draws into, which is the main target's.
	 * <p>
	 * On 26.2 a phase of the level that a pack serves was about to open a pass of its own, and the
	 * engine read the images that pass would have been opened on to build the pack's pass beside
	 * them. On 26.3 the phase is handed this pass instead, and these two are those images.
	 */
	public GpuTextureView colour() {
		return this.colour;
	}

	/** The depth image this pass tests against, or null where it has none. */
	public @Nullable GpuTextureView depth() {
		return this.depth;
	}

	/**
	 * Closes the real pass under the level's pass, if one is open, so the engine can record what a
	 * pass may not hold. The level's next call opens it again. Safe at any instant, and a no-op
	 * outside the level.
	 */
	public static void suspendCurrent() {
		LevelPass pass = current;
		if (pass != null) {
			pass.suspend();
		}
	}

	/** Whether the level's pass currently holds a real pass open. */
	public static boolean holding() {
		LevelPass pass = current;
		return pass != null && pass.real != null;
	}

	private void suspend() {
		RenderPass open = this.real;
		if (open == null) {
			return;
		}

		this.real = null;
		for (int i = 0; i < this.groups.size(); i++) {
			open.popDebugGroup();
		}

		open.close();
	}

	private RenderPass real() {
		RenderPass open = this.real;
		if (open != null) {
			return open;
		}

		if (this.closed) {
			throw new IllegalStateException("The level's pass is used after the level closed it");
		}

		open = this.opener.get();
		this.real = open;
		RenderSystem.bindDefaultUniforms(open);
		for (Supplier<String> group : this.groups) {
			open.pushDebugGroup(group);
		}

		if (this.scissor != null) {
			open.enableScissor(this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
		}

		return open;
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
		this.groups.add(label);
		if (this.real != null) {
			this.real.pushDebugGroup(label);
		}
	}

	@Override
	public void popDebugGroup() {
		if (!this.groups.isEmpty()) {
			this.groups.removeLast();
		}

		if (this.real != null) {
			this.real.popDebugGroup();
		}
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
		real().writeTimestamp(pool, index);
	}

	@Override
	public void setPipeline(CompiledRenderPipeline pipeline) {
		real().setPipeline(pipeline);
	}

	@Override
	public void setUniform(String name, GpuTextureView view, GpuSampler sampler) {
		real().setUniform(name, view, sampler);
	}

	@Override
	public void setUniform(String name, GpuBuffer buffer) {
		real().setUniform(name, buffer);
	}

	@Override
	public void setUniform(String name, GpuBufferSlice slice) {
		real().setUniform(name, slice);
	}

	@Override
	public void pushConstants(ByteBuffer constants) {
		real().pushConstants(constants);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		this.scissor = new int[] {x, y, width, height};
		real().enableScissor(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		this.scissor = null;
		if (this.real != null) {
			this.real.disableScissor();
		}
	}

	@Override
	public void setVertexBuffer(int slot, GpuBufferSlice slice) {
		real().setVertexBuffer(slot, slice);
	}

	@Override
	public void setIndexBuffer(GpuBuffer buffer, IndexType type) {
		real().setIndexBuffer(buffer, type);
	}

	@Override
	public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount,
			int firstInstance) {
		real().drawIndexed(baseVertex, firstIndex, indexCount, instanceCount, firstInstance);
	}

	@Override
	public void multiDrawIndexed(IntBuffer baseVertices, int firstIndex, int indexCount,
			int drawCount) {
		real().multiDrawIndexed(baseVertices, firstIndex, indexCount, drawCount);
	}

	@Override
	public void multiDrawIndexed(PointerBuffer indices, IntBuffer counts, IntBuffer baseVertices,
			int drawCount) {
		real().multiDrawIndexed(indices, counts, baseVertices, drawCount);
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		real().drawIndexedIndirect(commands, drawCount);
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer indices,
			IndexType type, Collection<String> uniformNames, T user) {
		real().drawMultipleIndexed(draws, indices, type, uniformNames, user);
	}

	@Override
	public void draw(int firstVertex, int vertexCount, int firstInstance, int instanceCount) {
		real().draw(firstVertex, vertexCount, firstInstance, instanceCount);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, int vertexCount, int instanceCount,
			int drawCount) {
		real().multiDraw(firstVertices, vertexCount, instanceCount, drawCount);
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		real().multiDraw(firstVertices, vertexCounts, drawCount);
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		real().drawIndirect(commands, drawCount);
	}

	@Override
	public void close() {
		suspend();
		this.closed = true;
		if (current == this) {
			// The one it was opened over, unless that one has been closed meanwhile, which a
			// storage drawn across the level's end would see.
			LevelPass next = this.under;
			while (next != null && next.closed) {
				next = next.under;
			}

			current = next;
		}
	}
}
