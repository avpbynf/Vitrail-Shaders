package dev.vitrail.uniform;

import dev.vitrail.uniform.values.CameraValues;
import dev.vitrail.uniform.values.CelestialValues;
import dev.vitrail.uniform.values.DhValues;
import dev.vitrail.uniform.values.DrawValues;
import dev.vitrail.uniform.values.GeometryValues;
import dev.vitrail.uniform.values.MatrixValues;
import dev.vitrail.uniform.values.PlayerValues;
import dev.vitrail.uniform.values.ShadowGeometryValues;
import dev.vitrail.uniform.values.ShadowMatrixValues;
import dev.vitrail.uniform.values.TimeValues;
import dev.vitrail.uniform.values.WeatherValues;
import dev.vitrail.uniform.values.WorldValues;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The name to source table. Built once, statically, split across the values package.
 * <p>
 * A name that is not in the table is not an error and is not guessed at: the block writes zeroes
 * for it and says the name out loud once. That is the difference between a gap you can read in a
 * log and a wrong image nobody can explain.
 * <p>
 * The engine table is a partition, so registering a name twice is a mistake rather than an
 * intention and {@link #builder()} refuses it. A pack's own uniforms are a different matter: they
 * are layered on top with {@link #builder(UniformCatalog)}, where shadowing an engine name is
 * exactly what the layering is for.
 */
public final class UniformCatalog {

	private static volatile UniformCatalog engine;
	private static volatile UniformCatalog geometry;
	private static volatile UniformCatalog shadowGeometry;

	private final Map<String, Entry> entries;

	private UniformCatalog(Map<String, Entry> entries) {
		this.entries = entries;
	}

	private record Entry(UniformShape natural, UniformSource source) {
	}

	/**
	 * Cached. Calls the register method of every class in the values package, in a fixed order.
	 * <p>
	 * The order is fixed because it is the order a duplicate would be reported in, not because
	 * anything downstream depends on it: a block is walked in the order the translation fixed, and
	 * this table is only ever looked up by name.
	 */
	public static UniformCatalog engine() {
		UniformCatalog built = engine;
		if (built != null) {
			return built;
		}

		synchronized (UniformCatalog.class) {
			if (engine == null) {
				Builder builder = builder();
				DrawValues.register(builder);
				CameraValues.register(builder);
				MatrixValues.register(builder);
				ShadowMatrixValues.register(builder);
				DhValues.register(builder);
				CelestialValues.register(builder);
				TimeValues.register(builder);
				WeatherValues.register(builder);
				WorldValues.register(builder);
				PlayerValues.register(builder);
				coreMatrices(builder);
				engine = builder.build();
			}

			return engine;
		}
	}

	/**
	 * The engine table with the six fixed function names answered for a pass drawn over the world.
	 * <p>
	 * A layer and not a table of its own: {@link #builder(UniformCatalog)} refuses a duplicate only
	 * among the names it registers itself, so shadowing an engine name is what layering is for and
	 * registering the same name twice here would still be caught.
	 */
	public static UniformCatalog geometry() {
		UniformCatalog built = geometry;
		if (built != null) {
			return built;
		}

		synchronized (UniformCatalog.class) {
			if (geometry == null) {
				Builder builder = builder(engine());
				GeometryValues.register(builder);
				coreMatrices(builder);
				geometry = builder.build();
			}

			return geometry;
		}
	}

	/**
	 * The same again for a pass drawn from the light, where the six answer the shadow pair. Layered
	 * over {@link #geometry()} rather than over the engine table, so that the two stay one list of
	 * six names: a name added to one and forgotten in the other would leave a shadow program reading
	 * the camera.
	 */
	public static UniformCatalog shadowGeometry() {
		UniformCatalog built = shadowGeometry;
		if (built != null) {
			return built;
		}

		synchronized (UniformCatalog.class) {
			if (shadowGeometry == null) {
				Builder builder = builder(geometry());
				ShadowGeometryValues.register(builder);
				coreMatrices(builder);
				shadowGeometry = builder.build();
			}

			return shadowGeometry;
		}
	}

	/**
	 * The two names OptiFine's core profile mode gives the fixed function pair, answered from
	 * whatever that layer has just put behind the {@code gl_} spelling.
	 * <p>
	 * Called last in each of the three tables rather than written into the three values classes,
	 * because that is what keeps the two spellings the same value. It is what Iris does too, one
	 * family at a time and over two transformers picked by the profile the unit declares; the
	 * paths and their lines are set out in {@link dev.vitrail.glsl.LegacyGlsl#CORE_MATRICES}, and
	 * the packs are written against them.
	 */
	private static void coreMatrices(Builder builder) {
		builder.alias("modelViewMatrix", "of_ModelViewMatrix");
		builder.alias("projectionMatrix", "of_ProjectionMatrix");
	}

	public static Builder builder() {
		return new Builder(Map.of());
	}

	/** Starts from an existing catalogue, so a pack's own uniforms can be layered on the engine. */
	public static Builder builder(UniformCatalog base) {
		return new Builder(base.entries);
	}

	/** null when the name is not in the table. */
	public UniformSource source(String name) {
		Entry entry = this.entries.get(name);

		return entry == null ? null : entry.source();
	}

	public UniformShape natural(String name) {
		Entry entry = this.entries.get(name);

		return entry == null ? null : entry.natural();
	}

	public Set<String> names() {
		return Collections.unmodifiableSet(this.entries.keySet());
	}

	public static final class Builder {

		private final Map<String, Entry> entries;

		/** Names this builder registered itself, which is what a duplicate is measured against. */
		private final Set<String> added = new HashSet<>();

		private Builder(Map<String, Entry> base) {
			this.entries = new LinkedHashMap<>(base);
		}

		/**
		 * @param natural the shape the engine holds the value in, which is not necessarily the one
		 *                a program declares it under. The declaration decides what is written; this
		 *                only says what there is to write.
		 */
		public Builder add(String name, UniformShape natural, UniformSource source) {
			if (!this.added.add(name)) {
				throw new IllegalStateException(name + " is registered twice in the same catalogue");
			}

			this.entries.put(name, new Entry(natural, source));

			return this;
		}

		/**
		 * Registers a second spelling of a name already in this builder, reading whatever that name
		 * holds at this point.
		 * <p>
		 * Taken here and not written out again on purpose: the value of the second spelling is the
		 * value of the first, and a layer that changes one and forgets the other is exactly the
		 * failure this avoids.
		 */
		public Builder alias(String name, String existing) {
			Entry entry = this.entries.get(existing);
			if (entry == null) {
				throw new IllegalStateException(
						"Cannot make " + name + " a second name for " + existing + ", which is not registered");
			}

			return add(name, entry.natural(), entry.source());
		}

		public UniformCatalog build() {
			return new UniformCatalog(Collections.unmodifiableMap(new LinkedHashMap<>(this.entries)));
		}
	}
}
