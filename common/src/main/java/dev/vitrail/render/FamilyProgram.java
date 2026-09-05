package dev.vitrail.render;

import dev.vitrail.uniform.WorldState;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;

/**
 * What every family's program is over the {@link GeometryProgram} it holds: the part of the
 * contract that is the same seven times, delegated once.
 * <p>
 * A family adapter exists for what differs, which is how its geometry is placed and what it is
 * drawn with: the sky hands in a model view and a colour, the terrain an atlas, the far terrain a
 * projection. Everything that is not that, compiling, dumping, rotating the ring, releasing, was
 * written out seven times as {@code return this.body.x();}, and a family that forgot one of them
 * silently took the interface's default: it compiled, it drew, and it paid shaderc on the render
 * thread at its first draw with nothing to say so. Here the default is the delegation, and the
 * one family that must not compile ahead says so where it stands.
 */
abstract class FamilyProgram implements DumpedProgram {

	protected final GeometryProgram body;

	FamilyProgram(GeometryProgram body) {
		this.body = body;
	}

	/**
	 * The pass this program is drawn into, or null to leave the renderer the one it meant to open.
	 *
	 * @see GeometryProgram#descriptor
	 */
	RenderPassDescriptor descriptor(GpuTextureView colour, GpuTextureView depth) {
		return this.body.descriptor(colour, depth);
	}

	/**
	 * Binds this program's block and every sampler it declares, inside the pass just opened.
	 *
	 * @see GeometryProgram#bind
	 */
	void bind(RenderPass pass) {
		this.body.bind(pass);
	}

	/** @see GeometryProgram#compile */
	@Override
	public boolean compile(GpuDevice device) {
		return this.body.compile(device);
	}

	/** @see GeometryProgram#compiled */
	@Override
	public boolean compiled() {
		return this.body.compiled();
	}

	/** @see GeometryProgram#forgetCompiled */
	@Override
	public void forgetCompiled() {
		this.body.forgetCompiled();
	}

	/** @see GeometryProgram#warmAhead */
	@Override
	public boolean warmAhead(VulkanDevice device, GlslCompiler compiler) {
		return this.body.warmAhead(device, compiler);
	}

	/** @see GeometryProgram#discardAhead */
	@Override
	public void discardAhead() {
		this.body.discardAhead();
	}

	/** @see GeometryProgram#decoded */
	@Override
	public String decoded(WorldState world) {
		return this.body.decoded(world);
	}

	/** @see GeometryProgram#path */
	@Override
	public String path() {
		return this.body.path();
	}

	/** @see GeometryProgram#label */
	@Override
	public String label() {
		return this.body.label();
	}

	/**
	 * Rotates the ring buffer, once the frame's draw has been recorded.
	 *
	 * @see GeometryProgram#rotate
	 */
	void rotate() {
		this.body.rotate();
	}

	/**
	 * Closes this program's block and the placeholder textures it made.
	 *
	 * @see GeometryProgram#release
	 */
	void release() {
		this.body.release();
	}
}
