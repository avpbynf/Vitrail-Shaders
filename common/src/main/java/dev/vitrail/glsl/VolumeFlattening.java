package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslLexer.Kind;
import dev.vitrail.glsl.GlslLexer.Token;
import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;
import dev.vitrail.pack.target.SamplerPlan;
import dev.vitrail.pack.target.SamplerTypes;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The volumes a pack ships, laid out flat, and every read of one sent through a helper.
 * <p>
 * A three dimensional sampler is refused by the game's own compiler wherever it is declared, read
 * or not, so a pack that ships one has to be rewritten rather than served. The declaration becomes
 * a {@code sampler2D} over an atlas of slices and each lookup becomes a helper that reads two of
 * them and mixes them, which is one rewrite over the token list with a table of what it found.
 * <p>
 * Apart because what it needs is its own and narrow: the tokens, the unit's liveness, the volumes
 * the pack declared and the two tables of macro names, since a pack reads a volume through an
 * alias as readily as through the name it declared. What it leaves behind is the helpers the
 * header owes and the two counts that say a name could not be moved.
 */
final class VolumeFlattening {

	/** What a lookup on a volume the pack ships is called once the volume has been laid out flat. */
	private static final String VOLUME_LOOKUP = "ofTexture3D_";

	/** The number of arguments {@link GlslTranslator#LOOKUP} takes where it reads a volume. */
	private static final int LOOKUP_ARGUMENTS = 2;

	private final TokenStream tokens;
	private final ExpandedUnit unit;

	/**
	 * The volumes the pack ships and this engine serves flat, by the name the pack samples them
	 * under. Empty everywhere but the two packs of the corpus that ship one.
	 */
	private final Map<String, VolumeAtlas> volumes;

	/** Names the pack defines as macros, and the ones standing for exactly one other name. */
	private final Set<String> packMacros;
	private final Map<String, String> macroAliases;

	/** The volumes this unit reads, and so the helpers its header owes, by the pack's own name. */
	private final Map<String, VolumeAtlas> readVolumes = new LinkedHashMap<>();

	private int volumeLookups;
	private int volumesLeftAlone;

	VolumeFlattening(TokenStream tokens, ExpandedUnit unit, Map<String, VolumeAtlas> volumes,
			Set<String> packMacros, Map<String, String> macroAliases) {
		this.tokens = tokens;
		this.unit = unit;
		this.volumes = Collections.unmodifiableMap(new LinkedHashMap<>(volumes));
		this.packMacros = packMacros;
		this.macroAliases = macroAliases;
	}

	/** The volumes really read, and so the helpers the header owes, by the pack's own name. */
	Map<String, VolumeAtlas> read() {
		return this.readVolumes;
	}

	/** How many lookups were moved onto a helper. */
	int lookups() {
		return this.volumeLookups;
	}

	/** How many volumes were left exactly as the pack wrote them, and so left the program refused. */
	int leftAlone() {
		return this.volumesLeftAlone;
	}

	/**
	 * Moves every volume the pack ships onto a flat atlas: the declaration to a {@code sampler2D}
	 * under a forged name, and each lookup to a helper that reads two slices and mixes them.
	 * <p>
	 * <strong>The declaration is what has to go, not the lookup.</strong>
	 * {@code GlslCompiler.addToBindGroup} refuses anything the reflection reports as neither
	 * {@code SpvDim2D} nor {@code SpvDimCube}, and the reflection lists a module's whole resource
	 * list at optimisation level zero, so a {@code sampler3D} declared in a shared include and never
	 * read costs the program its pipeline exactly as one sampled on every pixel does. Supplying a
	 * real volume would not help either: the type is the refusal.
	 * <p>
	 * <strong>Every program carrying the declaration is rewritten, and Iris rewrites only the stage
	 * the directive names.</strong> Its {@code TextureTransformer} runs per stage, so under it
	 * Mellow's composites and its final keep a live {@code sampler3D colortex6} bound to nothing,
	 * which GL tolerates and Vulkan does not. Renaming everywhere invents nothing: the pack has
	 * named exactly one file for that identifier, with its shape, its size and its format written
	 * out, and that file is what every one of those declarations was going to read.
	 * <p>
	 * Nothing is moved unless everything can be. A name this unit reaches any other way than as
	 * {@code texture(name, vec3)}, the name being the declared one or a macro the unit defines as
	 * exactly that name, is counted and left exactly as it stands, declaration included, so the
	 * program stays refused with the message it had. There is no site in the corpus like that, and
	 * the count is what would say one had appeared.
	 */
	void flatten() {
		if (this.volumes.isEmpty()) {
			return;
		}

		int[] lines = this.tokens.lineNumbers();
		this.volumes.forEach((name, atlas) -> flattenOne(name, atlas, lines));
	}

	private void flattenOne(String name, VolumeAtlas atlas, int[] lines) {
		// The name token of a declaration, and the pair of tokens each lookup rewrites: the callee
		// and the argument. Held rather than found again below, so that what is rewritten is what
		// was judged.
		List<Integer> declarations = new ArrayList<>();
		Map<Integer, Integer> lookups = new LinkedHashMap<>();
		boolean elsewhere = this.packMacros.contains(name);
		Set<String> spellings = spellingsOf(name);

		for (int index = 0; index < this.tokens.size(); index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.IDENTIFIER || !spellings.contains(token.text())
					|| !this.unit.isLive(lines[index])) {
				continue;
			}

			if (token.directive() != null) {
				// The preprocessor's own lines: an alias being defined or tested is its business,
				// and the name standing as an alias's replacement text IS the alias. The name on
				// any other directive is a use this pass cannot follow.
				if (token.text().equals(name) && !aliasBody(index)) {
					elsewhere = true;
				}

				continue;
			}

			int callee = plainLookup(index);
			if (token.text().equals(name) && volumeDeclaration(index)) {
				declarations.add(index);
			} else if (callee >= 0) {
				lookups.put(index, callee);
			} else {
				elsewhere = true;
			}
		}

		// A unit that reads the name without declaring it has been handed the sampler by something
		// this pass has not seen, so there is nothing here to rename it against.
		if (declarations.isEmpty()) {
			return;
		}

		if (elsewhere) {
			this.volumesLeftAlone++;
			return;
		}

		String forged = SamplerPlan.forged(name);
		for (int declaration : declarations) {
			this.tokens.replace(this.tokens.significantBefore(declaration), "sampler2D");
			this.tokens.replace(declaration, forged);
		}

		lookups.forEach((argument, callee) -> {
			this.tokens.replace(callee, VOLUME_LOOKUP + name);
			this.tokens.replace(argument, forged);
		});

		this.volumeLookups += lookups.size();
		if (!lookups.isEmpty()) {
			this.readVolumes.put(name, atlas);
		}
	}

	/** The name and every macro the unit defines as exactly that name, aliases of aliases included. */
	private Set<String> spellingsOf(String name) {
		Set<String> spellings = new HashSet<>();
		spellings.add(name);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (Map.Entry<String, String> alias : this.macroAliases.entrySet()) {
				if (spellings.contains(alias.getValue()) && spellings.add(alias.getKey())) {
					grew = true;
				}
			}
		}

		return spellings;
	}

	/**
	 * Whether this token, on a directive, is the whole replacement text of a macro standing for it:
	 * the {@code depthtex0} of {@code #define ATMOSPHERE_SCATTERING_LUT depthtex0}.
	 */
	private boolean aliasBody(int index) {
		for (int scan = index - 1; scan >= 0; scan--) {
			Token token = this.tokens.get(scan);
			if (token.trivia()) {
				continue;
			}

			return token.macroName()
					&& this.tokens.get(index).text().equals(this.macroAliases.get(token.text()));
		}

		return false;
	}

	/**
	 * Whether this name is being declared as a uniform of a three dimensional shape here.
	 * <p>
	 * The {@code uniform} is demanded and the type is not enough on its own: a function taking a
	 * {@code sampler3D} parameter of the same name declares a name inside its own body, and renaming
	 * that would leave the body reading a parameter nobody passes.
	 */
	private boolean volumeDeclaration(int index) {
		int type = this.tokens.significantBefore(index);
		if (type < 0 || this.tokens.get(type).kind() != Kind.IDENTIFIER
				|| !"3D".equals(SamplerTypes.shapeOf(this.tokens.get(type).text()))) {
			return false;
		}

		int cursor = this.tokens.significantBefore(type);
		while (cursor >= 0 && GlslTranslator.isQualifier(this.tokens.get(cursor))) {
			cursor = this.tokens.significantBefore(cursor);
		}

		return cursor >= 0 && this.tokens.get(cursor).identifier("uniform");
	}

	/**
	 * The {@code texture} this name is the first argument of, or -1 when it is reached any other
	 * way. The argument count is checked as well as the name: {@code texture(s, p, bias)} compiles
	 * and means something else, and the helper takes two.
	 */
	private int plainLookup(int index) {
		int open = this.tokens.significantBefore(index);
		if (open < 0 || !this.tokens.get(open).operator("(")) {
			return -1;
		}

		int callee = this.tokens.significantBefore(open);
		if (callee < 0 || !this.tokens.get(callee).identifier(GlslTranslator.LOOKUP)) {
			return -1;
		}

		int close = this.tokens.matchingBracket(open);

		return close >= 0 && arguments(open, close) == LOOKUP_ARGUMENTS ? callee : -1;
	}

	/** How many arguments a call holds, counting the commas that belong to it and not to a nested one. */
	private int arguments(int open, int close) {
		int depth = 0;
		int count = 1;

		for (int index = open; index < close; index++) {
			Token token = this.tokens.get(index);
			if (token.kind() != Kind.OPERATOR || token.directive() != null) {
				continue;
			}

			String text = token.text();
			if (text.equals("(") || text.equals("[")) {
				depth++;
			} else if (text.equals(")") || text.equals("]")) {
				depth--;
			} else if (depth == 1 && text.equals(",")) {
				count++;
			}
		}

		return count;
	}

	/**
	 * The trilinear read of a volume, over the atlas its slices were laid out in.
	 * <p>
	 * The hardware does the two dimensional half: each slice carries one texel of gutter holding
	 * what lies past its edge, the far edge for a volume that repeats and the edge itself for one
	 * that clamps, so a bilinear tap at the edge of a tile reads what {@code REPEAT} or
	 * {@code CLAMP} would have read on a real volume rather than the slice next door. Only the depth
	 * is done here, two taps and a mix, because nothing interpolates between tiles of an atlas, and
	 * the slice index repeats or clamps as the pack asked.
	 * <p>
	 * The half texel is the whole of the arithmetic: a lookup at {@code u} samples the volume at
	 * {@code u * size - 0.5} in texels, and the atlas coordinate has to land on the same pair of
	 * texels the hardware would have blended. Every constant here comes from {@link VolumeAtlas} so
	 * that this and the upload cannot drift apart; a layout written twice reads as noise, and noise
	 * that is wrong looks exactly like noise that is right.
	 */
	static List<String> helper(String name, VolumeAtlas atlas) {
		String depth = whole(atlas.depth());
		String last = Integer.toString(atlas.depth() - 1);
		String tiles = Integer.toString(atlas.tilesPerRow());

		List<String> lines = new ArrayList<>();
		lines.add("vec4 " + VOLUME_LOOKUP + name + "(sampler2D ofMap, vec3 ofAt) {");
		lines.add(atlas.clamp() ? "\tvec3 ofQ = clamp(ofAt, 0.0, 1.0);" : "\tvec3 ofQ = fract(ofAt);");
		lines.add("\tfloat ofZ = ofQ.z * " + depth + " - 0.5;");
		lines.add("\tfloat ofBase = floor(ofZ);");
		lines.add("\tvec2 ofIn = ofQ.xy * vec2(" + whole(atlas.width()) + ", " + whole(atlas.height())
				+ ") + " + whole(VolumeAtlas.GUTTER) + ";");
		if (atlas.clamp()) {
			lines.add("\tint ofNear = clamp(int(ofBase), 0, " + last + ");");
			lines.add("\tint ofFar = clamp(int(ofBase) + 1, 0, " + last + ");");
		} else {
			lines.add("\tint ofNear = int(mod(ofBase, " + depth + "));");
			lines.add("\tint ofFar = int(mod(ofBase + 1.0, " + depth + "));");
		}

		lines.add("\tvec2 ofTile = vec2(" + whole(atlas.tileStride()) + ", " + whole(atlas.tileHeight())
				+ ");");
		lines.add("\tvec2 ofSize = vec2(" + whole(atlas.atlasWidth()) + ", " + whole(atlas.atlasHeight())
				+ ");");
		lines.add("\tvec2 ofA = (vec2(ofNear % " + tiles + ", ofNear / " + tiles
				+ ") * ofTile + ofIn) / ofSize;");
		lines.add("\tvec2 ofB = (vec2(ofFar % " + tiles + ", ofFar / " + tiles
				+ ") * ofTile + ofIn) / ofSize;");
		lines.add("\treturn mix(texture(ofMap, ofA), texture(ofMap, ofB), clamp(ofZ - ofBase, 0.0, 1.0));");
		lines.add("}");

		return lines;
	}

	/** An integer as a GLSL float literal, spelled by hand so that no locale can put a comma in it. */
	private static String whole(int value) {
		return value + ".0";
	}
}
