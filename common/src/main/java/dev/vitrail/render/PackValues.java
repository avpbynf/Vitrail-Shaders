package dev.vitrail.render;

import dev.vitrail.pack.id.BlockIds;
import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.OptionValue;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.source.ShaderPackSource;
import dev.vitrail.pack.source.ShaderProperties;
import dev.vitrail.pack.target.PackDirectives;
import dev.vitrail.uniform.expr.CustomUniforms;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformGaps;
import dev.vitrail.uniform.WorldState;
import dev.vitrail.Vitrail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	private final FrameState state = new FrameState();

	/** Everything the pack declared, live or not, which is what tells a gap from a mistake. */
	private final Set<String> declared = new LinkedHashSet<>();

	private final List<String> problems = new ArrayList<>();

	private CustomUniforms customs;
	private UniformCatalog catalog = UniformCatalog.engine();
	private UniformCatalog geometry = UniformCatalog.geometry();

	private PackValues() {
	}

	/**
	 * Reads one pack's directives and its own uniforms.
	 * <p>
	 * The machine is installed first and not last, and the caller has to read a pack's values
	 * before translating its programs. The biome symbols decide which branch of
	 * {@code shaders.properties} is live and which branch of the GLSL compiles, so they have to be
	 * in the table before either is read.
	 *
	 * @param dimension which set of programs the directives are folded from, {@code world0} for the
	 *                  overworld
	 * @param chosen    the settings forced on the pack, by the name the pack declares them under
	 * @param profile   a profile the pack declares, or the empty string
	 */
	public static PackValues read(Path packPath, String dimension, Map<String, OptionValue> chosen,
			String profile) throws IOException {
		PackValues values = new PackValues();
		PackDefines.install(values.state.biomes());

		try (ShaderPackSource source = ShaderPackSource.open(packPath)) {
			OptionIndex options = OptionIndex.build(source);
			ShaderProperties properties = ShaderProperties.parse(source);
			Map<String, OptionValue> fromProfile =
					profile.isEmpty() ? Map.of() : properties.expandProfile(profile);
			SettingSet settings =
					SettingSet.resolve(fromProfile, chosen, profile.isEmpty() ? "chosen" : profile);

			values.state.directives(PackDirectives.read(source, options, settings, dimension));
			values.state.endFlashShadows(properties.endFlashShadows());
			values.declare(properties, settings.globalDefines(options));

			// Read against the same settings as everything above, which is not a formality: BSL wraps
			// all its declarations in one conditional and keeps a fifth of them under the #else, so a
			// reading with an empty table measures a different pack.
			BlockStateIds.install(BlockIds.read(source, settings.globalDefines(options)));
		}

		return values;
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

	/** What a block is written from. The same object every frame, refilled by {@link #advance()}. */
	/** How big a noise image the pack asked for, its own directive, 256 unless it says otherwise. */
	public int noiseResolution() {
		return Math.round(this.state.noiseTextureResolution());
	}

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

	/** One line per declaration the pack lost, and what the pack has left. Said once per load. */
	public List<String> notes() {
		List<String> notes = new ArrayList<>();
		if (!this.declared.isEmpty()) {
			notes.add("The pack declares " + this.declared.size() + " values of its own, "
					+ this.customs.exposed().size() + " of which reach a shader");
		}

		this.problems.forEach(problem -> notes.add("Dropped the pack's own " + problem));

		return notes;
	}

	/**
	 * Sorts the names a program's block could not be given into the three things they can mean.
	 * They read as one list today and they are not one problem: a name the pack declares is ours
	 * to resolve, a name that waits on a pass is nobody's fault yet, and only what is left is a
	 * value the engine owes.
	 */
	public Gaps classify(List<String> unanswered) {
		List<String> engine = new ArrayList<>();
		List<String> pack = new ArrayList<>();
		List<String> awaited = new ArrayList<>();

		for (String name : unanswered) {
			String waiting = UniformGaps.awaited(name);
			if (this.declared.contains(name)) {
				pack.add(name);
			} else if (waiting != null) {
				awaited.add(name + " (" + waiting + ")");
			} else {
				engine.add(name);
			}
		}

		return new Gaps(List.copyOf(engine), List.copyOf(pack), List.copyOf(awaited));
	}

	/**
	 * Which of a block's members are answered with a stand-in rather than with a value. These
	 * count as supplied everywhere else, which is exactly why they are worth naming: a zero that
	 * came through a registered source cannot be told from a measured one by looking at it.
	 */
	public static List<String> standIns(List<String> members) {
		List<String> named = new ArrayList<>();
		for (String member : members) {
			String reason = UniformGaps.standIn(member);
			if (reason != null) {
				named.add(member + " (" + reason + ")");
			}
		}

		return List.copyOf(named);
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
	}

	/**
	 * @param engine  names the engine owes and does not answer
	 * @param pack    names the pack declares itself, whose declaration did not survive
	 * @param awaited names that wait on machinery that does not run, each with the reason
	 */
	public record Gaps(List<String> engine, List<String> pack, List<String> awaited) {
	}
}
