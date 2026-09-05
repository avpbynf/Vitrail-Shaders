package dev.vitrail.render;

import dev.vitrail.mixin.access.TextureManagerAccessor;
import dev.vitrail.Vitrail;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * The other door onto the same two names: what a resource pack ships beside a texture that is a
 * texture and not an atlas, which is what an entity skin and an armour layer are.
 * <p>
 * {@link PbrAtlases} answers for a stitched atlas, where every sprite lives inside one image and the
 * maps have to be stitched to match it. Nothing of that applies here. The draw binds one whole image
 * and reads it over its whole range, so {@code creeper_n.png} beside {@code creeper.png} is uploaded
 * as it is drawn: no slot to land in, no border to replicate, and no mip chain, the albedo beside it
 * having none either ({@code ReloadableTexture.doLoad} creates its texture with one level).
 * <p>
 * That pair of doors is Iris's shape and not a division invented here: it registers one loader per
 * texture class ({@code pbr/loader/PBRTextureLoaderRegistry.java:15-16}), the atlas one against
 * {@code TextureAtlas} and {@code pbr/loader/SimplePBRLoader.java:19-31} against
 * {@code SimpleTexture}, and resolves between them from the albedo the draw has bound
 * ({@code pbr/texture/PBRTextureManager.java:126-141}). The resolution on this side is the same
 * question asked of the same thing, and it lands in the same place: {@link GeometryProgram} tries
 * the atlas door first and this one behind it.
 * <p>
 * <strong>A map cannot be read at the moment it is wanted, and that decides the whole shape of this
 * file.</strong> The want is discovered inside {@code GeometryProgram.bind}, which runs inside the
 * render pass the draw is being recorded into, where creating a texture and writing to it is not
 * allowed. So a texture met for the first time is remembered, answered with the flat value for that
 * frame, and read at the top of the next one. Iris does the same, at the same place and by the same
 * means: {@code pipeline/IrisRenderingPipeline.java:856} asks for the maps while it binds its
 * samplers, and {@code pbr/texture/PBRTextureManager.java:116-123} answers with the flat pair and
 * queues the read into its own {@code onNewFrame}. What a player sees either way is one frame of a
 * mob without relief, at the moment it first comes on screen.
 */
public final class PbrTextures {

	/** Written once and sampled. Nothing is ever cleared into one of these, so no attachment. */
	private static final int USAGE = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;

	/**
	 * By the name the texture was loaded from, because the texture is what a resource reload
	 * replaces: the game closes the old one and builds another under the same name. Keyed on the name
	 * rather than the image, this is what lets a reload be met without a hook of its own - the new
	 * image misses below, its name is found here, and what the old one held is handed back before the
	 * new is built.
	 * <p>
	 * <strong>What that does not cover is a name that is never drawn again, and it is a divergence
	 * worth its two lines.</strong> Iris hands a pair back the moment the albedo it followed is
	 * deleted ({@code pbr/texture/PBRTextureManager.java:147-152}). Here nothing is watching the
	 * deletion, so after a resource reload the two images built for a skin the player never meets
	 * again stay resident until the session ends. It is bounded rather than open - one pair per
	 * distinct name ever served, and the next reload of that name frees it - so this is work not
	 * done rather than a wall: what it would take is a hook on the game closing a texture, which is
	 * a mixin nothing else here needs yet.
	 */
	private static final Map<Identifier, Maps> BY_NAME = new HashMap<>();

	/**
	 * By the image a draw really binds, which is the question {@link #view} is asked and the only one
	 * it can answer without walking the game's whole register. It holds the empty answer too, or a
	 * texture the pack ships nothing for would be looked up again at every draw of it.
	 * <p>
	 * Weakly, and that is what keeps a session of reloads from growing: the keys are the game's own
	 * images, an entry lives exactly as long as the image a draw could still bind, and {@link Maps}
	 * deliberately holds no reference back to the image it was built from. Identity comes with the
	 * map for free, {@code GpuTexture} defining neither {@code equals} nor {@code hashCode}.
	 */
	private static final Map<GpuTexture, Maps> BY_TEXTURE = new WeakHashMap<>();

	/** The images met since the last frame began, read at the top of the next one. */
	private static final Set<GpuTexture> WANTED = Collections.newSetFromMap(new IdentityHashMap<>());

	/** What a texture the resource pack ships neither map for is remembered as. */
	private static final Maps NOTHING = new Maps();

	private PbrTextures() {
	}

	/**
	 * The map behind one of the two names for the image a pass is drawing with, or null where the
	 * resource pack ships none for it, where the image is not one the game loaded from a resource, or
	 * where it is met for the first time and has not been read yet.
	 *
	 * @param bound the image the pass draws with, which is null for every family that has none
	 * @param map   which of the two names is being answered
	 */
	static GpuTextureView view(GpuTextureView bound, PbrMap map) {
		if (bound == null) {
			return null;
		}

		Maps built = BY_TEXTURE.get(bound.texture());
		if (built == null) {
			WANTED.add(bound.texture());

			return null;
		}

		return built.views.get(map);
	}

	/**
	 * Reads what the images met during the last frame have beside them, at a point of the frame where
	 * no render pass is open.
	 */
	public static void load() {
		if (WANTED.isEmpty()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			WANTED.clear();

			return;
		}

		Map<Identifier, AbstractTexture> loaded =
				((TextureManagerAccessor) minecraft.getTextureManager()).vitrail$byPath();
		ResourceManager resources = minecraft.getResourceManager();

		for (GpuTexture wanted : WANTED) {
			BY_TEXTURE.put(wanted, read(wanted, loaded, resources));
		}

		WANTED.clear();
	}

	/**
	 * Hands back every image built for every texture. Called where the device is still alive, which
	 * is the whole reason it is a call and not a finaliser.
	 */
	public static void close() {
		BY_NAME.values().forEach(Maps::close);
		BY_NAME.clear();
		BY_TEXTURE.clear();
		WANTED.clear();
	}

	/**
	 * What one image of the game has beside it, or {@link #NOTHING} where it has neither map, where
	 * the game did not load it from a resource, or where reading it failed.
	 */
	private static Maps read(GpuTexture wanted, Map<Identifier, AbstractTexture> loaded,
			ResourceManager resources) {
		Identifier name = named(wanted, loaded);
		if (name == null) {
			return NOTHING;
		}

		// Whatever this name held before, which is what a resource reload leaves behind: the image
		// under it has been replaced, so the maps built against the old one are reached by nothing
		// and go back here rather than at the end of the session.
		Maps replaced = BY_NAME.remove(name);
		if (replaced != null) {
			replaced.close();
		}

		Maps built = new Maps();
		try {
			for (PbrMap map : PbrMap.values()) {
				read(built, map, beside(name, map), resources);
			}
		} catch (RuntimeException e) {
			// Named and swallowed, the same answer PbrAtlases gives for the same reason: an image
			// beside a texture is an improvement on a flat texel and none of them is owed, so a
			// resource pack's file does not take the world down with it.
			built.close();
			Vitrail.logger().error("The material maps of {} could not be built, so it keeps the flat "
					+ "values", name, e);

			return NOTHING;
		}

		if (built.views.isEmpty()) {
			return NOTHING;
		}

		BY_NAME.put(name, built);
		Vitrail.logger().info("The resource pack answers {} for {}", built.answered(), name);

		return built;
	}

	/**
	 * The name the game loaded an image under, or null where no texture of its own carries it.
	 * <p>
	 * Only a {@link SimpleTexture} is answered for, which is where Iris draws the same line: its
	 * registry holds this class and the atlas one and nothing else, so an image the game builds
	 * rather than reads - the light map, the overlay, a skin that came down over HTTP - has no map
	 * beside it on either side. The test is not written the same way and the difference is worth the
	 * sentence: Iris looks the loader up by the EXACT class ({@code texture.getClass()} into a map
	 * at {@code pbr/texture/PBRTextureManager.java:129} and
	 * {@code pbr/loader/PBRTextureLoaderRegistry.java:27-28}), where this takes subclasses too. On
	 * 26.2 the two answer the same for everything the game ships, {@code SimpleTexture} having no
	 * subclass in it, so what the difference decides today is nothing.
	 * <p>
	 * The name is the texture's own resource identifier rather than the key it is registered under,
	 * which is the one Iris reads too ({@code pbr/loader/SimplePBRLoader.java:20}): the two differ
	 * wherever the game loads one file into a slot named after something else.
	 */
	private static Identifier named(GpuTexture wanted, Map<Identifier, AbstractTexture> loaded) {
		for (AbstractTexture texture : loaded.values()) {
			if (texture instanceof SimpleTexture simple && holds(simple, wanted)) {
				return simple.resourceId();
			}
		}

		return null;
	}

	/**
	 * Whether a texture of the game's is the image wanted. Identity and never contents, and caught
	 * rather than tested: a texture registered for the next reload holds no image until that reload
	 * applies one, and the getter answers that with a throw.
	 */
	@SuppressWarnings("ReferenceEquality")
	private static boolean holds(AbstractTexture texture, GpuTexture wanted) {
		try {
			return texture.getTexture() == wanted;
		} catch (IllegalStateException absent) {
			return false;
		}
	}

	/**
	 * Where a map lives beside a plain texture, the suffix going before the extension rather than
	 * after it: {@code creeper.png} is answered by {@code creeper_n.png} and never by
	 * {@code creeper.png_n}. Iris builds the same name at {@code pbr/texture/PBRType.java:57-64}, and
	 * falls back the same way on a path that carries no extension at all.
	 */
	private static Identifier beside(Identifier texture, PbrMap map) {
		String path = texture.getPath();
		int dot = path.lastIndexOf('.');
		String suffixed = dot > path.lastIndexOf('/')
				? path.substring(0, dot) + map.suffix() + path.substring(dot)
				: path + map.suffix();

		return Identifier.fromNamespaceAndPath(texture.getNamespace(), suffixed);
	}

	/**
	 * One map, read and uploaded whole, or nothing added at all where the pack ships none under that
	 * name or ships one that cannot be decoded.
	 * <p>
	 * No scaling and no mip chain, where {@link PbrAtlas} does both: a map is read over its own whole
	 * range by a texture coordinate that runs from nought to one, so a pack drawing its maps at
	 * another resolution than its skins is met by the sampler rather than by a resample. That is
	 * Iris's answer as well, its simple loader handing the file straight to a texture of the game's.
	 */
	private static void read(Maps into, PbrMap map, Identifier location, ResourceManager resources) {
		Optional<Resource> resource = resources.getResource(location);
		if (resource.isEmpty()) {
			return;
		}

		NativeImage image;
		try (InputStream stream = resource.get().open()) {
			image = NativeImage.read(stream);
		} catch (IOException | RuntimeException e) {
			Vitrail.logger().warn("{} could not be read, so its texture keeps the flat {} value",
					location, map.sampler(), e);

			return;
		}

		try {
			GpuDevice device = RenderSystem.getDevice();
			GpuTexture texture = device.createTexture(location::toString, USAGE,
					GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
			// Held before the view is made and not after: a throw between the two would otherwise
			// leave an image nothing can ever close.
			into.textures.put(map, texture);
			into.views.put(map, device.createTextureView(texture));
			device.createCommandEncoder().writeToTexture(texture, image);
		} finally {
			image.close();
		}
	}

	/**
	 * The two images that follow one texture of the game, each of them absent wherever the resource
	 * pack ships nothing under that name.
	 * <p>
	 * <strong>It holds no reference to the texture it was built from, and that is not an
	 * omission</strong>: {@link #BY_TEXTURE} keys on that texture weakly, and a value pointing back
	 * at its own key is what would keep every image of every reload alive for the session.
	 */
	private static final class Maps {

		private final Map<PbrMap, GpuTexture> textures = new EnumMap<>(PbrMap.class);
		private final Map<PbrMap, GpuTextureView> views = new EnumMap<>(PbrMap.class);

		/** Which of the two names answered, in the order the names are declared. */
		String answered() {
			return this.views.keySet().stream().map(PbrMap::sampler)
					.collect(Collectors.joining(" and "));
		}

		void close() {
			// The views first: closing a texture does not close the views onto it, and nothing on the
			// Vulkan backend checks that a bound view is still alive.
			this.views.values().forEach(GpuTextureView::close);
			this.views.clear();
			this.textures.values().forEach(GpuTexture::close);
			this.textures.clear();
		}
	}
}
