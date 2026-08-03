package dev.vitrail.pack.target;

import dev.vitrail.pack.option.OptionIndex;
import dev.vitrail.pack.option.SettingSet;
import dev.vitrail.pack.program.ProgramSet;
import dev.vitrail.pack.program.ProgramStage;
import dev.vitrail.pack.source.DimensionSet;
import dev.vitrail.pack.source.IncludeExpander;
import dev.vitrail.pack.source.ShaderPackSource;

import dev.vitrail.pack.source.IncludeExpander.ExpandedUnit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The settings a pack declares about itself, gathered from the {@code const} lines of every one of
 * its programs.
 * <p>
 * {@link ConstDirectives} reads the lines of one unit and knows no names; {@link TargetDirectives}
 * turns them into colour targets. Neither of them carries the handful of directives that are about
 * the pack as a whole rather than about a buffer: how fast the ground dries, how big the noise
 * image is, how far the shadow map reaches. Those are read here, over the whole dimension, in the
 * order Iris folds them in, and <strong>the last live declaration wins</strong>: a pack that
 * declares one value per setting has several declarations of the same name in its text and only
 * one of them means anything.
 * <p>
 * A directive that fails to parse is dropped rather than defaulted loudly, which is Iris's
 * behaviour and therefore the packs': they ship lines like {@code const float shadowDistance =
 * SHADOW_DISTANCE;} behind a setting that was never expanded.
 * <p>
 * One directive of Iris's list is missing and it is deliberate: {@code SHADOWHPL}, the older
 * spelling of {@code shadowDistance}, is a comment directive rather than a {@code const} one and
 * needs a second grammar. No pack of the corpus writes it.
 */
public final class PackDirectives {

	private final float sunPathRotation;
	private final float ambientOcclusionLevel;
	private final float wetnessHalflife;
	private final float eyeBrightnessHalflife;
	private final float centerDepthHalflife;
	private final int noiseTextureResolution;
	private final float shadowDistance;
	private final float shadowNearPlane;
	private final float shadowFarPlane;
	private final float shadowIntervalSize;

	private PackDirectives(Builder builder) {
		this.sunPathRotation = builder.sunPathRotation;
		this.ambientOcclusionLevel = builder.ambientOcclusionLevel;
		this.wetnessHalflife = builder.wetnessHalflife;
		this.eyeBrightnessHalflife = builder.eyeBrightnessHalflife;
		this.centerDepthHalflife = builder.centerDepthHalflife;
		this.noiseTextureResolution = builder.noiseTextureResolution;
		this.shadowDistance = builder.shadowDistance;
		this.shadowNearPlane = builder.shadowNearPlane;
		this.shadowFarPlane = builder.shadowFarPlane;
		this.shadowIntervalSize = builder.shadowIntervalSize;
	}

	/** What a pack that declares nothing gets, which is what most of the corpus gets. */
	public static PackDirectives defaults() {
		return builder().build();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Reads every fragment entry point of one dimension. A dimension directory replaces the root
	 * rather than being layered over it, exactly as {@link TargetPlan} reads it, so a pack that
	 * ships nothing under world0 is read from the root and not from both.
	 */
	public static PackDirectives read(ShaderPackSource source, OptionIndex options,
			SettingSet settings, String dimension) throws IOException {
		ProgramSet programs = ProgramSet.enumerate(source, DimensionSet.discover(source));
		List<ProgramSet.ProgramKey> here = fragmentsOf(programs, dimension);
		List<ProgramSet.ProgramKey> entries = here.isEmpty()
				? fragmentsOf(programs, ProgramSet.ROOT)
				: here;

		IncludeExpander expander = new IncludeExpander(source, options, settings);
		Builder builder = builder();

		for (ProgramSet.ProgramKey key : sorted(entries)) {
			Optional<Path> file = source.file(key.file());
			if (file.isEmpty()) {
				continue;
			}

			try {
				builder.accept(expander.expand(file.get()));
			} catch (IOException | RuntimeException e) {
				// One unreadable composite must not cost the pack every other setting it declares.
			}
		}

		return builder.build();
	}

	public float sunPathRotation() {
		return this.sunPathRotation;
	}

	public float ambientOcclusionLevel() {
		return this.ambientOcclusionLevel;
	}

	/** The rise of {@code wetness}, in deciseconds. Both halflife directives land here. */
	public float wetnessHalflife() {
		return this.wetnessHalflife;
	}

	/**
	 * The fall of {@code wetness}, which is always 200 deciseconds.
	 * <p>
	 * Iris holds it in a final field that neither directive reaches: both {@code wetnessHalflife}
	 * and {@code drynessHalflife} are registered against the rise, so no pack can change the fall,
	 * and packs are written against that. Reading the pack's own {@code drynessHalflife} here
	 * instead would be more sensible and would make the ground dry several times too fast on the
	 * three packs of the corpus that declare both.
	 */
	public float drynessHalflife() {
		return Builder.DRYNESS_HALFLIFE;
	}

	public float eyeBrightnessHalflife() {
		return this.eyeBrightnessHalflife;
	}

	public float centerDepthHalflife() {
		return this.centerDepthHalflife;
	}

	public int noiseTextureResolution() {
		return this.noiseTextureResolution;
	}

	public float shadowDistance() {
		return this.shadowDistance;
	}

	public float shadowNearPlane() {
		return this.shadowNearPlane;
	}

	public float shadowFarPlane() {
		return this.shadowFarPlane;
	}

	public float shadowIntervalSize() {
		return this.shadowIntervalSize;
	}

	private static List<ProgramSet.ProgramKey> fragmentsOf(ProgramSet programs, String place) {
		return programs.keys().stream()
				.filter(key -> key.stage() == ProgramStage.FRAGMENT)
				.filter(key -> key.dimension().equals(place))
				.toList();
	}

	private static List<ProgramSet.ProgramKey> sorted(List<ProgramSet.ProgramKey> entries) {
		return entries.stream()
				.sorted(Comparator
						.comparingInt((ProgramSet.ProgramKey key) -> rank(key.name().family()))
						.thenComparingInt(key -> key.name().slot())
						.thenComparing(ProgramSet.ProgramKey::file))
				.toList();
	}

	/** The order Iris folds directives in, the same one {@link TargetPlan} uses. */
	private static int rank(String family) {
		return switch (family) {
			case "shadowcomp" -> 0;
			case "begin" -> 1;
			case "prepare" -> 2;
			case "deferred" -> 4;
			case "composite" -> 5;
			default -> 3;
		};
	}

	public static final class Builder {

		private static final float DRYNESS_HALFLIFE = 200.0F;

		private float sunPathRotation;
		private float ambientOcclusionLevel = 1.0F;
		private float wetnessHalflife = 600.0F;
		private float eyeBrightnessHalflife = 10.0F;
		private float centerDepthHalflife = 1.0F;
		private int noiseTextureResolution = 256;
		private float shadowDistance = 160.0F;
		private float shadowNearPlane = -100.05F;
		private float shadowFarPlane = 156.0F;
		private float shadowIntervalSize = 2.0F;

		private Builder() {
		}

		/** Folds one program's live declarations in. Call order is the precedence order. */
		public Builder accept(ExpandedUnit unit) {
			for (ConstDirectives.Directive directive : ConstDirectives.read(unit)) {
				apply(directive);
			}

			return this;
		}

		public PackDirectives build() {
			return new PackDirectives(this);
		}

		private void apply(ConstDirectives.Directive directive) {
			switch (directive.name()) {
				case "sunPathRotation" -> asFloat(directive, value -> this.sunPathRotation = value);
				case "ambientOcclusionLevel" -> asFloat(directive,
						value -> this.ambientOcclusionLevel = Math.clamp(value, 0.0F, 1.0F));
				case "wetnessHalflife" -> asFloat(directive, value -> this.wetnessHalflife = value);
				// Not a typo and not ours. Iris registers the dryness directive against the rise as
				// well, so the two names are one setting and the last one read wins. Reproduced
				// rather than fixed, because correcting it would make our image diverge from the
				// reference the packs were tuned against.
				case "drynessHalflife" -> asFloat(directive, value -> this.wetnessHalflife = value);
				case "eyeBrightnessHalflife" -> asFloat(directive, value -> this.eyeBrightnessHalflife = value);
				case "centerDepthHalflife" -> asFloat(directive, value -> this.centerDepthHalflife = value);
				case "noiseTextureResolution" -> asInt(directive, value -> this.noiseTextureResolution = value);
				case "shadowDistance" -> asFloat(directive, value -> this.shadowDistance = value);
				case "shadowNearPlane" -> asFloat(directive, value -> this.shadowNearPlane = value);
				case "shadowFarPlane" -> asFloat(directive, value -> this.shadowFarPlane = value);
				case "shadowIntervalSize" -> asFloat(directive, value -> this.shadowIntervalSize = value);
				default -> {
				}
			}
		}

		private static void asFloat(ConstDirectives.Directive directive, FloatSetter setter) {
			if (!directive.type().equals("float")) {
				return;
			}

			// A pack writes 2.0f as often as 2.0, and Float.parseFloat takes both; what it also
			// takes is a bare name, which is why the type is checked first and the failure is
			// silent afterwards.
			try {
				setter.set(Float.parseFloat(directive.value().trim().toLowerCase(Locale.ROOT)));
			} catch (NumberFormatException e) {
				// A directive left standing behind a setting nobody expanded. Iris drops it too.
			}
		}

		private static void asInt(ConstDirectives.Directive directive, IntSetter setter) {
			if (!directive.type().equals("int")) {
				return;
			}

			try {
				setter.set(Integer.parseInt(directive.value().trim()));
			} catch (NumberFormatException e) {
				// As above.
			}
		}

		@FunctionalInterface
		private interface FloatSetter {
			void set(float value);
		}

		@FunctionalInterface
		private interface IntSetter {
			void set(int value);
		}
	}
}
