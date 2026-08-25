package dev.vitrail.render;

import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.resources.Identifier;

import java.util.Locale;
import java.util.Optional;

/**
 * The world's depth in the window the pack reads depth in: the image taken before the world's
 * translucents, the one taken after, and a third taken before the player's own hand on the frames
 * the engine draws that hand itself.
 * <p>
 * <strong>The conversion is here and not in the shader, and that is the whole point of the
 * class.</strong> The game rasterises with a reversed Z over zero to one and a pack is written for
 * the OpenGL volume, so what a lookup hands back has to be turned round once, {@code readA * d +
 * readB}. Doing it in the shader means finding the lookups, and a lookup can only be found by the
 * name of its sampler: Bliss declares
 * {@code BilateralUpscale_REUSE_Z(sampler2D tex1, sampler2D tex2, sampler2D depth, ...)} and hands
 * it {@code colortex12} at {@code composite1.fsh:987} and {@code depthtex0} two lines below, both
 * live in the same body. One of the two has to be turned round and the other must not, and the body
 * cannot be written twice. Converting the image instead makes every lookup right whatever name it
 * was reached through, including the ones no rewrite could ever have seen.
 * <p>
 * The precedent is the shadow map, which stores the window the pack reads for exactly the same
 * reason: {@link ShadowTargets} writes the forward window so that a {@code shadowtex} lookup never
 * has to be wrapped. This is that rule applied to the world's depth as well.
 * <p>
 * Nothing is lost against a shader computing it itself. The operation is the same one, in the
 * same place in the order, applied before any filtering: {@code |readA|} is one, the image is bound
 * NEAREST, and a {@code textureGather} or a {@code texelFetch} therefore comes back bit for bit
 * what it came back before. What is spent is memory, a full screen image of one float per moment
 * the pack can ask about instead of one copy of the depth.
 * <p>
 * More than one image, because the pack asks more than one question. {@code depthtex1} and
 * {@code depthtex2} are the opaque world, taken before anything translucent is drawn;
 * {@code depthtex0} is the depth as it stands, which for a deferred is that same opaque world and
 * for a composite is the whole scene. Served from one image the second half of the frame would blur
 * and fog straight through water without anything failing.
 * <p>
 * The third image is the same rule applied one step earlier: {@code depthtex2} is the opaque world
 * WITHOUT the hand, so that a pack can read what the hand it is holding stands in front of, and
 * only {@link #takeOpaque}'s image carries the hand.
 * <p>
 * <strong>The far terrain of Distant Horizons has a pair of its own, under the same rule
 * again.</strong> Iris keeps DH's depth beside the world's and serves it raw, {@code dhDepthTex0}
 * being DH's own image and {@code dhDepthTex1} a copy of it without the translucent LODs
 * ({@code samplers/IrisSamplers.java:109-110} and
 * {@code compat/dh/DHCompatInternal.java:260-269}); this engine's copy of that image is reversed
 * like everything else it rasterises, so the same conversion turns it round. The two moments
 * mirror the world's: {@link #takeDistantOpaque} before the translucent half is drawn, which is
 * {@code dhDepthTex1} and the {@code dhDepthTex0} of the deferred stage, and
 * {@link #takeDistantScene} once the water LODs are in, which is the {@code dhDepthTex0} of the
 * composites. They are taken only on the frames the pack really drew the far terrain, and
 * {@link #forgetDistant} drops them at every frame boundary, so a frame Distant Horizons drew
 * nothing on serves the far plane rather than the last far terrain it did draw.
 * <p>
 * <strong>It is allocated with the hand and not with the pair</strong>, where Iris builds a third
 * texture beside the other two ({@code targets/RenderTargets.java:73}), rebuilds it on a resize or
 * a depth format change like the pair ({@code targets/RenderTargets.java:172-178}) and refills it
 * once a frame, its copy being called unconditionally
 * ({@code targets/RenderTargets.java:234} from {@code pipeline/IrisRenderingPipeline.java:1056}).
 * What a pack reads is the same either way, and that is
 * the whole of why the difference is allowed to stand: nothing between the two moments writes the
 * game's depth except the hand's own solid pass, so on a frame that draws no hand the two images
 * would be the same one to the bit, and {@code depthtex2} is answered from the pair instead. What
 * the difference buys is one full screen image and one conversion a frame, which arrive with
 * {@code hand=on} and go with it: {@link #forgetPreHand} hands the image back the frame the family
 * stops being this engine's, and the conversion is only paid on the frames a hand is really drawn.
 */
final class PackDepth {

	private static final Identifier VERTEX_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/depth_window_vertex");
	private static final Identifier FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/depth_window_fragment");
	private static final Identifier DISTANT_FRAGMENT_ID =
			Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pack/distant_window_fragment");

	private static final String SAMPLER = "InSampler";

	/** The three the take that follows the far terrain's water half reads; see its own fragment. */
	private static final String BLENDED = "InBlended";
	private static final String CARRIED = "InCarried";
	private static final String PURE = "InPure";

	/** Two triangles, the quad every full screen pass of this engine draws. */
	private static final int VERTICES = 6;

	/** One float a texel: a window depth is one number and nothing here needs the other three. */
	private static final GpuFormat FORMAT = GpuFormat.R32_FLOAT;

	private static final String LABEL = "Vitrail depth window";

	/** Its own, and not the one above: the frame's pass census names a pass by its label. */
	private static final String DISTANT_LABEL = "Vitrail far terrain depth window";

	private static final String VERTEX = """
			#version 460 core

			in vec3 Position;
			in vec2 UV0;

			out vec2 ofTexCoord;

			void main() {
				ofTexCoord = UV0;
				gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	/**
	 * The pair is read off {@link ClipSpace#REVERSED} rather than written out, because the game's own
	 * targets are what this reads and that constant is where their convention is decided. Written in
	 * as literals rather than passed as a uniform: this pass has no block of its own, and a number
	 * that cannot move is one less thing to keep in step.
	 */
	private static final String FRAGMENT = String.format(Locale.ROOT, """
			#version 460 core

			uniform sampler2D InSampler;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				ofFragData0 = vec4(%s * texture(InSampler, ofTexCoord).r + %s);
			}
			""", ClipSpace.REVERSED.z, ClipSpace.REVERSED.w);

	/**
	 * The same conversion, out of three images instead of one, for the take that follows the far
	 * terrain's water half.
	 * <p>
	 * <strong>What the three are for is keeping {@code dhDepthTex0} the far terrain and nothing
	 * else.</strong> The water half is rasterised against an image the world's own depth was seeded
	 * into, so that it stands behind the player; that image therefore holds the world wherever the
	 * world won, and handing it to a pack would say there is far terrain in front of every wall.
	 * The seed was kept as it was written, so a texel still carrying it is a texel the world won
	 * and the answer there is the pure image, which holds the far terrain hidden behind the world
	 * or nothing at all. Everywhere else the blended image is the far terrain's own, water
	 * included.
	 * <p>
	 * What is lost, and it is worth naming: far WATER hidden behind the near world answers with the
	 * far terrain behind it rather than with the water, its own depth having been thrown away by
	 * the very test that keeps it off the player. It is a texel where nothing of the far terrain is
	 * visible, and the answer given there is a surface that is really there rather than one that is
	 * not.
	 */
	private static final String DISTANT_FRAGMENT = String.format(Locale.ROOT, """
			#version 460 core

			uniform sampler2D InBlended;
			uniform sampler2D InCarried;
			uniform sampler2D InPure;

			in vec2 ofTexCoord;

			layout(location = 0) out vec4 ofFragData0;

			void main() {
				float blended = texture(InBlended, ofTexCoord).r;
				float far = blended > texture(InCarried, ofTexCoord).r
						? blended
						: texture(InPure, ofTexCoord).r;

				ofFragData0 = vec4(%s * far + %s);
			}
			""", ClipSpace.REVERSED.z, ClipSpace.REVERSED.w);

	private static final ShaderSource SOURCE = (id, type) -> {
		if (type == ShaderType.FRAGMENT) {
			if (DISTANT_FRAGMENT_ID.equals(id)) {
				return DISTANT_FRAGMENT;
			}

			return FRAGMENT_ID.equals(id) ? FRAGMENT : null;
		}

		return VERTEX_ID.equals(id) ? VERTEX : null;
	};

	private RenderPipeline pipeline;

	/** Said once and not per frame, so that a driver that will not have this shader is readable. */
	private boolean refused;

	/**
	 * The same pair for the three image take, latched apart: a driver that refuses that one still
	 * has the one above, and what falls back is the far terrain's water and not every depth a pack
	 * reads.
	 */
	private RenderPipeline distantPipeline;
	private boolean distantRefused;

	private TargetSurface opaque;
	private TargetSurface scene;

	/** Null until a frame that draws the hand asks for it; see the class comment. */
	private TargetSurface preHand;

	/** Null until a frame the pack draws the far terrain on asks for them; see the class comment. */
	private TargetSurface distantOpaque;
	private TargetSurface distantScene;

	/**
	 * Whether anything has written each image since it was allocated.
	 * <p>
	 * A fresh texture holds whatever the driver left there, so an image the conversion never managed
	 * to fill must not be handed to a pack: it would read as a depth and be memory. The same rule
	 * emptied the shadow map when its stage is off.
	 */
	private boolean opaqueWritten;
	private boolean sceneWritten;

	/**
	 * The same for the third image, except that it is cleared at the end of every frame rather than
	 * held for the load; {@link #forgetPreHand} says why.
	 */
	private boolean preHandWritten;

	/**
	 * The same per frame rule for the far terrain's pair, and for the same reason the hand's image
	 * has it: the pair is only filled on the frames the pack really drew the far terrain, so a flag
	 * left standing would serve the last far terrain drawn to every frame that drew none.
	 */
	private boolean distantOpaqueWritten;
	private boolean distantSceneWritten;

	private boolean broken;

	/**
	 * The same latch for the third image alone, so that a refusal there does not take the pair down
	 * with it. It needs no size of its own: it is only ever raised at the size the pair is already
	 * allocated at, and the resize that would lift it goes through {@link #release}.
	 */
	private boolean preHandBroken;

	/**
	 * And for the far terrain's pair, with a size of its own: unlike the hand's image, this pair is
	 * allocated at the size of the image Distant Horizons drew rather than at one {@link #ensure}
	 * has already settled, so the refusal has to remember which size it was about.
	 */
	private boolean distantBroken;
	private int distantBrokenWidth;
	private int distantBrokenHeight;

	/**
	 * The screen the allocation failed at, so that the refusal is about that screen and not about the
	 * pack. Same rule and same reason as {@link ColorTargets}: the panorama capture asks for 4096
	 * square for six frames, and a refusal latched for the session would leave every depthtex lookup
	 * of the pack reading the far plane long after the window had gone back to its own size.
	 */
	private int brokenWidth;
	private int brokenHeight;

	/**
	 * Converts the depth of the opaque world, which the pack reads as {@code depthtex1}, as
	 * {@code depthtex0} for as long as the deferred stage is the present half, and as
	 * {@code depthtex2} on every frame {@link #takePreHand} has no image of its own for. Must run on
	 * the render thread and outside any render pass.
	 *
	 * @param live the game's depth as it stands, which the caller has to take before anything
	 *             translucent is drawn
	 */
	boolean takeOpaque(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView live,
			int width, int height) {
		if (!ensure(width, height) || !fill(encoder, device, quad, live, this.opaque)) {
			return false;
		}

		this.opaqueWritten = true;

		return true;
	}

	/**
	 * Converts the depth of the whole scene, the world's translucents and the redirected features
	 * included, which the pack reads as {@code depthtex0} from the composites on. Must run on the
	 * render thread and outside any render pass.
	 */
	boolean takeScene(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView live,
			int width, int height) {
		if (!ensure(width, height) || !fill(encoder, device, quad, live, this.scene)) {
			return false;
		}

		this.sceneWritten = true;

		return true;
	}

	/**
	 * Converts the depth of the world as it stood before the player's own hand was drawn, which the
	 * pack reads as {@code depthtex2}. Must run on the render thread and outside any render pass.
	 * <p>
	 * The caller has to take it before the hand's solid pass and may only take it on the frames that
	 * pass really draws something, which is {@code HandDraw.draws} and not the load's own answer: an
	 * image taken on a frame that drew no hand of ours is the opaque world's over again, and paying
	 * a conversion for it would buy nothing.
	 *
	 * @param live the game's depth as it stands, with the world's opaque features in it and the hand
	 *             not yet
	 */
	boolean takePreHand(CommandEncoder encoder, GpuDevice device, GpuBuffer quad, GpuTextureView live,
			int width, int height) {
		if (!ensure(width, height) || !ensurePreHand(width, height)
				|| !fill(encoder, device, quad, live, this.preHand)) {
			return false;
		}

		this.preHandWritten = true;

		return true;
	}

	/**
	 * Converts the far terrain's depth as it stands before its translucent half is drawn, which the
	 * pack reads as {@code dhDepthTex1} everywhere and as {@code dhDepthTex0} up to the deferred
	 * stage. Must run on the render thread and outside any render pass.
	 * <p>
	 * The caller may only take it on the frames the pack really drew the far terrain, for the reason
	 * the hand's image has the same rule: an image taken on another frame would hand the pack the
	 * far plane dressed as a depth, and one taken from a stale source the far terrain of a camera
	 * that has moved on.
	 *
	 * @param distant the far terrain's own depth image, reversed like the game's, with its opaque
	 *                half in it and its water not yet
	 */
	boolean takeDistantOpaque(CommandEncoder encoder, GpuDevice device, GpuBuffer quad,
			GpuTextureView distant, int width, int height) {
		if (!ensureDistant(width, height) || !fill(encoder, device, quad, distant, this.distantOpaque)) {
			return false;
		}

		this.distantOpaqueWritten = true;

		return true;
	}

	/**
	 * Converts the far terrain's depth with its water in, which the pack reads as
	 * {@code dhDepthTex0} from the composites on. Must run on the render thread and outside any
	 * render pass, and only on the frames the pack really drew the far terrain, like the take above.
	 */
	boolean takeDistantScene(CommandEncoder encoder, GpuDevice device, GpuBuffer quad,
			GpuTextureView blended, GpuTextureView carried, GpuTextureView pure, int width,
			int height) {
		if (!ensureDistant(width, height)) {
			return false;
		}

		// One image where the frame seeded nothing under the water half, and then the pure image
		// carries that water itself and is the whole answer; three where it did, and then the water
		// is in the blended one and the pure one is what the world is told back out with.
		boolean filled = (blended == null || carried == null)
				? fill(encoder, device, quad, pure, this.distantScene)
				: fillDistant(encoder, device, quad, blended, carried, pure);
		if (!filled) {
			return false;
		}

		this.distantSceneWritten = true;

		return true;
	}

	/**
	 * The opaque world's depth, or null while nothing has filled it. Looked up at every use like
	 * every other view of a place: a resize destroys and recreates it.
	 */
	GpuTextureView opaque() {
		return this.opaqueWritten ? this.opaque.view() : null;
	}

	/** The whole scene's depth, or null while nothing has filled it. Never held. */
	GpuTextureView scene() {
		return this.sceneWritten ? this.scene.view() : null;
	}

	/**
	 * The opaque world's depth without the hand, or null while this frame has none.
	 * <p>
	 * Null covers more than one case and a caller cannot tell them apart, which is deliberate: the
	 * answer to all of them is the same, fall back to whatever the pass would have read for a depth
	 * copy without this class.
	 * <p>
	 * Two of them are exact and are the ordinary answer. A frame that drew no hand of ours really has
	 * nothing to give, the two moments holding one image. And a pass drawn earlier in the frame than
	 * {@link PackChain#markPreHandDepth} is asking before the image exists, which is what
	 * {@code GeometryProgram} counts on to keep the terrain and the shadow map off it.
	 * <p>
	 * The rest are failures. A refused allocation of this image alone leaves the pair standing, so
	 * {@code depthtex2} falls exactly where {@code depthtex1} falls: the world WITH the hand wherever
	 * the opaque image answers, and the far plane wherever it does not. {@link #ensurePreHand} logs
	 * that. A refused pair ({@link #ensure}) leaves nothing allocated at all, and a refused
	 * conversion ({@link #pipeline(GpuDevice)}) leaves all three allocated and none of them written,
	 * which comes to the same thing for a reader: every depth lookup of the pack reads the far plane.
	 * Each of those two says so on its own line, once.
	 * <p>
	 * Three ways in are silent, and all three are a screen rather than a pack: a window with no
	 * width, a refusal already logged at this size and being met again, and {@link #fill} finding no
	 * quad, no live depth or no image to draw into. None of them is a state a line would add
	 * anything to.
	 */
	GpuTextureView preHand() {
		return this.preHandWritten ? this.preHand.view() : null;
	}

	/**
	 * Whether the three image conversion has given up for this load, which is what stops the far
	 * terrain's water being seeded at all.
	 * <p>
	 * <strong>The two refusals have to recover in the same direction, and that is the whole of why
	 * this is asked.</strong> {@code DistantDraw} handing its own seed back sends the water half
	 * into the pure image, so {@code dhDepthTex0} is whole again and only the occlusion is lost.
	 * This one on its own would leave the seed running and the water landing in an image nothing
	 * reads, and then that name would answer the far terrain with no water at all, on every texel
	 * rather than on the few the design gives up.
	 */
	boolean distantRefused() {
		return this.distantRefused;
	}

	/**
	 * The far terrain's depth without its water, or null while this frame has none. Null falls back
	 * to the far plane, which is what the name answered before the pack drew the far terrain at all.
	 */
	GpuTextureView distantOpaque() {
		return this.distantOpaqueWritten ? this.distantOpaque.view() : null;
	}

	/** The far terrain's depth with its water in, or null while this frame has none. */
	GpuTextureView distantScene() {
		return this.distantSceneWritten ? this.distantScene.view() : null;
	}

	/**
	 * Forgets this frame's pair of far terrain images, at the frame boundary and for the reason the
	 * hand's image is forgotten there: they are only filled on the frames the pack really drew the
	 * far terrain, so a flag left standing would serve a stale one. The memory stays, like the
	 * hand's, because the frames that fill it again are every frame Distant Horizons draws.
	 */
	void forgetDistant() {
		this.distantOpaqueWritten = false;
		this.distantSceneWritten = false;
	}

	/**
	 * Forgets this frame's pre-hand image, and gives the memory back with the family it was
	 * allocated for.
	 * <p>
	 * The forgetting is per frame, where the other two images are per load, and the difference is
	 * the one thing that makes this image safe. They are refilled at a fixed point of every frame;
	 * this one is only filled while the engine draws the hand itself, so a flag left standing would
	 * go on serving the last frame that drew a hand to every frame that did not, which is a depth
	 * one frame of camera movement out of date and nothing that would report it.
	 * <p>
	 * The memory outlives the frame and not the family, which is what makes the class comment's cost
	 * true both ways. It is deliberately NOT freed on the frames that simply drew no hand - third
	 * person, a hidden interface - since those come back within a keystroke and an image freed and
	 * built again at every one of them would trade one full screen image, whose real size
	 * {@link #ensurePreHand} prints, for an allocation a frame.
	 *
	 * @param held whether the hand is still this engine's to draw, which is the load's answer and
	 *             not the frame's: it goes false when the family is turned off in the options and
	 *             when {@code EntityDraw} drops it mid session after a failed draw, and the image
	 *             has nothing left to be for in either case
	 */
	void forgetPreHand(boolean held) {
		this.preHandWritten = false;

		if (!held) {
			this.preHand = close(this.preHand);
			this.preHandBroken = false;
		}
	}

	/**
	 * Frees every image. The views go with them, since {@link TargetSurface} closes the two
	 * together.
	 */
	void release() {
		releaseWorld();
		this.distantOpaque = close(this.distantOpaque);
		this.distantScene = close(this.distantScene);
		this.distantOpaqueWritten = false;
		this.distantSceneWritten = false;
		this.distantBroken = false;
	}

	/**
	 * Frees the world's three images and not the far terrain's pair, which has a lifecycle of its
	 * own in {@link #ensureDistant}. The split is a defect a review caught: {@link #ensure} frees
	 * whatever is stale when the world's pair moves, and the far terrain's image of this very frame
	 * is taken EARLIER in it than the world's, so freeing the pair here destroyed an image already
	 * filled and the deferred stage read the far plane on every frame the screen resized.
	 */
	private void releaseWorld() {
		this.opaque = close(this.opaque);
		this.scene = close(this.scene);
		this.preHand = close(this.preHand);
		this.opaqueWritten = false;
		this.sceneWritten = false;
		this.preHandWritten = false;
		this.preHandBroken = false;
	}

	/**
	 * Makes the pair every frame needs exist at the size of the screen, reallocating when it moved.
	 * The third image is {@link #ensurePreHand}'s and rides on the {@link #releaseWorld} this one
	 * does; the far terrain's pair does not, {@link #releaseWorld} says why.
	 *
	 * @return false when there is nothing to draw into, in which case every depth lookup of the pack
	 *         falls back to the far plane
	 */
	private boolean ensure(int width, int height) {
		// Before the latch and not after, the same order ColorTargets.ensure keeps and for the
		// reason written there: a minimised window is another size, and lifting a refusal on a size
		// nothing is ever allocated at only makes the real size pay the failure twice.
		if (width <= 0 || height <= 0) {
			return false;
		}

		if (this.broken && (width != this.brokenWidth || height != this.brokenHeight)) {
			this.broken = false;
		}

		if (this.broken) {
			return false;
		}

		if (this.opaque != null && this.opaque.width() == width && this.opaque.height() == height) {
			return true;
		}

		try {
			// Both or neither, and always the same size: one of them left at the old size would be
			// read at the new one and stretch the depth over the screen rather than fail.
			releaseWorld();
			this.opaque = new TargetSurface("Vitrail depth before the translucents", FORMAT, false,
					width, height);
			this.scene = new TargetSurface("Vitrail depth with the translucents", FORMAT, false,
					width, height);
		} catch (RuntimeException e) {
			this.broken = true;
			this.brokenWidth = width;
			this.brokenHeight = height;
			releaseWorld();
			Vitrail.logger().error("Vitrail could not allocate the two depth images the pack reads at "
					+ "{}x{}, so every depthtex lookup of this pack reads the far plane until the "
					+ "screen is another size", width, height, e);

			return false;
		}

		// Said out loud rather than left to be discovered: this is two full screen images of a float
		// each in place of one copy of the game's depth, about eight more mebibytes at
		// 1080p and thirty-two at 4K.
		Vitrail.logger().info("The world's depth is converted into the pack's window in two images at "
				+ "{}x{}, {} MiB", width, height,
				(this.opaque.bytes() + this.scene.bytes()) / (1024L * 1024L));

		return true;
	}

	/**
	 * Makes the third image exist, at the size {@link #ensure} has just settled and never at another
	 * one. Called only from {@link #takePreHand}, so a session that never draws the hand never pays
	 * for it.
	 *
	 * @return false when there is nothing to draw into, in which case {@code depthtex2} falls back
	 *         to whatever the reading pass answers a depth copy with: the opaque world's image after
	 *         the deferred stage, which carries the one thing the name excludes, and the far plane
	 *         on the hand's own solid pass, which carries nothing
	 */
	private boolean ensurePreHand(int width, int height) {
		if (this.preHandBroken) {
			return false;
		}

		if (this.preHand != null && this.preHand.width() == width
				&& this.preHand.height() == height) {
			return true;
		}

		try {
			this.preHand = close(this.preHand);
			this.preHand =
					new TargetSurface("Vitrail depth before the hand", FORMAT, false, width, height);
		} catch (RuntimeException e) {
			this.preHandBroken = true;
			this.preHand = close(this.preHand);
			Vitrail.logger().error("Vitrail could not allocate the depth image the pack reads past the "
					+ "hand with at {}x{}, so until the screen is another size depthtex2 answers "
					+ "exactly as depthtex1 does: the world with the hand in it where that one is "
					+ "served, and the far plane elsewhere, the hand's own pass included",
					width, height, e);

			return false;
		}

		// Said out loud like the pair above, and worth saying apart from them: this one is the cost
		// of hand=on and of nothing else.
		Vitrail.logger().info("The depth before the hand is converted into the pack's window in one "
				+ "more image at {}x{}, {} MiB", width, height,
				this.preHand.bytes() / (1024L * 1024L));

		return true;
	}

	/**
	 * Makes the far terrain's pair exist at the size of the image Distant Horizons drew, which is
	 * the screen's, reallocating when it moved. Called only from the two distant takes, so a session
	 * whose pack never draws the far terrain never pays for it.
	 *
	 * @return false when there is nothing to draw into, in which case every {@code dhDepthTex}
	 *         lookup of the pack falls back to the far plane and the pack's own Distant Horizons
	 *         branches stay shut
	 */
	private boolean ensureDistant(int width, int height) {
		if (width <= 0 || height <= 0) {
			return false;
		}

		if (this.distantBroken
				&& (width != this.distantBrokenWidth || height != this.distantBrokenHeight)) {
			this.distantBroken = false;
		}

		if (this.distantBroken) {
			return false;
		}

		if (this.distantOpaque != null && this.distantOpaque.width() == width
				&& this.distantOpaque.height() == height) {
			return true;
		}

		try {
			// Both or neither, like the world's own pair and for the same reason. The written flags
			// fall with the surfaces they described, or a getter would dereference an image this
			// very block has just closed.
			this.distantOpaqueWritten = false;
			this.distantSceneWritten = false;
			this.distantOpaque = close(this.distantOpaque);
			this.distantScene = close(this.distantScene);
			this.distantOpaque = new TargetSurface("Vitrail far terrain depth before its water",
					FORMAT, false, width, height);
			this.distantScene = new TargetSurface("Vitrail far terrain depth with its water",
					FORMAT, false, width, height);
		} catch (RuntimeException e) {
			this.distantBroken = true;
			this.distantBrokenWidth = width;
			this.distantBrokenHeight = height;
			this.distantOpaque = close(this.distantOpaque);
			this.distantScene = close(this.distantScene);
			Vitrail.logger().error("Vitrail could not allocate the two depth images the pack reads "
					+ "the far terrain out of at {}x{}, so every dhDepthTex lookup of this pack reads "
					+ "the far plane until the screen is another size", width, height, e);

			return false;
		}

		// Said out loud like the world's pair: this is the cost of a pack that draws the far
		// terrain, and of nothing else.
		Vitrail.logger().info("The far terrain's depth is converted into the pack's window in two "
				+ "more images at {}x{}, {} MiB", width, height,
				(this.distantOpaque.bytes() + this.distantScene.bytes()) / (1024L * 1024L));

		return true;
	}

	/**
	 * Draws one image from the depth as it stands. Opens a render pass of its own, so it must not be
	 * called while another one is recording; the copy it replaces had the same rule.
	 */
	private boolean fill(CommandEncoder encoder, GpuDevice device, GpuBuffer quad,
			GpuTextureView live, TargetSurface into) {
		if (quad == null || live == null || into == null) {
			return false;
		}

		RenderPipeline compiled = pipeline(device);
		if (compiled == null) {
			return false;
		}

		// Loaded rather than cleared: the draw covers the image whole, so a clear would be one more
		// write of the same texels.
		try (RenderPass pass = encoder.createRenderPass(() -> LABEL, into.view(), Optional.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			// NEAREST, and it is what makes this a rewrite of the value and not of the image: one
			// destination texel covers one source texel, so what a pack fetches here is what it would
			// have fetched from the depth itself.
			pass.bindTexture(SAMPLER, live,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return true;
	}

	/**
	 * Draws the far terrain's scene image out of the three the seeded water half leaves behind.
	 * Falls back on the pure image alone where the three image pipeline will not compile, which
	 * costs the water rather than the whole name.
	 */
	private boolean fillDistant(CommandEncoder encoder, GpuDevice device, GpuBuffer quad,
			GpuTextureView blended, GpuTextureView carried, GpuTextureView pure) {
		if (quad == null || this.distantScene == null) {
			return false;
		}

		RenderPipeline compiled = distantPipeline(device);
		if (compiled == null) {
			return fill(encoder, device, quad, pure, this.distantScene);
		}

		try (RenderPass pass = encoder.createRenderPass(() -> DISTANT_LABEL,
				this.distantScene.view(), Optional.empty())) {
			pass.setPipeline(compiled);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setVertexBuffer(0, quad.slice());
			pass.bindTexture(BLENDED, blended,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(CARRIED, carried,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture(PURE, pure,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.draw(VERTICES, 1, 0, 0);
		}

		return true;
	}

	/** The three image pipeline, on the same terms as the one below. */
	private RenderPipeline distantPipeline(GpuDevice device) {
		if (this.distantRefused) {
			return null;
		}

		if (this.distantPipeline == null) {
			this.distantPipeline = buildDistant();
		}

		if (device.precompilePipeline(this.distantPipeline, SOURCE).isValid()) {
			return this.distantPipeline;
		}

		this.distantRefused = true;
		this.distantPipeline = null;
		Vitrail.logger().error("The far terrain's depth conversion did not compile, so dhDepthTex0 "
				+ "answers the far terrain without its water rather than a depth carrying the world");

		return null;
	}

	/**
	 * The pipeline, compiled the first time it is asked for and kept.
	 * <p>
	 * The compiled form lives in the device cache, which the game empties at every resource reload,
	 * so this asks the device every time rather than trusting a flag of its own: that call is a
	 * {@code computeIfAbsent} on the device side and costs nothing once it has been made.
	 */
	private RenderPipeline pipeline(GpuDevice device) {
		if (this.refused) {
			return null;
		}

		if (this.pipeline == null) {
			this.pipeline = build();
		}

		if (device.precompilePipeline(this.pipeline, SOURCE).isValid()) {
			return this.pipeline;
		}

		this.refused = true;
		this.pipeline = null;
		Vitrail.logger().error("The depth conversion did not compile, so every depthtex lookup of this "
				+ "pack reads the far plane rather than a depth in the wrong direction");

		return null;
	}

	private static RenderPipeline buildDistant() {
		return RenderPipeline.builder()
				.withLocation(
						Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/distant_window"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(DISTANT_FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder()
						.withSampler(BLENDED)
						.withSampler(CARRIED)
						.withSampler(PURE)
						.build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	private static RenderPipeline build() {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath(Vitrail.MOD_ID, "pipeline/depth_window"))
				.withVertexShader(VERTEX_ID)
				.withFragmentShader(FRAGMENT_ID)
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayout.builder().withSampler(SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withColorTargetState(new ColorTargetState(Optional.empty(), FORMAT,
						ColorTargetState.WRITE_ALL))
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();
	}

	private static TargetSurface close(TargetSurface surface) {
		if (surface != null) {
			surface.close();
		}

		return null;
	}
}
