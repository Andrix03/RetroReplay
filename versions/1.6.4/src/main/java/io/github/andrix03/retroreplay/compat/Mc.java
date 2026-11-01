package io.github.andrix03.retroreplay.compat;

import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import io.github.andrix03.retroreplay.RetroReplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.client.resource.Identifier;
import net.minecraft.network.LocalConnection;
import net.minecraft.network.packet.Packet;

/** What differs between Minecraft versions, for the shared code. This is the 1.6.4 one. */
public final class Mc {

	public static final String VERSION = "1.6.4";
	public static final int PROTOCOL = 78;

	private static final Identifier REPLAY_GUI = new Identifier("retroreplay", "textures/replay_gui.png");
	private static final Identifier JGUI = new Identifier("retroreplay", "textures/jgui.png");
	private static final Identifier LOGO = new Identifier("retroreplay", "textures/logo_button.png");
	private static final Identifier CAMERA_HEAD = new Identifier("retroreplay", "textures/camera_head.png");
	private static final Identifier WIDGETS = new Identifier("textures/gui/widgets.png");

	private Mc() {
	}

	public static Minecraft minecraft() {
		return Minecraft.getInstance();
	}

	/**
	 * One of "replay_gui", "jgui", "logo", "camera_head", "widgets". The resource manager does not see mod
	 * files without a resource loader mod, so the mod's own textures are read from its jar
	 * and registered once.
	 */
	public static void bindTexture(String name) {
		Identifier texture = name.equals("replay_gui") ? REPLAY_GUI : name.equals("jgui") ? JGUI : name.equals("logo") ? LOGO : name.equals("camera_head") ? CAMERA_HEAD : WIDGETS;
		TextureManager textures = Minecraft.getInstance().getTextureManager();
		if (texture != WIDGETS && textures.get(texture) == null) {
			String path = "/assets/retroreplay/" + texture.getPath();
			try (InputStream in = Mc.class.getResourceAsStream(path)) {
				textures.register(texture, new DynamicTexture(ImageIO.read(in)));
			} catch (IOException | IllegalArgumentException e) {
				RetroReplay.LOGGER.error("Cannot load " + path, e);
				return;
			}
		}
		textures.bind(texture);
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

	public static net.minecraft.client.gui.widget.TextFieldWidget textField(Screen screen, int x, int y, int width, int height) {
		return new net.minecraft.client.gui.widget.TextFieldWidget(minecraft().textRenderer, x, y, width, height);
	}

	/** Dev self-test only: opens (or creates, seed 1234) a survival singleplayer world. */
	public static void startSelftestWorld(String name) {
		minecraft().startGame(name, name, new net.minecraft.world.WorldSettings(1234L, net.minecraft.world.WorldSettings.GameMode.SURVIVAL, true, false, net.minecraft.world.gen.WorldGeneratorType.DEFAULT));
	}

	/** Dev self-test only. */
	public static Screen connectScreen(Screen parent, String host, int port) {
		return new ConnectScreen(parent, Minecraft.getInstance(), host, port);
	}
}
