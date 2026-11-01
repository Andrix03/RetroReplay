package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.Minecraft;

/** What differs between Minecraft versions, for the shared code. This is the 1.5.2 one. */
public final class Mc {

	public static final String VERSION = "1.5.2";
	public static final int PROTOCOL = 61;

	private Mc() {
	}

	/**
	 * One of "replay_gui", "jgui", "logo", "widgets". Before 1.6 textures are bound by their
	 * path on the classpath (the built-in texture pack also finds the mod's files).
	 */
	public static void bindTexture(String name) {
		String path = name.equals("replay_gui") ? "/assets/retroreplay/textures/replay_gui.png"
			: name.equals("jgui") ? "/assets/retroreplay/textures/jgui.png"
			: name.equals("logo") ? "/assets/retroreplay/textures/logo_button.png"
			: "/gui/gui.png";
		Minecraft.getInstance().textureManager.bind(path);
	}
}
