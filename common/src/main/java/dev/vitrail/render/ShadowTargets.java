package dev.vitrail.render;

import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuDeviceLossException;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.util.Mth;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * The shadow map: one depth image the world is drawn into from the light, and the colour targets
 * beside it.
 * <p>
 * <strong>Two colour targets, or eight where the pack asked for them.</strong> The ceiling is
 * Iris's and it is read off the PACK: two where {@code HIGHER_SHADOWCOLOR} is not declared, eight
 * where it is ({@code shadows/ShadowRenderTargets.java:46}, from
 * {@code shaderpack/properties/PackShadowDirectives.java:19-20}). Posing the define for every pack
 * does not raise it, a define being what a pack READS and a declaration what it ASKS FOR, so the
 * number here is {@code TargetPlan.shadowCeiling} and every index is admitted against that. Iris
 * opens {@code {0, 1}} for a shadow program whose draw buffers it cannot read
 * ({@code pipeline/programs/SodiumPrograms.java:137-139}). When each of them is built differs here,
 * and the field they live in says why.
 * <p>
 * Serving nought alone was not a saving, it was a picture: Complementary writes its light shaft
 * tint into {@code shadowcolor1} ({@code program/shadow.glsl:208-209}, under
 * {@code SHADOW_QUALITY >= 1}, which holds at its own defaults) and its volumetric light reads that
 * name for the density of every ray crossing something translucent
 * ({@code lib/atmospherics/volumetricLight/volumetricLight.glsl:191-194}). Handed the white stand-in
 * instead, the pack read {@code pow2(1.0 * 4.0)}, sixteen, where the tint its own shadow program
 * writes for plain glass is {@code vec3(0.3)} and gives {@code pow2(1.2)}, one and a half
 * ({@code program/shadow.glsl:189}). Eleven times the density on that one material, and every body
 * of water filled with milk: the whole screen from under the surface, the lake alone from the bank.
 * <p>
 * Square, at the resolution the pack's text asks for, which may already carry the player's shadow
 * map scale: that scale is applied by rewriting the pack's own declaration before a line of it is
 * translated, so what arrives here is a number the pack's whole arithmetic agrees with. Nothing
 * about the size is decided in this class, and it must not be. A pack picks its filter radius, its
 * shadow bias and its texel coordinates from the number it declared, so a map allocated at any
 * other size is a picture computed against an image that does not exist.
 * <p>
 * <strong>Every image here is allocated by this class, one call each, and they cannot part company
 * on a size because they read the same field.</strong> The depth and the first colour were once one
 * {@code TextureTarget}, which is what kept the two attachments of one render pass on one area;
 * that class allocates every texture with a single level ({@code RenderTarget.createBuffers}, a
 * hard coded 1) and so cannot express the chain a pack asks for on the depth. What it did for us
 * was two textures, two views and a depth format, and that is what is written out here: the same
 * usage word it passes, 15, and the same {@code GpuFormat.D32_FLOAT}.
 * <p>
 * <strong>The map stores the forward window, nought at the near plane and one at the far one, and
 * that is a decision rather than an inheritance.</strong> The scene is drawn under a reversed Z the
 * translation undoes on the way out, but a {@code shadowtex} lookup is never wrapped: the pack
 * compares what it reads against distances it computed itself, in OptiFine's own window, so the map
 * has to hold that window and the depth test has to run the other way from the scene's. Everything
 * that follows from it is in one place each: the {@code FORWARD} pair the shadow programs are given,
 * and the compare op of their pipeline.
 * <p>
 * No caller ever holds a texture view, for the same reason {@link ColorTargets} hands none out: a
 * resize closes the views behind it and nothing on this backend notices a view that has outlived its
 * texture. The views held here are safe because this map is square at the resolution the pack asked
 * for and is never resized.
 */
final class ShadowTargets {

	/** The far plane in the window this map stores. See the class comment before changing it. */
	private static final double FAR = 1.0;

	/** Past this the pack is asking for more than any device here will give it. */
	private static final int MAX_RESOLUTION = 16384;

	/**
	 * What the depth pair holds, which is the format {@code RenderTarget.createBuffers} gives a
	 * target that asked for a depth, and the one Iris allocates the map in
	 * ({@code shadows/ShadowRenderTargets.java:65-66}, the same {@code GpuFormat.D32_FLOAT}).
	 */
	private static final GpuFormat DEPTH_FORMAT = GpuFormat.D32_FLOAT;

	/**
	 * What the game asks for its own render targets, and what this class asks for every image it
	 * allocates: sampled, drawn into, and copied both ways. The blit that fills a chain needs the
	 * last two on one image, being a transfer from the level above into the level below.
	 */
	private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
			| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;

	/**
	 * The highest a {@code shadowcolor} name can go, which is what the arrays below are sized at and
	 * NOT what any one pack may reach: that is {@link #ceiling}, and a pack which never declared the
	 * flag stops at two of these eight slots.
	 */
	static final int MAX_COLOURS = PackDirectives.MAX_SHADOW_COLOURS;

	/**
	 * Its own, and not the one {@link ColorTargets} empties under: the census groups a frame's passes
	 * by label, so the two sharing a name made one flush of each read as two of the colour kind.
	 * It still begins with {@code Vitrail}, which is what {@code VulkanCommandEncoderMixin} reads
	 * to give our own passes the narrower barrier.
	 */
	private static final String CLEAR_LABEL = "Vitrail pending shadow clears";

	private final int resolution;
	private final List<PackDirectives.ShadowColour> asked;
	private final List<GpuFormat> formats;
	private final List<Vector4fc> clearColours;

	/**
	 * How the pack asks for each depth image to be read back, {@code shadowtex0} at nought and
	 * {@code shadowtex1} at one: its filter, and whether it carries a chain. Kept here so that the
	 * three roads that bind the map ask one place: a full screen pass, a geometry program and a
	 * compute reading one image through two filters is a difference nothing would ever explain.
	 */
	private final List<PackDirectives.ShadowDepth> depths;

	/**
	 * How many of them this pack may reach, its own declaration deciding. Clamped to what the arrays
	 * below hold, because the number is worked out in another class and an index past their length
	 * would be an out of bounds in the middle of an allocation rather than a buffer left out.
	 */
	private final int ceiling;

	/**
	 * Which of them are allocated, sorted, holes and all. Every loop of this class runs over this
	 * and never over the ceiling: a pack naming {@code shadowcolor2} alone gets three images and not
	 * eight, which is the same rule {@link ColorTargets} follows for the colour targets.
	 * <p>
	 * Nought is in it whatever the pack names, and that is Iris rather than a floor of our own: it
	 * builds the buffer at construction, for the framebuffer its depth copy is taken through
	 * ({@code shadows/ShadowRenderTargets.java:73,75}), so the image exists before a program has
	 * asked for anything.
	 */
	private final List<Integer> live;

	/**
	 * Whether each colour has yet to be emptied once. A pack that turns its own clear off is asking
	 * to keep what the shadow stage wrote from one frame to the next, not to read whatever the
	 * allocation left in the image, and Iris says the same on the directive: the clear colour is
	 * still what the buffer starts life holding.
	 */
	private final boolean[] unstarted = new boolean[MAX_COLOURS];

	/**
	 * The colour buffers of the place, one slot per name a pack may write.
	 * <p>
	 * <strong>The live ones are all made with the map, where Iris builds each one the first time a
	 * framebuffer or a sampler names it</strong> ({@code shadows/ShadowRenderTargets.java:127,136}).
	 * That is a difference in when memory is taken and in nothing a pack can read: a buffer no
	 * program writes holds its clear colour either way, and a sampler for one reads exactly that.
	 * <p>
	 * What it costs is nothing, and what it buys is that no order has to be kept between an image
	 * and the program that names it. The shadow programs are NOT all built before the first frame
	 * is drawn: the terrain's are read inside a frame ({@code TerrainDraw:625-634}) and the
	 * entities' from inside the light's own walk ({@code EntityDraw:1069-1070}), so a set filled
	 * from the programs would have to be answered by a pass already recording, where nothing may
	 * allocate. The plan is read instead, and it read every fragment stage of the place before the
	 * chain was built, which is why a pack that writes only nought pays for only nought.
	 * <p>
	 * None of them carries a chain, and the directives that ask for one are read by nobody: see
	 * {@link PackDirectives#shadowDepth(int)} for what Iris does with those names.
	 */
	private final TargetSurface[] colours = new TargetSurface[MAX_COLOURS];

	/** The depth the world is drawn into, which the pack reads as {@code shadowtex0}. */
	private GpuTexture depth;
	private GpuTextureView depthView;

	/**
	 * Level nought of the depth alone, which is what a render pass takes: Vulkan attaches a view of
	 * exactly one level, and the light draws into the base. The same object as {@link #depthView} on
	 * a map with no chain, where the whole view is one level already.
	 */
	private GpuTextureView depthAttachment;

	/**
	 * The map as it stood before anything translucent was drawn into it, which the OptiFine model
	 * calls {@code shadowtex1}.
	 * <p>
	 * A copy and not a second pass. The two names differ by one thing only, whether the translucent
	 * geometry is in them, so the cheap way round is to take the depth once the opaque half is done
	 * and let the translucent half carry on into the original. What a pack does with the pair is
	 * compare them: a point occluded in nought and clear in one is behind something translucent, and
	 * that is the whole test a coloured shadow rests on.
	 * <p>
	 * It takes its own chain from its own directive, the pack naming the two images apart, which is
	 * Iris's shape as well ({@code shadows/ShadowRenderTargets.java:65-66}).
	 */
	private GpuTexture noTranslucents;
	private GpuTextureView noTranslucentsView;

	/**
	 * The map as it stood once the OPAQUE world had been drawn into it and before anything that
	 * moves was, kept so that a later frame can start from it instead of walking the world again.
	 * <p>
	 * That moment is the one worth keeping and the only one: measured 6 September 2026, the opaque
	 * half of the shadow terrain costs 3.83 ms of the 4.03 ms both halves cost together, so the
	 * water and the glass are five per cent of it and are redrawn every frame for nothing. Casters
	 * that move are redrawn every frame too, on top of what is restored, which is what makes this
	 * different from keeping the finished map: nothing in the picture is late.
	 * <p>
	 * The colours go with the depth. A pack whose shadow programs write {@code shadowcolor} would
	 * otherwise read this frame's movers over the last frame's terrain colour, which is a picture
	 * nobody drew.
	 */
	private GpuTexture keptDepth;
	private final GpuTexture[] keptColour = new GpuTexture[MAX_COLOURS];
	private boolean kept;
	private boolean warnedCopy;
	private boolean saidRestored;

	/**
	 * Whether that copy still stands for the map as it is now. Lowered by every clear, because a copy
	 * is a moment of the map and emptying the map is the moment it stops being one: the same rule
	 * {@link PackDepth} follows for the two depths it converts, where an image nothing has filled is
	 * not handed to a pack at all.
	 */
	private boolean copied;

	/**
	 * Whether anything has written the levels past the base of each depth image since it was
	 * allocated, {@code shadowtex0} at nought and {@code shadowtex1} at one.
	 * <p>
	 * The one thing that decides whether a lod read is safe, and it is the rule {@link TargetSurface}
	 * follows for a colour target: a fresh image's levels hold whatever the driver left there, so a
	 * sampler allowed to climb before the reduction has run once serves undefined memory rather than
	 * a coarser image. The reduction is allowed to fail, a device refusing the blit on a depth
	 * format, and then this stays down and every lookup reads the base, which is the map a pack got
	 * before there were chains at all.
	 * <p>
	 * One flag per image and not one for the pair: the two are filled by two blits, and either may
	 * be refused while the other went through.
	 */
	private final boolean[] chainWritten = new boolean[2];

	private boolean broken;

	/** Load-ops waiting for the first shadow pass of the frame, or a standalone encode if none opens. */
	private final Vector4fc[] pendingColour = new Vector4fc[MAX_COLOURS];
	private boolean pendingDepth;

	/**
	 * @param named   which buffers a program of the place draws into or samples, from
	 *                {@code TargetPlan.shadowAllocated}, already read against the ceiling. A shadow
	 *                program declaring nothing is {@code {0, 1}} in there, which is the one way the
	 *                pair reaches this class
	 * @param ceiling how many the pack may reach, from {@code TargetPlan.shadowCeiling}
	 * @param depths  how the pack asks for {@code shadowtex0} and {@code shadowtex1} to be read
	 *                back, in that order
	 */
	ShadowTargets(int resolution, List<PackDirectives.ShadowColour> asked,
			List<PackDirectives.ShadowDepth> depths, Set<Integer> named, int ceiling) {
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

		this.depths = List.copyOf(depths);
		this.ceiling = Math.clamp(ceiling, 1, MAX_COLOURS);
		SortedSet<Integer> live = new TreeSet<>(Set.of(0));
		named.stream().filter(index -> index > 0 && index < this.ceiling).forEach(live::add);
		this.live = List.copyOf(live);
	}

	/**
	 * How one depth image of the map is filtered, LINEAR unless the pack asked otherwise.
	 * <p>
	 * LINEAR is where both engines start ({@code shadows/ShadowRenderer.java:267-280}), and what it
	 * decides is the read every pack of the corpus makes: a PCF loop sampling {@code shadowtex} as a
	 * plain {@code sampler2D}, every tap of which rides on this filter. A pack that writes
	 * {@code shadowtexNearest} or one of its per-index spellings is asking for those taps to land in
	 * texels of the map, and is tuned against an engine that gives it that.
	 *
	 * @param withoutTranslucents whether the name reads {@code shadowtex1}, the image drawn without
	 *                            the translucents, which is the second of the pair
	 */
	FilterMode depthFilter(boolean withoutTranslucents) {
		return this.depths.get(withoutTranslucents ? 1 : 0).nearest()
				? FilterMode.NEAREST
				: FilterMode.LINEAR;
	}

	/**
	 * Whether a lookup on one depth image may climb past level nought.
	 * <p>
	 * Two things at once, and both have to hold. The pack has to have asked for the chain, which is
	 * {@code generateShadowMipmap} and its per-image spellings, and the chain has to have been
	 * written since the image was allocated, which {@link #generateMipmaps} says. A sampler that
	 * climbed on the strength of the directive alone would read undefined memory on the frames the
	 * blit was refused, which is worse than the coarse level it was after.
	 * <p>
	 * The second question is asked of the image really bound and not of the name:
	 * {@link #depthWithoutTranslucents} falls back to the live map while no copy stands for it, and
	 * that image carries the OTHER directive's chain, or none.
	 */
	boolean depthMipmapped(boolean withoutTranslucents) {
		if (!this.depths.get(withoutTranslucents ? 1 : 0).mipmap()) {
			return false;
		}

		return this.chainWritten[withoutTranslucents && this.copied ? 1 : 0];
	}

	/**
	 * How many levels one image of the pair carries: Iris's count, which is not the full chain.
	 * <p>
	 * {@code shadows/ShadowRenderTargets.java:65-66} allocates {@code log2(resolution)} levels, the
	 * logarithm floored, where a chain running to one texel is one more. On a map of 1024 that is
	 * ten levels and the last is two texels square, so a pack reading a lod of ten or past it is
	 * clamped to that level there and would be handed a single averaged texel by a full chain. Iris
	 * is what the packs are tuned against, so the count is Iris's; the colour targets of the screen
	 * build the full chain instead ({@link TargetSurface#levelsFor}) because that is what
	 * {@code glGenerateMipmap} gives them there.
	 * <p>
	 * <strong>And one level wherever the device will not fill the chain.</strong> The fill is a
	 * blit, and Vulkan requires neither transfer bit of a depth format, where GL gave Iris
	 * {@code glGenerateMipmap} on anything. A command buffer records what it is given without
	 * answering, so there is no failure to catch afterwards: allocating the levels anyway would
	 * hand a pack whatever the driver left in them under the name of a coarser map. Asked once,
	 * before the memory is taken, and said in the log where the map's own line is.
	 */
	private int levels(int index) {
		if (!this.depths.get(index).mipmap()) {
			return 1;
		}

		if (!GpuFormats.blitsBothWays(DEPTH_FORMAT)) {
			return 1;
		}

		return Math.max(1, Mth.log2(this.resolution));
	}

	/**
	 * Whether the pack asked for a chain this device will not give it, which is the one case worth
	 * a word of its own: everything else about the chain is either the pack's own silence or a
	 * chain that works.
	 */
	private boolean chainRefused() {
		return (this.depths.get(0).mipmap() || this.depths.get(1).mipmap())
				&& !GpuFormats.blitsBothWays(DEPTH_FORMAT);
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

		if (this.depth != null) {
			return true;
		}

		try {
			int depthLevels = levels(0);
			this.depth = RenderSystem.getDevice().createTexture(() -> "Vitrail shadow depth", USAGE,
					DEPTH_FORMAT, this.resolution, this.resolution, 1, depthLevels);
			this.depthView = RenderSystem.getDevice().createTextureView(this.depth);
			this.depthAttachment = depthLevels > 1
					? RenderSystem.getDevice().createTextureView(this.depth, 0, 1)
					: this.depthView;

			for (int index : this.live) {
				this.colours[index] = new TargetSurface("Vitrail shadowcolor" + index,
						this.formats.get(index), false, this.resolution, this.resolution);
				this.unstarted[index] = true;
			}

			clear(RenderSystem.getDevice().createCommandEncoder());
			// The size and no word about why it is that size. This class cannot tell a pack that
			// asked for a small map from a player who asked for a smaller one: what it is handed is
			// one number, already through whatever was applied upstream, and a caption naming the
			// player's setting would be a claim it cannot make. PackChain says the setting is in
			// force, once, where it knows that.
			Vitrail.logger().info("Shadow map allocated at {}x{}, storing the forward depth window, "
				+ "with {}", this.resolution, this.resolution, describe());

			return true;
		} catch (GpuDeviceLossException e) {
			throw e;
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
		stash();
		flushPending(encoder);
	}

	/**
	 * Remembers this frame's empties without encoding them, so the first shadow pass can load-op
	 * them the way OpenGL clears as it binds the FBO.
	 */
	void defer() {
		stash();
	}

	Optional<Vector4fc> takeColourClear(int index) {
		Vector4fc colour = this.pendingColour[index];
		this.pendingColour[index] = null;
		return colour == null ? Optional.empty() : Optional.of(colour);
	}

	OptionalDouble takeDepthClear() {
		if (!this.pendingDepth) {
			return OptionalDouble.empty();
		}

		this.pendingDepth = false;
		return OptionalDouble.of(FAR);
	}

	/** Standalone clears for whatever the pass about to open will not write. */
	void flushPending(CommandEncoder encoder) {
		if (this.depth == null) {
			return;
		}

		List<GpuTextureView> colours = new ArrayList<>(this.live.size());
		List<Vector4fc> colourValues = new ArrayList<>(this.live.size());
		for (int index : this.live) {
			if (this.pendingColour[index] == null) {
				continue;
			}

			GpuTextureView view = colour(index);
			if (view == null) {
				continue;
			}

			colours.add(view);
			colourValues.add(this.pendingColour[index]);
			this.pendingColour[index] = null;
		}

		boolean depth = this.pendingDepth;
		this.pendingDepth = false;
		if (colours.isEmpty() && !depth) {
			return;
		}

		if (colours.isEmpty()) {
			encoder.clearDepthTexture(this.depth, FAR);
			return;
		}

		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> CLEAR_LABEL);
		for (int index = 0; index < colours.size(); index++) {
			descriptor.withColorAttachment(colours.get(index), Optional.of(colourValues.get(index)));
		}

		if (depth) {
			descriptor.withDepthAttachment(this.depthAttachment, OptionalDouble.of(FAR));
		}

		descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, this.resolution, this.resolution));
		encoder.createRenderPass(descriptor).close();
	}

	private void stash() {
		this.copied = false;
		this.pendingDepth = false;
		for (int index : this.live) {
			this.pendingColour[index] = null;
		}

		if (this.depth == null) {
			return;
		}

		// The chain stops standing for the map the moment the map is emptied, exactly as the copy
		// beside it does: what the levels hold is an average of a picture this call is throwing
		// away, and a lookup climbing to one would read the last frame's world under this frame's
		// base. The tail of the stage fills them again before anything reads them.
		this.chainWritten[0] = false;
		this.chainWritten[1] = false;
		this.pendingDepth = true;
		for (int index : this.live) {
			if (this.colours[index] != null && wanted(index)) {
				this.pendingColour[index] = this.clearColours.get(index);
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
		StringBuilder text = new StringBuilder();
		for (int index : this.live) {
			text.append(text.isEmpty() ? "" : " and ")
					.append("shadowcolor").append(index)
					.append(" as ").append(this.formats.get(index))
					.append(this.asked.get(index).clear() ? "" : ", which the pack keeps between frames");
		}

		int zero = levels(0);
		int one = levels(1);
		if (zero > 1 || one > 1) {
			// What each NAME is read at rather than what each image holds: the copy shadowtex1 is
			// read through is allocated the first time the stage takes it, and a pack no program of
			// which names it never has one at all.
			text.append(", and the pack asks for a chain the light fills every frame, ")
					.append(zero).append(" levels where it reads shadowtex0 and ")
					.append(one).append(" where it reads shadowtex1");
		} else if (chainRefused()) {
			// Said where the map's own line is, because it is the map that is smaller than the pack
			// asked for: a chain nobody can fill would be read as a coarser image and hold whatever
			// the driver left in it, so the map keeps one level and every lookup reads the base.
			text.append(", and the pack asks for a chain this device will not fill on a depth "
					+ "format, so the map carries one level and every lookup reads it");
		}

		return text.toString();
	}

	/**
	 * Takes the copy the pack reads as {@code shadowtex1}: the map as it stands, which the caller
	 * has to invoke once the opaque halves are drawn and before the translucent one is. Must run on
	 * the render thread and outside any render pass.
	 */
	void copyWithoutTranslucents(CommandEncoder encoder) {
		if (this.depth == null || this.broken) {
			return;
		}

		if (this.noTranslucents == null) {
			// The source's own format rather than an assumed one, the same rule the world's depth
			// copy follows: a copy whose format differs from its source is refused outright.
			// Three usages and not the four the map itself takes: nothing ever draws into this
			// image, it is written by the copy alone. COPY_SRC is here for the chain, a blit
			// reading the level above the one it writes.
			this.noTranslucents = RenderSystem.getDevice().createTexture(() -> "Vitrail shadowtex1",
					GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC
							| GpuTexture.USAGE_TEXTURE_BINDING,
					this.depth.getFormat(), this.resolution, this.resolution, 1,
					levels(1));
			this.noTranslucentsView = RenderSystem.getDevice().createTextureView(this.noTranslucents);
		}

		encoder.copyTextureToTexture(this.depth, this.noTranslucents, 0, 0, 0, 0, 0, this.resolution,
				this.resolution);
		this.copied = true;
	}

	/**
	 * Fills the levels past the base of both depth images, which the stage invokes at its tail, once
	 * everything the map holds is in it. Must run outside any render pass.
	 * <p>
	 * Where Iris puts it, and for the same reason: it generates the chain after the translucent
	 * group and before it restores the player's viewport
	 * ({@code shadows/ShadowRenderer.java:613-615}), so what the levels average is the finished map
	 * and never a half drawn one. Every frame and not once, because every frame writes the base
	 * again, whether by drawing the world into it or by putting the store back.
	 * <p>
	 * Silent and harmless on a pack that asked for no chain, which is all of the corpus but one: the
	 * images then carry a single level and the reduction has nothing to do.
	 */
	void generateMipmaps(CommandEncoder encoder) {
		if (this.depth == null || this.broken) {
			return;
		}

		this.chainWritten[0] = MipmapReduction.generate(encoder, this.depth);
		// Its own chain over its own base, which the copy has just written. Left out, the levels of
		// shadowtex1 would hold the average of whatever the last fill saw, which is a frame of the
		// world older than the base under them.
		this.chainWritten[1] = this.noTranslucents != null
				&& MipmapReduction.generate(encoder, this.noTranslucents);
	}

	/**
	 * Keeps the map as it stands, which the caller invokes once the opaque world is drawn and before
	 * anything that moves is. Must run on the render thread and outside any render pass.
	 * <p>
	 * Every image the stage writes goes into the store, the depth and each colour the place named,
	 * because a frame that restores half of them draws this frame's movers over last frame's colour.
	 */
	void keep(CommandEncoder encoder) {
		if (this.depth == null || this.broken || !copyable(this.depth)) {
			return;
		}

		if (this.keptDepth == null) {
			this.keptDepth = store("Vitrail kept shadow depth", this.depth.getFormat());
		}

		encoder.copyTextureToTexture(this.depth, this.keptDepth, 0, 0, 0, 0, 0, this.resolution,
				this.resolution);
		for (int index : this.live) {
			GpuTexture colour = colourTexture(index);
			if (colour == null) {
				continue;
			}

			if (this.keptColour[index] == null) {
				this.keptColour[index] = store("Vitrail kept shadowcolor" + index,
						colour.getFormat());
			}

			encoder.copyTextureToTexture(colour, this.keptColour[index], 0, 0, 0, 0, 0,
					this.resolution, this.resolution);
		}

		if (!this.kept) {
			Vitrail.logger().info("Shadow map store taken, so a later frame may put the opaque world "
					+ "back instead of walking for it");
		}

		this.kept = true;
	}

	/**
	 * Puts the kept map back, which a frame does instead of walking the world for the opaque half.
	 * <p>
	 * The copy beside it goes down with the restore: {@code shadowtex1} is a moment of this frame's
	 * map, and the moment it names has not happened yet when this runs.
	 *
	 * @return whether there was anything to put back
	 */
	boolean restore(CommandEncoder encoder) {
		if (!this.kept || this.depth == null || this.broken || this.keptDepth == null) {
			return false;
		}

		encoder.copyTextureToTexture(this.keptDepth, this.depth, 0, 0, 0, 0, 0, this.resolution,
				this.resolution);
		for (int index : this.live) {
			GpuTexture colour = colourTexture(index);
			if (colour != null && this.keptColour[index] != null) {
				encoder.copyTextureToTexture(this.keptColour[index], colour, 0, 0, 0, 0, 0,
						this.resolution, this.resolution);
			}
		}

		this.copied = false;
		// The store holds level nought alone, so a restore leaves the levels above it standing for
		// a map that is no longer under them. The tail of the stage fills them again.
		this.chainWritten[0] = false;
		this.chainWritten[1] = false;
		if (!this.saidRestored) {
			this.saidRestored = true;
			Vitrail.logger().info("Shadow map put back from the store, so this frame draws only "
					+ "what moves and the water");
		}

		return true;
	}

	/** Whether a map is in the store at all, which is what says a frame may restore rather than draw. */
	boolean hasKept() {
		return this.kept && !this.broken;
	}

	private GpuTexture store(String name, GpuFormat format) {
		return RenderSystem.getDevice().createTexture(() -> name,
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC, format,
				this.resolution, this.resolution, 1, 1);
	}

	private GpuTexture colourTexture(int index) {
		TargetSurface surface = this.colours[index];

		return surface == null ? null : surface.texture();
	}

	/**
	 * Whether the live image may be copied INTO, which is what a restore does and what nothing else
	 * in this engine has ever asked of the map. Said once and not per frame: a device that refuses
	 * it makes the whole road unavailable rather than throwing inside a frame.
	 */
	private boolean copyable(GpuTexture depth) {
		if ((depth.usage() & GpuTexture.USAGE_COPY_DST) != 0) {
			return true;
		}

		if (!this.warnedCopy) {
			this.warnedCopy = true;
			Vitrail.logger().warn("The shadow map cannot be copied into on this device, so it is "
					+ "drawn every frame and the reuse setting does nothing");
		}

		return false;
	}

	/** The depth with everything in it, which the pack reads as {@code shadowtex0}. */
	GpuTextureView depth() {
		return this.depthView;
	}

	/**
	 * The same image as one level, which is what a render pass attaches. Never handed to a sampler:
	 * a lookup at a lod on this view would be clamped to the base whatever the pack asked for.
	 */
	GpuTextureView depthAttachment() {
		return this.depthAttachment;
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
	 * A shadow colour target, or null for one nothing of this place named. Never nought's image under
	 * another name: a pack reading a buffer nothing filled has to read the clear colour, which is
	 * white and which it multiplies by, where nought's image would be a picture that is plausible and
	 * wrong.
	 */
	GpuTextureView colour(int index) {
		if (index < 0 || index >= MAX_COLOURS) {
			return null;
		}

		TargetSurface surface = this.colours[index];

		return surface == null ? null : surface.view();
	}

	int resolution() {
		return this.resolution;
	}

	/**
	 * How many colour buffers this pack may name, which is what a declared list is admitted against
	 * and never how many exist. See the class comment for where the number comes from.
	 */
	int colourCeiling() {
		return this.ceiling;
	}

	/**
	 * What a colour is allocated in, read off the directives and settled before any image exists.
	 * A pipeline names the format of the attachment it will be bound against and is built where the
	 * program is, which can be earlier in the session than the first {@link #ensure}, so this
	 * answers whether or not anything has been allocated - and for no index the light cannot draw
	 * into, the caller taking its own from {@code DrawBuffers.shadowColours}.
	 */
	GpuFormat format(int index) {
		return this.formats.get(index);
	}

	void release() {
		this.copied = false;
		this.chainWritten[0] = false;
		this.chainWritten[1] = false;
		// The one-level view first, and only where it is an object of its own: on a map with no
		// chain it IS the whole view, and closing it twice closes a handle somebody else may still
		// hold under the other name.
		if (this.depthAttachment != null && this.depthAttachment != this.depthView) {
			this.depthAttachment.close();
		}

		this.depthAttachment = null;
		if (this.depthView != null) {
			this.depthView.close();
			this.depthView = null;
		}

		if (this.depth != null) {
			this.depth.close();
			this.depth = null;
		}

		for (int index = 0; index < MAX_COLOURS; index++) {
			if (this.colours[index] != null) {
				this.colours[index].close();
				this.colours[index] = null;
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

		// The store goes with the images it was taken from: a map kept at one size is not a map at
		// another, and the restore would be refused for a size mismatch rather than for the reason.
		this.kept = false;
		if (this.keptDepth != null) {
			this.keptDepth.close();
			this.keptDepth = null;
		}

		for (int index = 0; index < MAX_COLOURS; index++) {
			if (this.keptColour[index] != null) {
				this.keptColour[index].close();
				this.keptColour[index] = null;
			}
		}
	}
}
