package dev.vitrail.cache;

import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import dev.vitrail.mixin.game.IntermediaryShaderModuleAccessor;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.RawLocals;
import dev.vitrail.render.SamplerReach;
import dev.vitrail.Vitrail;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Keeps the whole of what the game's compiler makes of each shader unit, on disk, so a second load
 * of the same pack pays for neither the compile nor the reading of what came out of it.
 * <p>
 * Loading a pack costs seconds, and a clock around the two halves of that number said where they
 * go: shaderc turning GLSL into SPIR-V, and the SPIRV-Cross reflection walking the result to find
 * out what it binds. Both are pure functions of the same input, so both are cached by that input,
 * and a served unit costs a file read and no native work at all. Caching only the first half was
 * built and measured first, and it left the second half standing, which is why the reflection is
 * stored beside the bytes it read rather than replayed over them.
 * <p>
 * <strong>The key IS the input, hashed</strong>, and nothing else: the exact text handed to the
 * compiler, the stage it is compiled for, and everything that decides what that text turns into,
 * which is the mod's version and, on a development build, the commit behind it, the game's, the
 * loader with its own version, and the LWJGL build whose bundled shaderc and SPIRV-Cross do the
 * work. Nothing is keyed on a pack name, a file path or the debug name the module carries, so
 * there is no invalidation to get wrong and none is written. An edited shader, a moved pack
 * setting, a translator that emits one word differently, a loader that patches the compiler: each
 * is a different key, and the blob under the old one is never asked for again.
 * <p>
 * <strong>The debug name stays out of the key on purpose.</strong> The game's pipeline cache is
 * keyed on the identifier and never on the text, so this engine puts the load number in that name
 * ({@code pack/<load>/...}): two chains must not share an identifier when their GLSL differs
 * ({@code docs/internals/game-graphics-api.md}). The disk key already carries the text, so hashing
 * the load number as well would make every Apply, every R and every portal a miss of identical
 * GLSL. F3+T does not bump the load and already hit; a pack reload now hits too. The live name is
 * still handed to {@link #lookup} so the rebuilt module carries the identifier this chain's
 * pipelines will ask for. Two texts colliding under one blob would need a SHA-256 collision of
 * the source, which is not the trap the load number exists to prevent.
 * <p>
 * <strong>One bit of the name is keyed all the same</strong>, because it changes what is stored:
 * whether the unit is this engine's, which is what {@code RawLocals.ours} reads off the name. Only
 * such a unit is given its zeroes and has its names stripped, and only such a unit has the
 * samplers its entry point never reaches dropped from its table, so one text compiled under a name
 * of ours and under the game's comes out as two different modules. The bit goes in and the name,
 * load number and all, stays out.
 * <p>
 * <strong>What is stored is the module as its maker handed it over</strong>, at the one instant at
 * which it is finished and nothing has yet bent it to a pipeline: the bytes, the uniform buffers
 * and samplers the reflection found, the inputs and outputs it numbered, and the storage images and
 * buffers this engine appends behind them because the game never asks for those. {@code rebind}
 * comes later and rewrites the bytes for one pipeline's bindings, so a module is stored before any
 * caller has had it, and every hit is an allocation of its own.
 * <p>
 * <strong>The same digest names the unit in memory.</strong> {@link ModuleShare} keeps what one pack
 * load has made under it, so a second program with the same text is handed a copy of the first one's
 * unit instead of a second compile or a second file read; {@link #keep} is what fills both places
 * and {@link #shared} reads the first. It is made without a disk when there is none
 * ({@link #shareKeyOf}), so a load with this cache switched off shares all the same.
 * <p>
 * Everything from the digest that vouches for a file to the ceiling and the folder of each
 * edition is the same on both games and is written in {@link ModuleStore}.
 */
public final class ModuleCache {

	/**
	 * How many entries of one kind a stored module may claim before the file is read as damaged
	 * rather than as a module. Far above what any pack reaches, and low enough that a wild count
	 * cannot ask for an allocation that the digest was going to refuse a moment later anyway.
	 */
	private static final int MOST_ENTRIES = 65_536;

	/**
	 * How large the store may grow, in mebibytes, offered on the Sodium page. Half a gigabyte is
	 * what this class shipped as a constant; the slider keeps that as its untouched value.
	 */
	public static final int MIN_CEILING_MIB = ModuleStore.MIN_CEILING_MIB;
	public static final int MAX_CEILING_MIB = ModuleStore.MAX_CEILING_MIB;
	public static final int DEFAULT_CEILING_MIB = ModuleStore.DEFAULT_CEILING_MIB;
	public static final int CEILING_STEP_MIB = ModuleStore.CEILING_STEP_MIB;

	/**
	 * Bumped by hand when the layout of a file changes rather than its content, or when what the
	 * tables in it hold does. Every blob written under an older one is then unreachable, and the
	 * sweep is what eventually collects it. Two is where the uniform buffer table first carries the
	 * storage blocks: every blob before it lists none, a hit skips the reflection that would add
	 * them, and a pipeline built off such a blob leaves every storage block on the binding its pack
	 * wrote. Three is where the SPIR-V itself first carries the zeroes of {@link RawLocals}: a
	 * blob before it holds the compiler's bare variables, and a hit on one would draw the black
	 * faces the pass exists to take away, with nothing in the log to say why. What the pass emits
	 * for a module can move without this layout moving, so the pass carries a version of its own
	 * that {@link #keyOf} hashes beside this.
	 */
	private static final String FORMAT = "vitrail-module-3";

	private ModuleCache() {
	}

	/**
	 * How large the store may grow, in mebibytes, which is what the Sodium slider reads. An
	 * absent or unreadable file is {@link #DEFAULT_CEILING_MIB}.
	 */
	public static int ceilingMib() {
		return ModuleStore.ceilingMib();
	}

	/**
	 * Writes the ceiling and keeps the live answer, so the next store sees it. A store already
	 * over the new number is swept at once: no pack reload and no restart.
	 */
	public static void setCeilingMib(int mib) {
		ModuleStore.setCeilingMib(mib);
	}

	/**
	 * What names this unit on disk, or null when there is nowhere to look and nowhere to write.
	 * <p>
	 * Worked out once by the caller and handed to both ends, because the source of a composite runs
	 * to hundreds of kilobytes and hashing it twice to answer one question is work for nothing.
	 * <p>
	 * The debug name is not an argument. It used to be, and a pack reload then missed every unit
	 * whose GLSL had not moved, because the name carries the load number ({@code pack/<load>/...})
	 * and {@code PackChain} increments that number on every new chain. The file layout did not
	 * change, so the format token stays; old blobs under the names-in-the-key hashes sit until
	 * the sweep collects them.
	 *
	 * @param source the text the compiler was handed, the pipeline's defines already injected
	 * @param stage  vertex, fragment, or the compute recipe token, which decides the whole compile
	 * @param ours   whether {@code RawLocals.ours} claims the unit's debug name, which decides the
	 *               passes run over what the compiler made and so what is stored
	 */
	public static @Nullable String keyOf(String source, String stage, boolean ours) {
		if (ModuleStore.directory() == null || !ModuleShape.available()) {
			return null;
		}

		return digestOf(source, stage, ours);
	}

	/**
	 * What names this unit in {@link ModuleShare}: the same digest as {@link #keyOf}, made whether or
	 * not there is a disk to keep it on, so a load with the cache switched off shares its units all
	 * the same. Null only where a unit cannot be rebuilt from what is kept, which is a build that did
	 * not find the game's module records.
	 * <p>
	 * Asked only where {@link #keyOf} answered null: with a disk cache the one digest names the unit
	 * in both places, and hashing a composite's hundreds of kilobytes twice is work for nothing.
	 */
	public static @Nullable String shareKeyOf(String source, String stage, boolean ours) {
		return ModuleShape.available() ? digestOf(source, stage, ours) : null;
	}

	private static String digestOf(String source, String stage, boolean ours) {
		MessageDigest digest = ModuleStore.keyStart(FORMAT);
		// The last of those switches, and the one 26.3 leaves out of its key: the samplers it drops from
		// a table are stored here, where 26.3 applies it to the words a served unit already is.
		ModuleStore.feed(digest, SamplerReach.cacheWord());
		// Whose unit this is: a unit of the game's that shares its text with one of ours is walked
		// by none of those passes, so the same text is two modules.
		ModuleStore.feed(digest, ours ? "ours" : "theirs");
		ModuleStore.feed(digest, stage);
		ModuleStore.feed(digest, source);

		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * The key of a unit of this engine's own compute road, which is this engine's by construction:
	 * {@code PackCompute} labels every unit it builds {@code pack/<load>/...}, and the stage token
	 * that road keys under is its own, so no unit of the game's can share it.
	 *
	 * @param source the text shaderc is to read
	 * @param stage  the stage token the compute road keys under
	 */
	public static @Nullable String keyOf(String source, String stage) {
		return keyOf(source, stage, true);
	}

	/**
	 * The module the compiler would have built for this unit, or null when it has to build one.
	 * <p>
	 * A hit costs a file read, one allocation and a handful of small records; nothing native runs.
	 * What comes back is the caller's exactly as a compiled module is, freed by the same
	 * {@code close}, and holding bytes of its own so that the {@code rebind} that follows rewrites
	 * nobody else's.
	 */
	public static @Nullable IntermediaryShaderModule lookup(@Nullable String key, String filename) {
		ModuleStore.Hit hit = ModuleStore.read(key);
		if (hit == null) {
			return null;
		}

		IntermediaryShaderModule module = rebuild(filename, hit.raw(), hit.length());
		if (module == null) {
			return null;
		}

		ModuleStore.served(hit.file());

		return module;
	}

	/**
	 * The module an earlier asker of this load made of the unit, built afresh around a copy of it, or
	 * null when nobody has made it yet.
	 * <p>
	 * A hit costs one allocation and a handful of small records, nothing from disk and nothing
	 * native. What comes back is the caller's exactly as a served or compiled module is, carrying
	 * {@code filename} as its name and holding bytes of its own, so the {@code rebind} that follows
	 * rewrites nobody else's.
	 *
	 * @param unit the key {@link ModuleShare} files the unit under
	 */
	public static @Nullable IntermediaryShaderModule shared(@Nullable String unit, String filename) {
		ModuleShare.Blob blob = ModuleShare.load().find(unit);
		if (blob == null) {
			return null;
		}

		IntermediaryShaderModule module = rebuild(filename, blob.raw(), blob.length());
		if (module != null) {
			ModuleStore.shared();
		}

		return module;
	}

	/**
	 * Builds the module a stored file describes, or null when this build cannot.
	 * <p>
	 * The buffer is allocated where the game's own is, in native memory, because what frees it is
	 * the game's own {@code close} and that is a {@code memFree}. The game reaches for
	 * {@code memCalloc} and this reaches for {@code memAlloc}, which is the same allocator and one
	 * less pass over bytes that are all written anyway. It is freed here, and only here, when the
	 * build gives up part way through: nothing else has been handed it yet.
	 */
	private static @Nullable IntermediaryShaderModule rebuild(String filename, byte[] raw,
			int length) {
		ByteBuffer spirv = null;
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw, 0, length))) {
			byte[] words = ModuleStore.readWords(in, length);

			List<Object> uniformBuffers = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				uniformBuffers.add(ModuleShape.uniformBuffer(in.readUTF(), in.readInt()));
			}

			List<Object> samplers = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				samplers.add(ModuleShape.sampler(in.readUTF(), in.readInt(), in.readInt()));
			}

			List<Object> outputs = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				outputs.add(ModuleShape.variable(in.readUTF(), in.readInt()));
			}

			List<Object> inputs = new ArrayList<>();
			for (int left = count(in); left > 0; left--) {
				inputs.add(ModuleShape.variable(in.readUTF(), in.readInt()));
			}

			spirv = MemoryUtil.memAlloc(words.length);
			spirv.put(words);
			spirv.flip();

			return ModuleShape.module(filename, spirv, uniformBuffers, samplers, outputs, inputs);
		} catch (IOException | ReflectiveOperationException | RuntimeException
				| OutOfMemoryError e) {
			// The Error is in the list on purpose. Everything else in here is a miss, and a length
			// this could not allocate for would otherwise be the one shape of damaged file that
			// takes the pack load down instead.
			if (spirv != null) {
				MemoryUtil.memFree(spirv);
			}

			ModuleStore.sayAboutReading("a stored module could not be rebuilt (" + e + ")");

			return null;
		}
	}

	private static int count(DataInputStream in) throws IOException {
		int size = in.readInt();
		if (size < 0 || size > MOST_ENTRIES) {
			throw new IOException("a stored module claims " + size + " entries of one kind");
		}

		return size;
	}

	/**
	 * Counts a unit the compiler is about to build, said BEFORE it builds it, and names it for the
	 * line at the end of the load.
	 *
	 * @param filename the debug name the compile was given, which says whose unit it is
	 */
	public static void building(String filename) {
		ModuleStore.building(filename);
	}

	/**
	 * Keeps the module the compiler has just built, under the key of the text it was built from.
	 * <p>
	 * Called with the module the caller is about to receive and before anything has been done to
	 * it, which is the one instant at which it is both finished and untouched.
	 */
	public static void store(@Nullable String key, IntermediaryShaderModule module) {
		if (key == null || ModuleStore.directory() == null || module.spirv() == null) {
			return;
		}

		keep(key, key, module);
	}

	/**
	 * Keeps a module the compiler has just built, or a served one, where the rest of this load can
	 * have a copy of it and on disk where there is a disk, from one description of it.
	 * <p>
	 * The same instant as {@link #store} takes: the module is finished and nothing has bent it to a
	 * pipeline, so both places hold it as its maker handed it over.
	 *
	 * @param unit the key {@link ModuleShare} files it under, or null for a unit that is not to be
	 *             shared, which is every unit of the game's own
	 * @param key  the key of its file, or null where there is nowhere to write one
	 */
	public static void keep(@Nullable String unit, @Nullable String key,
			IntermediaryShaderModule module) {
		if ((unit == null && key == null) || module.spirv() == null) {
			return;
		}

		byte[] raw;
		try {
			raw = describe(module);
		} catch (IOException | ReflectiveOperationException | RuntimeException e) {
			ModuleStore.sayAboutStoring("a module could not be written down (" + e + ")");

			return;
		}

		if (unit != null) {
			ModuleShare.load().offer(unit, new ModuleShare.Blob(raw, raw.length));
		}

		Path root = ModuleStore.directory();
		if (key != null && root != null) {
			ModuleStore.keep(root, key, raw);
		}
	}

	/** Everything a module is, in the order {@link #rebuild} reads it back. */
	private static byte[] describe(IntermediaryShaderModule module)
			throws IOException, ReflectiveOperationException {
		IntermediaryShaderModuleAccessor access = (IntermediaryShaderModuleAccessor) (Object) module;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try (DataOutputStream out = new DataOutputStream(bytes)) {
			// A view of its own, so the caller's position and limit are left where they were.
			ByteBuffer view = module.spirv().duplicate();
			byte[] words = new byte[view.remaining()];
			view.get(words);
			out.writeInt(words.length);
			out.write(words);

			List<?> uniformBuffers = access.vitrail$uniformBuffers();
			out.writeInt(uniformBuffers.size());
			for (Object buffer : uniformBuffers) {
				out.writeUTF(ModuleShape.uniformBufferName(buffer));
				out.writeInt(ModuleShape.uniformBufferBinding(buffer));
			}

			List<?> samplers = access.vitrail$samplers();
			out.writeInt(samplers.size());
			for (Object sampler : samplers) {
				out.writeUTF(ModuleShape.samplerName(sampler));
				out.writeInt(ModuleShape.samplerBinding(sampler));
				out.writeInt(ModuleShape.samplerDimensions(sampler));
			}

			writeVariables(out, access.vitrail$outputs());
			writeVariables(out, access.vitrail$inputs());
		}

		return bytes.toByteArray();
	}

	private static void writeVariables(DataOutputStream out, List<?> variables)
			throws IOException, ReflectiveOperationException {
		out.writeInt(variables.size());
		for (Object variable : variables) {
			out.writeUTF(ModuleShape.variableName(variable));
			out.writeInt(ModuleShape.variableLocation(variable));
		}
	}

	/**
	 * One line for the load that has just finished, in both directions and whatever happened; called
	 * at every client tick and silent until the compiler has been quiet long enough for a load to be
	 * over. {@link ModuleStore#say} says what it prints.
	 */
	public static void say() {
		ModuleStore.say("compiled and reflected");
	}

	/**
	 * Deletes what another edition left, but for the one neighbour a build carrying a commit spares.
	 * {@link ModuleStore#dropOtherEditions} says which and why.
	 */
	static String dropOtherEditions(Path root, Path mine, String family) {
		return ModuleStore.dropOtherEditions(root, mine, family);
	}

	/**
	 * The three record types a module is made of, reached by reflection because they are package
	 * private and this package is not theirs.
	 * <p>
	 * Naming them is what a mixin accessor cannot do either, which is why the lists it hands back
	 * are raw: an interface of ours declaring {@code List<SpvSampler>} would not compile, and a
	 * class of ours in their package would not boot. So an entry is read and written one component
	 * at a time, and the module's own canonical constructor is called the same way, its parameter
	 * types being those three.
	 * <p>
	 * <strong>A build that cannot find them serves nothing and stores nothing</strong>, rather than
	 * failing at the first pack: a game update that moves one of these leaves the cache silent and
	 * the compiler doing exactly what it did before.
	 */
	private static final class ModuleShape {

		private static final @Nullable Constructor<?> UNIFORM_BUFFER;
		private static final @Nullable Constructor<?> SAMPLER;
		private static final @Nullable Constructor<?> VARIABLE;
		private static final @Nullable Constructor<?> MODULE;
		private static final @Nullable Method UNIFORM_BUFFER_NAME;
		private static final @Nullable Method UNIFORM_BUFFER_BINDING;
		private static final @Nullable Method SAMPLER_NAME;
		private static final @Nullable Method SAMPLER_BINDING;
		private static final @Nullable Method SAMPLER_DIMENSIONS;
		private static final @Nullable Method VARIABLE_NAME;
		private static final @Nullable Method VARIABLE_LOCATION;

		static {
			Shape shape;
			try {
				shape = find();
			} catch (ReflectiveOperationException | RuntimeException e) {
				Vitrail.logger().warn("No module cache this run, because a shader module is not the "
						+ "shape this build expects: {}", e.toString());
				shape = new Shape(null, null, null, null, null, null, null, null, null, null, null);
			}

			UNIFORM_BUFFER = shape.uniformBuffer();
			SAMPLER = shape.sampler();
			VARIABLE = shape.variable();
			MODULE = shape.module();
			UNIFORM_BUFFER_NAME = shape.uniformBufferName();
			UNIFORM_BUFFER_BINDING = shape.uniformBufferBinding();
			SAMPLER_NAME = shape.samplerName();
			SAMPLER_BINDING = shape.samplerBinding();
			SAMPLER_DIMENSIONS = shape.samplerDimensions();
			VARIABLE_NAME = shape.variableName();
			VARIABLE_LOCATION = shape.variableLocation();
		}

		private ModuleShape() {
		}

		/** Everything reached in one go, so that a half found shape can never be a usable one. */
		private record Shape(@Nullable Constructor<?> uniformBuffer, @Nullable Constructor<?> sampler,
				@Nullable Constructor<?> variable, @Nullable Constructor<?> module,
				@Nullable Method uniformBufferName, @Nullable Method uniformBufferBinding,
				@Nullable Method samplerName, @Nullable Method samplerBinding,
				@Nullable Method samplerDimensions, @Nullable Method variableName,
				@Nullable Method variableLocation) {
		}

		private static Shape find() throws ReflectiveOperationException {
			Class<?> buffers = Class.forName("com.mojang.blaze3d.vulkan.glsl.SpvUniformBuffer");
			Class<?> samplers = Class.forName("com.mojang.blaze3d.vulkan.glsl.SpvSampler");
			Class<?> variables = Class.forName("com.mojang.blaze3d.vulkan.glsl.SpvVariable");

			return new Shape(
					make(buffers, String.class, int.class),
					make(samplers, String.class, int.class, int.class),
					make(variables, String.class, int.class),
					make(IntermediaryShaderModule.class, String.class, ByteBuffer.class, List.class,
							List.class, List.class, List.class),
					open(buffers, "name"), open(buffers, "bindingOffset"),
					open(samplers, "name"), open(samplers, "bindingOffset"),
					open(samplers, "dimensions"),
					open(variables, "name"), open(variables, "locationOffset"));
		}

		private static Constructor<?> make(Class<?> owner, Class<?>... parameters)
				throws ReflectiveOperationException {
			Constructor<?> constructor = owner.getDeclaredConstructor(parameters);
			constructor.setAccessible(true);

			return constructor;
		}

		private static Method open(Class<?> owner, String component)
				throws ReflectiveOperationException {
			Method method = owner.getDeclaredMethod(component);
			method.setAccessible(true);

			return method;
		}

		static boolean available() {
			return MODULE != null;
		}

		static Object uniformBuffer(String name, int bindingOffset)
				throws ReflectiveOperationException {
			return require(UNIFORM_BUFFER).newInstance(name, bindingOffset);
		}

		static Object sampler(String name, int bindingOffset, int dimensions)
				throws ReflectiveOperationException {
			return require(SAMPLER).newInstance(name, bindingOffset, dimensions);
		}

		static Object variable(String name, int locationOffset) throws ReflectiveOperationException {
			return require(VARIABLE).newInstance(name, locationOffset);
		}

		static IntermediaryShaderModule module(String name, ByteBuffer spirv,
				List<?> uniformBuffers, List<?> samplers, List<?> outputs, List<?> inputs)
				throws ReflectiveOperationException {
			return (IntermediaryShaderModule) require(MODULE)
					.newInstance(name, spirv, uniformBuffers, samplers, outputs, inputs);
		}

		static String uniformBufferName(Object entry) throws ReflectiveOperationException {
			return (String) require(UNIFORM_BUFFER_NAME).invoke(entry);
		}

		static int uniformBufferBinding(Object entry) throws ReflectiveOperationException {
			return (Integer) require(UNIFORM_BUFFER_BINDING).invoke(entry);
		}

		static String samplerName(Object entry) throws ReflectiveOperationException {
			return (String) require(SAMPLER_NAME).invoke(entry);
		}

		static int samplerBinding(Object entry) throws ReflectiveOperationException {
			return (Integer) require(SAMPLER_BINDING).invoke(entry);
		}

		static int samplerDimensions(Object entry) throws ReflectiveOperationException {
			return (Integer) require(SAMPLER_DIMENSIONS).invoke(entry);
		}

		static String variableName(Object entry) throws ReflectiveOperationException {
			return (String) require(VARIABLE_NAME).invoke(entry);
		}

		static int variableLocation(Object entry) throws ReflectiveOperationException {
			return (Integer) require(VARIABLE_LOCATION).invoke(entry);
		}

		private static Constructor<?> require(@Nullable Constructor<?> constructor)
				throws ReflectiveOperationException {
			if (constructor == null) {
				throw new NoSuchMethodException("a shader module record is not where it was");
			}

			return constructor;
		}

		private static Method require(@Nullable Method method) throws ReflectiveOperationException {
			if (method == null) {
				throw new NoSuchMethodException("a shader module component is not where it was");
			}

			return method;
		}
	}
}
