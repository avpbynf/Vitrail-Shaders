package dev.vitrail.mixin;

import dev.vitrail.render.EntityIdentifiers;
import dev.vitrail.render.PackNameIds;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says, for the length of one submission, which kind of entity is being handed in.
 * <p>
 * <strong>This is the only moment in the frame the answer exists</strong>, which is what
 * {@link EntityIdentifiers} is about: everything submitted here is drawn much later, out of one
 * batch, and nothing in a draw says which mob a given vertex came from. Around the dispatcher and
 * not around each renderer, where Iris puts it too
 * ({@code mixin/entity_render_context/MixinEntityRenderDispatcher.java:53} and {@code :87}).
 * <p>
 * The number comes from {@code entity.properties} and is the pack's own, so a pack that named
 * nothing leaves every mob at minus one and no branch of it is ever taken. What is not a table
 * lookup is the two names below: neither is an entity type, and both are the pack's way of asking a
 * question the registry has no key for.
 * <p>
 * Both injections required. Only the first applying leaves the last mob's number standing over
 * everything drawn after it; only the second applying does nothing at all. Neither says anything on
 * screen.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

	@Inject(method = "submit", at = @At("HEAD"), require = 1)
	private <S extends EntityRenderState> void vitrail$begin(S state, CameraRenderState camera,
			double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector,
			CallbackInfo callback) {
		EntityIdentifiers.entity(vitrail$identify(state));
	}

	@Inject(method = "submit", at = @At("RETURN"), require = 1)
	private <S extends EntityRenderState> void vitrail$end(S state, CameraRenderState camera,
			double x, double y, double z, PoseStack poseStack, SubmitNodeCollector collector,
			CallbackInfo callback) {
		EntityIdentifiers.entity(0);

		// The held item goes down with the entity that was holding it, and not at the end of the item
		// itself: an item is submitted inside this call and its own window closes first, so leaving
		// this out would keep the last item's number over everything after it. Iris drops the two
		// together here for the same reason (MixinEntityRenderDispatcher.java:88-89).
		EntityIdentifiers.item(0);
	}

	/**
	 * Which number this render state is worth, the two special names first.
	 * <p>
	 * The order is Iris's and it decides what a cured zombie villager reads: the conversion is asked
	 * before the type, so a pack that named both gets the conversion. Each is asked of the pack
	 * first, because a pack that named neither has to fall back on the type rather than on a number
	 * that means nothing.
	 */
	private static int vitrail$identify(EntityRenderState state) {
		if (state instanceof ZombieVillagerRenderState villager && villager.isConverting
				&& PackNameIds.namesConvertingVillager()) {
			return PackNameIds.convertingVillager();
		}

		if (state instanceof AvatarRenderState avatar && PackNameIds.namesCurrentPlayer()
				&& Minecraft.getInstance().getCameraEntity() instanceof AbstractClientPlayer camera
				&& camera.getId() == avatar.id) {
			return PackNameIds.currentPlayer();
		}

		return PackNameIds.entity(state.entityType);
	}
}
