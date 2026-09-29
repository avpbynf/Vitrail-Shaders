package dev.vitrail.render;

import dev.vitrail.mixin.game.RenderTypeAccessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.resources.Identifier;

/**
 * Hands 26.3's enchantment glint back to the two draws 26.2 made of it, for as long as a pack is
 * drawing.
 * <p>
 * <strong>26.3 draws an enchanted piece and its glint in one draw.</strong> An item, a piece of
 * armour, a trident and a shield each take a render type of their own when they carry the glint
 * ({@code RenderTypes.itemCutoutGlint}, {@code armorCutoutNoCullGlint}, {@code entitySolidGlint} and
 * their neighbours), whose pipeline is the plain one with a {@code GLINT} define: it samples the
 * glint sheet through a second sampler at {@code TextureMat * UV0} and adds its square onto the
 * lit colour, which is {@code BlendFunction.GLINT} done inside the shader. 26.2 drew the same thing
 * as the plain piece followed by a second pass through {@code RenderPipelines.GLINT}.
 * <p>
 * <strong>None of those pipelines has a row in {@link EntityDraw}, and none could have one
 * honestly</strong>: the OptiFine model this engine serves has no program that draws a piece and
 * its glint at once, only {@code gbuffers_hand} or {@code gbuffers_entities} for the one and
 * {@code gbuffers_armor_glint} for the other. So every enchanted piece went back to the game, and
 * the scene seed carried the game's picture into the pack's first draw buffer: a vanilla-looking
 * item under Complementary, and a purple smear under Photon, whose first draw buffer is a packed
 * gbuffer.
 * <p>
 * The answer is to submit the two draws 26.2 submitted, and nothing further down has to change for
 * it: the piece reaches its ordinary row, the glint reaches the glint rows, and a piece or a glint
 * the pack does not serve is drawn by the game exactly as it was on 26.2. The glint passes are 26.2's
 * own three render types rebuilt here, {@code glint}, {@code entity_glint} and
 * {@code armor_entity_glint}, because 26.3 has none of them left.
 * <p>
 * <strong>Not the two glint render types 26.3 kept, and that was measured.</strong>
 * {@code trimmedArmorGlint} and {@code patternedShieldGlint} carry the same texture, texturing and
 * layering, but both force the solid model phase, so a glint submitted through them is drawn in the
 * opaque window, on the early glint row, where 26.2 drew a model's glint in the translucent one, on
 * the late row. Photon draws the late row and nothing of the early one onto a mob: a trident and a
 * shield carried their glint on 26.2 and had none on 26.3 through the kept types.
 * <p>
 * <strong>Only while a pack is drawing.</strong> Without one the game's own combined draw is the
 * picture and stays it. With one, what this engine does not serve is drawn by the game through the
 * GLINT pipeline instead, which adds the same square of the same sample: the two roads meet on
 * screen.
 */
public final class GlintSplit {

	/** 26.2's {@code glint}: the item's glint pass, the glint sheet under the item texturing. */
	private static final RenderType ITEM_GLINT = RenderTypeAccessor.vitrail$create("vitrail_glint",
			RenderSetup.builder(RenderPipelines.GLINT)
					.withTexture("Sampler0", ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
					.setTextureTransform(TextureTransform.GLINT_TEXTURING)
					.createRenderSetup());

	/** 26.2's {@code entity_glint}: a trident's and a shield's, the same sheet more coarsely laid. */
	private static final RenderType ENTITY_GLINT = RenderTypeAccessor.vitrail$create(
			"vitrail_entity_glint",
			RenderSetup.builder(RenderPipelines.GLINT)
					.withTexture("Sampler0", ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
					.setTextureTransform(TextureTransform.ENTITY_GLINT_TEXTURING)
					.createRenderSetup());

	/**
	 * 26.2's {@code armor_entity_glint}: the armour's own sheet, pulled towards the camera by the same
	 * layering the plain armour carries, so that the two still meet at the depth the glint tests
	 * equal against.
	 */
	private static final RenderType ARMOUR_GLINT = RenderTypeAccessor.vitrail$create(
			"vitrail_armor_entity_glint",
			RenderSetup.builder(RenderPipelines.GLINT)
					.withTexture("Sampler0", ItemFeatureRenderer.ENCHANTED_GLINT_ARMOR)
					.setTextureTransform(TextureTransform.ARMOR_ENTITY_GLINT_TEXTURING)
					.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
					.createRenderSetup());

	/**
	 * The combined model render types met so far, each with its plain half. Filled as the game makes
	 * them, the texture being known there and nowhere cheaper: the factories are memoised per texture,
	 * so one texture is one entry for the whole session.
	 */
	private static final Map<RenderType, RenderType> MODEL_BASES = new ConcurrentHashMap<>();

	/** The glint pass of each of those, keyed the same way. */
	private static final Map<RenderType, RenderType> MODEL_GLINTS = new ConcurrentHashMap<>();

	private GlintSplit() {
	}

	/** Whether the two draws are submitted instead of one. */
	public static boolean active() {
		return PackChain.drawingPack();
	}

	/** Notes an armour layer's combined render type, made for this texture. */
	public static void armour(RenderType combined, Identifier texture) {
		if (!MODEL_BASES.containsKey(combined)) {
			MODEL_GLINTS.put(combined, ARMOUR_GLINT);
			MODEL_BASES.put(combined, RenderTypes.armorCutoutNoCull(texture));
		}
	}

	/** Notes a trident's or a shield's combined render type, made for this texture. */
	public static void entity(RenderType combined, Identifier texture) {
		if (!MODEL_BASES.containsKey(combined)) {
			MODEL_GLINTS.put(combined, ENTITY_GLINT);
			MODEL_BASES.put(combined, RenderTypes.entitySolid(texture));
		}
	}

	/** The plain half of a combined model render type, or null for any other render type. */
	public static RenderType modelBase(RenderType renderType) {
		return MODEL_BASES.get(renderType);
	}

	/** The glint pass of a combined model render type {@link #modelBase} answered for. */
	public static RenderType modelGlint(RenderType renderType) {
		return MODEL_GLINTS.get(renderType);
	}

	/** The item's glint pass. */
	public static RenderType itemGlint() {
		return ITEM_GLINT;
	}
}
