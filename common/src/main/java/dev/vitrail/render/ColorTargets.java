package dev.vitrail.render;

import dev.vitrail.pack.program.BlendMode;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.TargetDirectives;
import dev.vitrail.pack.target.TargetFormat;
import dev.vitrail.pack.target.TargetName;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSchedule;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.pack.texture.ImageInformation;
import dev.vitrail.pack.texture.TextureStage;
import dev.vitrail.uniform.ClipSpace;
import dev.vitrail.uniform.NoiseTexture;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * The textures behind one pack's colour targets: what exists, at which format and size, what is
 * cleared and to what, and what a sampler is handed when nothing serves it.
 * <p>
 * Nothing here decides anything. Which indices exist, which format each was declared with, which
 * side of a ping-pong a program reads, and which sizes are relative to the screen are all answers
 * the plan already carries; this class allocates what the plan describes and clears it. Keeping
 * the decisions on the pack side is what lets them be measured against the eight packs without
 * starting the game.
 * <p>
 * Allocating has to happen outside a render pass. Creating a texture records a barrier into the
 * very command buffer a pass would be recording into. Clears of the pack's colour targets are
 * remembered until the first pass that writes them, and become that pass's load operation: a
 * standalone texture clear is a GPU stop of its own on this backend, which OpenGL's
 * {@code glClear} as the FBO is bound is not.
 * <p>
 * No caller ever holds a texture view. A resize destroys and recreates the texture behind a target
 * and closes every view onto it, and nothing on the Vulkan backend checks that a bound view is
 * still alive: a view kept across a resize is a silent use after free rather than an exception.
 * Views are therefore looked up again at every use, which is what {@link #view} is for. The views
 * themselves belong to {@link TargetSurface}, which closes them with the texture they look onto,
 * so the two can never be freed apart.
 * <p>
 * A target some program reads at a lod carries a mip chain, and that is why these are
 * {@link TargetSurface} rather than the game's own {@code TextureTarget}: that class allocates one
 * level, hard coded, and cannot express a chain. Filling it is not this class's work, only holding
 * it. {@link MipmapReduction} writes the levels, and they have to be rewritten whenever level
 * nought changes, so the moment it runs belongs to whoever walks the frame.
 * <p>
 * A target the schedule turns over carries two textures, and which half a program reads and
 * writes is the schedule's answer rather than a flag kept here. The one thing this class owes the
 * ping pong is {@link #swapBack}: a target the pack keeps between frames and that the chain left
 * on the alternate half has to become the main one, because the next frame starts its walk
 * from an empty flipped set and would otherwise read the half nothing wrote. The two surfaces
 * swap names. Copying the texels would be another GPU stop for the same fact.
 * <p>
 * The files the pack ships as textures of its own live here too, and for one reason: they are
 * allocated, uploaded and freed at exactly the moments the constants are, and a second holder
 * threaded through the same calls would only be a second chance for the two to fall out of step.
 * They are not colour targets in any other sense, {@link PackImages} decides everything about them,
 * and nothing here ever resizes one.
 */
final class ColorTargets {

	/** Enough for the three constants: they carry one pixel each and nothing samples their precision. */
	private static final GpuFormat CONSTANT_FORMAT = GpuFormat.RGBA8_UNORM;

	/**
	 * One float a pixel, and no conversion: the mask carries the depth the pack's geometry left,
	 * and it is compared with the game's own depth rather than read as one.
	 * <p>
	 * A float and nothing narrower because the comparison is for equality as much as for order. The
	 * game's depth attachment is {@code D32_FLOAT} ({@code RenderTarget.createBuffers}), the mask is
	 * filled from the very value the fragment stage hands that attachment, and both sides then carry
	 * the same thirty two bits: a pixel nothing has been drawn over compares exactly equal. Rounded
	 * into a narrower format it would not, and the seed would repaint the pack's own geometry
	 * wherever the rounding fell the wrong way.
	 */
	private static final GpuFormat COVERAGE_FORMAT = GpuFormat.R32_FLOAT;

	/**
	 * What the mask holds where the pack's geometry has written nothing, which is outside the depth
	 * range on the far side of the eye.
	 * <p>
	 * <strong>This is what makes the cut one comparison instead of two.</strong> Every real depth is
	 * in front of it, so a pixel the pack never wrote answers "the game drew in front" through the
	 * same test as a pixel it did write and something was drawn over, and the reader owes an empty
	 * pixel no test of its own. The value follows {@link ClipSpace#REVERSED} rather than being
	 * written out: the game rasterises reversed, so far is nought and this has to be below it.
	 */
	static final float COVERAGE_EMPTY = ClipSpace.REVERSED.z < 0.0F ? -1.0F : 2.0F;

	/** The one target the format says starts at the fog colour. Every other one starts at a constant. */
	private static final int FOG_TARGET = 0;

	private static final Vector4fc OPAQUE_BLACK = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
	private static final Vector4fc OPAQUE_WHITE = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector4fc MID_GREY = new Vector4f(0.5F, 0.5F, 0.5F, 1.0F);

	/** The sentinel above as a clear colour: one channel, and the mask reads the first of them. */
	private static final Vector4fc UNWRITTEN =
			new Vector4f(COVERAGE_EMPTY, COVERAGE_EMPTY, COVERAGE_EMPTY, COVERAGE_EMPTY);

	/** Past this much the log says so once. Refusing to allocate would trade a stutter for a black screen. */
	private static final long LOUD_BYTES = 512L * 1024L * 1024L;

	/**
	 * What to assume a pass can carry before there is a device to ask. Eight is what a pack's own
	 * draw buffers already use.
	 */
	private static final int ASSUMED_COLOR_ATTACHMENTS = 8;

	/**
	 * What the census files this pass under, and it says COLOUR because the shadow map empties its
	 * own the same way under a name of its own. One name over both read as two colour flushes in a
	 * frame that had paid one of each, and a count that stands for two things at once sends whoever
	 * reads it hunting a fault that is not there.
	 */
	private static final String CLEAR_LABEL = "Vitrail pending colour clears";

	/**
	 * What one sampler is handed for a texture the pack ships: the image, and how the pack asked for
	 * it to be read. The three travel together because they are one answer, written in one directive
	 * and the {@code .mcmeta} beside its file, rather than three questions for three places.
	 */
	record PackBinding(GpuTextureView view, FilterMode filter, boolean repeat) {
	}

	private final TargetPlan plan;
	private final Set<Integer> doubled;
	private final int noiseResolution;

	/** The pack's own noise image, or null when the generated field stands in. */
	private final NoiseTexture.Image noiseImage;

	/** The textures the pack ships as files, already decoded, waiting for a device to exist. */
	private final PackImages packImages;

	/** Storage images the pack declared. Allocated on the device; not bound yet. */
	private final StorageImages storageImages;
	private final StorageBuffers storageBuffers;

	/** One surface per texture the pack ships, allocated and uploaded with the constants. */
	private final Map<PackImages.Image, TargetSurface> packSurfaces = new LinkedHashMap<>();

	/**
	 * The shadow map, allocated and cleared with the rest.
	 * <p>
	 * It is held here rather than beside because everything that binds a sampler already holds this
	 * object, and a second one threaded through the same calls would only be a second chance for the
	 * two to be allocated, cleared or released at different moments. It is a family of its own all
	 * the same: its own resolution, its own depth window, its own clear colour.
	 */
	private final ShadowTargets shadowMap;

	/**
	 * The world's depth as the pack reads it, held here for the same reason the shadow map is:
	 * everything that binds a sampler already holds this object.
	 */
	private final PackDepth depth = new PackDepth();

	/**
	 * The smoothed depth at the centre of the screen, held here for the same reason again: it is a
	 * sampler a program binds, and everything that binds one already holds this object.
	 */
	private final CenterDepth centerDepth = new CenterDepth();

	private final Map<Integer, GpuFormat> formats = new LinkedHashMap<>();
	private final Map<Integer, Vector4fc> clearColours = new LinkedHashMap<>();

	/**
	 * Whether colortex0 starts each frame at the fog colour rather than at the colour below.
	 * <p>
	 * That is the rule packs are written against, and it is not a nicety: everything the pack's own
	 * geometry does not cover is exactly the sky under the horizon, and a target emptied to black
	 * there hands the whole of the chain a hole where the distance should be. It is a per frame value
	 * and this class holds no frame, which is why the plan cannot answer it and only says whether the
	 * pack named a colour of its own instead.
	 */
	private final boolean fogCleared;
	private final Map<Integer, TargetSurface> mainSide = new LinkedHashMap<>();
	private final Map<Integer, TargetSurface> altSide = new LinkedHashMap<>();
	private final Set<Integer> fellBack = new TreeSet<>();
	private final List<String> notes = new ArrayList<>();

	/**
	 * The targets some program of this place reads at a lod, which are the ones allocated with a mip
	 * chain. Both halves of a doubled target carry one: which half a program reads is the schedule's
	 * answer and it is not the same for every reader, so a chain on one half only would serve some
	 * of them and hand the others an empty level.
	 */
	private final Set<Integer> mipmapped;

	private TargetSurface black;
	private TargetSurface white;
	private TargetSurface grey;
	private TargetSurface noise;
	private TargetSurface unwritten;

	/**
	 * The depth the pack's own opaque geometry left this frame, one float a pixel, so that whoever
	 * puts the game's picture into the same target can tell a pixel that is still the pack's from
	 * one the game has drawn a feature over since.
	 * <p>
	 * Emptied here at the head of every frame rather than by the pass that writes it, and that is
	 * the whole safety of the thing. The frames where it is not written are exactly the frames
	 * nothing of ours would clear it: a pack whose terrain program was refused, a place whose
	 * targets are scaled, the frames before anything is allocated. A mask left standing from the
	 * previous frame hides the game's picture behind geometry that has since moved, which reads as
	 * a smear rather than as a stale mask.
	 */
	private TargetSurface coverage;

	private int screenWidth;
	private int screenHeight;
	private boolean clearOwed;

	/**
	 * Whether the full round of emptying a reallocation asks for is still owed, which is a different
	 * question from {@link #clearOwed} and is why it is a field of its own.
	 * <p>
	 * A round is WRITTEN in one part and RECORDED in another, and the two are not finished at the
	 * same moment. The constants, the noise field and the pack's own textures are written where the
	 * round is decided, so they are done the instant {@link #clear} runs, and {@code clearOwed} is
	 * all they need. The per target emptying is only recorded there and paid afterwards, by the
	 * load-op of the first pass that attaches the surface or by the flush that takes what is left,
	 * so it can be lost outright: the frame that recorded it may open no pass at all, and the next
	 * one drops what it finds. This flag is what carries the recording across such a frame.
	 * <p>
	 * <strong>What it watches is the record, not the texels.</strong> It comes down when nothing is
	 * left on the books, and an entry leaves them where a pass descriptor is BUILT rather than where
	 * one opens. A frame that builds descriptors and opens none of them therefore clears the books
	 * without emptying anything, and this comes down with them. That gap is older than this flag and
	 * it is not in the carrying. What it used to cost was a target starting from whatever the
	 * driver had left in it; since every half of every target is emptied outright the moment the
	 * round is decided, it costs a recorded clear that is dropped after the texels were emptied
	 * anyway.
	 * <p>
	 * Warm up is the case this exists for, and it is the ordinary road rather than an odd one: the
	 * terrain opens the targets and hands the frame straight back, once per frame, for as long as
	 * programs are still compiling. Carrying {@code clearOwed} itself across those frames would
	 * rewrite every constant and re-upload every texture the pack ships on each one of them.
	 */
	private boolean fullDeferOwed;

	/**
	 * The targets a compute of the pack writes as {@code colorimgN}, which have to be created with
	 * the storage usage: the bit is baked into the image, so it is known before the first
	 * allocation and never added to one.
	 */
	private Set<Integer> storageTargets = Set.of();

	/** The targets a compute asked to store into and the device refused, each said once. */
	private final Set<Integer> storageRefused = new HashSet<>();

	private boolean broken;

	/**
	 * Colour textures still owed a clear this frame. Folded into the load-op of the first pass that
	 * writes them, which is the OpenGL equivalent of {@code glClear} as the FBO is bound; a
	 * standalone {@code clearColorTexture} is a GPU stop of its own on this backend.
	 */
	private final Map<GpuTexture, Vector4fc> pendingClears = new IdentityHashMap<>();

	/**
	 * The megabytes the last announcement carried, or -1 before there has been one. Held so that a
	 * resize says something only when there is something new to say; see {@link #announceSize}.
	 */
	private long announcedMegabytes = -1L;

	/**
	 * The screen the allocation failed at, so that the refusal is about that screen rather than
	 * about the pack.
	 * <p>
	 * The panorama capture is what makes the difference matter. The game takes the main target to
	 * 4096 square for six frames there, in {@code Minecraft.grabPanoramixScreenshot}, resizing it
	 * itself rather than through the window the rest of the frame follows, and this class follows the
	 * target: a machine that is comfortable at 1080p can fail to find the memory for the same chain
	 * sixteen times over, and the pack was then dead for the rest of the session, on a window that
	 * had gone back to its own size a frame later.
	 */
	private int brokenWidth;
	private int brokenHeight;

	/**
	 * The plan describes the programs that run and no others, so what its schedule turns over is
	 * exactly what needs a second texture. Nothing is filtered here: a set of doubled targets
	 * worked out from anything but the schedule the samplers were bound against is a parity that
	 * disagrees with itself, and that shows as a plausible picture rather than as an error.
	 */
	ColorTargets(TargetPlan plan, int noiseResolution, NoiseTexture.Image noiseImage,
			PackImages packImages, ImageInformation.Reading storageImages, int shadowResolution,
			List<PackDirectives.ShadowColour> shadowColours) {
		this.plan = plan;
		this.packImages = packImages;
		this.storageImages = new StorageImages(storageImages);
		this.storageBuffers = new StorageBuffers(CustomStorage.reading());
		// The pack asks for it by directive and 256 is the default. Held rather than looked up at
		// allocation: this class knows nothing of a frame, and the resolution never moves while a
		// pack is loaded.
		this.noiseResolution = noiseResolution;
		// The image wins over the directive when the pack ships both: the directive sizes the
		// generated field, and the image is uploaded as it stands, which is Iris's rule.
		this.noiseImage = noiseImage;
		// Which of the light's colour buffers are worth the memory is the plan's answer and not a
		// count, on the rule the colour targets themselves follow: a pack naming shadowcolor2 gets
		// the image, a pack writing nought alone pays for nought alone. The ceiling those names were
		// read against comes from the same place, being the pack's own declaration and not ours.
		this.shadowMap = new ShadowTargets(shadowResolution, shadowColours, plan.shadowAllocated(),
				plan.shadowCeiling());
		this.doubled = Set.copyOf(plan.schedule().doubled());

		// The format and the starting colour are read once and kept. Neither moves while a pack
		// is loaded, and the colour especially is a decision the plan has already made: the Iris
		// defaults, the pack's own override and the corrections this engine owes are settled
		// there, and answering any of it again here would be the second answer to one question.
		TargetDirectives directives = plan.directives();
		for (int index : plan.ordered()) {
			TargetDirectives.Colour colour = directives.clearColour(index);
			this.formats.put(index, GpuFormats.of(directives.format(index).used()));
			this.clearColours.put(index, new Vector4f(colour.r(), colour.g(), colour.b(), colour.a()));
		}

		this.fogCleared = !directives.declaresClearColour(FOG_TARGET);

		// Narrowed to what is actually allocated: a pack may turn the directive on for a target no
		// program of this place writes or samples, and a chain is a property of a texture that
		// exists. What is dropped here is named below with everything else that was asked for and
		// is not there.
		this.mipmapped = directives.mipmapped().stream()
				.filter(this.formats::containsKey)
				.collect(Collectors.toCollection(TreeSet::new));

		// A flip directive may turn over a target no program of this place writes or samples, and
		// nothing is allocated for one of those. Said here because the count of doubled targets is
		// read against the memory the pack costs, and those two would otherwise disagree.
		List<Integer> nowhere = this.doubled.stream()
				.filter(index -> !this.formats.containsKey(index))
				.toList();
		if (!nowhere.isEmpty()) {
			note("targets the schedule turns over and that no program of this place writes or "
					+ "samples, so no texture exists for either half: " + nowhere);
		}
	}

	/**
	 * Makes every planned target exist at its own size, allocating or resizing as needed, and
	 * owes itself a full clear whenever it had to. Must run on the render thread and outside any
	 * render pass.
	 *
	 * @return false when nothing usable could be prepared, in which case nothing may be drawn
	 */
	boolean ensure(int screenWidth, int screenHeight) {
		// A screen with no surface is refused before anything else, and before the latch below is
		// consulted at all: a minimised window reports nought, which is another size and would lift
		// a refusal on a size no allocation is ever attempted at. The frame the window comes back
		// would then pay the failure again, in full, with its stack trace.
		if (screenWidth <= 0 || screenHeight <= 0) {
			return false;
		}

		// A screen of another size is another question, so it is asked again rather than answered by
		// the last refusal. Nothing is retried at the size that failed: a screen that stays where it
		// is would otherwise pay a full allocation and a full log line every frame.
		if (this.broken && (screenWidth != this.brokenWidth || screenHeight != this.brokenHeight)) {
			this.broken = false;
		}

		if (this.broken) {
			return false;
		}

		this.screenWidth = screenWidth;
		this.screenHeight = screenHeight;

		boolean changed = false;
		try {
			changed = ensureConstants();
			changed |= ensureCoverage(screenWidth, screenHeight);
			// Not sized on the screen and therefore never resized with it: the pack's own resolution
			// is the whole point of the map. Its answer is not folded into the debt below because
			// the map is never in the clear that pays it: it empties itself where it is allocated,
			// and again at the top of the stage that fills it, because its content has to cross the
			// frame boundary.
			this.shadowMap.ensure();
			this.storageImages.ensure(screenWidth, screenHeight);
			this.storageBuffers.ensure(screenWidth, screenHeight);
			for (int index : this.plan.ordered()) {
				// Each target has its own size, so one of them can be half the screen and be the
				// only one reallocated when the window changes.
				TargetSize size = this.plan.directives().size(index);
				int width = size.width(screenWidth);
				int height = size.height(screenHeight);

				changed |= ensureSide(this.mainSide, index, width, height, "");
				if (this.doubled.contains(index)) {
					changed |= ensureSide(this.altSide, index, width, height, " alt");
				}
			}
		} catch (RuntimeException e) {
			this.broken = true;
			this.brokenWidth = screenWidth;
			this.brokenHeight = screenHeight;
			// The notes are printed on their own, one line each and with nothing in front of them,
			// so this one carries its subject: the error below is written where it happened and this
			// is read again much later, beside the notes of the plan. The size stays out of it on
			// purpose, note() being deduplicated on the exact text: a window dragged while the
			// allocation keeps failing would otherwise add one line per size it passed through.
			note(this.plan.packName() + " could not allocate its colour targets: " + e.getMessage());
			Vitrail.logger().error("Vitrail could not allocate the colour targets of {} at {}x{}, so "
					+ "nothing is drawn until the screen is another size", this.plan.packName(),
					screenWidth, screenHeight, e);

			return false;
		}

		if (changed) {
			// One debt, paid by the next clear. Two calls that a later reader could put out of
			// step would show as an image that explodes one frame after a resize and blames
			// everything but the resize.
			this.clearOwed = true;
			announceSize();
		}

		return true;
	}

	/**
	 * Records the emptying that the main and the alternate surface of each target the pack asked to
	 * have cleared are owed, and of every other target as well while a round is a full one.
	 * <p>
	 * A record is paid afterwards, by the load-op of the first pass that attaches the surface or by
	 * the flush that takes whatever is left, and {@link #fullDeferOwed} is what carries it over a
	 * frame that pays nothing. On {@link #clearOwed} alone, once per reallocation and never again
	 * while one is merely carried, a second part is written here and now: the constants, the noise
	 * field and the textures the pack ships, which are not targets and fold into no load-op, and
	 * both halves of every target, emptied outright so that a half no pass attaches while the
	 * programs are still compiling does not wait on a record that can be dropped.
	 * <p>
	 * The shadow map is deliberately not in this list. It is drawn at the end of a frame for the
	 * next one, so what it holds when the frame opens is exactly what the gbuffers are about to
	 * read, and the shadow stage empties it itself right before drawing.
	 *
	 * @param fog what colortex0 starts this frame at, unless the pack named a colour of its own for
	 *            it: the fog the game computed, with an alpha of one. The alpha is not a detail and
	 *            Iris says so where it builds the same vector: a zero there gives Sildur's pink
	 *            reflections, because the pack reads that channel as something of its own
	 */
	void clear(CommandEncoder encoder, Vector4fc fog) {
		// Entries still standing are the last frame's, recorded and never paid, and the wipe below
		// drops them without emptying a texel. So a full round that got no further than being
		// recorded is owed again, and stays owed while anything of it is still on the books.
		this.fullDeferOwed &= !this.pendingClears.isEmpty();

		boolean full = this.clearOwed || this.fullDeferOwed;

		// The written part, on the flag a reallocation raises and nothing carries forward. Hanging
		// it off `full` instead would run all of this on every frame of a warm up, where the targets
		// are opened and the frame handed back before any pass exists: four clears of their own, the
		// noise field written out again, and every texture the pack ships uploaded again, once per
		// frame for as long as programs are still compiling.
		if (this.clearOwed) {
			clear(encoder, this.black, OPAQUE_BLACK);
			clear(encoder, this.white, OPAQUE_WHITE);
			clear(encoder, this.grey, MID_GREY);
			clear(encoder, this.unwritten, UNWRITTEN);
			uploadNoise(encoder);
			this.packSurfaces.forEach((image, surface) -> upload(encoder, surface, image.pixels()));

			// Every half of every target, here and now rather than as a load-op. A half no pass
			// attaches while the programs are still compiling keeps its load-op on the books until
			// a frame that builds a descriptor and opens none drops the record, which is the hole
			// fullDeferOwed names, and a target the pack keeps between frames then starts from
			// whatever the driver left in it. Iris clears both framebuffers of every target on the
			// first frame after an allocation, clear flag or not (ClearPassCreator with fullClear),
			// and this is that clear, paid once per reallocation.
			for (int index : this.plan.ordered()) {
				Vector4fc colour = this.fogCleared && index == FOG_TARGET
						? fog
						: this.clearColours.get(index);
				clear(encoder, this.mainSide.get(index), colour);
				clear(encoder, this.altSide.get(index), colour);
			}
		}

		this.pendingClears.clear();

		// Every frame and never conditionally: the mask answers a question about THIS frame, and an
		// answer carried over from the last one is worse than no answer at all. Left standing, last
		// frame's depths would be compared with this frame's, the camera having moved between the
		// two, and the seed would repaint the pack's geometry over most of the screen.
		defer(this.coverage, UNWRITTEN);

		for (int index : this.plan.ordered()) {
			// A full clear ignores colortexNClear: a target that is kept from one frame to the
			// next still has to start from something known the first time it is written.
			if (!full && !this.plan.directives().clears(index)) {
				continue;
			}

			Vector4fc colour = this.fogCleared && index == FOG_TARGET
					? fog
					: this.clearColours.get(index);
			defer(this.mainSide.get(index), colour);
			defer(this.altSide.get(index), colour);
		}

		// Last, so a throw above leaves both flags standing. Written off at the top, an upload that
		// threw halfway left the pack sampling whatever the driver had put in a texture nobody had
		// written, for the rest of the session and without a line to say so.
		//
		// The written part is done by the time this line runs and is written off here. The record
		// has only just been made, so it is owed from here until the check at the top of the next
		// round finds the books clear.
		this.fullDeferOwed = full;
		this.clearOwed = false;
	}

	/**
	 * The clear colour of this view, once, for the pass that first writes it. Empty when the
	 * texture was already cleared or is kept from the last frame.
	 */
	Optional<Vector4fc> takeClear(GpuTextureView view) {
		if (view == null) {
			return Optional.empty();
		}

		Vector4fc colour = this.pendingClears.remove(view.texture());
		return colour == null ? Optional.empty() : Optional.of(colour);
	}

	/** True while a colour texture is still owed a clear this frame. */
	boolean hasPendingClears() {
		return !this.pendingClears.isEmpty();
	}

	/** One surface still owed its emptying, and the colour it is owed. */
	private record Pending(GpuTextureView view, Vector4fc colour) {
	}

	/**
	 * Empties every colour target still owed it. A pass about to open never finds its own targets
	 * here: it took theirs through {@code takeClear} while building its descriptor, so what is left
	 * is owed by surfaces nothing this frame is attaching.
	 * <p>
	 * <strong>Everything at once, and that is the point.</strong> Clearing only what one program
	 * samples left the rest pending, so the next program that needed one opened another pass for
	 * it, and a pass opened here ENDS THE GEOMETRY HOLD: the run of programs writing the same
	 * images that this engine works to keep in a single pass is cut at every one of those. Measured
	 * on Complementary Reimagined, a frame paid two of these passes and the entity family opened
	 * four to six passes where the terrain, which meets none of them, opened two.
	 * <p>
	 * The trade is paid by the target a LATER pass of the same frame writes: it used to keep its
	 * debt and ride that pass's load-op for free, and it is now emptied here, a full-surface write
	 * ahead of one more. Fewer passes, more cleared texels, and the frame comes out ahead because
	 * a pass is a GPU stop while a clear is bandwidth.
	 * <p>
	 * Doing them together also puts the emptying back where it was before it was deferred, at the
	 * head of the frame rather than scattered through it, which is the order the rest of this class
	 * was written against.
	 * <p>
	 * Encoded as one empty pass per size, load-op clear, which is {@code glClear} as the FBO is
	 * bound. One {@code clearColorTexture} apiece would each be a GPU stop of its own.
	 */
	void flushPending(CommandEncoder encoder) {
		if (this.pendingClears.isEmpty()) {
			return;
		}

		List<Pending> pending = new ArrayList<>();
		collectPending(this.coverage, pending);
		for (TargetSurface surface : this.mainSide.values()) {
			collectPending(surface, pending);
		}

		for (TargetSurface surface : this.altSide.values()) {
			collectPending(surface, pending);
		}

		if (pending.isEmpty()) {
			return;
		}

		LinkedHashMap<Long, List<Pending>> bySize = new LinkedHashMap<>();
		for (Pending one : pending) {
			GpuTexture texture = one.view().texture();
			long key = ((long) texture.getWidth(0) << 32) | (texture.getHeight(0) & 0xFFFFFFFFL);
			bySize.computeIfAbsent(key, ignored -> new ArrayList<>()).add(one);
		}

		int perPass = maxColorAttachments();
		for (List<Pending> group : bySize.values()) {
			int width = group.get(0).view().texture().getWidth(0);
			int height = group.get(0).view().texture().getHeight(0);
			for (int from = 0; from < group.size(); from += perPass) {
				int to = Math.min(from + perPass, group.size());
				RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> CLEAR_LABEL);
				for (int index = from; index < to; index++) {
					Pending one = group.get(index);
					descriptor.withColorAttachment(one.view(), Optional.of(one.colour()));
				}

				descriptor.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
				encoder.createRenderPass(descriptor).close();
			}
		}
	}

	/**
	 * Takes this surface's owed emptying. Silent on a surface that is not there or owes nothing,
	 * which is the ordinary case for most of them.
	 */
	private void collectPending(TargetSurface surface, List<Pending> into) {
		GpuTexture texture = surface == null ? null : surface.texture();
		if (texture == null) {
			return;
		}

		Vector4fc colour = this.pendingClears.remove(texture);
		if (colour != null) {
			into.add(new Pending(surface.view(), colour));
		}
	}

	/**
	 * How many colour attachments one pass may carry, asked of the device rather than assumed.
	 * <p>
	 * The Vulkan minimum a driver has to offer is four, not eight, and the count that matters here
	 * is whatever this device reports: a pass built with more attachments than it allows is not a
	 * slow pass, it is an invalid one. Asked per call rather than held, because the only cost is a
	 * record field and a device can be replaced under this class.
	 */
	private static int maxColorAttachments() {
		GpuDevice device = RenderSystem.tryGetDevice();

		return device == null
				? ASSUMED_COLOR_ATTACHMENTS
				: Math.max(1, device.getDeviceInfo().limits().maxColorAttachments());
	}

	/**
	 * Names the targets a compute of the pack writes as an image, before any of them is allocated.
	 * Whether the device makes a storage image of a target's format is asked at its allocation, on
	 * the render thread where a device exists, and a refusal is said there: the target is
	 * allocated as it was and the compute naming it is refused at its first dispatch.
	 */
	void storageTargets(Set<Integer> targets) {
		this.storageTargets = Set.copyOf(targets);
	}

	/**
	 * Makes the alternate half the main one, for the targets the plan named and no others. After
	 * the last pass of the frame, and only a name swap: the next frame walks the chain from an
	 * empty flipped set, so it reads the main half of everything before anything has written it.
	 * Without this the pack would be handed, once per frame, the half it filled two frames ago.
	 * <p>
	 * The texels stay where they are. A copy would be a GPU stop, and both halves already carry
	 * the same format. Mip levels past nought are rebuilt from nought before any program reads
	 * one, the same as they were when this used to copy.
	 * <p>
	 * Iris copies here and says in the same breath that it would rather not:
	 * {@code FinalPassRenderer} carries "we merely copy it from alt to main, this works for the
	 * most part but it's not perfect", the better approach being framebuffers for every other
	 * frame. This backend builds its pass descriptor per frame, so it can have that for nothing.
	 * <p>
	 * <strong>The debug labels cross over and are not corrected.</strong> A surface is named at
	 * allocation and keeps that name, so after an odd number of frames the texture labelled
	 * {@code Vitrail colortexN alt} is the main half. It costs nothing at runtime and it misreads
	 * badly in a GPU capture, which is where a target is looked up by name.
	 */
	void swapBack(List<Integer> targets) {
		for (int index : targets) {
			TargetSurface alt = this.altSide.get(index);
			TargetSurface main = this.mainSide.get(index);
			if (alt == null || main == null) {
				note("nothing to swap back for " + TargetName.canonical(index) + ": the plan asks "
						+ "for it and only one half of it exists");
				continue;
			}

			this.mainSide.put(index, alt);
			this.altSide.put(index, main);
		}
	}

	/**
	 * The world's depth in the window the pack reads it in. Never null, and its own images are null
	 * until a frame has filled them.
	 */
	PackDepth depth() {
		return this.depth;
	}

	/**
	 * The one texel {@code centerDepthSmooth} is read out of, and the pass that draws it. Its own
	 * image is null until a frame has drawn it, and a name bound to it then reads the far plane.
	 */
	CenterDepth centerDepth() {
		return this.centerDepth;
	}

	/** Never held from one frame to the next. Null when this index was never allocated. */
	GpuTextureView view(int index, TargetSchedule.Side side) {
		TargetSurface surface = target(index, side);

		return surface == null ? null : surface.view();
	}

	GpuTexture texture(int index, TargetSchedule.Side side) {
		TargetSurface surface = target(index, side);

		return surface == null ? null : surface.texture();
	}

	/**
	 * The surface itself, for the reduction that fills its chain. Null when this index was never
	 * allocated, and carrying a single level whenever nothing reads this target at a lod.
	 */
	TargetSurface surface(int index, TargetSchedule.Side side) {
		return target(index, side);
	}

	/** What this program asks to blend with, or null when it asks for nothing. */
	BlendMode blend(String program) {
		return this.plan.blend(program).orElse(null);
	}

	/**
	 * Which targets one program reads at a lod, narrowed to those that carry a chain.
	 * <p>
	 * Per program and not the union, because this is what decides when a chain is rebuilt. A chain
	 * is only valid until something writes level nought again, so it is refilled before the next
	 * program that reads one after such a write; taking the union here would rebuild every chain
	 * before every program that happens to sample the target, which is ten render passes apiece
	 * for a result nothing reads.
	 */
	Set<Integer> lodReads(String program) {
		return this.plan.directives().mipmapRequests().getOrDefault(program, Set.of()).stream()
				.filter(this.mipmapped::contains)
				.collect(Collectors.toCollection(TreeSet::new));
	}

	GpuFormat format(int index) {
		return this.formats.get(index);
	}

	/**
	 * The format one {@code shadowcolor} is allocated in, which the pack may have chosen. Read by the
	 * shadow pipelines: dynamic rendering wants the colour state of a pipeline to name the format of
	 * the attachment it is bound against, so a pack asking for R8 moves both or neither.
	 */
	GpuFormat shadowFormat(int index) {
		return this.shadowMap.format(index);
	}

	int screenWidth() {
		return this.screenWidth;
	}

	int screenHeight() {
		return this.screenHeight;
	}

	/** LINEAR wherever Iris allows it, which is everywhere but an integer format. */
	FilterMode filter(int index) {
		TargetFormat format = this.plan.directives().format(index).used();

		return has(index) ? GpuFormats.filterFor(format) : FilterMode.NEAREST;
	}

	/**
	 * Whether the plan carries this target at all, which is answerable before anything has been
	 * allocated. {@link #view} still comes back null until {@link #ensure} has run.
	 */
	boolean has(int index) {
		return this.formats.containsKey(index);
	}

	/** Opaque black, one pixel, for a sampler the engine has no answer for. Never the scene. */
	GpuTextureView black() {
		return this.black == null ? null : this.black.view();
	}

	/**
	 * One pixel of the sentinel, which is a mask saying the pack wrote nowhere at all. Stands in
	 * for the mask itself over the frames before it is allocated, so that the seed covers its
	 * target whole there rather than being cut against a depth nothing wrote.
	 */
	GpuTextureView unwritten() {
		return this.unwritten == null ? null : this.unwritten.view();
	}

	/** White, so a shadow lookup reads the far plane rather than putting the world in shadow. */
	GpuTextureView white() {
		return this.white == null ? null : this.white.view();
	}

	/** Mid grey, for a lookup this engine has no answer for. */
	GpuTextureView grey() {
		return this.grey == null ? null : this.grey.view();
	}

	/** The pack's noise image, at the resolution it asked for. */
	GpuTextureView noise() {
		return this.noise == null ? grey() : this.noise.view();
	}

	/**
	 * The depth the pack's opaque geometry left this frame, or null until the first frame allocates
	 * it. Never held across a frame by anyone: it is looked up like every other view here.
	 */
	GpuTextureView coverage() {
		return this.coverage == null ? null : this.coverage.view();
	}

	/**
	 * The format the mask is allocated in, which the terrain pipeline has to name as well: dynamic
	 * rendering refuses a pipeline whose colour state does not match the attachment it is bound
	 * against, by name and in the middle of the world.
	 */
	GpuFormat coverageFormat() {
		return COVERAGE_FORMAT;
	}

	/** The shadow map. Never null, and its own images are null until the first frame allocates them. */
	ShadowTargets shadow() {
		return this.shadowMap;
	}

	long bytes() {
		return sum(TargetSurface::bytes);
	}

	/** What the same targets would cost with no chain, so that the two costs are read apart. */
	private long baseBytes() {
		return sum(TargetSurface::baseBytes);
	}

	private long sum(ToLongFunction<TargetSurface> cost) {
		long total = 0L;
		for (TargetSurface surface : this.mainSide.values()) {
			total += cost.applyAsLong(surface);
		}

		for (TargetSurface surface : this.altSide.values()) {
			total += cost.applyAsLong(surface);
		}

		return total;
	}

	List<String> notes() {
		return List.copyOf(this.notes);
	}

	/**
	 * Empties the storage images the pack marked {@code clear = true}, at the top of the shadow
	 * stage, before geometry writes them. Complementary's voxel volume is one of those.
	 */
	void clearStorage(CommandEncoder encoder) {
		this.storageImages.clearMarked(encoder);
	}

	/** Moves what may be moved onto this frame's anchor. {@link StorageImages#reanchor} says why. */
	void reanchorStorage(CommandEncoder encoder, int dx, int dy, int dz) {
		this.storageImages.reanchor(encoder, dx, dy, dz);
	}

	void release() {
		this.mainSide.values().forEach(TargetSurface::close);
		this.altSide.values().forEach(TargetSurface::close);
		this.mainSide.clear();
		this.altSide.clear();

		this.packSurfaces.values().forEach(TargetSurface::close);
		this.packSurfaces.clear();
		this.storageImages.close();
		this.storageBuffers.close();

		this.black = release(this.black);
		this.white = release(this.white);
		this.grey = release(this.grey);
		this.noise = release(this.noise);
		this.unwritten = release(this.unwritten);
		this.coverage = release(this.coverage);
		this.shadowMap.release();
		this.depth.release();
		this.centerDepth.release();
		this.pendingClears.clear();

		// Whatever is allocated next is a first allocation again, and it has to say what it costs
		// even when it happens to cost the same as what was just let go.
		this.announcedMegabytes = -1L;
	}

	private boolean ensureConstants() {
		if (this.black != null) {
			return false;
		}

		// The device's own set first, made here because this runs outside any pass and the passes
		// below cannot: it carries the far plane in a depth format, which is the one fallback a
		// comparison sampler can be bound against.
		ConstantTextures.of(RenderSystem.getDevice());

		// One pixel each, and clamped when they are bound, so the value is what a lookup reads
		// wherever it lands. None of them carries a chain: a constant has nothing to average, and
		// the noise image is a field a pack indexes itself rather than one anything reads at a lod.
		this.black = new TargetSurface("Vitrail black", CONSTANT_FORMAT, false, 1, 1);
		this.white = new TargetSurface("Vitrail white", CONSTANT_FORMAT, false, 1, 1);
		this.grey = new TargetSurface("Vitrail grey", CONSTANT_FORMAT, false, 1, 1);
		// The mask's own format and not the constants', because it stands in for the mask: what
		// reads it compares it with a depth, and black would say the pack wrote the whole screen at
		// the far plane rather than that it wrote nothing.
		this.unwritten = new TargetSurface("Vitrail unwritten mask", COVERAGE_FORMAT, false, 1, 1);

		if (this.noiseImage != null) {
			this.noise = new TargetSurface("Vitrail noise", CONSTANT_FORMAT, false,
					this.noiseImage.width(), this.noiseImage.height());
			Vitrail.logger().info("noisetex is the pack's own image, {}x{}",
					this.noiseImage.width(), this.noiseImage.height());
		} else {
			int resolution = this.noiseResolution;
			this.noise = new TargetSurface("Vitrail noise", CONSTANT_FORMAT, false, resolution,
					resolution);
			Vitrail.logger().info("noisetex is the generated field at {}x{}", resolution, resolution);
		}

		ensurePackTextures();

		return true;
	}

	/**
	 * Makes the coverage mask exist at the size of the screen, which is the size the geometry it
	 * records is rasterised at. No chain: nothing reads it at a lod, and a coarser mask would let
	 * the seed through along every silhouette.
	 */
	private boolean ensureCoverage(int width, int height) {
		if (this.coverage == null) {
			this.coverage = new TargetSurface("Vitrail terrain coverage", COVERAGE_FORMAT, false,
					width, height);

			return true;
		}

		return this.coverage.resize(width, height);
	}

	/**
	 * One texture per file the pack ships, at the size it decoded to, with no chain.
	 * <p>
	 * No chain because a texture of the pack's own has never had one: Iris forces the maximum level
	 * to nought on every one of them, and a lookup table read at a level nobody wrote is the kind of
	 * wrong that looks right. Never resized either, since none of them is sized on the screen.
	 * <p>
	 * The size is written out texture by texture and the memory once at the end, because neither is
	 * readable from the pack: Body Camera's lookup table is fifty nine kilobytes in the zip and
	 * sixty four megabytes once it is four thousand and ninety six square in memory.
	 */
	private void ensurePackTextures() {
		List<PackImages.Image> images = this.packImages.images();
		if (images.isEmpty()) {
			return;
		}

		for (PackImages.Image image : images) {
			Vitrail.logger().info("The pack supplies {}", PackImages.describe(image));

			// One at a time, because one refusal here must cost one texture and not the pack. These
			// are the only surfaces of the engine whose size comes from a downloaded file rather
			// than from the window, and everything else in this method is on the road that sets
			// broken and draws nothing. Same answer ShadowTargets gives for a size a pack chose: a
			// lookup table is not worth taking the pack down for, and a sampler with nothing behind
			// it already reads black.
			try {
				this.packSurfaces.put(image, new TargetSurface(
						"Vitrail " + image.texture().sampler(), image.format(), false, image.width(),
						image.height()));
			} catch (RuntimeException e) {
				note(image.texture().sampler() + " could not be allocated at " + image.width() + "x"
						+ image.height() + ", so it reads one black pixel: " + e.getMessage());
				Vitrail.logger().warn("Vitrail could not allocate {} at {}x{}, so it reads one black pixel",
						image.texture().sampler(), image.width(), image.height(), e);
			}
		}

		long bytes = this.packImages.bytes();
		Vitrail.logger().info("{} textures of the pack's own, {} MiB once decoded", images.size(),
				megabytes(bytes));

		if (this.packImages.loud()) {
			// Said apart because it is invisible from the pack: what is read is compressed and what
			// is allocated is not, and the two are three orders of magnitude apart for a lookup
			// table.
			Vitrail.logger().warn("The pack's own textures take {} MiB of memory, far more than the "
					+ "files they were read from", megabytes(bytes));
		}
	}

	/**
	 * The image the pack supplies for that name at that stage, with the filter and the addressing it
	 * asked for, or null when the pack takes the name over and nothing could be put behind it.
	 * <p>
	 * A volume is addressed CLAMP whatever the pack wrote, and that is the one answer this class
	 * overrides. Its wrapping is done by the helper the translation printed, on coordinates that
	 * never leave the tile; letting the sampler repeat as well would wrap the ATLAS, which is a
	 * different image, and the gutter would stop being read at all.
	 */
	PackBinding packTexture(TextureStage stage, String sampler) {
		PackImages.Image image = this.packImages.find(stage, sampler);
		TargetSurface surface = image == null ? null : this.packSurfaces.get(image);
		if (surface == null || surface.view() == null) {
			return null;
		}

		boolean flat = !sampler.equals(SamplerPlan.behind(sampler));

		return new PackBinding(surface.view(),
				image.texture().blur() ? FilterMode.LINEAR : FilterMode.NEAREST,
				!flat && !image.texture().clamp());
	}

	/**
	 * Uploads the noise image, once: the pack's own when it ships one, otherwise the generator the
	 * harness has a fingerprint for.
	 * <p>
	 * A pack indexes this image with coordinates of its own, so it is not enough for the picture to
	 * look like noise: it has to be the image the pack was tuned against, which is why the generator
	 * follows Iris bit for bit and why no observation in the game could ever prove it right. The
	 * same rule is what makes {@code texture.noise} matter: four packs of the corpus were tuned
	 * against a smooth image of their own, and the generated white noise fed to their water octaves
	 * crumples the surface into facets.
	 * <p>
	 * Until this was here every {@code noisetex} lookup read one mid grey pixel, which is a constant
	 * where the pack asked for a field. That is not a missing detail: BSL builds its cloud distance
	 * from it, and a cloud distance of nought discards every fragment of water.
	 */
	private void uploadNoise(CommandEncoder encoder) {
		byte[] pixels = this.noiseImage != null
				? this.noiseImage.rgba()
				: NoiseTexture.rgba(this.noise.width());
		upload(encoder, this.noise, pixels);
	}

	/** One image into one surface, whole, at level nought. Outside any render pass, like the clears. */
	private static void upload(CommandEncoder encoder, TargetSurface surface, byte[] pixels) {
		ByteBuffer data = ByteBuffer.allocateDirect(pixels.length).order(ByteOrder.nativeOrder());
		data.put(pixels).flip();

		encoder.writeToTexture(surface.texture(), data, 0, 0, 0, 0, surface.width(),
				surface.height());
	}

	private boolean ensureSide(Map<Integer, TargetSurface> side, int index, int width, int height, String suffix) {
		TargetSurface surface = side.get(index);
		if (surface != null && surface.width() == width && surface.height() == height) {
			return false;
		}

		String name = TargetName.canonical(index) + suffix;
		boolean mipped = this.mipmapped.contains(index);
		if (surface == null) {
			GpuFormat format = this.formats.get(index);
			TargetDirectives directives = this.plan.directives();
			boolean storage = this.storageTargets.contains(index) && GpuFormats.storageCapable(format);
			if (this.storageTargets.contains(index) && !storage && this.storageRefused.add(index)) {
				Vitrail.logger().warn("{} is written by a compute as an image, and this device makes "
						+ "no storage image of {}, so that compute is not dispatched", name, format);
			}
			// Named before it is allocated, on purpose. RG11B10_FLOAT as a colour attachment is
			// not something the Vulkan specification guarantees and nothing in the game asks the
			// driver whether it has it, so the last line written has to name the format asked for.
			// The declaration comes with it, because a wrong image starts at a wrong declaration
			// and reading it off the picture is what has to stop being necessary.
			Vitrail.logger().info("Allocating {} as {} at {}x{}, {} level(s), declared {} at {}{}", name,
					format, width, height, TargetSurface.levelsFor(mipped, width, height),
					directives.format(index).declared(), directives.formatSource(index),
					storage ? ", writable from a compute" : "");
			side.put(index, new TargetSurface("Vitrail " + name, format, mipped, storage, width,
					height));
		} else {
			surface.resize(width, height);
		}

		return true;
	}

	private void announceSize() {
		long bytes = bytes();
		long base = baseBytes();
		long single = this.plan.bytesAt(this.screenWidth, this.screenHeight, Set.of());

		// Dragging a window edge reallocates at every step the game reports, and each of those used
		// to copy this whole block out again, the memory warning with it, until a line that means
		// "this pack is expensive" read as an alarm going off ten times a second. The screen moves
		// by the pixel and the figure this block is about moves by the megabyte, so that figure is
		// what decides whether any of it is worth saying twice.
		long total = megabytes(bytes);
		if (total == this.announcedMegabytes) {
			return;
		}

		this.announcedMegabytes = total;
		Vitrail.logger().info("Colour targets of {} sized for {}x{}: {} targets, {} MiB",
				this.plan.packName(), this.screenWidth, this.screenHeight, this.mainSide.size(),
				megabytes(bytes));

		// Named and not counted. Which targets take part in the ping pong is the one thing a wrong
		// picture is read back against, and the cost of the second half is the price of the chain.
		// Read against the cost of level nought alone, so that this line stays about the doubling:
		// the mip chains are the next one's to account for.
		if (!this.doubled.isEmpty()) {
			Vitrail.logger().info("{} targets doubled: {}, {} MiB instead of {}",
					this.doubled.size(), this.doubled, megabytes(base), megabytes(single));
		}

		if (!this.mipmapped.isEmpty()) {
			Vitrail.logger().info("{} targets carry a mip chain because a program reads them at a "
					+ "lod: {}, {} MiB more", this.mipmapped.size(), this.mipmapped,
					megabytes(bytes - base));
		}

		if (bytes > LOUD_BYTES) {
			// The second halves cost base minus single, NOT bytes minus single: bytes carries the
			// mip chains as well, and charging them to the ping pong would name the wrong cause to
			// somebody reading this line to decide what to turn off. The chains are named on their
			// own line above.
			Vitrail.logger().warn("{} takes {} MiB of colour targets at {}x{}, {} of them for the "
					+ "second halves of {} and {} for the mip chains", this.plan.packName(),
					megabytes(bytes), this.screenWidth, this.screenHeight, megabytes(base - single),
					this.doubled, megabytes(bytes - base));
		}
	}

	private static long megabytes(long bytes) {
		return bytes / (1024L * 1024L);
	}

	private TargetSurface target(int index, TargetSchedule.Side side) {
		// A target nothing flips has one texture, and a schedule asking for its other side is
		// asking for the only one there is.
		if (side == TargetSchedule.Side.ALT) {
			TargetSurface alt = this.altSide.get(index);
			if (alt != null) {
				return alt;
			}

			// Said once per target, because past this point it must never happen: the schedule
			// doubles everything a running program turns over, so a fall back here means the halves
			// the samplers were bound against and the halves that exist are two different answers.
			// Left silent it is the one place such a disagreement can hide.
			if (this.formats.containsKey(index) && this.fellBack.add(index)) {
				Vitrail.logger().warn("{} was read from its alternate half and only one half of it "
						+ "exists, so both sides of the ping pong are the same texture",
						TargetName.canonical(index));
			}
		}

		return this.mainSide.get(index);
	}

	private void note(String text) {
		if (!this.notes.contains(text)) {
			this.notes.add(text);
		}
	}

	/**
	 * Remembers a clear for the first pass that writes this surface. A standalone texture clear is
	 * a full GPU stop on this backend; a load-op on the pass that first attaches it is not.
	 */
	private void defer(TargetSurface surface, Vector4fc colour) {
		GpuTexture texture = surface == null ? null : surface.texture();
		if (texture != null) {
			this.pendingClears.put(texture, colour);
		}
	}

	/**
	 * Clears the whole chain, and that is the backend's choice rather than ours: there is no level
	 * argument on {@code clearColorTexture}, and {@code VulkanCommandEncoder} sets the subresource
	 * range's {@code levelCount} to the texture's full mip count. So a mipmapped target costs about
	 * four thirds of a clear rather than one.
	 * <p>
	 * Used for the one-pixel constants, which no pack pass writes and which therefore cannot fold
	 * into a load-op, and once per reallocation for both halves of every target, where the load-op
	 * the round records may never be paid.
	 */
	private static void clear(CommandEncoder encoder, TargetSurface surface, Vector4fc colour) {
		GpuTexture texture = surface == null ? null : surface.texture();
		if (texture != null) {
			encoder.clearColorTexture(texture, colour);
		}
	}

	private static TargetSurface release(TargetSurface surface) {
		if (surface != null) {
			surface.close();
		}

		return null;
	}
}
