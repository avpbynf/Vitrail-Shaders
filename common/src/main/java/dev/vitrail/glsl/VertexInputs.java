package dev.vitrail.glsl;

import java.util.List;

/**
 * Where a vertex stage takes its inputs from.
 * <p>
 * A property of the pass and never of the file: the same {@code gbuffers_terrain.vsh} would read a
 * chunk mesh under one renderer and a quad under another, and the pack says nothing about it. It
 * decides one thing and it decides it silently, which is why it is an enum rather than a flag:
 * attributes are matched by name against the elements of the vertex format, and a stage that
 * declares a name the format has not got shifts the location of every name after it without a word.
 * <p>
 * <strong>Two constants may name one format and still be two contracts.</strong> The chunk mesh
 * carries two colours, Sodium's own word and the pair a pack's {@code separateAo} asks to read, and
 * which of them a stage takes its vertex colour from is settled when the stage is written and not
 * when it is drawn. That is where a vertex stage takes its inputs from, which is what this enum is
 * for: the same file under the other constant declares the same names, binds the same buffer and
 * reads a different element, and nothing downstream of the translation could tell the two apart.
 */
public enum VertexInputs {

	/** Two triangles from (0,0) to (1,1). What every composite and every {@code final} is drawn on. */
	FULLSCREEN,

	/**
	 * The fixed function attributes, declared as this stage names them. Nothing in this engine
	 * provides them yet, so a program prepared this way is measured rather than drawn.
	 */
	WORLD,

	/**
	 * Sodium's chunk mesh: four attributes in twenty bytes, out of which the six names a pack reads
	 * are made. {@link SodiumVertex} carries the decode and says what it cannot make. The vertex
	 * colour is Sodium's own {@code a_Color}, the tint with the ambient occlusion multiplied in.
	 */
	TERRAIN,

	/**
	 * The same mesh read by a pack that wrote {@code separateAo}: the vertex colour comes from
	 * {@link SodiumVertex#TINT_AND_AO} instead, which keeps the tint undivided and puts the
	 * occlusion in the alpha. Same elements, same order, same stride, so the format and the
	 * declarations are the ones {@link #TERRAIN} gets and only the line that fills the colour
	 * differs.
	 */
	TERRAIN_SEPARATE_AO,

	/**
	 * The game's own entity mesh: the seven elements of {@code DefaultVertexFormat.ENTITY}, the six
	 * the game lays out and the one this engine appends, out of which the names a pack reads are made.
	 * {@link EntityVertex} carries the renaming and says which name nothing answers for.
	 */
	ENTITY,

	/**
	 * The same mesh read by a piece the game draws at full light: {@code UV2} is bound and carries a
	 * real light map, and the two names a pack reads it under are answered with the brightest value
	 * instead. {@link EntityVertex} carries the constant.
	 * <p>
	 * A second contract over one format, which is what {@link #TERRAIN_SEPARATE_AO} is over
	 * {@link #TERRAIN}: same elements, same order, same stride, and only the two lines that fill the
	 * light map names differ. It is a property of the piece and not of the file, the one program name
	 * in this position walking the fallback tree like any other.
	 * <p>
	 * <strong>It is half of what Iris means by full light, and the other half is the sampler.</strong>
	 * The constant here is {@code transform/transformer/VanillaCoreTransformer.java:117-118}, taken
	 * where {@code gl/state/ShaderAttributeInputs.java:42} refused {@code UV2}; the white pixel bound
	 * behind {@code lightmap} is {@code samplers/IrisSamplers.java:202-206}, and
	 * {@code PackProgram.Loaded#fullbright} is what carries this answer there. A piece given one half
	 * without the other is served darker than the game would have drawn it, a pack multiplying the
	 * two.
	 */
	ENTITY_FULLBRIGHT,

	/**
	 * The game's own particle mesh: the four elements of {@code DefaultVertexFormat.PARTICLE}, out of
	 * which the names a pack reads are made. Two families bind it, the quad particles and the
	 * weather. {@link ParticleVertex} carries the renaming and says what a particle has not got.
	 */
	PARTICLE,

	/**
	 * The mesh an enchantment's glint is drawn from: the two elements of
	 * {@code DefaultVertexFormat.POSITION_TEX} and nothing else. It comes in by the entity door like
	 * the entity mesh and is not that mesh, which is the whole reason this constant exists:
	 * {@link GlintVertex} carries what the two elements answer for and what is a constant.
	 */
	GLINT,

	/**
	 * The game's own sky meshes. Alone among these, it is not one format: {@code SkyRenderer} binds
	 * four between its eight passes, so the elements to declare come from the pass rather than from
	 * this constant. {@link SkyVertex} carries the renaming and says what the sky has not got.
	 */
	SKY,

	/**
	 * The game's clouds, which are not a mesh at all: {@code CloudRenderer} binds no vertex buffer
	 * and the stage works every corner out of {@code gl_VertexID} and a texel buffer.
	 * {@link CloudVertex} carries the whole of it.
	 */
	CLOUDS;

	/**
	 * Whether a vertex input the pack declares that the bound format has not got has to be taken out
	 * of the body and answered with a constant.
	 * <p>
	 * True everywhere a real format is bound, {@link #FULLSCREEN} included, and leaving a
	 * declaration standing there costs the whole module rather than nothing: a quad carries
	 * {@code Position} and {@code UV0} and no more, and {@code IntermediaryShaderModule.rebind}
	 * refuses a stage asking for anything else. What made this look harmless is that a composite
	 * reads the fixed function names through macros, which is true and is beside the point, since
	 * what a pack declares for itself is {@code mc_midTexCoord} and its kind.
	 * <p>
	 * False for {@link #WORLD} alone, which binds nothing: it is measured rather than drawn, and its
	 * head declares the fixed function names as inputs rather than answering them.
	 */
	public boolean synthesizes() {
		return this != WORLD;
	}

	/** Whether this is Sodium's chunk mesh, under either of the two colours it may be read with. */
	public boolean terrain() {
		return this == TERRAIN || this == TERRAIN_SEPARATE_AO;
	}

	/**
	 * Whether the vertex colour comes from this engine's own element rather than from Sodium's
	 * word, which is a pack's {@code separateAo} and nothing else.
	 */
	public boolean separateAo() {
		return this == TERRAIN_SEPARATE_AO;
	}

	/**
	 * Whether the light map names are answered with the brightest value rather than with the element
	 * the mesh really carries, which is Iris's full light and is asked of the piece rather than of
	 * the mesh.
	 */
	public boolean fullbright() {
		return this == ENTITY_FULLBRIGHT;
	}

	/**
	 * Whether the bound format carries the overlay, which is the element {@code entityColor} is made
	 * out of and the one question that decides where that name comes from.
	 * <p>
	 * Iris asks the format first and then one thing more
	 * ({@code pipeline/transform/transformer/VanillaCoreTransformer.java:21-26}): the format has to
	 * carry the overlay, and the row must not be text. The second half is a question about the row's
	 * own name, {@code ShaderKey.isText} being {@code name().contains("TEXT")} at
	 * {@code pipeline/programs/ShaderKey.java:157}, carried into the attribute inputs by
	 * {@code IrisRenderingPipeline.java:675}. There is nothing to exclude here, no text family being
	 * served; the day one is, this gate has to grow the same second half.
	 * <p>
	 * What is not asked is the fallback tree, and that matters: {@code gbuffers_spidereyes} falls
	 * back through {@code gbuffers_textured}, so a rule read off the tree would answer no for a row
	 * drawn from this very mesh. The glint is the other way round, coming in by the same door with
	 * {@code POSITION_TEX} and no overlay at all.
	 * <p>
	 * The light is beside the point: {@link #ENTITY_FULLBRIGHT} is the same format under another
	 * contract, and what it changes is {@code UV2} rather than {@code UV1}.
	 */
	public boolean overlay() {
		return this == ENTITY || this == ENTITY_FULLBRIGHT;
	}

	/**
	 * The names the head declares for itself, which the pack may therefore not use for anything of
	 * its own.
	 * <p>
	 * These names are not ours to choose. {@code GlslCompiler.compile} hands {@code rebind} the
	 * element names of the format and {@code rebind} looks each one up in the SPIR-V under that
	 * name, so an input has to be spelled the way the format spells it. Where a pack already uses
	 * one of them, it is the pack's that has to move, which is what {@link ProgramTranslator} does
	 * with them. Empty for {@link #WORLD}, whose names are the translator's own and which no pack
	 * writes.
	 * <p>
	 * For {@link #SKY} this is the UNION of the four formats and not any one of them, which is the
	 * one place a union is the right answer: this list only decides which of the pack's own symbols
	 * are renamed out of the way, and renaming one the bound format happens not to carry costs
	 * nothing. What gets DECLARED is the bound format alone, and that is
	 * {@link SkyVertex#prologue}'s to know.
	 * <p>
	 * {@link #CLOUDS} names no attribute here because it binds no format, and the two names it does
	 * give are a uniform block and a texel buffer. Nothing about the renaming cares which: a pack
	 * declaring one of them for something of its own is a redefinition at file scope either way.
	 */
	public List<String> elements() {
		return switch (this) {
			case FULLSCREEN -> LegacyGlsl.FULLSCREEN_ELEMENTS;
			case TERRAIN, TERRAIN_SEPARATE_AO -> SodiumVertex.ATTRIBUTES;
			case ENTITY, ENTITY_FULLBRIGHT -> EntityVertex.ATTRIBUTES;
			case GLINT -> GlintVertex.ATTRIBUTES;
			case PARTICLE -> ParticleVertex.ATTRIBUTES;
			case SKY -> SkyVertex.ATTRIBUTES;
			case CLOUDS -> CloudVertex.ATTRIBUTES;
			case WORLD -> List.of();
		};
	}
}
