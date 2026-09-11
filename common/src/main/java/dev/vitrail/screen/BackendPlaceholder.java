package dev.vitrail.screen;

import dev.vitrail.ScreenText;
import dev.vitrail.Vitrail;

import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * What a door to the pack screen opens on a backend this engine does not draw on: a sentence saying
 * so, a button that switches the game to Vulkan and closes it, and one that goes back.
 * <p>
 * It is the reference's screen for the same situation the other way round, which Iris opens on
 * Vulkan in place of its pack screen ({@code IrisConfig.java:50-51}, {@code IrisVKOnly.java:24}).
 * The layout, the words with the two backends swapped and what the switch does are
 * {@code ShaderPackScreenPlaceholder.java}'s, line for line.
 * <p>
 * The switch closes the game rather than leaving that to the player, because the backend is chosen
 * once, in the {@code Minecraft} constructor, and the saved option does nothing before the next
 * start. The integrated server is halted and the world left through the game's saving screen first,
 * {@code ShaderPackScreenPlaceholder.java:48-53}.
 */
public final class BackendPlaceholder extends Screen {

	private final @Nullable Screen parent;

	private @Nullable MultiLineLabel message;

	public BackendPlaceholder(@Nullable Screen parent) {
		super(Component.literal(Vitrail.MOD_NAME));
		this.parent = parent;
	}

	@Override
	protected void init() {
		super.init();
		MultiLineLabel label = MultiLineLabel.create(this.font,
				Component.translatable(ScreenText.BACKEND_PLACEHOLDER), this.width - 50);
		this.message = label;
		int textSize = (label.getLineCount() + 1) * 9;

		this.addRenderableWidget(Button.builder(Component.translatable(ScreenText.BACKEND_SWITCH),
						_ -> switchToVulkan())
				.bounds(this.width / 2 - 155, 100 + textSize, 150, 20)
				.build());
		this.addRenderableWidget(Button.builder(Component.translatable(ScreenText.BACKEND_RETURN),
						_ -> onClose())
				.bounds(this.width / 2 - 155 + 160, 100 + textSize, 150, 20)
				.build());
	}

	private void switchToVulkan() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.options.preferredGraphicsBackend().set(PreferredGraphicsApi.VULKAN);
		minecraft.options.save();

		IntegratedServer server = minecraft.getSingleplayerServer();
		if (minecraft.isLocalServer() && server != null) {
			server.halt(true);
		}

		minecraft.disconnectWithSavingScreen();
		minecraft.stop();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 50, -1);

		MultiLineLabel label = this.message;
		if (label != null) {
			label.visitLines(TextAlignment.CENTER, this.width / 2, 70, 9, graphics.textRenderer());
		}
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(this.parent);
	}
}
