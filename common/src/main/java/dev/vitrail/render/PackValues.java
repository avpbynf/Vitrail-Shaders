package dev.vitrail.render;

import dev.vitrail.pack.id.BlockIds;
import dev.vitrail.pack.id.NameIds;
import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.program.RenderStage;
import dev.vitrail.pack.source.OpenedPack;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.source.ShadowCasters;
import dev.vitrail.pack.source.ShadowCullState;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.pack.texture.BufferObject;
import dev.vitrail.pack.texture.CustomStorage;
import dev.vitrail.pack.texture.ImageInformation;
import dev.vitrail.uniform.expr.CustomUniforms;
import dev.vitrail.uniform.NoiseTexture;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformGaps;
import dev.vitrail.uniform.values.CelestialValues;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4fc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * Everything one pack is answered with, assembled once when the pack is read.
 * <p>
 * It is a separate object rather than a part of the pass on purpose. Three pieces have to agree
 * and none of them belongs to a pass: the pack's own directives, which set how fast the ground
 * dries and how far the shadow map reaches; the uniforms the pack declares as expressions, which
 * layer over the engine's table and have to be evaluated once a frame however many passes read
 * them; and the frame state itself, which every pass of a frame has to be handed identically or
 * two of them reproject against different cameras. A caller wires four lines and gets all three:
 * read it when the pack is read, hand {@link #catalog()} to each program's block, call
 * {@link #advance()} once at the top of a frame, and write with {@link #world()}.
 * <p>
 * Reading the pack a second time here is deliberate. The alternative is to carry the directives
 * and the properties out of the translation, which couples what a program is to what a pack is and
 * makes the translation harder to run on its own, and this costs half of one load rather than
 * anything per frame.
 */
public final class PackValues {

	/** How many blocks a chunk is wide, which is the whole of the conversion between the two units. */
	private static final int CHUNK_BLOCKS = 16;

	private final FrameState state = new FrameState();

	/** Everything the pack declared, live or not, which is what tells a gap from a mistake. */
	private final Set<String> declared = new LinkedHashSet<>();

	private final List<String> problems = new ArrayList<>();

	private CustomUniforms customs;
	private ShaderProperties.SkyElements skyElements = new ShaderProperties.SkyElements(true, true,
			true, true, ShaderProperties.CloudSetting.DEFAULT);
	private ShaderProperties.Weather weather = new ShaderProperties.Weather(true, true);
	private boolean rainDepth;

	/**
	 * Which of the world's families this pack draws into its shadow map, everything but the player
	 * alone until it says otherwise. The defaults are Iris's and {@code ShaderProperties} carries why
	 * the player one is not the flag it looks like.
	 */
	private ShadowCasters shadowCasters = ShadowCasters.DEFAULT;

	/**
	 * Whether this pack wants Distant Horizons' far terrain drawn into its shadow map, on until it
	 * says otherwise. The default is Iris's and is the opposite way round from the six words above,
	 * which {@code ShaderProperties.dhShadow} says in full.
	 */
	private boolean dhShadow = true;

	/** Which shape this pack asked the light to measure a section against. */
	private ShadowCullState shadowCull = ShadowCullState.DEFAULT;

	private boolean separateAo;
	private Optional<String> particleOrdering = Optional.empty();
	private NoiseTexture.Image noiseImage;
	private PackImages packImages = PackImages.none();
	private ImageInformation.Reading storageImages = ImageInformation.Reading.empty();
	private BufferObject.Reading bufferObjects = BufferObject.Reading.empty();
	private UniformCatalog catalog = UniformCatalog.engine();
	private UniformCatalog geometry = UniformCatalog.geometry();
	private UniformCatalog shadowGeometry = UniformCatalog.shadowGeometry();

	private PackValues() {
	}

	/**
	 * Reads one pack's directives and its own uniforms.
	 * <p>
	 * The machine has to have been installed before this pack was opened. The biome symbols decide
	 * which branch of {@code shaders.properties} is live and which branch of the GLSL compiles, so
	 * they have to be in the table before either is read, and {@code SettingSet} copies that table
	 * at {@code OpenedPack.open}. Installing here would be too late for the expander, and would
	 * point the translator at a newer table than the one the expander walked.
	 *
	 * @param pack      the pack, already opened and already read for its settings. Handed in rather
	 *                  than opened here because the load reads it half a dozen times over, and
	 *                  every one of those readings walked the whole archive to rebuild the same
	 *                  index of the same settings
	 * @param dimension which set of programs the directives are folded from, {@code world0} for the
	 *                  overworld
	 */
	public static PackValues read(OpenedPack pack, String dimension) throws IOException {
		PackValues values = new PackValues();

		ShaderPackSource source = pack.source();
		OptionIndex options = pack.options();
		ShaderProperties properties = pack.properties();
		SettingSet settings = pack.settings();

		values.state.directives(PackDirectives.read(source, settings, dimension));
		values.state.endFlashShadows(properties.endFlashShadows());
		values.skyElements = properties.skyElements(settings.globalDefines(options));
		values.weather = properties.weather(settings.globalDefines(options));
		values.rainDepth = properties.rainDepth(settings.globalDefines(options));
		values.shadowCasters = properties.shadowCasters(settings.globalDefines(options));
		values.dhShadow = properties.dhShadow(settings.globalDefines(options));
		values.shadowCull = properties.shadowCull(settings.globalDefines(options));
		values.separateAo = properties.separateAo(settings.globalDefines(options));
		values.particleOrdering = properties.particleOrdering(settings.globalDefines(options));
		values.declare(properties, settings.globalDefines(options));
		values.readNoise(properties, source, settings.globalDefines(options));
		values.packImages =
				PackImages.read(properties, settings.globalDefines(options), source);
		values.storageImages = properties.imageDirectives(settings.globalDefines(options));
		values.bufferObjects = properties.bufferObjects(settings.globalDefines(options));
		CustomStorage.install(values.bufferObjects);

		// Read against the same settings as everything above, which is not a formality: BSL wraps
		// all its declarations in one conditional and keeps a fifth of them under the #else, so a
		// reading with an empty table measures a different pack.
		BlockStateIds.install(BlockIds.read(source, settings.globalDefines(options)));
		PackNameIds.install(
				NameIds.read(source, settings.globalDefines(options), NameIds.Kind.ENTITY),
				NameIds.read(source, settings.globalDefines(options), NameIds.Kind.ITEM));

		return values;
	}

	/**
	 * The pack's own noise image, decoded, or null when the pack declares none or it could not be
	 * read; either way the generated field stands in. Null rather than an empty image, because
	 * the two answers are allocated at different sizes.
	 */
	public NoiseTexture.Image noiseImage() {
		return this.noiseImage;
	}

	/** The textures the pack ships as files of its own, decoded and waiting to be uploaded. */
	PackImages packImages() {
		return this.packImages;
	}

	/**
	 * The storage images the pack declared, not yet allocated. Empty until custom images are
	 * served; the notes still name them so a missing volume is a missing pass rather than a
	 * missing file.
	 */
	ImageInformation.Reading storageImages() {
		return this.storageImages;
	}

	/** The engine's table with the pack's own uniforms layered over it. */
	public UniformCatalog catalog() {
		return this.catalog;
	}

	/**
	 * The same, for a pass drawn over the world: six fixed function names answer the gbuffer pair
	 * instead of the stand ins a full screen quad needs.
	 * <p>
	 * The pack's own uniforms are layered on top of the geometry table and not on top of the answer
	 * {@link #catalog()} gives, so a pack that declares an expression over {@code gl_ModelViewMatrix}
	 * reads the world's matrix here and the quad's there, which is what each pass was written for.
	 */
	public UniformCatalog geometryCatalog() {
		return this.geometry;
	}

	/**
	 * The same again for a pass drawn from the light. Its own layer for the same reason the other two
	 * have one: a pack's expression over {@code gl_ModelViewMatrix} has to read the matrix the pass it
	 * belongs to was drawn with, and there is no frame in which one of the three is the right answer
	 * for all of them.
	 */
	public UniformCatalog shadowGeometryCatalog() {
		return this.shadowGeometry;
	}

	/**
	 * Which depth convention the target the next block is written for carries. A property of where a
	 * pass draws rather than of the frame, so it is set by the pass, before it writes, and every
	 * frame: the shadow map is ours and stores the forward window, the game's targets are reversed.
	 */
	public void convention(Vector4fc convention) {
		this.state.convention(convention);
	}

	/**
	 * Which model view the next block is written for: the pass's own, or null for the frame's. Set
	 * beside the convention and answering the same kind of question, "where does this pass draw".
	 * <p>
	 * Null for the terrain, and set by the two families the game hands a matrix of its own to. The
	 * sky is the loud one: the game puts the sun, the moon and the stars where they belong by
	 * pushing a rotation onto its own stack, so a sky pass hands that matrix in here; see
	 * {@link dev.vitrail.uniform.ViewSource#passModelView} for what answering it with the camera's
	 * would do. The hand is the quiet one: it is drawn under an identity model view, the whole of
	 * its transform sitting in the projection, so the frame's camera would be the one wrong answer.
	 * <p>
	 * The rest of the entity door hands in nothing, and that is worth a line because it used to: what
	 * varies with the DRAW there, the depth nudge of a render type included, is read out of the
	 * game's own per draw block instead, {@code LegacyGlsl.readsDrawModelView} saying which passes
	 * and why.
	 * <p>
	 * The bob comes with it rather than beside it, and the hand is again the one family that hands
	 * one in: it is drawn under a projection this engine builds, and built with the walk bob and the
	 * damage tilt alone. See {@code ViewMatrices.passBob}.
	 *
	 * @param bob the left factor this pass's geometry was really placed by, or null for the frame's
	 */
	public void modelView(Matrix4fc matrix, Matrix4fc bob) {
		this.state.passModelView(matrix, bob);
	}

	/**
	 * Which projection the next block is written for: the pass's own, or null for the frame's. Set
	 * beside the model view and answering the same kind of question.
	 * <p>
	 * The hand is the only family that sets one, and it is a volume of its own rather than the
	 * frame's moved: a head-up field of view and a clip depth squeezed to an eighth. See
	 * {@link dev.vitrail.uniform.ViewSource#passProjection} for what answering it with the frame's
	 * would do.
	 */
	public void projection(Matrix4fc matrix) {
		this.state.passProjection(matrix);
	}

	/**
	 * The colour the next block is written for, or null for white. The game modulates a whole draw
	 * by one, and for the sky that is where the colour of the disc is: see
	 * {@link dev.vitrail.uniform.ViewSource#passColour}.
	 */
	public void passColour(Vector4fc colour) {
		this.state.passColour(colour);
	}

	/**
	 * What the next block is written for, which a pack reads as {@code renderStage} and branches on
	 * with {@code MC_RENDER_STAGE_*}. Set beside the convention and answering the same kind of
	 * question, and said by every writer rather than inherited: this one is in the table a full
	 * screen pass shares with a geometry pass, so a value left standing after the sky would be read
	 * by the whole of the chain.
	 */
	public void renderStage(RenderStage stage) {
		this.state.renderStage(stage);
	}

	/**
	 * The drawn shadow pair multiplied through, which is the matrix that culls the world for the
	 * light: the same pair the map is about to be drawn with, not the published one, which is a
	 * frame older. The projection is the legacy volume, and that is the convention JOML's plane
	 * extraction reads, so the product goes out as it stands.
	 */
	public Matrix4f shadowFrustum(Matrix4f dest) {
		ViewMatrices view = this.state.view();

		return dest.set(view.drawnShadowProjection()).mul(view.drawnShadowModelView());
	}

	/**
	 * What the light's walk needs to choose a shape, all of it read out of the one frame.
	 * <p>
	 * <strong>The camera's volume is the PUBLISHED projection and not the drawn one, and that is the
	 * conversion this whole step turns on.</strong> The game rasterises with a reversed Z over zero
	 * to one; the plane extraction the frustum performs is Iris's, written against the OpenGL volume;
	 * and {@code ViewMatrices.gbufferProjection} is the frame's matrix already put into that volume by
	 * {@link dev.vitrail.uniform.ClipSpace#toLegacyDepth} at {@code ViewMatrices:205}. So Iris's
	 * lines hold as they stand, and it is the DRAWN matrix that would lose the far plane outright;
	 * the arithmetic and the second way of getting it wrong are worked through on
	 * {@code dev.vitrail.sodium.ShadowCullFrustum}.
	 * <p>
	 * The frame's own volume and not the distant one, which is Iris's condition and not the absence
	 * of it: it reaches for {@code DHCompat.getProjection()} only under
	 * {@code shouldRenderDH && DHCompat.hasRenderingEnabled()}
	 * ({@code shadows/ShadowRenderer.java:366}), and its left half is the pack asking for the far
	 * terrain in its SHADOW map ({@code :150}, the {@code dhShadow} directive). Nothing puts the far
	 * terrain into this engine's shadow map at all, so that half can only be answered false here and
	 * the branch Iris takes is the frame's own projection. Widening the volume to Distant Horizons'
	 * would walk further out for casters that have nowhere to be drawn.
	 *
	 * <strong>The two distances are settled here and not at the frustum</strong>, because two of the
	 * four states step outside the arbitration {@link #shadowRenderDistance} performs and the
	 * arbitration has one home. {@link ShadowCullState#DISTANCE} reads the pack's own product and
	 * never the player's setting, Iris asking for {@code halfPlaneLength * renderMultiplier} on its
	 * own at {@code shadows/ShadowRenderer.java:303} and turning the walk loose when that is not
	 * positive or reaches past the loaded world ({@code :317-322}). The safe zone steps outside it
	 * the other way: it forces a multiplier the pack never declared to one BEFORE the branch that
	 * would have read the player's setting ({@code :330-331}), so the player's number cannot reach
	 * it either, and its two boxes are the pack's own throughout.
	 *
	 * @param userChunks           the player's own setting in chunks, for the states that read it
	 * @param renderDistanceChunks the world that is loaded, which is what every bound is capped
	 *                             against
	 * @param light                scratch for the light vector, written and carried into the plan
	 * @param camera               scratch for the camera's volume, likewise
	 * @param voxelised            whether the shadow program voxelises, already read off that
	 *                             program rather than guessed from a {@code .gsh} being bound
	 */
	public ShadowCullPlan shadowCullPlan(int userChunks, int renderDistanceChunks, Vector3f light,
			Matrix4f camera, boolean voxelised) {
		ViewMatrices view = this.state.view();
		camera.set(view.gbufferProjection()).mul(view.gbufferModelView());

		PackDirectives directives = this.state.directives();
		float multiplier = directives.shadowDistanceRenderMul();
		float bound;
		float safeZone = -1.0F;

		switch (this.shadowCull) {
			case DISTANCE -> {
				float distance = directives.shadowDistance() * multiplier;
				bound = distance <= 0.0F || distance > renderDistanceChunks * (float) CHUNK_BLOCKS
						? -1.0F : distance;
			}
			case SAFE_ZONE -> {
				float effective = multiplier < 0.0F ? 1.0F : multiplier;
				safeZone = directives.voxelDistance() * effective;
				bound = directives.shadowDistance() * effective;
			}
			default -> bound = shadowRenderDistance(userChunks, renderDistanceChunks);
		}

		return new ShadowCullPlan(this.shadowCull, voxelised,
				CelestialValues.shadowLightVector(this.state, light), camera, bound, safeZone);
	}

	/**
	 * The same pair handed over unmultiplied, for whoever needs the two halves apart rather than the
	 * matrix that culls. The drawn pair again and for the same reason: the published one is a frame
	 * older than the map about to be drawn.
	 */
	public void drawnShadowPair(Matrix4f modelView, Matrix4f projection) {
		ViewMatrices view = this.state.view();
		modelView.set(view.drawnShadowModelView());
		projection.set(view.drawnShadowProjection());
	}

	/** How big a noise image the pack asked for, its own directive, 256 unless it says otherwise. */
	public int noiseResolution() {
		return Math.round(this.state.noiseTextureResolution());
	}

	/** How wide a shadow map the pack asked for, its own directive, 1024 unless it says otherwise. */
	public int shadowResolution() {
		return this.state.directives().shadowMapResolution();
	}

	/**
	 * How far from the camera the light still gathers the world, IN BLOCKS, or minus one where
	 * nothing bounds it beyond the light's own frustum.
	 * <p>
	 * <strong>Zero is a distance and not the absence of one</strong>, which is why the two are told
	 * apart by a sentinel rather than by a sign: a player who drags the setting to the bottom is
	 * asking for a shadow map with nothing in it, and Iris gathers nothing there too, its box culler
	 * being built at zero like any other value ({@code shadows/ShadowRenderer.java:354}).
	 * <p>
	 * <strong>This is where the two units meet, and they are not the same unit.</strong> The pack
	 * declares a half plane in BLOCKS, {@code shadowDistance}, and multiplies it by
	 * {@code shadowDistanceRenderMul} to say how far the walk goes. The player's setting is in
	 * CHUNKS, because that is the unit the game's own render distance is offered in and the unit
	 * Iris offers this one in ({@code gui/option/IrisVideoSettings.java:50}, a range of 0 to 32).
	 * The conversion is one multiplication and it is done here and nowhere else:
	 * <strong>blocks = chunks x 16</strong>, which is Iris's own
	 * {@code IrisVideoSettings.shadowDistance * 16} at {@code shadows/ShadowRenderer.java:337}.
	 * <p>
	 * Who wins is decided exactly as Iris decides it:
	 * <ul>
	 * <li>the pack, where it declared a multiplier it means
	 *     ({@link PackDirectives#forcesShadowRenderDistance()}). The distance is then its own half
	 *     plane times its own multiplier, and the player's setting is not consulted at all;</li>
	 * <li>the player otherwise, which covers both the pack that never wrote the line and the pack
	 *     that wrote a negative one. Iris takes the same branch for both, on the sign of the
	 *     multiplier alone ({@code shadows/ShadowRenderer.java:336});</li>
	 * <li>nobody, where whoever won asks for at least as far as the world is loaded. Iris drops the
	 *     bound entirely there ({@code shadows/ShadowRenderer.java:341-345}), and so does this: a
	 *     box that cannot cut anything is a test paid for on every section for nothing. The setting
	 *     starts at 32 chunks, the largest render distance the game offers, so a player who never
	 *     touches it lands here and the light is walked against its own frustum alone.</li>
	 * </ul>
	 * <p>
	 * <strong>A default slider is NOT the same thing as no bound, and most of the corpus proves
	 * it.</strong> Seven of the eight packs tested declare {@code shadowDistanceRenderMul}, six of
	 * them at one, so they take the first branch and are bounded at their own half plane whatever
	 * the slider says. That is the pack getting the distance it asked for and was tuned against; it
	 * is parity, and it is a walk that stops earlier than the slider on its own would.
	 *
	 * @param userChunks           what the player asked for, in chunks
	 * @param renderDistanceChunks the game's effective render distance, in chunks, which is as far
	 *                             as there is a world to gather
	 */
	public float shadowRenderDistance(int userChunks, int renderDistanceChunks) {
		return distanceFor(this.state.directives().shadowDistanceRenderMul(), userChunks,
				renderDistanceChunks);
	}

	/**
	 * One multiplier turned into a distance in blocks, which is the whole of Iris's
	 * {@code createShadowFrustum} ({@code shadows/ShadowRenderer.java:333-345}) and is asked twice:
	 * once for the world and once, under a different multiplier, for the casters that move.
	 * <p>
	 * <strong>The sign is the switch and the value is only read when it is not negative.</strong>
	 * Iris branches on nothing else ({@code :336}), which is what makes a pack that never declared
	 * the directive and a pack that declared a negative one the same case: the default is minus one.
	 */
	private float distanceFor(float multiplier, int userChunks, int renderDistanceChunks) {
		float blocks = multiplier < 0.0F
				? userChunks * (float) CHUNK_BLOCKS
				: this.state.directives().shadowDistance() * multiplier;

		return blocks >= renderDistanceChunks * (float) CHUNK_BLOCKS ? -1.0F : blocks;
	}

	/**
	 * How far the pack itself insists the light gathers, in CHUNKS and rounded up, or empty where it
	 * leaves the distance to the player. What a settings screen greys its slider out on.
	 * <p>
	 * Rounded up rather than truncated, and by Iris's own arithmetic
	 * ({@code pipeline/IrisRenderingPipeline.java:287-289}): a pack asking for 161 blocks is asking
	 * for more than ten chunks, and a slider that reads ten would be reading a lie.
	 * <p>
	 * <strong>One thing is answered differently from Iris and it is the screen alone.</strong> Iris
	 * fills this whenever the line was WRITTEN, including with a negative value, and hands out minus
	 * one in that case ({@code pipeline/IrisRenderingPipeline.java:292}); its slider then greys out
	 * while {@code ShadowRenderer} goes on reading the player's number, so the screen says the pack
	 * decides and the image says otherwise. Here the answer is empty in that case, which is what the
	 * image does. Nothing the pack can read changes either way.
	 */
	public OptionalInt forcedShadowRenderDistanceChunks() {
		PackDirectives directives = this.state.directives();
		if (!directives.forcesShadowRenderDistance()) {
			return OptionalInt.empty();
		}

		int blocks = (int) (directives.shadowDistance() * directives.shadowDistanceRenderMul());

		return OptionalInt.of((blocks + CHUNK_BLOCKS - 1) / CHUNK_BLOCKS);
	}

	/**
	 * How far from the camera a caster that MOVES may still be and reach the map, in blocks, or
	 * minus one where nothing bounds it beyond the light's own frustum.
	 * <p>
	 * <strong>The two multipliers are multiplied together, they are not the smaller of the two.</strong>
	 * Iris builds this frustum by handing {@code createShadowFrustum} the product
	 * {@code renderDistanceMultiplier * entityShadowDistanceMultiplier}
	 * ({@code shadows/ShadowRenderer.java:540}), so a pack asking for half the world and half again
	 * for its mobs gets a quarter, not a half.
	 * <p>
	 * <strong>And the product carries the sign, which is what makes the pack's entity multiplier
	 * DISAPPEAR whenever the player governs.</strong> The world multiplier is minus one then, so the
	 * product is negative whatever the entity one says, and the branch taken is the player's own
	 * distance: the casters that move are bounded exactly as the world is. That is not a rounding of
	 * Iris, it is Iris, and it falls out of the same line.
	 * <p>
	 * One multiplier and one only is short-circuited before any of that: one, and anything negative,
	 * mean the pack asks nothing extra of its moving casters, and the answer is then the world's own
	 * bound rather than a second computation ({@code shadows/ShadowRenderer.java:536-537}).
	 */
	public float entityShadowDistance(int userChunks, int renderDistanceChunks) {
		float multiplier = this.state.directives().entityShadowDistanceMul();
		if (multiplier == 1.0F || multiplier < 0.0F) {
			return shadowRenderDistance(userChunks, renderDistanceChunks);
		}

		return distanceFor(this.state.directives().shadowDistanceRenderMul() * multiplier,
				userChunks, renderDistanceChunks);
	}

	/**
	 * What the pack asks of each {@code shadowcolor} the light may draw into: its format, and what
	 * emptying it means. One entry a buffer, in order, and a buffer the pack said nothing about
	 * carries Iris's own defaults rather than being left out.
	 */
	public List<PackDirectives.ShadowColour> shadowColours() {
		return IntStream.range(0, ShadowTargets.COLOURS)
				.mapToObj(index -> this.state.directives().shadowColour(index))
				.toList();
	}

	/**
	 * How far the pack tilts the path the sun and the moon travel, in degrees, nought unless the
	 * pack says otherwise. BSL asks for minus forty.
	 * <p>
	 * It is not only the light's business, and that is the whole point of the directive: the same
	 * angle has to move the bodies the player sees, or the pack lights a world from one place while
	 * the game draws its sun in another. The shadow matrices already turn by it on the X axis, in
	 * the light's own space; the bodies turn by it on Z, in the celestial space the game draws them
	 * in, which is what Iris does at the same point of the same method.
	 */
	public float sunPathRotation() {
		return this.state.directives().sunPathRotation();
	}

	/**
	 * Which pieces of the game's own sky this pack still wants drawn, all four unless it says
	 * otherwise. Read once with the settings the rest of the pack was read with, because two packs
	 * of the corpus write these lines under a conditional on one of their own.
	 */
	public ShaderProperties.SkyElements skyElements() {
		return this.skyElements;
	}

	/**
	 * What this pack still wants of the game's own weather, both halves unless it says otherwise.
	 * Read on the same walk and with the same settings as the sky's four words, being the same family
	 * of directive.
	 */
	public ShaderProperties.Weather weather() {
		return this.weather;
	}

	/** Whether this pack asked for the rain and the snow to write the world's depth. */
	public boolean rainDepth() {
		return this.rainDepth;
	}

	/**
	 * Which of the world's families this pack wants drawn into its shadow map, read on the same walk
	 * and with the same settings as the sky's four words.
	 */
	public ShadowCasters shadowCasters() {
		return this.shadowCasters;
	}

	/**
	 * Whether this pack wants the far terrain drawn into its shadow map, read on the same walk and
	 * with the same settings as the six words above.
	 * <p>
	 * It is not one of them and it is not read as one: a pack that ships no {@code dh_shadow} at all
	 * is answered by the fallback tree rather than by this, {@code dh_shadow} having no parent to
	 * reach, so what this decides is the one case where a pack ships the program and asks for it not
	 * to be drawn.
	 */
	public boolean dhShadow() {
		return this.dhShadow;
	}

	/**
	 * Whether this pack asked for the terrain's ambient occlusion to be kept out of the vertex
	 * colour and put in its alpha instead. What answers it is the TRANSLATION and not the mesh: the
	 * chunk vertex carries both colours whatever any pack asked, and this only decides which of the
	 * two a terrain vertex stage is written to read, {@code VertexInputs.TERRAIN_SEPARATE_AO}. So
	 * nothing is built again when it moves - a pack that moves it is a pack whose programs are read
	 * again anyway.
	 */
	public boolean separateAo() {
		return this.separateAo;
	}

	/**
	 * Where this pack asked for its particles to be drawn about the deferred stage, and empty where
	 * it did not ask. No default is invented, and {@code ShaderProperties.particleOrdering} says why.
	 */
	public Optional<String> particleOrdering() {
		return this.particleOrdering;
	}

	/** What a block is written from. The same object every frame, refilled by {@link #advance()}. */
	public WorldState world() {
		return this.state;
	}

	/**
	 * Moves the frame on. Called once, at a named point, before the first block of the frame is
	 * written and never per program: the previous frame's matrices shift here, and a
	 * {@code smooth()} in a pack's expression integrates here, so calling it twice makes every
	 * smoothed value in the pack fade at twice the speed with nothing on screen to say so.
	 */
	public void advance() {
		this.state.advance();
		this.customs.update(this.state, this.state.frameTime());

		// Normally empty, and this is the only place able to say anything: an expression that
		// throws is dropped where it is evaluated, and that side of the line has no logger.
		this.customs.drainProblems().forEach(problem ->
				Vitrail.logger().warn("The pack's own {}", problem));
	}

	/** Drops what a client that has left a world was still holding, see {@link FrameState#leaveWorld}. */
	public void leaveWorld() {
		this.state.leaveWorld();
	}

	/** One line per declaration the pack lost, and what the pack has left. Said once per load. */
	public List<String> notes() {
		List<String> notes = new ArrayList<>();
		if (!this.declared.isEmpty()) {
			notes.add("The pack declares " + this.declared.size() + " values of its own, "
					+ this.customs.exposed().size() + " of which reach a shader");
		}

		this.problems.forEach(problem -> notes.add("Dropped the pack's own " + problem));
		this.storageImages.images().forEach(image -> notes.add("storage image " + image.describe()));
		this.storageImages.dropped().forEach(dropped -> notes.add("Dropped " + dropped));
		this.bufferObjects.buffers().forEach(buffer -> notes.add("storage buffer " + buffer.describe()));
		this.bufferObjects.dropped().forEach(dropped -> notes.add("Dropped " + dropped));
		notes.addAll(this.packImages.notes());

		return notes;
	}

	/**
	 * Sorts the names a program's block could not be given into the three things they can mean. They
	 * read as one list otherwise and they are not one problem: a name the pack declares for itself
	 * is ours to resolve, a name no engine answers is nobody's, and what is left is a value the
	 * engine owes.
	 */
	public Gaps classify(List<String> unanswered) {
		List<String> engine = new ArrayList<>();
		List<String> pack = new ArrayList<>();
		List<String> nobody = new ArrayList<>();

		for (String name : unanswered) {
			if (this.declared.contains(name)) {
				pack.add(name);
			} else if (UniformGaps.unanswerable(name) != null) {
				nobody.add(name);
			} else {
				engine.add(name);
			}
		}

		return new Gaps(List.copyOf(engine), List.copyOf(pack), List.copyOf(nobody));
	}

	/**
	 * Which of a block's members are answered with a stand-in rather than with a value, the names
	 * grouped under the one sentence that explains them. These count as supplied everywhere else,
	 * which is exactly why they are worth naming: a zero that came through a registered source
	 * cannot be told from a measured one by looking at it.
	 * <p>
	 * Grouped and not one entry per name, because the reason is a sentence and the names that share
	 * one are usually several: a list built the other way printed the same clause three times in a
	 * row and the names were what got lost in it.
	 */
	public static Map<String, List<String>> standIns(List<String> members) {
		Map<String, List<String>> named = new LinkedHashMap<>();
		for (String member : members) {
			String reason = UniformGaps.standIn(member);
			if (reason != null) {
				named.computeIfAbsent(reason, _ -> new ArrayList<>()).add(member);
			}
		}

		// Not Map.copyOf, which shuffles its iteration order differently on every run: the groups
		// are printed, and two launches of the same jar must print them in the members' order both
		// times or the journals stop comparing.
		named.replaceAll((_, names) -> List.copyOf(names));

		return Collections.unmodifiableMap(named);
	}

	/**
	 * Reads and decodes the image {@code texture.noise} names, when the pack names one. A failure
	 * of any kind falls back to the generated field and is named in the notes: the stand in looks
	 * like noise too, which is exactly why silence here would cost somebody a day.
	 */
	private void readNoise(ShaderProperties properties, ShaderPackSource source,
			Map<String, String> defines) {
		String path = properties.noiseTexturePath(defines).orElse(null);
		if (path == null) {
			return;
		}

		Optional<Path> file = source.file(path);
		if (file.isEmpty()) {
			this.problems.add("noise image: texture.noise names " + path
					+ " and no such file is in the pack, so the generated field stands in");
			return;
		}

		try {
			this.noiseImage = NoiseTexture.decode(source.bytes(file.get()));
		} catch (IOException | RuntimeException e) {
			this.problems.add("noise image " + path + ": " + e.getMessage()
					+ ", so the generated field stands in");
		}
	}

	private void declare(ShaderProperties properties, Map<String, String> defines) {
		CustomUniforms.Builder builder = CustomUniforms.builder();
		for (ShaderProperties.CustomUniform line : properties.customUniforms(defines)) {
			this.declared.add(line.name());
			builder.declare(line.name(), line.type(), line.expression(), line.exposed());
		}

		this.customs = builder.build(UniformCatalog.engine(), this.problems);
		this.catalog = this.customs.layerOn(UniformCatalog.engine());
		this.geometry = this.customs.layerOn(UniformCatalog.geometry());
		this.shadowGeometry = this.customs.layerOn(UniformCatalog.shadowGeometry());
	}

	/**
	 * The names nothing answers, sorted by what is missing behind each.
	 *
	 * @param engine names the engine owes and does not answer
	 * @param pack   names the pack declares itself, whose declaration did not survive
	 * @param nobody names no engine answers, Iris included, so a pack reads the same nought under
	 *               it. {@link UniformGaps#unanswerable} carries why each one is here
	 */
	public record Gaps(List<String> engine, List<String> pack, List<String> nobody) {
	}
}
