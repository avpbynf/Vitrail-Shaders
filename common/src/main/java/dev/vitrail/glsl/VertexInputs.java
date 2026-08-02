package dev.vitrail.glsl;

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
	TERRAIN
}
