package dev.vitrail.render;

import dev.vitrail.dh.DhLods;
import dev.vitrail.glsl.DistantVertex;
import dev.vitrail.glsl.PackProgram;
import dev.vitrail.glsl.VertexInputs;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.program.AlphaTest;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.target.ChainPlan;
import dev.vitrail.pack.target.TargetPlan;
import dev.vitrail.pack.target.TargetSize;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Draws Distant Horizons' far terrain with the pack's own programs, where DH would have drawn it
 * with its.
 * <p>
 * <strong>This is the whole of what the far terrain was missing, and what it was missing was the
 * light.</strong> The colour used to be DH's, so the two halves of one landscape were lit by two
 * engines and met at a seam no pack could close. The geometry {@code dh/DhLods} takes over is drawn
 * here instead, once per half of the frame, with {@code dh_terrain} and {@code dh_water} - the two
 * names every pack of the corpus ships, and the two Iris serves
 * ({@code compat/dh/DHCompatInternal.java:67-79}).
 * <p>
 * <strong>The depth image is this engine's own, and that is what keeps DH out of the picture
 * entirely.</strong> Nothing is drawn into DH's own colour or depth; DH's own apply pass then finds
 * its depth image exactly as it cleared it and discards every pixel of the screen
 * ({@code assets/distanthorizons/shaders/apply/blaze/frag.fsh} discarding on a depth of nought under
 * a reversed Z), so there is no compositing to switch off and no event to bind. What the far terrain
 * leaves here is what {@code render/PackDepth} converts into the pack's window and serves back
 * under {@code dhDepthTex}, in two takes that bracket the water half exactly as the world's own
 * depth is taken around its translucents.
 * <p>
 * <strong>One value belongs to the section rather than to the pass</strong>, which is where DH keeps
 * a section's corner: three unsigned shorts cannot hold a world coordinate, so the vertex carries
 * block coordinates inside the section and the corner arrives beside it. A value that changes
 * between draws of one pass cannot live in the block every draw of that pass reads, and a pass per
 * section is not an answer either: a render pass is a load and a store of every attachment it names.
 * So there is a second block, one aligned slot per section, written before the pass opens and bound
 * as each section comes up. DH does exactly this with its own, one buffer per container
 * ({@code common/render/blaze/BlazeDhTerrainRenderer.java:280}), and Iris sets a uniform per buffer
 * ({@code compat/dh/IrisLodRenderProgram.java:124}) because a GL uniform is per program rather than
 * per draw.
 */
public final class DistantDraw {

	/**
	 * One half of the far terrain: what the pack is asked for, and where in the frame it falls.
	 *
	 * @param element       one word for the log and for the shader identifier
	 * @param program       the bare name the pack is asked for
	 * @param afterDeferred whether this half is drawn after the deferred stage, which decides the
	 *                      half of every target it reads and writes. DH calls its own two halves from
	 *                      the head of the game's opaque chunk group and from the head of its
	 *                      translucent one ({@code neoforge/mixins/client/MixinChunkSectionsToRender
	 *                      .java:57-64}), which is where the game's own solid and translucent chunk
	 *                      passes stand, so the two answers are the chunk passes' own
	 */
	public record Element(String element, String program, boolean afterDeferred) {

		/** What the pack has to be read for to serve this half, in terms the translation knows. */
		private PackProgram.GeometryElement asked() {
			// No alpha test: an LOD carries no texture at all, DH's mesh having no texture coordinate
			// this engine reads, so there is no sampled alpha for a threshold to cut. Iris hands its
			// DH keys no cutout either. No coverage mask, for the reason DistantProgram gives.
			return new PackProgram.GeometryElement(this.element, this.program, AlphaTest.OFF,
					VertexInputs.DISTANT, false);
		}

		/**
		 * What a pack is told it is drawing. The chunk passes' own answer, which is what the geometry
		 * is: a pack branching on {@code MC_RENDER_STAGE_TERRAIN_SOLID} in a {@code dh_terrain} is
		 * branching on the stage it was written for.
		 */
		RenderStage stage() {
			return this.afterDeferred ? RenderStage.TERRAIN_TRANSLUCENT : RenderStage.TERRAIN_SOLID;
		}
	}

	/**
	 * The two halves, keyed by the word the log uses. Ordered, so that the lines the load may write
	 * about them come out in the order they are drawn.
	 */
	private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();

	static {
		ELEMENTS.put("distant", new Element("distant", "dh_terrain", false));
		ELEMENTS.put("distant_water", new Element("distant_water", "dh_water", true));
	}

	/** One vec3 under std140, which is what one slot HOLDS; how far apart slots start is the
	 * device's answer and {@link #slotBytes} alone carries it. */
	private static final int BLOCK_BYTES = 16;

	/** What the far terrain's own depth image holds, which is the format the game's own depth has. */
	private static final GpuFormat DEPTH_FORMAT = GpuFormat.D32_FLOAT;

	private final PackChain owner;
	private final Path packPath;
	private final String place;
	private final Map<String, OptionValue> chosen;
	private final String profile;
	private final PackValues values;
	private final int load;
	private final ChainPlan plan;
	private final TargetPlan chainTargets;
	private final boolean chainRuns;
	private final ColorTargets targets;

	/** One program per half the pack serves. Empty until the pack has been read. */
	private final Map<String, DistantProgram> programs = new LinkedHashMap<>();

	/** The elements of DH's mesh those programs declare, which is what the format is built from. */
	private List<String> carried = List.of();

	/** Whether the pack has been read for its far terrain. A reading that served nothing is still one. */
	private boolean read;

	/** The reasons this engine has already said something about, one line each and not one a frame. */
	private final Set<String> refused = new LinkedHashSet<>();

	/** Where the far terrain leaves its depth, in DH's own volume and reversed like the game's. */
	private GpuTexture depth;
	private GpuTextureView depthView;
	private int depthWidth;
	private int depthHeight;

	/** Whether anything was drawn into that image this frame, which is what the takes ask. */
	private boolean drew;

	/**
	 * Whether this family has stopped for the load. Set by a failure inside the draw and cleared only
	 * by a reload, which is what {@link #release} is.
	 */
	private boolean broken;

	/** The section corners of the halves recorded this frame, one aligned slot each. */
	private MappableRingBuffer sections;
	private int slots;

	/** How many of those slots this frame has already written, which is where the next half starts. */
	private int used;

	/**
	 * The most slots any one frame has spent, both halves together, so that a frame refused for
	 * width is refused once: the guess below doubles the OPAQUE half, and a water half wider than
	 * that would otherwise outgrow the buffer on every frame alike rather than only the first.
	 */
	private int peak;

	DistantDraw(PackChain owner, Path packPath, String place, Map<String, OptionValue> chosen,
			String profile, PackValues values, int load, ChainPlan plan, TargetPlan chainTargets,
			boolean chainRuns, ColorTargets targets) {
		this.owner = owner;
		this.packPath = packPath;
		this.place = place;
		this.chosen = Map.copyOf(chosen);
		this.profile = profile;
		this.values = values;
		this.load = load;
		this.plan = plan;
		this.chainTargets = chainTargets;
		this.chainRuns = chainRuns;
		this.targets = targets;
	}

	/**
	 * Draws one half of the far terrain with the pack's own program.
	 * <p>
	 * The one door {@code dh/DhLods} comes through, and the answer decides what DH does next: false
	 * hands the half back, and DH draws it with its own shader exactly as it did before this engine
	 * stood in the way.
	 *
	 * @param opaque   which half this is, taken from DH's own call rather than worked out here
	 * @param sections every section of the far terrain, in the order DH sorted them
	 * @return whether the pack really drew it
	 */
	public static boolean draw(boolean opaque, List<DhLods.Section> sections) {
		DistantDraw draw = PackChain.distant();
		GpuDevice device = RenderSystem.tryGetDevice();
		Minecraft minecraft = Minecraft.getInstance();
		if (draw == null || draw.broken || device == null || minecraft == null || sections.isEmpty()) {
			return false;
		}

		try {
			return draw.record(device, minecraft, ELEMENTS.get(opaque ? "distant" : "distant_water"),
					sections);
		} catch (RuntimeException e) {
			// Latched, like every other family the game calls back into: the alternative is one stack
			// trace a frame for a failure that will not mend itself. What is lost by stopping is only
			// this engine's own drawing of the far terrain, DH's being handed back on the next line.
			draw.broken = true;
			Vitrail.logger().error("Vitrail stopped drawing the far terrain after an error, so Distant "
					+ "Horizons draws it with its own shader for the rest of this pack", e);

			return false;
		}
	}

	/** The image the far terrain left its depth in, or null when it drew nothing this frame. */
	GpuTextureView served() {
		return this.drew ? this.depthView : null;
	}

	private boolean record(GpuDevice device, Minecraft minecraft, Element element,
			List<DhLods.Section> sections) {
		if (!this.read) {
			read();
		}

		DistantProgram program = this.programs.get(element.element());
		if (program == null) {
			return false;
		}

		// The water half only over an opaque half this engine drew in the same frame. Handed back
		// alone, DH would draw it into its own images, which hold nothing else, and its apply pass
		// would composite that water over the pack's far terrain with no opaque LODs left in DH's
		// depth to occlude it: water showing through the hills that stand in front of it. The same
		// rule the other way round is read()'s, which serves the two halves together or not at all.
		if (element.afterDeferred() && !this.drew) {
			return false;
		}

		RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
		if (main == null || main.getColorTextureView() == null) {
			return false;
		}

		this.owner.beginFrame();
		if (!this.owner.openTargets(device) || (this.chainRuns && !this.owner.drawable())) {
			return false;
		}

		if (!ensureDepth(device, main)) {
			return false;
		}

		RenderPipeline pipeline = program.prepare(device, this.values.world().drawnDistantProjection());
		if (pipeline == null) {
			return refuse("prepare:" + element.element(), "the " + element.element() + " program "
					+ "refused to prepare, which it says on its own line above. That is settled for as "
					+ "long as this pack is loaded, so the far terrain keeps Distant Horizons' own "
					+ "shader steadily rather than as a flicker");
		}

		RenderPassDescriptor descriptor = program.descriptor(main.getColorTextureView(),
				this.depthView);
		if (descriptor == null && !program.plain()) {
			return refuse("unallocated:" + element.element(), "one of the pack's colour targets had "
					+ "no image yet on some frame, so the pass this half wanted could not be built "
					+ "then. That comes and goes with the frame rather than lasting");
		}

		CommandEncoder encoder = device.createCommandEncoder();

		// Emptied at the first half of the frame that gets this far, and asked that way rather than
		// keyed on the opaque half: a half that refused, or a pack that serves only the other one,
		// would otherwise leave the image holding the frame before it, and the takes would hand the
		// pack last frame's far terrain in a world that has moved on.
		if (!this.drew) {
			// Nought is DH's own clear and the far plane of a reversed Z, so an untouched pixel reads
			// as nothing drawn, which converts to the far plane the pack tests for.
			encoder.clearDepthTexture(this.depth, 0.0);
		}

		int base = writeSections(device, sections, minecraft);
		if (base < 0) {
			return refuse("sections", "the far terrain grew wider than the block holding its section "
					+ "corners between the two halves of one frame, and the wider block cannot replace "
					+ "the one the half already recorded is drawn from. The next frame has it");
		}

		try (RenderPass pass = descriptor == null
				? encoder.createRenderPass(() -> "Vitrail " + element.element(),
						main.getColorTextureView(), Optional.empty(), this.depthView,
						java.util.OptionalDouble.empty())
				: encoder.createRenderPass(descriptor)) {
			pass.setPipeline(pipeline);
			program.bind(pass);

			GpuBuffer block = this.sections.currentBuffer();
			for (int index = 0; index < sections.size(); index++) {
				pass.setUniform(DistantVertex.SECTION_BLOCK,
						block.slice((long) (base + index) * slotBytes(device), BLOCK_BYTES));

				for (DhLods.Piece piece : sections.get(index).pieces()) {
					pass.setIndexBuffer(piece.indices(), IndexType.INT);
					pass.setVertexBuffer(0, piece.vertices().slice());
					pass.drawIndexed(piece.indexCount(), 1, 0, 0, 0);
				}
			}
		}

		this.drew = true;

		return true;
	}

	/**
	 * Writes every section's corner, relative to the camera, into one slot each.
	 * <p>
	 * <strong>Appended to what this frame has already written rather than written from the
	 * start</strong>, and the reason is that a frame has two halves and one buffer. The pass of the
	 * first half is RECORDED and not executed: it holds slices of this buffer, so slots the second
	 * half wrote over would be the ones the first half draws from, and the two halves do not see the
	 * same sections - a section carrying only water is in one list and not the other, so slot for slot
	 * the two lists are not the same sections at all.
	 * <p>
	 * The camera is the game's own and not DH's copy of it, although DH hands one in: everything else
	 * this engine places is placed against the game's, and a far terrain placed against a second
	 * reading of the same position would stand a fraction of a block away from the near terrain it
	 * meets.
	 *
	 * @return the slot this half's first section landed in, or -1 when there was no room left for it
	 */
	private int writeSections(GpuDevice device, List<DhLods.Section> sections, Minecraft minecraft) {
		int stride = slotBytes(device);
		// Room for BOTH halves and not for the one at hand, which is the whole reason the wanted count
		// is doubled at the head of a frame: the second half of a frame cannot be given a wider buffer,
		// the first half already holding slices of the one that stands. Sized off the half in hand
		// alone, a frame whose two halves each carry sixty sections would fit the first and refuse the
		// second on every frame for as long as the horizon stayed that wide. The peak covers the
		// case the doubling cannot: a water half wider than twice the opaque one is refused once,
		// and the frame after allocates what the refused frame really spent.
		int wanted = Math.max(this.peak,
				this.used == 0 ? 2 * sections.size() : this.used + sections.size());
		if (this.sections == null || this.slots < wanted) {
			// Not while a half of this frame is already drawn from it: closing the buffer that pass
			// holds slices of would pull the ground out from under a recorded draw. The caller says
			// so, and the refusal itself teaches the width: the next frame allocates what this one
			// wanted rather than a guess that already fell short once.
			if (this.used > 0) {
				this.peak = Math.max(this.peak, wanted);
				return -1;
			}

			if (this.sections != null) {
				this.sections.close();
			}

			// In steps rather than to the exact count, so that a player walking into a wider horizon
			// does not reallocate on every frame.
			this.slots = Math.max(64, Mth.smallestEncompassingPowerOfTwo(wanted));
			this.sections = new MappableRingBuffer(() -> "Vitrail far terrain sections",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, this.slots * stride);
		}

		int base = this.used;
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		try (GpuBufferSlice.MappedView view = this.sections.currentBuffer().map(false, true)) {
			ByteBuffer data = view.data();
			for (int index = 0; index < sections.size(); index++) {
				DhLods.Section section = sections.get(index);
				data.position((base + index) * stride);
				Std140Builder.intoBuffer(data).putVec3(
						(float) (section.x() - camera.x),
						(float) (section.y() - camera.y),
						(float) (section.z() - camera.z));
			}
		}

		this.used = base + sections.size();

		return base;
	}

	/** How far apart two slots stand, which is the device's answer and not a number of ours. */
	private static int slotBytes(GpuDevice device) {
		return Mth.roundToward(BLOCK_BYTES,
				device.getDeviceInfo().limits().minUniformOffsetAlignment());
	}

	/**
	 * Makes the far terrain's own depth image, or remakes it after a resize.
	 *
	 * @return whether there is one to draw into
	 */
	private boolean ensureDepth(GpuDevice device, RenderTarget main) {
		if (this.depth != null && this.depthWidth == main.width && this.depthHeight == main.height) {
			return true;
		}

		releaseDepth();
		this.depthWidth = main.width;
		this.depthHeight = main.height;
		if (this.depthWidth <= 0 || this.depthHeight <= 0) {
			return false;
		}

		// Three usages and every one of them is asked for by name somewhere: drawn into as an
		// attachment, sampled by the window takes, and emptied at the head of the frame. The clear is
		// the one that is not obvious: an encoder refuses to clear a depth image that was not also
		// made a copy destination (CommandEncoder.verifyDepthTexture), a clear being a write it
		// performs itself rather than a load the pass does.
		this.depth = device.createTexture(() -> "Vitrail far terrain depth",
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
						| GpuTexture.USAGE_COPY_DST,
				DEPTH_FORMAT, this.depthWidth, this.depthHeight, 1, 1);
		this.depthView = device.createTextureView(this.depth);

		// Emptied at birth and not only at the head of a frame: remade between the two halves of
		// one frame, on a resize, the per frame clear has already run and the water half would
		// otherwise rasterise against whatever the driver left here.
		device.createCommandEncoder().clearDepthTexture(this.depth, 0.0);

		return true;
	}

	/**
	 * Reads the pack for both halves at once, at the first frame the far terrain is drawn.
	 * <p>
	 * Both and not the one being asked for, for the reason every other family reads all of its
	 * pieces: the halves are one frame apart at most, and a reading is an opening and an expansion of
	 * the whole pack.
	 */
	private void read() {
		this.read = true;

		try {
			PackProgram.Distant distant = PackProgram.loadDistant(this.packPath, this.place,
					ELEMENTS.values().stream().map(Element::asked).toList(), this.chosen, this.profile);
			if (distant.programs().isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for the far terrain, so Distant "
						+ "Horizons keeps drawing it with its own shader", this.packPath.getFileName(),
						this.place.isEmpty() ? "its root" : this.place);

				return;
			}

			this.carried = distant.carried();
			for (Element element : ELEMENTS.values()) {
				PackProgram.Loaded one = distant.programs().get(element.element());
				if (one == null) {
					Vitrail.logger().info("{} serves nothing in {} for the {} half of the far terrain",
							this.packPath.getFileName(), this.place.isEmpty() ? "its root" : this.place,
							element.afterDeferred() ? "translucent" : "opaque");

					continue;
				}

				List<ChainPlan.Attachment> writes = writes(element, one);
				if (writes != null) {
					this.programs.put(element.element(), DistantProgram.of(one, element, this.carried,
							this.values, this.load, writes, this.chainTargets, this.targets,
							this.chainRuns));
				}
			}

			// The two halves together or not at all. One landscape drawn by two engines does not
			// compose: whichever went back to DH is composited by DH's apply pass out of an image
			// holding that half alone, with nothing left in its depth to occlude it, so far water
			// would show through the hills in front of it. Rarely reached at all: a pack without
			// one of the two files resolves it through its own fallback tree first.
			if (this.programs.size() == 1) {
				Vitrail.logger().info("The far terrain goes back to Distant Horizons whole: the pack "
						+ "serves one half of it and the two only compose together");
				this.programs.values().forEach(DistantProgram::release);
				this.programs.clear();
			}
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the far terrain programs of "
					+ this.packPath.getFileName() + ", so Distant Horizons keeps drawing it with its "
					+ "own shader", e);
		}
	}

	/**
	 * Where the outputs of one half belong, in draw buffer order, or null when this place cannot
	 * answer for it.
	 * <p>
	 * Empty is not a refusal: a program that declares no draw buffer is answered colortex0, as Iris
	 * answers it. Null is, and there is one reason for it, the one every family here refuses on: a
	 * place whose targets are not the size of the screen, one render pass having one render area.
	 */
	private List<ChainPlan.Attachment> writes(Element element, PackProgram.Loaded loaded) {
		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		Optional<ChainPlan.Pass> geometry = this.plan.geometryOf(servedBy, element.afterDeferred());
		if (geometry.isEmpty()) {
			return List.of();
		}

		ChainPlan.Pass pass = geometry.get();
		if (!pass.size().equals(TargetSize.ofScreen())) {
			Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so they cannot share "
					+ "a pass with the game's own target and Distant Horizons keeps drawing the {} half "
					+ "of the far terrain", servedBy,
					element.afterDeferred() ? "translucent" : "opaque");

			return null;
		}

		return pass.attachments();
	}

	/**
	 * Hands one half back to DH and says why, once per reason and per load.
	 *
	 * @return false always, so that a caller can hand this straight back
	 */
	private boolean refuse(String reason, String why) {
		if (this.refused.add(reason)) {
			Vitrail.logger().warn("The far terrain went back to Distant Horizons' own shader because {}",
					why);
		}

		return false;
	}

	/** The programs once the far terrain has been read, for the decoded dump. Empty until then. */
	Collection<DistantProgram> programs() {
		return this.programs.values();
	}

	/** Rotates the ring buffers. Called once the frame's far terrain draws have been recorded. */
	void rotate() {
		this.drew = false;
		this.used = 0;
		if (this.sections != null) {
			this.sections.rotate();
		}

		this.programs.values().forEach(DistantProgram::rotate);
	}

	void release() {
		this.programs.values().forEach(DistantProgram::release);
		this.programs.clear();
		this.refused.clear();
		this.carried = List.of();
		this.read = false;
		this.drew = false;
		this.broken = false;
		if (this.sections != null) {
			this.sections.close();
			this.sections = null;
			this.slots = 0;
			this.used = 0;
		}

		this.peak = 0;

		releaseDepth();
	}

	private void releaseDepth() {
		if (this.depthView != null) {
			this.depthView.close();
			this.depthView = null;
		}

		if (this.depth != null) {
			this.depth.close();
			this.depth = null;
		}
	}
}
