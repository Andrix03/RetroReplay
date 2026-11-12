package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.network.LocalConnection;
import net.minecraft.network.packet.Packet;

/** What differs between Minecraft versions, for the shared code. This is the 1.4.7 one. */
public final class Mc {

	public static final String VERSION = "1.4.7";
	public static final int PROTOCOL = 51;

	private Mc() {
	}

	public static Minecraft minecraft() {
		return Minecraft.getInstance();
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
		org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, Minecraft.getInstance().textureManager.load(path));
	}

	/** The "server" end of a replay: whatever the client sends is dropped. */
	public static LocalConnection newSinkConnection() {
		return new LocalConnection(null) {
			@Override
			public void accept(Packet packet) {
			}
		};
	}

	public static Packet createPacket(int id) {
		return Packet.create(id);
	}

	public static net.minecraft.client.gui.widget.TextFieldWidget textField(Screen screen, int x, int y, int width, int height) {
		return new net.minecraft.client.gui.widget.TextFieldWidget(minecraft().textRenderer, x, y, width, height);
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

	/** How chunk packets look, for the seek index. */
	public static final int CHUNK_FORMAT = io.github.andrix03.retroreplay.replay.ChunkIndex.INTEGRATED;

	/** Packet ids the replay treats specially (pre-Netty numbering). */
	public static final int PACKET_HANDSHAKE = 0x02;
	public static final int PACKET_HEALTH = 0x08;
	public static final int PACKET_ABILITIES = 0xCA;
	public static final int PACKET_CUSTOM_PAYLOAD = 0xFA;
	public static final int PACKET_POSITION = 0x0D;
	public static final int PACKET_GAME_STATE = 0x46;
	public static final int PACKET_RESPAWN = 0x09;

	/** A packet as the replay file stores it: id byte + payload. */
	public static byte[] serialize(Packet packet) throws java.io.IOException {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
		out.writeByte(packet.getId());
		packet.write(out);
		return bytes.toByteArray();
	}

	public static String packetName(Packet packet) {
		return packet.getId() + " (" + packet.getClass().getSimpleName() + ")";
	}

	/** A stored packet read back; the login packet's player id becomes the replay camera's. */
	public static Packet readPacket(byte[] data, int cameraId) throws java.io.IOException {
		Packet packet = createPacket(data[0] & 0xFF);
		if (packet == null) {
			return null;
		}
		packet.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(data, 1, data.length - 1)));
		if (packet instanceof net.minecraft.network.packet.LoginPacket) {
			((net.minecraft.network.packet.LoginPacket) packet).gameVersion = cameraId; // the field is the player's entity id
		}
		return packet;
	}

	public static int entityId(Object entity) {
		return ((net.minecraft.entity.Entity) entity).networkId;
	}

	public static int chatKeyCode() {
		return minecraft().options.chatKey.keyCode;
	}

	public static Screen confirmScreen(Screen parent, String title, String message, String yes, String no, int id) {
		return new net.minecraft.client.gui.screen.ConfirmScreen(parent, title, message, yes, no, id);
	}

	/** The GUI's width in scaled pixels. */
	public static int scaledWidth() {
		return new net.minecraft.client.render.Window(minecraft().options, minecraft().width, minecraft().height).getWidth();
	}

	public static void screenshot(String name) {
		net.minecraft.client.Screenshot.take(minecraft().gameDir, name, minecraft().width, minecraft().height);
	}

	/** Before 1.7 the game draws straight to the window: there is no framebuffer of its own to use. */
	public static boolean resizeGameTarget(int width, int height) {
		return false;
	}

	public static void bindGameTarget() {
	}

	/** The light-level-to-brightness table of the world shown now (null without a world). */
	public static float[] brightnessTable() {
		return minecraft().world == null ? null : minecraft().world.dimension.brightnessTable;
	}

	/** How far the local player's y is above its feet (its eyes before 1.8). */
	public static double feetOffset() {
		return minecraft().player.eyeHeight;
	}

	public static void swingArm() {
		minecraft().player.swingArm();
	}

	/** How high the camera's eyes are above its feet. */
	public static double eyeHeight() {
		return minecraft().player.eyeHeight;
	}

	/** Dev self-test only: opens (or creates, seed 1234) a survival singleplayer world. */
	public static void startSelftestWorld(String name) {
		minecraft().startGame(name, name, new net.minecraft.world.WorldSettings(1234L, net.minecraft.world.WorldSettings.GameMode.byId(Integer.parseInt(System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_GAMEMODE", "0"))), true, false, net.minecraft.world.gen.WorldGeneratorType.DEFAULT));
	}

	/** Dev self-test only. */
	public static Screen connectScreen(Screen parent, String host, int port) {
		return new ConnectScreen(Minecraft.getInstance(), host, port);
	}

	/** Block id << 4 | metadata at a position, or -1 when its chunk is not loaded (Quick Mode). */
	public static int blockState(int x, int y, int z) {
		net.minecraft.world.World world = minecraft().world;
		if (world == null || !world.isChunkLoaded(x, y, z)) {
			return -1;
		}
		return world.getBlock(x, y, z) << 4 | world.getBlockMetadata(x, y, z) & 15;
	}

	/** Block cracks in replays: from 1.3 the vanilla packet draws them, nothing to do here. */
	public static void renderMiningCrack(int x, int y, int z, int stage, float tickDelta) {
	}

	/** The vanilla packet for a block's cracks (stage -1 = none), or null before 1.3 (MiningCracks writes its own). */
	public static byte[] miningPacket(int entityId, int x, int y, int z, int stage) {
		try {
			return serialize(new net.minecraft.network.packet.BlockMiningProgressPacket(entityId, x, y, z, stage));
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e); // in memory: cannot happen
		}
	}
}
