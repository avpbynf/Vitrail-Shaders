package dev.vitrail.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

/**
 * The {@code <profile>} slot, shown as a setting whose values are the pack's profile names. This is
 * Iris's {@code ProfileElementWidget}.
 * <p>
 * <b>What is shown is worked out from the values, not read from a name kept beside them.</b> No name
 * is stored anywhere: picking a profile queues the values it names, exactly as Iris does, and the
 * file then carries those values one per line. After a reset that file holds nothing, the pack's own
 * values are back, and those values still amount to a profile that this then names.
 * <p>
 * <b>Never marked as waiting</b>, {@link #modified()} answering no as Iris's does. A profile is not a
 * value of its own, and every setting it moved is already marked amber by its own cell, so a
 * selector that went amber as well would be saying the same thing a second time.
 * <p>
 * Shift and a click do nothing here, a profile being a whole set of values rather than one of them.
 * Iris's own note gives the way back: reset the settings.
 * <p>
 * A pack declaring no profile never reaches this class, {@link dev.vitrail.pack.menu.PackMenu}
 * dropping the token rather than leaving a blank where the selector would have been.
 */
public final class ProfileWidget extends OptionWidget {

	/** No pack declares a setting by this name, and both settings files reserve the line. */
	private static final String NAME = "profile";

	/** How much of the cell the name takes before the value box gets what is left. Iris's number. */
	private static final int LABEL_ROOM = 16;

	private static final Component LABEL = Component.translatable(ScreenText.PROFILE);

	private static final Component CUSTOM = Component
			.translatable(ScreenText.PROFILE_CUSTOM)
			.withStyle(ChatFormatting.YELLOW);

	/** From the most constrained to the least, which is the order the selector walks through. */
	private final List<String> profiles;

	private Component profileLabel = CUSTOM;

	public ProfileWidget(List<String> profiles) {
		this.profiles = List.copyOf(profiles);
	}

	@Override
	public void init(ScreenHost host) {
		super.init(host);
		setLabel(LABEL.copy());

		String current = host.values().matchedProfile();
		this.profileLabel = current.isEmpty()
				? CUSTOM
				: ScreenText.fromPack(host.lang().profile(current));
	}

	@Override
	public void draw(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered) {
		measure(bounds().width() - (Minecraft.getInstance().font.width(LABEL) + LABEL_ROOM));
		drawWithValue(graphics, hovered || isFocused());
	}

	@Override
	protected String name() {
		return NAME;
	}

	@Override
	protected Component valueLabel() {
		return this.profileLabel;
	}

	/**
	 * Walks from whichever profile the values match. From Custom there is no profile to step away
	 * from, so a click forwards gives the first of the list and a click backwards the last, which is
	 * what {@code ProfileSet.scan} returns when nothing matched.
	 */
	private boolean step(int by) {
		if (this.profiles.isEmpty()) {
			return false;
		}

		int index = this.profiles.indexOf(host().values().matchedProfile());
		int wanted = index < 0
				? (by < 0 ? this.profiles.size() - 1 : 0)
				: Math.floorMod(index + by, this.profiles.size());
		host().values().queueProfile(this.profiles.get(wanted));

		return true;
	}

	@Override
	protected boolean nextValue() {
		return step(1);
	}

	@Override
	protected boolean previousValue() {
		return step(-1);
	}

	@Override
	protected boolean originalValue() {
		return false;
	}

	@Override
	protected boolean modified() {
		return false;
	}

	@Override
	protected Optional<String> comment() {
		return host().lang().profileComment();
	}
}
