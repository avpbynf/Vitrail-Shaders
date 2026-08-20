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
 * names every pack of the corpus ships, and two of the four Iris serves
 * ({@code compat/dh/DHCompatInternal.java:67-79}; the one still missing here is its
 * {@code dh_generic}, built at {@code :70-72}, and the backlog carries it).
 * <p>
 * <strong>The same geometry is drawn a second time from the light, with {@code dh_shadow}</strong>,
 * which is the third of the four. Without it a far hill shades itself out of its own depth and lays
 * nothing on the ground in front of it, and the near world lays nothing on the far one either: the
 * map they are both read out of simply has no LOD in it. Iris builds that program the same way it
 * builds the other three ({@code compat/dh/DHCompatInternal.java:80-89}), and the shape of this
 * class comes from where the light's stage stands here: at the tail of the frame, long after DH has
 * handed its geometry over, so the sections are KEPT for it rather than asked for again. The one
 * divergence that follows is written where it bites, on {@link #shadow}.
 * <p>
 * <strong>The depth image is this engine's own, and that is what keeps DH out of the picture
 * entirely.</strong> Nothing is drawn into DH's own colour or depth; DH's own apply pass then finds
 * its depth image exactly as it cleared it and discards every pixel of the screen
 * ({@code assets/distanthorizons/shaders/apply/blaze/frag.fsh} discarding on a depth of nought under
 * a reversed Z), so there is no compositing to switch off and no event to bind. Its two other post
 * passes are held off all the same, for a reason that is cost rather than picture, on
 * {@code dh/DhLods#mute}. What the far terrain leaves here is what {@code render/PackDepth} converts
 * into the pack's window and serves back under {@code dhDepthTex}, in two takes that bracket the
 * water half exactly as the world's own depth is taken around its translucents.
 * <p>
 * <strong>One value belongs to the section rather than to the pass</strong>, which is where DH keeps
 * a section's corner: three unsigned shorts cannot hold a world coordinate, so the vertex carries
 * block coordinates inside the section and the corner arrives beside it. A value that changes
 * between draws of one pass cannot live in the block every draw of that pass reads, and a pass per
 * section is not an answer either: a render pass is a load and a store of every attachment it names.
 * So there is a second block, one aligned slot per section, written before the pass opens and bound
 * as each section comes up. DH does exactly this with its own, one buffer per container
 * ({@code common/render/blaze/BlazeDhTerrainRenderer.java:280}), and Iris sets a uniform per buffer
 * ({@code compat/dh/IrisLodRenderProgram.java:252-253}) because a GL uniform is per program rather
 * than per draw.
 */
public final class DistantDraw {

	/**
	 * One draw of the far terrain: which half of DH's geometry it is, which of the two images it
	 * lands in, and what the pack is asked for to serve it.
	 *
	 * @param element one word for the log and for the shader identifier
	 * @param program the bare name the pack is asked for
	 * @param water   whether this is the half DH keeps its transparent LODs in rather than its
	 *                opaque ones. It is the geometry and nothing about the frame: both images take
	 *                both halves
	 * @param shadow  whether this draw fills the pack's shadow map rather than its picture. The two
	 *                images take the same geometry twice, which is what Iris does without a line of
	 *                its own about it: DH draws its LODs from the head of the game's two chunk
	 *                groups ({@code neoforge/mixins/client/MixinChunkSectionsToRender.java:67-74}),
	 *                and Iris's shadow stage runs those very groups a second time
	 *                ({@code shadows/ShadowRenderer.java:508-511} and {@code :598-601}), so every
	 *                LOD the camera pass drew is offered again from the light and bound to
	 *                {@code dh_shadow} there ({@code compat/dh/LodRendererEvents.java:220-222} and
	 *                {@code :332-334}). Each of those two calls sits inside the caster word that
	 *                governs its own chunk group, and this family is drawn inside them for that
	 *                reason
	 */
	public record Element(String element, String program, boolean water, boolean shadow) {

		/** What the pack has to be read for to serve this half, in terms the translation knows. */
		private PackProgram.GeometryElement asked() {
			// No alpha test: an LOD carries no texture at all, DH's mesh having no texture coordinate
			// this engine reads, so there is no sampled alpha for a threshold to cut. Iris hands its
			// DH keys no cutout either. No coverage mask, for the reason DistantProgram gives.
			return new PackProgram.GeometryElement(this.element, this.program, AlphaTest.OFF,
					VertexInputs.DISTANT, false);
		}

		/**
		 * Whether this draw falls after the deferred stage, which decides the half of every target it
		 * reads and writes.
		 * <p>
		 * The water half of the PICTURE does and nothing else does. DH calls its own two halves from
		 * the head of the game's opaque chunk group and from the head of its translucent one
		 * ({@code neoforge/mixins/client/MixinChunkSectionsToRender.java:67-74}, the water half held
		 * to the second by the switch {@code dh/DhLods} throws), which is where the game's own solid
		 * and translucent chunk passes stand. The light's two halves are drawn in a stage that runs
		 * once the deferreds are long over, and they take the same answer the world's own shadow
		 * halves take, which is no: {@code pack/program/TerrainPass.afterDeferred} is true for the
		 * translucent chunk pass alone.
		 */
		boolean afterDeferred() {
			return this.water && !this.shadow;
		}

		/**
		 * What a pack is told it is drawing. The chunk passes' own answer, which is what the geometry
		 * is: a pack branching on {@code MC_RENDER_STAGE_TERRAIN_SOLID} in a {@code dh_terrain} is
		 * branching on the stage it was written for.
		 * <p>
		 * A shadow half answers the same name as the half it shadows, which is the rule
		 * {@code TerrainPass.stage} already follows and which is what Iris really hands over: its
		 * shadow stage names its opaque chunk group {@code TERRAIN_SOLID} and its translucent one
		 * {@code TERRAIN_TRANSLUCENT} ({@code shadows/ShadowRenderer.java:508-511} and
		 * {@code :598-601}), and DH's LODs are drawn from inside those two calls.
		 */
		RenderStage stage() {
			return this.water ? RenderStage.TERRAIN_TRANSLUCENT : RenderStage.TERRAIN_SOLID;
		}

		/** What the log calls this draw in the middle of a sentence. */
		String half() {
			return (this.water ? "translucent" : "opaque") + (this.shadow ? " shadow" : "");
		}
	}

	/**
	 * The four draws, keyed by the word the log uses. Ordered, so that the lines the load may write
	 * about them come out in the order they are drawn.
	 */
	private static final Map<String, Element> ELEMENTS = new LinkedHashMap<>();

	static {
		ELEMENTS.put("distant", new Element("distant", "dh_terrain", false, false));
		ELEMENTS.put("distant_water", new Element("distant_water", "dh_water", true, false));
		ELEMENTS.put("distant_shadow", new Element("distant_shadow", "dh_shadow", false, true));
		ELEMENTS.put("distant_shadow_water",
				new Element("distant_shadow_water", "dh_shadow", true, true));
	}

	/** The key one half of DH's geometry is drawn under, in each of the two images. */
	private static String key(boolean water, boolean shadow) {
		return (shadow ? "distant_shadow" : "distant") + (water ? "_water" : "");
	}

	/** One vec3 under std140, which is what one slot HOLDS; how far apart slots start is the
	 * device's answer and {@link Corners#slotBytes} alone carries it. */
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

	/**
	 * The section corners of the halves recorded this frame, one aligned slot each.
	 * <p>
	 * <strong>It outlives the pack, and that is the point.</strong> A recorded pass binds
	 * slices of this ring, so destroying it is only safe where nothing holds one, which
	 * {@link Corners#write} already refuses to do while a half of the frame in hand has been
	 * drawn from it. A pack load has no such knowledge: it tears the chain down in the middle
	 * of a frame whose passes are already recorded against these very slices, and a ring
	 * closed there takes the device with it a few frames later. Kept across loads there is
	 * nothing to close, and it costs a few dozen kilobytes: the ring sizes itself to the
	 * widest horizon it has seen, and every slot is written again before it is read.
	 */
	private static final Corners CORNERS = new Corners("Vitrail far terrain sections");

	/**
	 * The same for the light's own two halves, which cannot share the ring above.
	 * <p>
	 * <strong>The turn is what forbids it, and not the frame boundary on its own.</strong> The ring
	 * moves on in {@link #rotate}, which runs before the light's stage, so the light writes a
	 * different buffer from the one the camera's recorded passes hold slices of - and that is
	 * exactly the trouble: the ring would then be one turn ahead of itself, and the NEXT frame's
	 * camera halves would map the buffer the light's own submission is still reading. A ring fences
	 * a buffer where it turns, and one shared ring would be asked to turn twice a frame.
	 * <p>
	 * It outlives the pack for the reason the ring above does.
	 */
	private static final Corners SHADOW_CORNERS = new Corners("Vitrail far terrain shadow sections");

	/** What DH has handed over on the frame being drawn, one list per half of its geometry. */
	private List<DhLods.Section> opaqueSections = List.of();
	private List<DhLods.Section> waterSections = List.of();

	/**
	 * The same two once the frame has closed, which is what the light draws.
	 * <p>
	 * Moved across by {@link #rotate} rather than read where they are written, and that is what
	 * bounds them in time: the shadow stage stands after the close, so a frame where DH handed
	 * nothing over leaves these empty and the light draws no far terrain rather than the last one
	 * it saw.
	 */
	private List<DhLods.Section> shadowOpaque = List.of();
	private List<DhLods.Section> shadowWater = List.of();

	/**
	 * Whether the light's own two halves have stopped for the load, which is latched apart from
	 * {@link #broken}: a failure drawing into the map says nothing about the picture, and the map is
	 * the half of the two that can be dropped without the far terrain changing colour.
	 */
	private boolean shadowBroken;

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

		// Kept before anything can refuse the draw, and kept whether or not the pack ends up drawing
		// it: what the light wants is the geometry DH handed over, and its own stage stands at the
		// far end of the frame from here.
		if (opaque) {
			draw.opaqueSections = sections;
		} else {
			draw.waterSections = sections;
		}

		try {
			return draw.record(device, minecraft, ELEMENTS.get(key(!opaque, false)), sections);
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

	/**
	 * Draws one half of the far terrain into the pack's shadow map, with the pack's own
	 * {@code dh_shadow}.
	 * <p>
	 * Called from the light's own stage, at the two moments the world's own chunk groups are drawn
	 * there, because that is where Iris's are: DH hangs its LOD draws off the head of
	 * {@code ChunkSectionsToRender.renderGroup}
	 * ({@code neoforge/mixins/client/MixinChunkSectionsToRender.java:67-74}) and Iris's shadow stage
	 * calls that very method for its own two groups ({@code shadows/ShadowRenderer.java:508-511} and
	 * {@code :598-601}), so the water half lands after the copy the pack reads as
	 * {@code shadowtex1}, exactly as the world's translucent half does. Both of Iris's calls stand
	 * inside the caster word that governs their own chunk group, and the caller draws this family
	 * inside the same two words for that reason.
	 * <p>
	 * <strong>The sections are the ones DH culled against the CAMERA, and that is a divergence with
	 * a cost.</strong> Under Iris the shadow pass makes DH build its list a second time, against a
	 * frustum of the light's - and against none at all unless something binds one, DH's own default
	 * being {@code NeverCullFrustum} ({@code core/render/RenderBufferHandler.java:98-103,154-164}) -
	 * so its map holds far terrain the camera cannot see. Here there is no second list to be had:
	 * {@code dh/DhLods} stands in for the one interface DH hands its geometry to, and DH walks that
	 * road once a frame, from the camera. What it costs the image is a hill behind the camera laying
	 * no shadow on the ground in front of it.
	 * <p>
	 * Quiet where there is nothing to draw, which is every frame of every session without that mod,
	 * and every pack that ships no {@code dh_shadow}.
	 *
	 * @param water  which half of DH's geometry this is
	 * @param camera where the light's stage measures its geometry from, which has to be the position
	 *               the shadow pair was built around
	 */
	public static void shadow(boolean water, Vec3 camera) {
		DistantDraw draw = PackChain.distant();
		GpuDevice device = RenderSystem.tryGetDevice();
		if (draw == null || draw.shadowBroken || device == null) {
			return;
		}

		try {
			draw.recordShadow(device, ELEMENTS.get(key(water, true)),
					water ? draw.shadowWater : draw.shadowOpaque, camera);
		} catch (RuntimeException e) {
			// Latched on its own, and the picture keeps going: what stops here is the far terrain's
			// entry into the map, so the LOD is lit by what the pack computes from its own depth,
			// which is the whole of what it had before this half existed.
			draw.shadowBroken = true;
			Vitrail.logger().error("Vitrail stopped drawing the far terrain into the shadow map after "
					+ "an error, so nothing of it casts into the map for the rest of this pack", e);
		}
	}

	/** The image the far terrain left its depth in, or null when it drew nothing this frame. */
	GpuTextureView served() {
		return this.drew ? this.depthView : null;
	}

	/**
	 * Records one half of the far terrain into the map, or leaves the map as it stands.
	 * <p>
	 * Neither {@code beginFrame} nor the colour targets are touched, and that is not thrift: this
	 * runs once the chain has closed the frame, so opening either would advance the value store a
	 * second time and empty targets holding the picture the player is looking at.
	 * {@code TerrainDraw.openShadowStage} has already made the map exist and emptied it.
	 */
	private void recordShadow(GpuDevice device, Element element, List<DhLods.Section> sections,
			Vec3 camera) {
		// Never read from here. The reading opens the pack and expands every include of it, which is
		// not something to do inside the light's own stage; the camera's own halves read at the
		// first frame the far terrain is drawn, and a map is only worth filling for a far terrain
		// something is drawing.
		DistantProgram program = this.programs.get(element.element());
		if (program == null || sections.isEmpty()) {
			return;
		}

		// No volume of its own: this half is drawn in the light's, which the shadow catalogue of the
		// six fixed function names already answers, and the projection handed to a pass is the
		// frame's camera one wherever a pack asks for it by its gbuffers name.
		RenderPipeline pipeline = program.prepare(device, null);
		if (pipeline == null) {
			refuseShadow(element, "prepare", "its program could not be prepared. There are two "
					+ "reasons for that and one of them says so on a line of its own above, the "
					+ "program refusing to compile; the other is a map that is not allocated yet, "
					+ "which passes on its own");

			return;
		}

		// The two arguments are the camera pass's and a shadow program reads neither: it names the
		// map's own attachments and the map's own square.
		RenderPassDescriptor descriptor = program.descriptor(null, null);
		if (descriptor == null) {
			refuseShadow(element, "unallocated", "the shadow map had no image yet on some frame, so "
					+ "the pass this half wanted could not be built then. That comes and goes with "
					+ "the frame rather than lasting");

			return;
		}

		int base = SHADOW_CORNERS.write(device, sections, camera);
		if (base < 0) {
			refuseShadow(element, "sections", "the far terrain grew wider than the block holding its "
					+ "section corners between the two halves of one stage, and the wider block "
					+ "cannot replace the one the half already recorded is drawn from. The next frame "
					+ "has it");

			return;
		}

		try (RenderPass pass = device.createCommandEncoder().createRenderPass(descriptor)) {
			pass.setPipeline(pipeline);
			program.bind(pass);

			for (int index = 0; index < sections.size(); index++) {
				pass.setUniform(DistantVertex.SECTION_BLOCK,
						SHADOW_CORNERS.slot(device, base + index));

				for (DhLods.Piece piece : sections.get(index).pieces()) {
					// Asked again here and not only where the section was taken, which is the one
					// thing this half owes to being a frame's width away from its own capture: these
					// are DH's buffers and DH is free to have closed one between its pass and this
					// stage. A section short of a piece is a hole in a shadow; a draw against a
					// closed buffer is the frame.
					if (piece.vertices().isClosed() || piece.indices().isClosed()) {
						continue;
					}

					pass.setIndexBuffer(piece.indices(), IndexType.INT);
					pass.setVertexBuffer(0, piece.vertices().slice());
					pass.drawIndexed(piece.indexCount(), 1, 0, 0, 0);
				}
			}
		}
	}

	/**
	 * Says why one half of the far terrain is missing from the map, once per reason and per load.
	 * <p>
	 * The half is part of the key and not only of the sentence: the two are refused independently,
	 * and one line naming the opaque half would otherwise stand for a water half nobody was told
	 * about.
	 */
	private void refuseShadow(Element element, String reason, String why) {
		if (this.refused.add("shadow:" + reason + ":" + element.element())) {
			Vitrail.logger().warn("The {} half of the far terrain is not drawn into the shadow map "
					+ "because {}", element.half(), why);
		}
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

		// The water half only over an opaque half somebody drew in the same frame, and the somebody
		// has to be the same engine on both. Handed back here, DH draws BOTH halves itself and its
		// images agree with each other; said once, because this is the one hand-back of the class
		// whose reason is the frame's shape rather than a failure.
		if (element.afterDeferred() && !this.drew) {
			if (this.refused.add("water-without-opaque")) {
				Vitrail.logger().info("A frame's far terrain had a water half and no opaque half "
						+ "drawn by this engine, so such frames go back to Distant Horizons whole");
			}

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
			return refuse(element, "prepare:" + element.element(), "the " + element.element()
					+ " program refused to prepare, which it says on its own line above. That is "
					+ "settled for as long as this pack is loaded, so the far terrain keeps Distant "
					+ "Horizons' own shader steadily rather than as a flicker");
		}

		RenderPassDescriptor descriptor = program.descriptor(main.getColorTextureView(),
				this.depthView);
		if (descriptor == null && !program.plain()) {
			return refuse(element, "unallocated:" + element.element(), "one of the pack's colour "
					+ "targets had no image yet on some frame, so the pass this half wanted could not "
					+ "be built then. That comes and goes with the frame rather than lasting");
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

		// The camera is the game's own and not DH's copy of it, although DH hands one in: everything
		// else this engine places is placed against the game's, and a far terrain placed against a
		// second reading of the same position would stand a fraction of a block away from the near
		// terrain it meets.
		int base = CORNERS.write(device, sections, minecraft.gameRenderer.mainCamera().position());
		if (base < 0) {
			return refuse(element, "sections", "the far terrain grew wider than the block holding its "
					+ "section corners between the two halves of one frame, and the wider block cannot "
					+ "replace the one the half already recorded is drawn from. The next frame has it");
		}

		try (RenderPass pass = descriptor == null
				? encoder.createRenderPass(() -> "Vitrail " + element.element(),
						main.getColorTextureView(), Optional.empty(), this.depthView,
						java.util.OptionalDouble.empty())
				: encoder.createRenderPass(descriptor)) {
			pass.setPipeline(pipeline);
			program.bind(pass);

			for (int index = 0; index < sections.size(); index++) {
				pass.setUniform(DistantVertex.SECTION_BLOCK, CORNERS.slot(device, base + index));

				for (DhLods.Piece piece : sections.get(index).pieces()) {
					// The shadow half's guard, asked here for what it says as much as for what
					// it spares. The capture looks at the vertex buffer alone and nothing has
					// ever looked at the index one, so a piece whose index buffer DH closed
					// before it handed the set over is drawn from freed memory two submissions
					// later, which is a device loss with no exception and no line to its name.
					// Which of the two it names is the whole of what this is here to learn.
					if (piece.vertices().isClosed() || piece.indices().isClosed()) {
						String closed = piece.vertices().isClosed() ? "vertex" : "index";
						if (this.refused.add("closed:" + closed + ":" + element.element())) {
							Vitrail.logger().warn("A piece of the {} half of the far terrain reached "
									+ "this frame's draw with its {} buffer closed, and is dropped. "
									+ "Distant Horizons closed it between its own pass and this one",
									element.half(), closed);
						}

						continue;
					}

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
	 * Reads the pack for every half at once, at the first frame the far terrain is drawn.
	 * <p>
	 * All of them and not the one being asked for, for the reason every other family reads all of
	 * its pieces: they are one frame apart at most, and a reading is an opening and an expansion of
	 * the whole pack. It also settles the mesh, which is the union of what they all declare and
	 * cannot be settled one half at a time.
	 */
	private void read() {
		this.read = true;

		try {
			List<Element> asked = ELEMENTS.values().stream().filter(this::wanted).toList();
			PackProgram.Distant distant = PackProgram.loadDistant(this.packPath, this.place,
					asked.stream().map(Element::asked).toList(), this.chosen, this.profile);
			if (distant.programs().isEmpty()) {
				Vitrail.logger().info("{} serves nothing in {} for the far terrain, so Distant "
						+ "Horizons keeps drawing it with its own shader", this.packPath.getFileName(),
						this.place.isEmpty() ? "its root" : this.place);

				return;
			}

			this.carried = distant.carried();
			for (Element element : asked) {
				PackProgram.Loaded one = distant.programs().get(element.element());
				if (one == null) {
					// Said for the picture's halves alone. The light's two resolve one name between
					// them, and that name has no parent in the fallback tree, so a pack without a
					// dh_shadow is the ordinary case rather than a gap: the line further down says it
					// once for the pair.
					if (!element.shadow()) {
						Vitrail.logger().info("{} serves nothing in {} for the {} half of the far "
								+ "terrain", this.packPath.getFileName(),
								this.place.isEmpty() ? "its root" : this.place, element.half());
					}

					continue;
				}

				List<ChainPlan.Attachment> writes = writes(element, one);
				if (writes != null) {
					this.programs.put(element.element(), DistantProgram.of(one, element, this.carried,
							this.values, this.load, writes, this.chainTargets, this.targets,
							this.chainRuns));
				}
			}

			// The two halves of the PICTURE together or not at all. One landscape drawn by two
			// engines does not compose: whichever went back to DH is composited by DH's apply pass
			// out of an image holding that half alone, with nothing left in its depth to occlude it,
			// so far water would show through the hills in front of it. Rarely reached at all: a
			// pack without one of the two files resolves it through its own fallback tree first.
			//
			// And the light's halves go back with them, which is Iris's own shape: every road its
			// events take is behind shouldOverride, so a far terrain handed back whole is handed back
			// from the map as well (compat/dh/LodRendererEvents.java:216-227 and :251-259).
			if (served(false) == 1) {
				Vitrail.logger().info("The far terrain goes back to Distant Horizons whole: the pack "
						+ "serves one half of it and the two only compose together");
				this.programs.values().forEach(DistantProgram::release);
				this.programs.clear();
			} else if (served(true) == 0) {
				sayNothingCastsIntoTheMap();
			}
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().error("Could not prepare the far terrain programs of "
					+ this.packPath.getFileName() + ", so Distant Horizons keeps drawing it with its "
					+ "own shader", e);
		}
	}

	/**
	 * Says why the far terrain casts nothing into the map, in the words of the reason it really is.
	 * <p>
	 * Three roads reach here and they are not the same fact. A session that draws no map at all is
	 * SILENT: {@link #wanted} kept the light's halves out before the pack was ever read, and a line
	 * about a program would send whoever reads it looking through a pack for something no map could
	 * have used. A pack that ships a {@code dh_shadow} and asks for it not to be drawn is quoted on
	 * its own directive rather than reported as shipping nothing, which is what this said before it
	 * was split and which was false of the one pack of the corpus that ships one.
	 */
	private void sayNothingCastsIntoTheMap() {
		if (!TerrainDraw.shadowsAsked()) {
			return;
		}

		if (!this.values.dhShadow()) {
			Vitrail.logger().info("{} asks with dhShadow.enabled that its far terrain stay out of "
					+ "its shadow map, so nothing of it is drawn there and what shades it is what "
					+ "the pack's own programs work out of its depth", this.packPath.getFileName());

			return;
		}

		Vitrail.logger().info("{} serves no dh_shadow in {}, so the far terrain casts nothing into "
				+ "the pack's shadow map and what shades it is what the pack's own programs work "
				+ "out of its depth", this.packPath.getFileName(),
				this.place.isEmpty() ? "its root" : this.place);
	}

	/**
	 * Whether this half is asked of the pack at all.
	 * <p>
	 * The picture's two always are. The light's two are asked for where a map is drawn this session
	 * and the pack has not refused them, which is the pair of questions Iris asks before it builds
	 * its own shadow program ({@code compat/dh/DHCompatInternal.java:80}, over the flag
	 * {@code pipeline/IrisRenderingPipeline.java:408} reads out of the directive). Not asking saves
	 * more than one program: what the mesh carries is the union of what every half declares, so a
	 * shadow half nothing draws would still widen the vertex the camera's halves are drawn from.
	 */
	private boolean wanted(Element element) {
		return !element.shadow() || (TerrainDraw.shadowsAsked() && this.values.dhShadow());
	}

	/** How many halves of one image the pack really served, which is what the load's own lines say. */
	private long served(boolean shadow) {
		return ELEMENTS.values().stream()
				.filter(element -> element.shadow() == shadow)
				.filter(element -> this.programs.containsKey(element.element()))
				.count();
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
		// The light's halves write the map's own colour targets, which no chain plan carries a word
		// about: GeometryProgram builds their attachments out of the program's own draw buffers and
		// the map's, and reads nothing of this list.
		if (element.shadow()) {
			return List.of();
		}

		String servedBy = loaded.path().substring(loaded.path().lastIndexOf('/') + 1);
		Optional<ChainPlan.Pass> geometry = this.plan.geometryOf(servedBy, element.afterDeferred());
		if (geometry.isEmpty()) {
			return List.of();
		}

		ChainPlan.Pass pass = geometry.get();
		if (!pass.size().equals(TargetSize.ofScreen())) {
			Vitrail.logger().warn("{} writes targets the pack asked to be scaled, so they cannot share "
					+ "a pass with the game's own target and Distant Horizons keeps drawing the {} half "
					+ "of the far terrain", servedBy, element.half());

			return null;
		}

		return pass.attachments();
	}

	/**
	 * Hands one half back to DH and says why, once per reason and per load - or DROPS it for the
	 * frame instead, and the difference is which half and when.
	 * <p>
	 * A water half that cannot be drawn over an opaque half this engine DID draw is claimed and
	 * skipped rather than handed back: DH would composite it out of an image holding that water
	 * alone, with nothing left in its depth to occlude it, and far water would show through the
	 * hills in front of it. A frame without far water costs less than one with water through the
	 * hills, and every reason that reaches this is transient by its own description.
	 *
	 * @return whether the caller should claim the half all the same, which is the drop; false hands
	 *         it back to DH
	 */
	private boolean refuse(Element element, String reason, String why) {
		boolean drop = element.afterDeferred() && this.drew;
		if (this.refused.add((drop ? "dropped:" : "") + reason)) {
			Vitrail.logger().warn("The {} half of the far terrain {} because {}", element.half(),
					drop ? "is dropped on such frames, the opaque half being this engine's already"
							: "went back to Distant Horizons' own shader",
					why);
		}

		return drop;
	}

	/** The programs once the far terrain has been read, for the decoded dump. Empty until then. */
	Collection<DistantProgram> programs() {
		return this.programs.values();
	}

	/**
	 * Rotates the ring buffers. Called once the frame's far terrain draws have been recorded.
	 * <p>
	 * <strong>And hands what DH gave this frame to the light</strong>, which is the one thing here
	 * that is not a turn of a buffer. The light's stage runs after this call, so what it draws is
	 * what the frame now closing captured; a frame where DH handed nothing over leaves the light
	 * with nothing rather than with the last far terrain it saw, which is what the two empty lists
	 * below buy.
	 */
	void rotate() {
		this.drew = false;
		this.shadowOpaque = this.opaqueSections;
		this.shadowWater = this.waterSections;
		this.opaqueSections = List.of();
		this.waterSections = List.of();
		CORNERS.rotate();
		SHADOW_CORNERS.rotate();
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
		this.shadowBroken = false;
		this.opaqueSections = List.of();
		this.waterSections = List.of();
		this.shadowOpaque = List.of();
		this.shadowWater = List.of();
		// NOT closed, which is the whole of issue 111: a recorded pass holds slices of these
		// two rings, and a load tears the chain down in the middle of a frame that has
		// already bound them. What is dropped here is what this load wrote into them.
		CORNERS.forget();
		SHADOW_CORNERS.forget();

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

	/**
	 * One ring of section corners, and the slot bookkeeping the halves that share it need.
	 * <p>
	 * A ring rather than one buffer, for the reason every other per frame block of this engine is
	 * one: the backend keeps two submissions in flight, and a buffer written again while a frame
	 * that has not finished is still reading it is the far terrain of two frames at once. It turns
	 * where {@link DistantDraw#rotate} turns.
	 * <p>
	 * <strong>One ring cannot serve halves drawn on either side of that turn</strong>, which is
	 * why there is more than one of these. The camera's two halves are recorded before the frame
	 * closes and the light's two after it, so a single ring would have the light writing over the
	 * very slots the camera's recorded passes hold slices of.
	 */
	private static final class Corners {

		private final String label;

		private MappableRingBuffer buffer;
		private int slots;

		/** How many slots this frame has already written, which is where the next half starts. */
		private int used;

		/**
		 * The most slots any one frame has spent, both halves together, so that a frame refused for
		 * width is refused once: the guess below doubles the FIRST half, and a second half wider
		 * than that would otherwise outgrow the buffer on every frame alike rather than only the
		 * first.
		 */
		private int peak;

		Corners(String label) {
			this.label = label;
		}

		/**
		 * Writes every section's corner, relative to the camera, into one slot each.
		 * <p>
		 * <strong>Appended to what this frame has already written rather than written from the
		 * start</strong>, and the reason is that a frame has two halves and one buffer. The pass of
		 * the first half is RECORDED and not executed: it holds slices of this buffer, so slots the
		 * second half wrote over would be the ones the first half draws from, and the two halves do
		 * not see the same sections - a section carrying only water is in one list and not the
		 * other, so slot for slot the two lists are not the same sections at all.
		 *
		 * @param camera where the pass this belongs to measures its geometry from, which is the
		 *               game's own camera and not DH's copy of it: everything else this engine
		 *               places is placed against the game's, and a far terrain placed against a
		 *               second reading of the same position would stand a fraction of a block away
		 *               from the near terrain it meets
		 * @return the slot this half's first section landed in, or -1 when there was no room left
		 *         for it
		 */
		int write(GpuDevice device, List<DhLods.Section> sections, Vec3 camera) {
			int stride = slotBytes(device);
			// Room for BOTH halves and not for the one at hand, which is the whole reason the wanted
			// count is doubled at the head of a frame: the second half of a frame cannot be given a
			// wider buffer, the first half already holding slices of the one that stands. Sized off
			// the half in hand alone, a frame whose two halves each carry sixty sections would fit
			// the first and refuse the second on every frame for as long as the horizon stayed that
			// wide. The peak covers the case the doubling cannot: a second half wider than twice the
			// first is refused once, and the frame after allocates what the refused frame really
			// spent.
			int wanted = Math.max(this.peak,
					this.used == 0 ? 2 * sections.size() : this.used + sections.size());
			if (this.buffer == null || this.slots < wanted) {
				// Not while a half of this frame is already drawn from it: closing the buffer that
				// pass holds slices of would pull the ground out from under a recorded draw. The
				// caller says so, and the refusal itself teaches the width: the next frame allocates
				// what this one wanted rather than a guess that already fell short once.
				if (this.used > 0) {
					this.peak = Math.max(this.peak, wanted);

					return -1;
				}

				if (this.buffer != null) {
					this.buffer.close();
				}

				// In steps rather than to the exact count, so that a player walking into a wider
				// horizon does not reallocate on every frame.
				this.slots = Math.max(64, Mth.smallestEncompassingPowerOfTwo(wanted));
				this.buffer = new MappableRingBuffer(() -> this.label,
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, this.slots * stride);
			}

			int base = this.used;
			try (GpuBufferSlice.MappedView view = this.buffer.currentBuffer().map(false, true)) {
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

		/** The slot one section was written into, as the pass binds it. */
		GpuBufferSlice slot(GpuDevice device, int index) {
			return this.buffer.currentBuffer().slice((long) index * slotBytes(device), BLOCK_BYTES);
		}

		/** How far apart two slots stand, which is the device's answer and not a number of ours. */
		private static int slotBytes(GpuDevice device) {
			return Mth.roundToward(BLOCK_BYTES,
					device.getDeviceInfo().limits().minUniformOffsetAlignment());
		}

		void rotate() {
			this.used = 0;
			if (this.buffer != null) {
				this.buffer.rotate();
			}
		}

		/**
		 * Drops what a load wrote here, and destroys nothing: see the two fields this
		 * stands behind for why a ring is never closed on a reload.
		 */
		void forget() {
			this.used = 0;
			this.peak = 0;
		}
	}
}
