package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resource.Identifier;

/** What differs between Minecraft versions, for the shared code. This is the 1.6.4 one. */
public final class Mc {

	public static final String VERSION = "1.6.4";
	public static final int PROTOCOL = 78;

	private static final Identifier REPLAY_GUI = new Identifier("retroreplay", "textures/replay_gui.png");
	private static final Identifier JGUI = new Identifier("retroreplay", "textures/jgui.png");
	private static final Identifier LOGO = new Identifier("retroreplay", "textures/logo_button.png");
	private static final Identifier WIDGETS = new Identifier("textures/gui/widgets.png");

	private Mc() {
	}

	/** One of "replay_gui", "jgui", "logo", "widgets". */
	public static void bindTexture(String name) {
		Identifier texture = name.equals("replay_gui") ? REPLAY_GUI : name.equals("jgui") ? JGUI : name.equals("logo") ? LOGO : WIDGETS;
		Minecraft.getInstance().getTextureManager().bind(texture);
	}
}
