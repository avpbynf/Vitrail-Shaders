package dev.vitrail.render;

import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The shadow map: one depth image the world is drawn into from the light, and the colour targets
 * beside it.
 * <p>
 * <strong>Two colour targets and not one, which is Iris's own default.</strong> It opens
 * {@code {0, 1}} for a shadow program whose draw buffers it cannot read
 * ({@code pipeline/programs/SodiumPrograms.java:137-139}) and allocates the pair whatever the pack
 * declares ({@code shadows/ShadowRenderTargets.java:45}, sized from
 * {@code shaderpack/properties/PackShadowDirectives.java:19-20}). Serving nought alone was not a
 * saving, it was a picture: Complementary writes its light shaft tint into {@code shadowcolor1}
 * ({@code program/shadow.glsl:208-209}, under {@code SHADOW_QUALITY >= 1}, which holds at its own
 * defaults) and its volumetric light reads that name for the density of every ray crossing something
 * translucent ({@code lib/atmospherics/volumetricLight/volumetricLight.glsl:191-194}). Handed the
 * white stand-in instead, the pack read {@code pow2(1.0 * 4.0)}, sixteen where its own tint gives at
 * most about one and a half, and every body of water filled with milk - the whole screen from under
 * the surface, the lake alone from the bank.
 * <p>
 * Square, at the resolution the pack asked for and at no other, which is the one number about this
 * stage that cannot be chosen here. A pack picks its filter radius in texels of its own map, so a
 * map at half the size it declared is a penumbra at twice the width, and it looks like a pack that
 * was written that way rather than like a mistake of ours.
 * <p>
 * <strong>The map stores the forward window, nought at the near plane and one at the far one, and
 * that is a decision rather than an inheritance.</strong> The scene is drawn under a reversed Z the
 * translation undoes on the way out, but a {@code shadowtex} lookup is never wrapped: the pack
 * compares what it reads against distances it computed itself, in OptiFine's own window, so the map
 * has to hold that window and the depth test has to run the other way from the scene's. Everything
 * that follows from it is in one place each: the {@code FORWARD} pair the shadow programs are given,
 * and the compare op of their pipeline.
 * <p>
 * No texture view is ever held, for the same reason {@link ColorTargets} holds none: a resize closes
 * the views behind it and nothing on this backend notices a view that has outlived its texture.
 */
final class ShadowTargets {

	/** The far plane in the window this map stores. See the class comment before changing it. */
	private static final double FAR = 1.0;

	/** Past this the pack is asking for more than any device here will give it. */
	private static final int MAX_RESOLUTION = 16384;

	/**
	 * How many colour targets the light draws into. Iris's own number, and its ceiling of eight is
	 * reached only by a pack asking for {@code HIGHER_SHADOWCOLOR}, a feature nothing here declares:
	 * a pack that asks for it is being told the eight are not there, so allocating them would be
	 * memory nothing can address.
	 */
	static final int COLOURS = 2;

	private final int resolution;
	private final List<PackDirectives.ShadowColour> asked;
	private final List<GpuFormat> formats;
	private final List<Vector4fc> clearColours;

	/**
	 * Whether each colour has yet to be emptied once. A pack that turns its own clear off is asking
	 * to keep what the shadow stage wrote from one frame to the next, not to read whatever the
	 * allocation left in the image, and Iris says the same on the directive: the clear colour is
	 * still what the buffer starts life holding.
	 */
	private final boolean[] unstarted = new boolean[COLOURS];

	private TextureTarget target;

	/**
	 * Every colour past nought, which the depth cannot share a {@link TextureTarget} with: that class
	 * carries one colour attachment and one depth, so the rest are images of their own, attached
	 * beside it by whoever opens the pass.
	 */
	private final TargetSurface[] rest = new TargetSurface[COLOURS - 1];

	/**
	 * The map as it stood before anything translucent was drawn into it, which the OptiFine model
	 * calls {@code shadowtex1}.
	 * <p>
	 * A copy and not a second pass. The two names differ by one thing only, whether the translucent
	 * geometry is in them, so the cheap way round is to take the depth once the opaque half is done
	 * and let the translucent half carry on into the original. What a pack does with the pair is
	 * compare them: a point occluded in nought and clear in one is behind something translucent, and
	 * that is the whole test a coloured shadow rests on.
	 */
	private GpuTexture noTranslucents;
	private GpuTextureView noTranslucentsView;

	/**
	 * Whether that copy still stands for the map as it is now. Lowered by every clear, because a copy
	 * is a moment of the map and emptying the map is the moment it stops being one: the same rule
	 * {@link PackDepth} follows for the two depths it converts, where an image nothing has filled is
	 * not handed to a pack at all.
	 */
	private boolean copied;

	private boolean broken;

	ShadowTargets(int resolution, List<PackDirectives.ShadowColour> asked) {
		// Clamped rather than refused: a directive that survived a setting nobody expanded can be
		// any number at all, and a shadow map is not worth taking the pack down for.
		this.resolution = Math.clamp(resolution, 1, MAX_RESOLUTION);
		if (this.resolution != resolution) {
			Vitrail.logger().warn("The pack asks for a shadow map of {} texels, which is outside what "
					+ "this engine will allocate, so it is drawn at {}", resolution, this.resolution);
		}

		this.asked = List.copyOf(asked);
		this.formats = this.asked.stream().map(one -> GpuFormats.of(one.format().used())).toList();
		// The same correction the colour targets make, and it stops where theirs stops: a format
		// that gained an alpha channel on the way to the device starts opaque, because in GL the
		// three component texture the pack wrote against always sampled as one and the promoted
		// image returns what is really there - but a pack that named a clear colour itself wrote
		// four components and is handed the four it wrote. Forcing the alpha over the pack's own
		// value would be this engine overruling it on a channel it was explicit about.
		this.clearColours = this.asked.stream().map(one -> {
			TargetDirectives.Colour colour = one.clearColour();

			return (Vector4fc) new Vector4f(colour.r(), colour.g(), colour.b(),
					one.format().alphaAdded() && !one.declaresClearColour() ? 1.0F : colour.a());
		}).toList();
	}

	/**
	 * Makes the map exist and empties it once. Must run on the render thread and outside any render
	 * pass.
	 * <p>
	 * <strong>Emptied where it is allocated, and that is not a duplicate of the clear the stage does
	 * at its top.</strong> The map is allocated with the colour targets, at the head of a frame, and
	 * the stage that fills it runs at the tail of one: the gbuffers in between read a map nothing has
	 * ever written, which is whatever the driver left in that memory, sampled as a depth. And a stage
	 * the engine option keeps switched off never opens at all, so the clear at its top is not reached
	 * once in the session while the map is allocated all the same. One clear here answers both, where
	 * a clear per frame would pay for a map the size the pack chose to no purpose.
	 *
	 * @return false when there is nothing to draw into, in which case no shadow pass may run
	 */
	boolean ensure() {
		if (this.broken) {
			return false;
		}

		if (this.target != null) {
			return true;
		}

		try {
			// One object for both, so that the colour and the depth cannot part company on a size:
			// they are attachments of one render pass and one render pass has one render area.
			this.target = new TextureTarget("Vitrail shadow", this.resolution, this.resolution, true,
					this.formats.get(0));
			for (int index = 1; index < COLOURS; index++) {
				this.rest[index - 1] = new TargetSurface("Vitrail shadowcolor" + index,
						this.formats.get(index), false, this.resolution, this.resolution);
			}

			Arrays.fill(this.unstarted, true);
			clear(RenderSystem.getDevice().createCommandEncoder());
			Vitrail.logger().info("Shadow map allocated at {}x{}, depth and {} colour targets: {}",
					this.resolution, this.resolution, COLOURS, describe());

			return true;
		} catch (RuntimeException e) {
			this.broken = true;
			// The images go with the refusal, so that a map allocated and not emptied is never the
			// one a lookup lands on: ensure answering false only stops the pass being drawn, where
			// the gbuffers bind whatever depth() still holds.
			release();
			Vitrail.logger().error("Vitrail could not allocate the shadow map, so no shadow pass will "
					+ "run and every shadowtex lookup keeps reading one pixel", e);

			return false;
		}
	}

	/**
	 * Empties the map, once a frame, before anything is drawn into it. The depth goes to the far
	 * plane of the window this map stores, and the colour to what the pack asked for, which is white
	 * unless it said otherwise: white is what a pack reads as "nothing of the shadow stage touched
	 * this", and it is what a coloured shadow multiplies by.
	 * <p>
	 * The depth is emptied whatever the pack says, and only the colour takes the directive.
	 * {@code shadowcolorNClear} is about the buffer the pack writes; the depth is the map itself, and
	 * a map carried over from a frame that drew a different world is not something any pack asks for.
	 * <p>
	 * <strong>Emptying the map drops the copy beside it as well</strong>, and it has to: the depth is
	 * read under two names and the clear only reaches one image. A copy left standing is what
	 * {@code shadowtex1} keeps being served, so a stage that stops between frames would empty the map
	 * the pack samples as {@code shadowtex0} and leave the other name on the last half drawn map of
	 * the session, which is exactly the picture the clear exists to prevent. Dropped rather than
	 * emptied in turn: {@link #depthWithoutTranslucents} already falls back to the live map, so what
	 * the pack then reads is the image this call just took to the far plane, at no cost per frame.
	 */
	void clear(CommandEncoder encoder) {
		this.copied = false;
		if (this.target == null) {
			return;
		}

		// Each buffer takes its own directive, which is why this is a loop and not one test: Mellow
		// asks for nought to be kept between frames and says nothing about one, and a pack may say
		// the opposite. The depth rides on nought because they are one object here, and it is
		// emptied whichever way that directive reads.
		if (wanted(0)) {
			encoder.clearColorAndDepthTextures(this.target.getColorTexture(), this.clearColours.get(0),
					this.target.getDepthTexture(), FAR);
		} else {
			encoder.clearDepthTexture(this.target.getDepthTexture(), FAR);
		}

		for (int index = 1; index < COLOURS; index++) {
			TargetSurface surface = this.rest[index - 1];
			if (surface != null && wanted(index)) {
				encoder.clearColorTexture(surface.texture(), this.clearColours.get(index));
			}
		}
	}

	/**
	 * Whether this buffer is emptied on this frame: because the pack asks for it every frame, or
	 * because nothing has ever written it and what a fresh allocation holds is not a value a pack
	 * asked to keep.
	 */
	private boolean wanted(int index) {
		if (this.asked.get(index).clear() || this.unstarted[index]) {
			this.unstarted[index] = false;

			return true;
		}

		return false;
	}

	/** Each colour target's format and whether the pack keeps it, for the allocation's own line. */
	private String describe() {
		return IntStream.range(0, COLOURS)
				.mapToObj(index -> "shadowcolor" + index + " as " + this.formats.get(index)
						+ (this.asked.get(index).clear() ? "" : ", which the pack keeps between frames"))
				.collect(Collectors.joining(", "));
	}

	/**
	 * Takes the copy the pack reads as {@code shadowtex1}: the map as it stands, which the caller
	 * has to invoke once the opaque halves are drawn and before the translucent one is. Must run on
	 * the render thread and outside any render pass.
	 */
	void copyWithoutTranslucents(CommandEncoder encoder) {
		GpuTexture depth = this.target == null ? null : this.target.getDepthTexture();
		if (depth == null || this.broken) {
			return;
		}

		if (this.noTranslucents == null) {
			// The source's own format rather than an assumed one, the same rule the world's depth
			// copy follows: a copy whose format differs from its source is refused outright.
			this.noTranslucents = RenderSystem.getDevice().createTexture(() -> "Vitrail shadowtex1",
					GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, depth.getFormat(),
					this.resolution, this.resolution, 1, 1);
			this.noTranslucentsView = RenderSystem.getDevice().createTextureView(this.noTranslucents);
		}

		encoder.copyTextureToTexture(depth, this.noTranslucents, 0, 0, 0, 0, 0, this.resolution,
				this.resolution);
		this.copied = true;
	}

	/** The depth with everything in it, which the pack reads as {@code shadowtex0}. */
	GpuTextureView depth() {
		return this.target == null ? null : this.target.getDepthTextureView();
	}

	/**
	 * The depth without the translucents, or the depth with them while no copy stands for the map as
	 * it is now.
	 * <p>
	 * Falling back to the live image rather than to white is the same choice the world's depth copy
	 * makes: the wrong moment of the right image says "nothing translucent is in the way", which is
	 * true of every frame until the translucent shadow pass runs, and white would say the far plane
	 * and put every surface behind glass.
	 */
	GpuTextureView depthWithoutTranslucents() {
		return this.copied ? this.noTranslucentsView : depth();
	}

	/**
	 * A shadow colour target, or null past the pair this engine allocates. Never nought's image under
	 * another name: a pack reading a buffer nothing filled has to read the clear colour, which is
	 * white and which it multiplies by, where nought's image would be a picture that is plausible and
	 * wrong.
	 */
	GpuTextureView colour(int index) {
		if (index < 0 || index >= COLOURS) {
			return null;
		}

		if (index == 0) {
			return this.target == null ? null : this.target.getColorTextureView();
		}

		TargetSurface surface = this.rest[index - 1];

		return surface == null ? null : surface.view();
	}

	int resolution() {
		return this.resolution;
	}

	/** What a colour is allocated in, known from the directives before anything is allocated. */
	GpuFormat format(int index) {
		return this.formats.get(index);
	}

	void release() {
		this.copied = false;
		if (this.target != null) {
			this.target.destroyBuffers();
			this.target = null;
		}

		for (int index = 1; index < COLOURS; index++) {
			if (this.rest[index - 1] != null) {
				this.rest[index - 1].close();
				this.rest[index - 1] = null;
			}
		}

		if (this.noTranslucentsView != null) {
			this.noTranslucentsView.close();
			this.noTranslucentsView = null;
		}

		if (this.noTranslucents != null) {
			this.noTranslucents.close();
			this.noTranslucents = null;
		}
	}
}
