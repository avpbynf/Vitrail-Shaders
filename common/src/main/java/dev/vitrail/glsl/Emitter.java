package dev.vitrail.glsl;

import dev.vitrail.glsl.GlslTranslator.Output;
import dev.vitrail.glsl.VaryingSplit.SplitArray;
import dev.vitrail.glsl.VaryingSplit.SplitMatrix;
import dev.vitrail.glsl.VaryingSplit.SplitStruct;
import dev.vitrail.glsl.VaryingSplit.StructMember;
import dev.vitrail.pack.model.AlphaTest;
import dev.vitrail.pack.model.ProgramStage;
import dev.vitrail.pack.texture.CustomImages;
import dev.vitrail.pack.texture.VolumeAtlas;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The header written in front of one rewritten body, and nothing but that.
 * <p>
 * Everything the passes of {@link GlslTranslator} settled has to be declared to the compiler
 * somewhere: the uniform block, the samplers in the order that keeps a Metal slot reachable, the
 * attributes of the mesh the pass is drawn from, the helpers the rewrites called, the varyings
 * both stages have to agree about, the fragment outputs from zero up with no gaps, and the
 * {@code main} that runs the pack's own between a prologue and an epilogue. Writing that is not a
 * pass over the text and does not belong among them, which is the whole of why it is here.
 * <p>
 * A record of what the header is written from, so that what this can see is the list below and
 * stays the list below: every one of them is read here and not one is written back. Several are
 * the translator's own lists and maps rather than copies of them, and {@code splits} is the
 * split itself; the record is built at render, which is after every pass has run and the last
 * moment any of them is written, and it is read in the one expression that builds it.
 */
record Emitter(ProgramStage stage, VertexInputs inputs, List<String> bound, AlphaTest alphaTest,
		Set<String> extensions,
		Map<String, String> engineDefines, Map<String, String> memoryQualifiers, Set<String> used,
		Set<String> declaredNames, Map<String, String> synthesized,
		Map<String, VolumeAtlas> readVolumes, Map<Integer, Output> packOutputs,
		int maxFragmentOutput, Map<String, String> owedOutputs, VaryingSplit splits,
		int gameTextureMatrix, int gameModelView, int softRewrites, int trigCalls, int hashCalls,
		boolean mainWrapped, boolean depthEpilogue, boolean terrainPrologue,
		boolean distantPrologue, boolean entityWrapped, boolean linesWrapped,
		boolean alphaEpilogue, boolean covers,
		boolean wrapsFragment, boolean ordered, boolean namesFragDepth, boolean makesOverlayColour) {

	private static final String VERSION = "#version 460 core";

	/** The block name has to be declared to the pipeline by hand later, so it is fixed here. */
	private static final String UNIFORM_BLOCK = "OfGlobals";

	/**
	 * The member types that may not be interpolated, so their varying is flat whatever the pack
	 * wrote: the integers, and the doubles the language holds to the same rule.
	 */
	private static final Set<String> INTEGER_MEMBER_TYPES = Set.of(
			"int", "ivec2", "ivec3", "ivec4", "uint", "uvec2", "uvec3", "uvec4",
			"double", "dvec2", "dvec3", "dvec4");

	/** The fetch the overlay colour is made from, named so that the pack's own names cannot meet it. */
	private static final String OVERLAY_TEXEL = "ofOverlayTexel";

	private static final String FOG_STRUCT =
			"struct OfFog { vec4 color; float density; float start; float end; float scale; };";

	/**
	 * The one output this engine adds itself: the depth the pack's geometry left at this pixel, so
	 * that whoever puts the game's own picture into the same target can tell whether anything has
	 * been drawn in front of it since.
	 * <p>
	 * A depth and not a flag, and what it buys is the whole of what a flag could not answer: the
	 * reader compares it with the world's depth as it stands, so a pixel nothing was drawn over
	 * compares equal and belongs to the pack, and a pixel the game drew a feature onto does not.
	 * The pixels the pack never wrote carry a value outside zero to one instead, which every real
	 * depth is in front of, and the reader owes them no test of their own.
	 */
	private static final String COVERAGE = "ofCoverage";

	String header(List<TranslatedUnit.Uniform> block, List<TranslatedUnit.Uniform> samplers,
			Set<String> varyings, Set<String> shadowed) {
		List<String> lines = new ArrayList<>();
		lines.add(VERSION);

		// Straight after the version, which is the only place the language takes them, and as
		// enable rather than require. GlslTranslator.dropVersionAndExtensions carries why they are
		// hoisted here instead of staying where the pack wrote them, and what a lost one costs.
		this.extensions.forEach(extension -> lines.add("#extension " + extension + " : enable"));

		for (Map.Entry<String, String> define : this.engineDefines.entrySet()) {
			lines.add(define.getValue().isEmpty()
					? "#define " + define.getKey()
					: "#define " + define.getKey() + " " + define.getValue());
		}

		// The block is written in the order it was handed over, and nothing sorts it: a std140
		// buffer is filled by walking the members, so a different order is a different buffer.
		if (block.stream().anyMatch(member -> member.name().equals("of_Fog"))) {
			lines.add(FOG_STRUCT);
		}

		if (!block.isEmpty()) {
			lines.add("layout(std140) uniform " + UNIFORM_BLOCK + " {");
			for (TranslatedUnit.Uniform member : block) {
				lines.add("\t" + member.declaration() + ";");
			}

			lines.add("};");
		}

		// The game's own block, beside ours and never merged into it: this one is filled by the game
		// once per draw and ours is written once per run, which is the whole reason a matrix that
		// changes with the draw is read from over there.
		if (this.gameTextureMatrix > 0 || this.gameModelView > 0) {
			lines.addAll(LegacyGlsl.GAME_TRANSFORMS_BLOCK);
		}

		// Written here, in the order the program handed over, rather than left in the body. The
		// compiler numbers a sampler by the order it first meets the name, and MoltenVK turns that
		// number into a Metal slot that only accepts 0 through 15. Sampled names come first.
		for (TranslatedUnit.Uniform sampler : samplers) {
			lines.add(declareOpaque(sampler));
		}

		// Attributes stay a matter for the stage that has them. Only a vertex shader has inputs
		// from a buffer, so there is no other side to agree with.
		if (this.stage == ProgramStage.VERTEX) {
			switch (this.inputs) {
				case FULLSCREEN -> {
					lines.addAll(LegacyGlsl.FULLSCREEN_ATTRIBUTES);
					lines.addAll(VertexPrologue.tail(this.used, this.synthesized));
				}
				case TERRAIN, TERRAIN_SEPARATE_AO -> lines.addAll(SodiumVertex.prologue(this.bound,
						this.used, this.synthesized, this.inputs.separateAo()));
				case ENTITY, ENTITY_FULLBRIGHT -> lines.addAll(
						EntityVertex.prologue(this.used, this.synthesized, this.inputs.fullbright()));
				case GLINT -> lines.addAll(GlintVertex.prologue(this.used, this.synthesized));
				case CRUMBLING -> lines.addAll(CrumblingVertex.prologue(this.used, this.synthesized));
				case GLYPH -> lines.addAll(
						GlyphVertex.prologue(this.bound, this.used, this.synthesized));
				case LINES -> {
					lines.addAll(LinesVertex.prologue(this.used, this.synthesized));
					lines.addAll(LinesVertex.widen());
				}
				case PARTICLE -> lines.addAll(ParticleVertex.prologue(this.used, this.synthesized));
				case SKY -> lines.addAll(SkyVertex.prologue(this.bound, this.used, this.synthesized));
				case CLOUDS -> lines.addAll(CloudVertex.prologue(this.used, this.synthesized));
				case DISTANT -> lines.addAll(
						DistantVertex.prologue(this.bound, this.used, this.synthesized));
				case WORLD -> {
					for (Map.Entry<String, String> attribute : LegacyGlsl.FIXED_ATTRIBUTES.entrySet()) {
						if (this.used.contains(attribute.getKey())) {
							lines.add("in " + attribute.getValue() + ";");
						}
					}

					for (Map.Entry<String, String> attribute : LegacyGlsl.ENGINE_ATTRIBUTES.entrySet()) {
						if (this.used.contains(attribute.getKey())
								&& !this.declaredNames.contains(attribute.getKey())) {
							lines.add("in " + attribute.getValue() + ";");
						}
					}
				}
			}
		}

		// The four taps a hardware comparison blends, for the lookups the road decision sent here:
		// GpuSampler carries no compare mode, so when the comparison cannot ride the binding, what
		// the hardware does is done in arithmetic instead. The hardware compares each of the four
		// texels a bilinear filter would have taken and blends the four RESULTS, so that is what
		// this does: textureGather brings the four back whatever filter is bound, the comparison is
		// made on each, and the blend uses the weights of the filter. Comparing an already filtered
		// depth is the one thing it must not do, and that is the difference: the average of four
		// depths is a surface standing nowhere, while the average of four comparisons is a fraction
		// of the light, which is the whole point of the thing. Iris binds GL_LINEAR plus
		// GL_COMPARE_REF_TO_TEXTURE for it, in ShadowRenderTargets.getSamplerFor, and the hardware
		// road binds the same pair through the descriptor instead of coming here.
		//
		// Not conditioned on shadowHardwareFiltering, and nothing here could condition it: the
		// header is written per stage, before any of the pack's directives are folded. It costs
		// nothing on this corpus. Without that directive Iris leaves the comparison mode off and
		// what a sampler2DShadow reads is undefined, so the declaration this translation found is
		// the only live meaning the directive has; and the harder shape the pair can ask for,
		// NEAREST_HW, needs shadowtexNearest, which no pack of the corpus writes.
		//
		// The sense is LEQUAL, which is what OptiFine sets on a shadow texture and therefore what
		// every pack is written against: one where the fragment is no further from the light than
		// what the map holds, and the map holds the forward window where nearer is smaller.
		//
		// The level of detail is dropped, which is what a comparison sampler with no mipmaps would
		// have done with it anyway: nothing ever fills a chain on the shadow map.
		if (this.softRewrites > 0) {
			lines.add("float " + GlslTranslator.SHADOW_COMPARE + "(sampler2D ofMap, vec3 ofAt) {"
					+ " vec4 ofTests = step(vec4(ofAt.z), textureGather(ofMap, ofAt.xy, 0));"
					+ " vec2 ofPart = fract(ofAt.xy * vec2(textureSize(ofMap, 0)) - 0.5);"
					+ " return mix(mix(ofTests.w, ofTests.z, ofPart.x),"
					+ " mix(ofTests.x, ofTests.y, ofPart.x), ofPart.y); }");
			lines.add("float " + GlslTranslator.SHADOW_COMPARE
					+ "(sampler2D ofMap, vec3 ofAt, float ofLod) {"
					+ " return " + GlslTranslator.SHADOW_COMPARE + "(ofMap, ofAt); }");
		}

		// One overload per shape the builtins take, and no driver sine anywhere in it. The turn
		// count is taken out through two constants whose sum carries two pi to thirty-three bits,
		// so the residue keeps its low bits where a single fp32 two-pi would shed them; the residue
		// is folded to a quarter turn and fed to the odd polynomial. Measured on the hash's own
		// yardstick: a single-constant reduction leaves a field the uniformity test rejects at 427
		// where white noise scores 15, and this form scores 11 to 14, alongside the reference.
		if (this.trigCalls > 0) {
			for (String shape : new String[] {"float", "vec2", "vec3", "vec4"}) {
				lines.add(shape + " " + GlslTranslator.REDUCED_SIN + "(" + shape + " ofX) {"
						+ " " + shape + " ofK = floor(ofX * 0.15915494);"
						+ " " + shape + " ofR = ofX - ofK * 6.28125 - ofK * 1.9353072e-3;"
						+ " ofR -= 6.2831855 * step(3.1415927, ofR);"
						+ " " + shape + " ofS = sign(ofR);"
						+ " " + shape + " ofA = 1.5707964 - abs(abs(ofR) - 1.5707964);"
						+ " " + shape + " ofZ = ofA * ofA;"
						+ " return ofS * ofA * (1.0 + ofZ * (-1.6666654611e-1"
						+ " + ofZ * (8.3321608736e-3 + ofZ * (-1.9515295891e-4)))); }");
				lines.add(shape + " " + GlslTranslator.REDUCED_COS + "(" + shape + " ofX) {"
						+ " return " + GlslTranslator.REDUCED_SIN + "(ofX + 1.5707964); }");
			}
		}

		// One overload per vector the idiom hashes. The bits are hashed rather than the sine of a
		// huge argument, which is the whole of rewriteGoldbergHash.
		if (this.hashCalls > 0) {
			lines.add("float " + GlslTranslator.HASH + "(vec2 ofP) {"
					+ " uvec2 ofV = floatBitsToUint(ofP);"
					+ " uint ofN = (ofV.x * 1597334677u) ^ (ofV.y * 3812015801u);"
					+ " ofN ^= ofN >> 16u;"
					+ " ofN *= 2246822519u;"
					+ " ofN ^= ofN >> 13u;"
					+ " ofN *= 3266489917u;"
					+ " ofN ^= ofN >> 16u;"
					+ " return float(ofN) * 2.3283064365386963e-10; }");
			lines.add("float " + GlslTranslator.HASH + "(vec3 ofP) {"
					+ " uvec3 ofV = floatBitsToUint(ofP);"
					+ " uint ofN = ofV.x ^ (ofV.y * 1597334677u) ^ (ofV.z * 3812015801u);"
					+ " ofN ^= ofN >> 16u;"
					+ " ofN *= 2246822519u;"
					+ " ofN ^= ofN >> 13u;"
					+ " ofN *= 3266489917u;"
					+ " ofN ^= ofN >> 16u;"
					+ " return float(ofN) * 2.3283064365386963e-10; }");
		}

		// Only where a lookup was moved. A stage carrying the declaration and never reading it, which
		// is most of them, has its declaration flattened and owes no helper.
		this.readVolumes.forEach((name, atlas) -> lines.addAll(VolumeFlattening.helper(name, atlas)));

		// Declared on both sides or on neither, whether this stage reads it or not. A varying the
		// vertex writes and the fragment never mentions is accepted in silence and shifts the
		// location of everything declared after it.
		if (varyings.contains(GlslTranslator.FOG_COORD)) {
			lines.add((this.stage == ProgramStage.VERTEX ? "out" : "in") + " float "
					+ GlslTranslator.FOG_COORD + ";");
		}

		// A global and not a varying, which is why it is asked of this stage's own names rather than
		// of the union: the fragment stage never sees it, so there is no other side to agree with.
		// GlslTranslator.FRONT_COLOUR says what it is and why writing it is enough.
		if (this.stage == ProgramStage.VERTEX && this.used.contains(GlslTranslator.FRONT_COLOUR)) {
			lines.add("vec4 " + GlslTranslator.FRONT_COLOUR + ";");
		}

		// The same rule for the overlay colour, and it arrives here by the same road: the union says
		// yes, so both sides declare it whichever of them reads it. No interpolation qualifier, which
		// is Iris's answer too: every vertex of one model part carries the same overlay coordinate,
		// so what is interpolated between them is one value.
		if (varyings.contains(GlslTranslator.ENTITY_COLOR)) {
			lines.add((this.stage == ProgramStage.VERTEX ? "out" : "in") + " vec4 "
					+ GlslTranslator.ENTITY_COLOR + ";");
		}

		// And the same rule again for the three identifiers, with the qualifier the language demands
		// rather than one chosen: an integer varying may not be interpolated, so flat is not a
		// decision here. Iris writes flat on its own ivec3 for the same reason
		// (EntityPatcher.java:159).
		for (String identifier : GlslTranslator.ENTITY_IDS) {
			if (varyings.contains(identifier)) {
				lines.add("flat " + (this.stage == ProgramStage.VERTEX ? "out" : "in") + " int "
						+ identifier + ";");
			}
		}

		for (SplitMatrix split : this.splits.matrices()) {
			lines.add(split.matrixType() + " " + split.name() + ";");
			String storage = split.input() ? "in" : "out";
			String qualified = split.qualifier().isEmpty()
					? storage + " " + split.columnType()
					: split.qualifier() + " " + storage + " " + split.columnType();
			for (int column = 0; column < split.columns(); column++) {
				lines.add(qualified + " " + VaryingSplit.matrixColumnName(split.name(), column) + ";");
			}
		}

		// The struct's definition, once per type, then the global of the pack's name, then one
		// varying per member in the order the struct lists them, so that both stages number them
		// alike. The definition is written from the members read off the body, whose own copy is
		// blank by now.
		Set<String> defined = new HashSet<>();
		for (SplitStruct split : this.splits.structs()) {
			if (defined.add(split.structType())) {
				StringBuilder definition = new StringBuilder("struct ").append(split.structType())
						.append(" { ");
				for (StructMember member : split.members()) {
					definition.append(member.type()).append(' ').append(member.name()).append("; ");
				}

				lines.add(definition.append("};").toString());
			}

			lines.add(split.structType() + " " + split.name() + ";");
			String storage = split.input() ? "in" : "out";
			for (StructMember member : split.members()) {
				// An integer member is flat whatever the pack wrote on the struct, as the entity
				// identifiers are: the language allows nothing else, and a struct of floats may
				// legally carry no qualifier at all.
				String qualifier = split.qualifier();
				if (INTEGER_MEMBER_TYPES.contains(member.type()) && !qualifier.contains("flat")) {
					qualifier = qualifier.isEmpty() ? "flat" : "flat " + qualifier;
				}

				String qualified = qualifier.isEmpty()
						? storage + " " + member.type()
						: qualifier + " " + storage + " " + member.type();
				lines.add(qualified + " "
						+ VaryingSplit.structMemberName(split.name(), member.name()) + ";");
			}
		}

		// The array as a global of the pack's name, then one varying per element in index order,
		// flat where the element may not be interpolated.
		for (SplitArray split : this.splits.arrays()) {
			lines.add(split.type() + " " + split.name() + "[" + split.size() + "];");
			String storage = split.input() ? "in" : "out";
			String qualifier = split.qualifier();
			if (INTEGER_MEMBER_TYPES.contains(split.type()) && !qualifier.contains("flat")) {
				qualifier = qualifier.isEmpty() ? "flat" : "flat " + qualifier;
			}

			String qualified = qualifier.isEmpty()
					? storage + " " + split.type()
					: qualifier + " " + storage + " " + split.type();
			for (int element = 0; element < split.size(); element++) {
				lines.add(qualified + " " + VaryingSplit.arrayElementName(split.name(), element) + ";");
			}
		}

		// From zero up with no gaps, because a location the game finds nothing declared at is not
		// left empty: it renumbers what is there and everything above the gap moves down one.
		for (int slot = 0; slot <= this.maxFragmentOutput; slot++) {
			Output output = this.packOutputs.get(slot);
			lines.add("layout(location = " + slot + ") out "
					+ (output == null ? "vec4" : output.type()) + " " + outputName(slot, shadowed) + ";");
		}

		// Above everything the pack declared, dead branches included, because the rank is what
		// becomes the location and the ranks below this one are already spoken for.
		if (this.covers) {
			lines.add("layout(location = " + (this.maxFragmentOutput + 1) + ") out float "
					+ COVERAGE + ";");
		}

		if (this.ordered) {
			StringBuilder order = new StringBuilder("void " + GlslTranslator.ORDER_OUTPUTS + "() {");
			for (int slot = 0; slot <= this.maxFragmentOutput; slot++) {
				order.append(' ').append(outputName(slot, shadowed)).append(';');
			}

			if (this.covers) {
				order.append(' ').append(COVERAGE).append(';');
			}

			lines.add(order.append(" }").toString());
		}

		// The same rule as the varying above, read from the other end: a varying the NEXT stage
		// declares and this one never wrote is not a silence but a refusal, so it is declared here
		// rather than taken out there. The qualifier travels with it, the two sides having to agree
		// on that as well.
		//
		// BELOW the colour outputs and below the ascending function, for the reason the next block
		// gives about itself: on a stage that has colour outputs, a plain out declaration met first
		// would take location nought from the one the game writes back. Only the last stage of a
		// pipeline has those and only a stage that is not last is ever owed anything, so the two do
		// not meet today. They are ordered anyway rather than left to that argument holding.
		this.owedOutputs.forEach((name, qualified) -> lines.add(outDeclaration(name, qualified)));

		// Below the block, since it reads it, and below the outputs and the ascending function for a
		// reason that decides the picture: a wrapper standing above them would be the first place the
		// compiler met an output name, and the rank it hands out there is the location the game
		// writes back. It has to be the ascending function that gets there first, so this goes last.
		// The pack's body is concatenated after the header, so its own main is only a name here and
		// has to be declared before it can be called.
		// Asked of the one thing that decides it, which is whether the pack's own main was renamed.
		// Every reason to wrap sets that flag as it renames, so this is the list of reasons said
		// once instead of twice, and it cannot fall out of step with them. It used to be the list
		// itself, and a split taken back out by dropUnprovidedSplits after the rename then left a
		// stage whose main was called ofPackMain and whose wrapper nobody wrote: no entry point,
		// which is the fragment stage of Sildur's gbuffers_textured.
		if (this.mainWrapped) {
			lines.add("void " + GlslTranslator.PACK_MAIN + "();");
			// The lines mesh runs the pack's main twice, the far end of the edge first and the
			// vertex itself second, so that every varying holds the vertex's own value when the
			// epilogues below read them, and widens between the two clip positions: Iris's own
			// order (VanillaTransformer.java:214-222). The depth conversion still lands after,
			// on the widened position, whose z the widening leaves alone.
			String body = this.linesWrapped
					? LinesVertex.OFFSET + " = Normal.xyz; " + GlslTranslator.PACK_MAIN
							+ "(); vec4 of_LineEnd = "
							+ "gl_Position; gl_Position = vec4(0.0); " + LinesVertex.OFFSET
							+ " = vec3(0.0); " + GlslTranslator.PACK_MAIN + "(); " + LinesVertex.WIDEN
							+ "(gl_Position, of_LineEnd);"
					: GlslTranslator.PACK_MAIN + "();";
			// The mask goes last of all, after the discard: a fragment the alpha test threw away
			// covered nothing, and marking it covered would leave a hole where a leaf was.
			lines.add("void main() { "
					+ (this.terrainPrologue ? SodiumVertex.PROLOGUE + "(); " : "")
					+ (this.distantPrologue ? DistantVertex.PROLOGUE + "(); " : "")
					+ overlayPrologue()
					+ identifierPrologue(varyings)
					+ (wrapsFragment() ? GlslTranslator.ORDER_OUTPUTS + "(); " : "")
					+ coveragePrologue()
					+ owedPrologue()
					+ this.splits.matrixPrologue()
					+ this.splits.structPrologue()
					+ this.splits.arrayPrologue()
					+ body
					+ this.splits.matrixEpilogue()
					+ this.splits.structEpilogue()
					+ this.splits.arrayEpilogue()
					+ (this.depthEpilogue ? " gl_Position.z = " + GlslTranslator.DEPTH_CONV
							+ ".x * gl_Position.z + " + GlslTranslator.DEPTH_CONV
							+ ".y * gl_Position.w;" : "")
					+ (this.alphaEpilogue
							? " " + this.alphaTest.discard(outputName(0, shadowed) + ".a")
							: "")
					+ (this.covers ? " " + COVERAGE + " = " + writtenDepth() + ";" : "")
					+ " }");
		}

		return String.join("\n", lines) + "\n";
	}

	/** Whether there is anything owed AND a wrapped main to assign it from. */
	private boolean owesInitialisers() {
		return this.mainWrapped && !this.owedOutputs.isEmpty();
	}

	/** The owed varyings set to their zero, ahead of the pack's own main. */
	private String owedPrologue() {
		if (!owesInitialisers()) {
			return "";
		}

		StringBuilder assignments = new StringBuilder();
		this.owedOutputs.forEach((name, qualified) ->
				assignments.append(initialiser(name, qualified)).append(' '));

		return assignments.toString();
	}

	/**
	 * What the depth attachment of this draw receives, which is what the mask is filled from.
	 * <p>
	 * The interpolated depth for an ordinary stage, and the stage's own where it writes one: those
	 * are the two values the hardware may write, and the mask exists to be compared with what was
	 * written. Neither is converted. The pack's own reads of {@code gl_FragCoord.z} are put back
	 * into the window it was written for, and its writes to {@code gl_FragDepth} are brought out of
	 * it again, both by {@link GlslTranslator#convertDepth}; this line is text of ours that pass
	 * never sees, so both names here carry the value the target really holds.
	 */
	private String writtenDepth() {
		return this.namesFragDepth ? "gl_FragDepth" : "gl_FragCoord.z";
	}

	/**
	 * Gives {@code gl_FragDepth} the value the hardware would have written, before the pack's own
	 * body runs and can write another.
	 * <p>
	 * <strong>Only where the mask is written and the stage names the builtin</strong>, and both
	 * halves are paid for. A stage that names it may still leave it alone on the branch that runs -
	 * Bliss writes it under {@code POM} and nowhere else ({@code dimensions/all_solid.fsh:359,394})
	 * - and reading a builtin the stage never wrote is undefined, so the mask would carry whatever
	 * the driver left there. Writing it costs the early depth test, which is why a stage that never
	 * names it is left alone: it would pay that for a value the line below can read off
	 * {@code gl_FragCoord} instead.
	 */
	private String coveragePrologue() {
		return this.covers && this.namesFragDepth ? "gl_FragDepth = gl_FragCoord.z; " : "";
	}

	/**
	 * Makes the hit flash and the damage tint out of the overlay the mesh carries, before the pack's
	 * own body runs and can read it.
	 * <p>
	 * Iris's three lines, term for term
	 * ({@code pipeline/transform/transformer/EntityPatcher.java:55-56} and {@code :62}, the fourth
	 * statement of that run being a vertex colour it hands on for its own reasons), and each is the
	 * game's rather than a choice. The image is {@code OverlayTexture}'s sixteen by sixteen, red over
	 * the top half and white with a falling alpha over the bottom, and the element is the pair
	 * {@code OverlayTexture.pack} wrote; the alpha is turned around because what the texture stores
	 * is how much of the mob shows through. The third line is a workaround Iris carries for the
	 * packs, and it stays because the packs are what this engine is written against: some read the
	 * colour without looking at the alpha and expect a black where there is no flash.
	 * <p>
	 * A {@code texelFetch} and not a sample, so nothing the bound sampler does can reach it: the
	 * coordinate is a texel of a sixteen wide image and a filter between two of them would be a
	 * flash halfway to the tint.
	 */
	private String overlayPrologue() {
		if (!this.makesOverlayColour) {
			return "";
		}

		return "vec4 " + OVERLAY_TEXEL + " = texelFetch(" + GlslTranslator.OVERLAY + ", UV1, 0); "
				+ GlslTranslator.ENTITY_COLOR + " = vec4(" + OVERLAY_TEXEL + ".rgb, 1.0 - "
				+ OVERLAY_TEXEL + ".a); "
				+ GlslTranslator.ENTITY_COLOR + ".rgb *= float(" + GlslTranslator.ENTITY_COLOR
				+ ".a != 0.0); ";
	}

	/**
	 * Hands the three identifiers on out of the element the mesh carries them on, before the pack's
	 * own body runs and can read them.
	 * <p>
	 * Iris's one line spread over three, and the difference is only that its names are components of
	 * one {@code ivec3} where these keep the spelling the pack wrote
	 * ({@code EntityPatcher.java:165-166}). Only what some stage really reads is written: what is in
	 * the union is what was declared, and writing a varying the header did not declare would not
	 * compile.
	 * <p>
	 * The lane is the position in {@link GlslTranslator#ENTITY_IDS} and the element is unsigned, so
	 * the cast is where a name the pack never mapped becomes 65535 rather than minus one. That is
	 * Iris's number as well, its own element being unsigned and its input an {@code ivec3} the
	 * driver zero extends into, and it is the number the packs are written against.
	 */
	private String identifierPrologue(Set<String> varyings) {
		if (!this.entityWrapped) {
			return "";
		}

		StringBuilder written = new StringBuilder();
		for (int lane = 0; lane < GlslTranslator.ENTITY_IDS.size(); lane++) {
			String identifier = GlslTranslator.ENTITY_IDS.get(lane);
			if (varyings.contains(identifier)) {
				written.append(identifier).append(" = int(")
						.append(EntityVertex.IDENTIFIERS).append('[').append(lane).append("]); ");
			}
		}

		return written.toString();
	}

	/** What output {@code slot} is called, which is the pack's own name when it declared one. */
	private String outputName(int slot, Set<String> shadowed) {
		Output output = this.packOutputs.get(slot);
		if (output == null) {
			return "ofFragData" + slot;
		}

		// Renamed here as well as in the body, since the declaration has moved up out of it and
		// the two halves of one name have to keep agreeing.
		return shadowed.contains(output.name()) ? "ofOwn_" + output.name() : output.name();
	}

	/**
	 * Vulkan GLSL requires a format on a storage image, unless the image is write-only. Iris's GL
	 * bind supplies the format at bind time, so Complementary writes
	 * {@code writeonly uniform uimage3D voxel_img} with none, and BSL writes
	 * {@code writeonly uniform image3D lightimg0} the same way. The format comes off the
	 * {@code image.} directive and is written here, in the header: the body declaration has
	 * already been lifted, so a layout qualifier inserted into the token stream would qualify a
	 * statement that is no longer in the shader.
	 * <p>
	 * The pack's own memory qualifiers are written back for the same reason, and they are what
	 * carries a declaration whose format we never learn: a pack switches its images off with the
	 * setting that switches off the program reading them, and then the {@code image.} lines go
	 * with it while the {@code writeonly} on the declaration stays. Dropping it turned a legal
	 * declaration into one shaderc refuses.
	 */
	private String declareOpaque(TranslatedUnit.Uniform sampler) {
		String memory = this.memoryQualifiers.getOrDefault(sampler.name(), "");
		String tail = (memory.isEmpty() ? "" : memory + " ") + "uniform " + sampler.declaration()
				+ ";";
		if (!isImageType(sampler.type())) {
			return tail;
		}

		return CustomImages.layoutFormat(sampler.name())
				.map(format -> "layout(" + format + ") " + tail)
				.orElse(tail);
	}

	private static boolean isImageType(String type) {
		return type.startsWith("image") || type.startsWith("iimage") || type.startsWith("uimage");
	}

	/**
	 * An owed varying written as an output declaration, with {@code out} where GLSL wants it: after
	 * the interpolation qualifier and before the type. Writing {@code out flat float} rather than
	 * {@code flat out float} is a syntax error, so the two cannot simply be concatenated.
	 *
	 * @param qualified the qualifier and the type, {@code flat float} or {@code vec3}
	 */
	private static String outDeclaration(String name, String qualified) {
		int type = qualified.lastIndexOf(' ');

		return type < 0
				? "out " + qualified + " " + name + ";"
				: qualified.substring(0, type) + " out " + qualified.substring(type + 1) + " " + name + ";";
	}

	/**
	 * What the stage assigns an owed varying, which is the type's zero, exactly as Iris writes it at
	 * {@code CompatibilityTransformer.java:494} out of {@code getInitializer:351-359}.
	 * <p>
	 * {@code type(0)} spells the zero of every type that can reach here. {@link LegacyGlsl#TYPE_NAMES}
	 * holds the scalars, the vectors and the matrices, and the constructor of each takes a single
	 * zero; {@code bool} and {@code bvec} are in it too and take one just as well. {@code void} is
	 * the one member no constructor answers for, and a varying cannot be declared under it.
	 */
	private static String initialiser(String name, String qualified) {
		int type = qualified.lastIndexOf(' ');

		return name + " = " + qualified.substring(type + 1) + "(0);";
	}
}
