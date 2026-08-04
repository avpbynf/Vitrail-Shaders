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
	 * are made. {@link SodiumVertex} carries the decode and says what it cannot make.
	 */
	TERRAIN,

	/**
	 * The game's own entity mesh: the six elements of {@code DefaultVertexFormat.ENTITY}, out of
	 * which the five names a pack reads are made. {@link EntityVertex} carries the renaming and
	 * says which element nothing answers for.
	 */
	ENTITY,

	/**
	 * The game's own sky meshes. Alone among these, it is not one format: {@code SkyRenderer} binds
	 * four between its eight passes, so the elements to declare come from the pass rather than from
	 * this constant. {@link SkyVertex} carries the renaming and says what the sky has not got.
	 */
	SKY;

	/**
	 * Whether this is a mesh of the engine's own, and so whether a vertex input the pack declares
	 * that the mesh has not got has to be taken out of the body and answered with a constant.
	 * <p>
	 * False for the two that are not drawn from one. A full screen quad answers for every name a
	 * composite reads with a macro, and {@link #WORLD} is measured rather than drawn, so leaving a
	 * declaration standing under either costs nothing.
	 */
	public boolean synthesizes() {
		return this == TERRAIN || this == ENTITY || this == SKY;
	}

	/**
	 * The names the head declares as vertex inputs, which the pack may therefore not use for
	 * anything of its own.
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
	 */
	public List<String> elements() {
		return switch (this) {
			case FULLSCREEN -> LegacyGlsl.FULLSCREEN_ELEMENTS;
			case TERRAIN -> SodiumVertex.ATTRIBUTES;
			case ENTITY -> EntityVertex.ATTRIBUTES;
			case SKY -> SkyVertex.ATTRIBUTES;
			case WORLD -> List.of();
		};
	}
}
