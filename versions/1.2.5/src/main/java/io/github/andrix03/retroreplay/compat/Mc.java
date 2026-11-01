package io.github.andrix03.retroreplay.compat;

import io.github.andrix03.retroreplay.mixin.MinecraftInstanceAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.network.packet.Packet;

/** What differs between Minecraft versions, for the shared code. This is the 1.2.5 one. */
public final class Mc {

	public static final String VERSION = "1.2.5";
	public static final int PROTOCOL = 29;

	/** The matching dedicated server, run in the background for singleplayer (see LocalServer). */
	public static final String SERVER_JAR_URL = "https://launcher.mojang.com/v1/objects/d8321edc9470e56b8ad5c67bbd16beba25843336/server.jar";
	public static final String SERVER_JAR_SHA1 = "d8321edc9470e56b8ad5c67bbd16beba25843336";

	private Mc() {
	}

	public static Minecraft minecraft() {
		return MinecraftInstanceAccessor.retroreplay$getInstance();
	}

	/**
	 * One of "replay_gui", "jgui", "logo", "camera_head", "widgets". Before 1.6 textures are bound by their
	 * path on the classpath (the built-in texture pack also finds the mod's files).
	 */
	public static void bindTexture(String name) {
		String path = name.equals("replay_gui") ? "/assets/retroreplay/textures/replay_gui.png"
			: name.equals("jgui") ? "/assets/retroreplay/textures/jgui.png"
			: name.equals("logo") ? "/assets/retroreplay/textures/logo_button.png"
			: name.equals("camera_head") ? "/assets/retroreplay/textures/camera_head.png"
			: "/gui/gui.png";
		org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, minecraft().textureManager.load(path));
	}

	public static Packet createPacket(int id) {
		return Packet.create(id);
	}

	public static net.minecraft.client.gui.widget.TextFieldWidget textField(Screen screen, int x, int y, int width, int height) {
		return new net.minecraft.client.gui.widget.TextFieldWidget(minecraft().textRenderer, x, y, width, height);
	}

	/** Dev self-test only: opens (or creates, seed 1234) a survival singleplayer world. */
	public static void startSelftestWorld(String name) {
		minecraft().startGame(name, name, new net.minecraft.world.WorldSettings(1234L, Integer.parseInt(System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_GAMEMODE", "0")), true, System.getenv("RETROREPLAY_AUTORECORD_HARDCORE") != null, net.minecraft.world.gen.WorldGeneratorType.DEFAULT));
	}

	/** Dev self-test only. */
	public static Screen connectScreen(Screen parent, String host, int port) {
		return new ConnectScreen(minecraft(), host, port);
	}
}
