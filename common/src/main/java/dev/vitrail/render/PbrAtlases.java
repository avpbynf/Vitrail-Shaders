package dev.vitrail.render;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * What a resource pack ships beside its block textures, one companion image per atlas of the game,
 * kept from the moment an atlas is stitched until the next reload replaces it.
 * <p>
 * The lookup is by the image a pass is really drawing with rather than by the family of the pass,
 * and that is Iris's rule rather than a simplification of it: it resolves the maps from the albedo
 * texture the draw has bound ({@code pipeline/IrisRenderingPipeline.java:849-871}), so the block
 * atlas, the item atlas and the particle atlas each answer for themselves without anything having
 * to know which is which.
 * <p>
 * <strong>A texture that is not an atlas gets nothing, and that is a divergence.</strong> Iris
 * registers a second loader for a plain texture
 * ({@code pbr/loader/PBRTextureLoaderRegistry.java:15} onto
 * {@code pbr/loader/SimplePBRLoader.java:19-31}), resolved per bound albedo at
 * {@code pbr/texture/PBRTextureManager.java:126-138}, so an entity skin and an armour layer read
 * their own {@code _n} and {@code _s} there. Here nothing but a stitched atlas is a door, so those
 * read the flat value: a mob stays matte while the terrain around it has relief. Nothing in 26.2
 * forbids the second door - the game hands out plain textures by name like any other - so this is
 * work not done rather than a wall, and it is the honest name for it.
 * <p>
 * Only the geometry programs are served, which is Iris's shape: {@code normals} and
 * {@code specular} are added by {@code IrisSamplers.addLevelSamplers} and by nothing else
 * ({@code samplers/IrisSamplers.java:215-216}). What a composite declaring one of the names reads
 * is NOT the same on both sides and this file does not claim it is: here it reads a flat texel,
 * where Iris leaves the sampler unassigned and it falls to whatever texture unit nought holds.
 */
public final class PbrAtlases {

	/** Where a resource pack says which convention its maps are drawn in. OptiFine's location. */
	private static final Identifier FORMAT = Identifier.withDefaultNamespace(
			"optifine/texture.properties");

	/**
	 * By atlas name rather than by texture, because the texture is what a reload replaces: the game
	 * closes the old one and builds another under the same name, so a map keyed on the texture would
	 * hold a companion nothing can ever reach again.
	 */
	private static final Map<Identifier, PbrAtlas> ATLASES = new HashMap<>();

	private static boolean labPbr;

	private PbrAtlases() {
	}

	/**
	 * Reads the maps for one atlas the game has just stitched, and lets go of whatever it had for
	 * that atlas before.
	 * <p>
	 * Called for every atlas and not only the block one. An atlas that no pack ships a map for costs
	 * two lookups per sprite, one for each map, and nothing else: no image is decoded and no texture
	 * is created, which is what an ordinary install without a material pack pays.
	 * <p>
	 * Not gated on a shader pack being loaded, where Iris builds nothing until one is and asks for
	 * the two names ({@code pipeline/IrisRenderingPipeline.java:855} off
	 * {@code samplers/IrisSamplers.hasPBRSamplers}). What that gate would buy is measured and small:
	 * all eight packs of the corpus declare at least one of the names, so it only ever spares the
	 * player who has a material resource pack and no shader pack running at all. What it would cost
	 * is a question this side cannot answer at the moment it is asked - an atlas is stitched during
	 * the resource reload, before any pack is chosen for the place being entered.
	 *
	 * @param atlas   what the atlas is called
	 * @param texture the texture the game stitched
	 * @param sprites every sprite of that atlas
	 */
	public static void stitched(Identifier atlas, GpuTexture texture,
			Collection<TextureAtlasSprite> sprites) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}

		ResourceManager resources = minecraft.getResourceManager();
		labPbr = labPbr(resources);

		PbrAtlas previous = ATLASES.remove(atlas);
		if (previous != null) {
			previous.close();
		}

		try {
			PbrAtlas read = PbrAtlas.read(atlas, texture, sprites, resources, labPbr);
			if (read != null) {
				ATLASES.put(atlas, read);
			}
		} catch (RuntimeException e) {
			// Named and swallowed. Every one of these images is an improvement on a flat texel and
			// none of them is owed: a pack that cannot get one still draws, and taking the world
			// down over a resource pack's texture would be the worse answer by a distance.
			Vitrail.logger().error("The material maps of {} could not be built, so its sprites keep "
					+ "the flat values", atlas, e);
		}
	}

	/**
	 * The map behind one of the two names for the image a pass is drawing with, or null where the
	 * resource pack ships none for it.
	 *
	 * @param atlas the image the pass draws with, which is null for every family that has none
	 */
	static GpuTextureView view(GpuTextureView atlas, PbrMap map) {
		if (atlas == null) {
			return null;
		}

		for (PbrAtlas built : ATLASES.values()) {
			if (built.follows(atlas)) {
				return built.view(map);
			}
		}

		return null;
	}

	/**
	 * Whether the resource pack fills one of the two names for any atlas at all.
	 * <p>
	 * A coarser question than {@link #view}, and it exists because the report a program prints once
	 * at load cannot ask the finer one. Four families choose their image per DRAW and not per
	 * program - the particles alone come off the block atlas, the item atlas and the particle atlas
	 * inside one pass - so there is no single answer a latched line could give. What that line can
	 * say without lying is whether anything fills the name this session; which atlases really answer
	 * is said exactly, once each, by {@link PbrAtlas} as they are built.
	 */
	static boolean supplies(PbrMap map) {
		for (PbrAtlas built : ATLASES.values()) {
			if (built.view(map) != null) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Whether the resource pack declares the labPBR convention, which decides how the specular map is
	 * reduced and how it is filtered. Read once per stitch and answered from there.
	 */
	static boolean labPbr() {
		return labPbr;
	}

	/**
	 * Hands back every image built for every atlas. Called where the device is still alive, which is
	 * the whole reason it is a call and not a finaliser.
	 */
	public static void close() {
		ATLASES.values().forEach(PbrAtlas::close);
		ATLASES.clear();
	}

	/**
	 * Whether {@code optifine/texture.properties} names labPBR.
	 * <p>
	 * Only the name of the format is read and the version after the slash is not: the two versions
	 * differ in what the channels mean to the PACK, which is the pack's business, and not in
	 * anything this engine does with them. Iris keeps the version and asks one thing of it, which
	 * this engine has no use for: {@code LabPBRTextureFormat.equals} compares it
	 * ({@code pbr/format/LabPBRTextureFormat.java:34-44}) and
	 * {@code pbr/format/TextureFormatLoader.java:28-32} reloads the whole pack when it changes,
	 * where here a resource reload restitches the atlases and rebuilds these anyway.
	 */
	static boolean labPbr(ResourceManager resources) {
		Optional<Resource> resource = resources.getResource(FORMAT);
		if (resource.isEmpty()) {
			return false;
		}

		Properties properties = new Properties();
		try (InputStream stream = resource.get().open()) {
			properties.load(stream);
		} catch (Exception e) {
			Vitrail.logger().warn("{} could not be read, so the material maps are reduced and "
					+ "filtered as if it named no format", FORMAT, e);

			return false;
		}

		String format = properties.getProperty("format", "").trim();
		int slash = format.indexOf('/');

		return "lab-pbr".equals(slash < 0 ? format : format.substring(0, slash));
	}
}
