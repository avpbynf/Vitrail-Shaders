package dev.vitrail.render;

import dev.vitrail.mixin.access.CommandEncoderAccessor;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;

/**
 * Fills the mip chain of a colour target. Nothing of the pack takes part.
 * <p>
 * It exists because the public encoder has no {@code generateMipmaps}. Iris pays one
 * {@code glGenerateMipmap} per chain; the Vulkan equivalent is a blit of each level into the next
 * on the frame's command buffer, which {@link MipmapCommands} puts on the encoder.
 * <p>
 * What the packs do with those levels is not decoration: BSL drives its automatic exposure from
 * {@code texture2DLod(colortex0, vec2(0.5), log2(viewHeight * R))}, which without a chain reads
 * level nought at the centre of the screen, so the whole image is exposed for one pixel and darkens
 * wholesale the moment a jump moves that pixel from the ground to the sky. The same pack reads lods
 * for its depth of field and for the tiles of its bloom.
 * <p>
 * The blit uses the hardware linear filter, which is what {@code glGenerateMipmap} gave the packs,
 * and it floors the extent of every level, which is what lets a chain run to one texel on the
 * longer side: a render pass per level was the road before it, and the game refuses a pass on a
 * level whose shorter side shifts to nought, so that road stopped a level short of OpenGL's chain
 * on every screen that is not as tall as it is wide.
 */
final class MipmapReduction {

	private MipmapReduction() {
	}

	/**
	 * Fills every level past the base of one surface, reading the level above each time.
	 * <p>
	 * Must run outside any render pass. Silent and harmless on a surface with no chain, which is
	 * every target no program reads at a lod: the caller is not expected to sort them out first.
	 *
	 * @return false when the chain could not be filled, in which case the levels hold whatever they
	 *         held and a lod read falls back to what it read before there were chains
	 */
	static boolean generate(CommandEncoder encoder, TargetSurface surface) {
		if (surface == null || surface.levels() <= 1) {
			return false;
		}

		GeometryHold.flush(() -> "a mip chain being filled");
		CommandEncoderBackend backend = ((CommandEncoderAccessor) encoder).vitrail$backend();
		if (!(backend instanceof MipmapCommands commands)
				|| !commands.vitrail$generateMipmaps(surface.texture())) {
			return false;
		}

		surface.chainWritten(true);

		return true;
	}
}
