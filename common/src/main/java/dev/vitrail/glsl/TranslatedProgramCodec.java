package dev.vitrail.glsl;

import dev.vitrail.pack.program.ProgramStage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A translated program written down and read back, byte for byte the same object either way.
 * <p>
 * It exists so that {@link TranslationCache} can keep a translation between two loads. Everything a
 * {@link ProgramTranslator.TranslatedProgram} carries is written: the text of each stage, the notes
 * the translation took on the way, the uniform block in the order that IS its layout, the samplers
 * in the order they bind, and the names the translator synthesised. Leaving one of them out would
 * not fail: it would hand back a program that draws with a block laid out differently from the one
 * the engine fills, which is a wrong picture and not a crash.
 * <p>
 * <strong>Every string is written behind its length in bytes</strong> rather than through
 * {@code writeUTF}, whose count is a short: a translated composite runs to tens of thousands of
 * characters, and every one of them would be refused at the write.
 * <p>
 * Nothing here validates. A file that does not read back is caught by the digest the cache checks
 * before this is ever called, and anything this throws is a miss.
 */
final class TranslatedProgramCodec {

	/** Bumped by hand when the layout changes. It is part of the key, so old blobs go unread. */
	static final String FORMAT = "vitrail-translation-1";

	private TranslatedProgramCodec() {
	}

	static byte[] write(ProgramTranslator.TranslatedProgram program) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (DataOutputStream out = new DataOutputStream(bytes)) {
			text(out, program.inputs().name());

			out.writeInt(program.stages().size());
			for (Map.Entry<ProgramStage, TranslatedUnit> stage : program.stages().entrySet()) {
				text(out, stage.getKey().name());
				unit(out, stage.getValue());
			}

			uniforms(out, program.uniforms());
			uniforms(out, program.samplers());

			out.writeInt(program.synthesized().size());
			for (Map.Entry<String, String> made : program.synthesized().entrySet()) {
				text(out, made.getKey());
				text(out, made.getValue());
			}
		}

		return bytes.toByteArray();
	}

	/**
	 * The program a blob describes.
	 * <p>
	 * The vertex inputs are read back and checked against the ones the caller asked for rather than
	 * trusted: they are in the key, so a disagreement means the key and the blob have come apart,
	 * and a program built for another format declares other attribute names.
	 */
	static ProgramTranslator.TranslatedProgram read(byte[] raw, int length, VertexInputs inputs)
			throws IOException {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw, 0, length))) {
			String written = text(in);
			if (!written.equals(inputs.name())) {
				throw new IOException("a stored translation was made for " + written);
			}

			Map<ProgramStage, TranslatedUnit> stages = new LinkedHashMap<>();
			for (int left = in.readInt(); left > 0; left--) {
				stages.put(ProgramStage.valueOf(text(in)), unit(in));
			}

			List<TranslatedUnit.Uniform> uniforms = uniforms(in);
			List<TranslatedUnit.Uniform> samplers = uniforms(in);

			Map<String, String> synthesized = new LinkedHashMap<>();
			for (int left = in.readInt(); left > 0; left--) {
				synthesized.put(text(in), text(in));
			}

			return new ProgramTranslator.TranslatedProgram(Map.copyOf(stages), uniforms, samplers,
					Map.copyOf(synthesized), inputs);
		} catch (IllegalArgumentException | NullPointerException e) {
			// A stage name no enum has, or a null where the format promised a string. Both are a
			// damaged blob and neither is worth its own catch upstream.
			throw new IOException(e);
		}
	}

	private static void unit(DataOutputStream out, TranslatedUnit unit) throws IOException {
		text(out, unit.entry());
		text(out, unit.stage().name());
		text(out, unit.text());
		notes(out, unit.notes());

		out.writeInt(unit.drawBuffers().size());
		for (int buffer : unit.drawBuffers()) {
			out.writeInt(buffer);
		}

		uniforms(out, unit.blockMembers());
		uniforms(out, unit.samplers());
	}

	private static TranslatedUnit unit(DataInputStream in) throws IOException {
		String entry = text(in);
		ProgramStage stage = ProgramStage.valueOf(text(in));
		String body = text(in);
		TranslatedUnit.Notes notes = notes(in);

		List<Integer> drawBuffers = new ArrayList<>();
		for (int left = in.readInt(); left > 0; left--) {
			drawBuffers.add(in.readInt());
		}

		return new TranslatedUnit(entry, stage, body, notes, List.copyOf(drawBuffers),
				uniforms(in), uniforms(in));
	}

	private static void notes(DataOutputStream out, TranslatedUnit.Notes notes) throws IOException {
		out.writeInt(notes.fragmentOutputs());
		out.writeInt(notes.dynamicFragData());
		out.writeInt(notes.uniformConflicts());
		out.writeInt(notes.shadowCalls());
		out.writeInt(notes.unwrappedShadow());
		out.writeInt(notes.strippedExtensions());
		out.writeInt(notes.depthEpilogue());
		out.writeInt(notes.alphaEpilogue());
		out.writeInt(notes.coverage());
		out.writeInt(notes.depthLookups());
		out.writeInt(notes.parameterLookups());
		out.writeInt(notes.fragCoordZ());
		out.writeInt(notes.fragCoordXyz());
		out.writeInt(notes.fragCoordUnhandled());
		out.writeInt(notes.fragDepthWrites());
		out.writeInt(notes.fragDepthUnhandled());
		names(out, notes.conflictNames());
		names(out, notes.comparedSamplers());
		names(out, notes.hardwareCompared());
		names(out, notes.storageBlocks());
		out.writeInt(notes.volumeLookups());
		out.writeInt(notes.volumesLeftAlone());
		out.writeInt(notes.trigCalls());
		out.writeInt(notes.gameTextureMatrix());
		out.writeInt(notes.gameModelView());
	}

	private static TranslatedUnit.Notes notes(DataInputStream in) throws IOException {
		return new TranslatedUnit.Notes(in.readInt(), in.readInt(), in.readInt(), in.readInt(),
				in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
				in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(),
				names(in), names(in), names(in), names(in),
				in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
	}

	private static void uniforms(DataOutputStream out, List<TranslatedUnit.Uniform> uniforms)
			throws IOException {
		out.writeInt(uniforms.size());
		for (TranslatedUnit.Uniform uniform : uniforms) {
			text(out, uniform.name());
			text(out, uniform.type());
			text(out, uniform.declaration());
		}
	}

	private static List<TranslatedUnit.Uniform> uniforms(DataInputStream in) throws IOException {
		List<TranslatedUnit.Uniform> uniforms = new ArrayList<>();
		for (int left = in.readInt(); left > 0; left--) {
			uniforms.add(new TranslatedUnit.Uniform(text(in), text(in), text(in)));
		}

		return List.copyOf(uniforms);
	}

	private static void names(DataOutputStream out, List<String> names) throws IOException {
		out.writeInt(names.size());
		for (String name : names) {
			text(out, name);
		}
	}

	private static List<String> names(DataInputStream in) throws IOException {
		List<String> names = new ArrayList<>();
		for (int left = in.readInt(); left > 0; left--) {
			names.add(text(in));
		}

		return List.copyOf(names);
	}

	private static void text(DataOutputStream out, String text) throws IOException {
		byte[] raw = text.getBytes(StandardCharsets.UTF_8);
		out.writeInt(raw.length);
		out.write(raw);
	}

	/**
	 * A string, bounded by what is left in the blob before the array is asked for.
	 * <p>
	 * {@code readFully} would refuse a length past the end anyway, but only after the array has
	 * been allocated, and a damaged word read as two gigabytes takes the process down before it can
	 * be refused. The stream is over a byte array, so what is left is exact and free to ask for.
	 */
	private static String text(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > in.available()) {
			throw new IOException("a stored translation claims a string of " + length + " bytes");
		}

		byte[] raw = new byte[length];
		in.readFully(raw);

		return new String(raw, StandardCharsets.UTF_8);
	}
}
