package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.network.LocalConnection;
import net.minecraft.network.packet.Packet;

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

	/** The "server" end of a replay: whatever the client sends is dropped. */
	public static LocalConnection newSinkConnection() {
		return new LocalConnection(Minecraft.getInstance().getLogger(), null) {
			@Override
			public void accept(Packet packet) {
			}
		};
	}

	public static Packet createPacket(int id) {
		return Packet.create(Minecraft.getInstance().getLogger(), id);
	}

	/** Dev self-test only. */
	public static Screen connectScreen(Screen parent, String host, int port) {
		return new ConnectScreen(parent, Minecraft.getInstance(), host, port);
	}
}
