package io.github.andrix03.retroreplay.compat;

import io.github.andrix03.retroreplay.mixin.MinecraftInstanceAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.network.packet.Packet;

/** What differs between Minecraft versions, for the shared code. This is the 1.0.0 one. */
public final class Mc {

	public static final String VERSION = "1.0.0";
	public static final int PROTOCOL = 22;

	/** The matching dedicated server, run in the background for singleplayer (see LocalServer). */
	public static final String SERVER_JAR_URL = "https://vault.omniarchive.uk/archive/java/server-release/1.0.0/1.0.0.jar";
	public static final String SERVER_JAR_SHA1 = "38cafa1bf1e927c5cdb4cfb8cc6a34dfdcfab2b8";

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
		return new net.minecraft.client.gui.widget.TextFieldWidget(screen, minecraft().textRenderer, x, y, width, height, "");
	}

	public static boolean isPlayer(Object entity) {
		return entity instanceof net.minecraft.entity.mob.player.PlayerEntity;
	}

	/** A player's name, or null for other entities. */
	public static String playerName(Object entity) {
		return entity instanceof net.minecraft.entity.mob.player.PlayerEntity ? ((net.minecraft.entity.mob.player.PlayerEntity) entity).name : null;
	}

	/** Watches the world through that entity's eyes (null: back to the free camera); false if it cannot. */
	public static boolean spectate(Object entity) {
		Minecraft minecraft = minecraft();
		if (entity == null) {
			minecraft.camera = minecraft.player;
			return true;
		}
		if (!(entity instanceof net.minecraft.entity.mob.MobEntity)) {
			return false;
		}
		minecraft.camera = (net.minecraft.entity.mob.MobEntity) entity;
		return true;
	}

	/** The entity the camera looks through. */
	public static Object cameraEntity() {
		return minecraft().camera;
	}

	/** Dev self-test only: opens (or creates, seed 1234) a survival singleplayer world. */
	public static void startSelftestWorld(String name) {
		minecraft().startGame(name, name, new net.minecraft.world.WorldSettings(1234L, Integer.parseInt(System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_GAMEMODE", "0")), true, System.getenv("RETROREPLAY_AUTORECORD_HARDCORE") != null));
	}

	/** Dev self-test only. */
	public static Screen connectScreen(Screen parent, String host, int port) {
		return new ConnectScreen(minecraft(), host, port);
	}
}
