package io.github.andrix03.retroreplay.compat;

import java.io.IOException;
import java.io.InputStream;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.mixin.KeyBindingAccessor;
import io.github.andrix03.retroreplay.mixin.LoginS2CPacketAccessor;
import io.netty.buffer.Unpooled;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.client.render.platform.NativeImage;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.network.NetworkProtocol;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.PacketFlow;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.LoginS2CPacket;
import net.minecraft.resource.Identifier;

/** What differs between Minecraft versions, for the shared code. This is the 1.13.2 one. */
public final class Mc {

	public static final String VERSION = "1.13.2";
	public static final int PROTOCOL = 404;

	/** How chunk packets look, for the seek index. */
	public static final int CHUNK_FORMAT = io.github.andrix03.retroreplay.replay.ChunkIndex.FLATTENED;

	/** Packet ids the replay treats specially (play state, server to client). */
	public static final int PACKET_HANDSHAKE = -1; // not a play packet any more
	public static final int PACKET_HEALTH = 0x44;
	public static final int PACKET_ABILITIES = 0x2E;
	public static final int PACKET_CUSTOM_PAYLOAD = 0x19;
	public static final int PACKET_POSITION = 0x32;
	public static final int PACKET_GAME_STATE = 0x20;
	public static final int PACKET_RESPAWN = 0x38;

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
	 * One of "replay_gui", "jgui", "logo", "camera_head", "widgets". The mod's own textures are read
	 * from its jar and registered once (no resource loader mod is needed).
	 */
	public static void bindTexture(String name) {
		Identifier texture = name.equals("replay_gui") ? REPLAY_GUI : name.equals("jgui") ? JGUI : name.equals("logo") ? LOGO : name.equals("camera_head") ? CAMERA_HEAD : WIDGETS;
		TextureManager textures = minecraft().getTextureManager();
		if (texture != WIDGETS && textures.get(texture) == null) {
			String path = "/assets/retroreplay/" + texture.getPath();
			try (InputStream in = Mc.class.getResourceAsStream(path)) {
				textures.register(texture, new DynamicTexture(NativeImage.read(in)));
			} catch (IOException | RuntimeException e) {
				RetroReplay.LOGGER.error("Cannot load " + path, e);
				return;
			}
		}
		textures.bind(texture);
	}

	/** A packet as the replay file stores it: VarInt id + payload, like Replay Mod. */
	public static byte[] serialize(Packet<?> packet) throws IOException {
		Integer id;
		try {
			id = NetworkProtocol.PLAY.getPacketId(PacketFlow.CLIENTBOUND, packet);
		} catch (Exception e) {
			throw new IOException("Not a play packet", e);
		}
		if (id == null) {
			throw new IOException("Not a play packet");
		}
		PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
		buf.writeVarInt(id);
		packet.write(buf);
		byte[] data = new byte[buf.readableBytes()];
		buf.readBytes(data);
		return data;
	}

	public static String packetName(Packet<?> packet) {
		return packet.getClass().getSimpleName();
	}

	/** A stored packet read back; the login packet's player id becomes the replay camera's. */
	public static Packet<?> readPacket(byte[] data, int cameraId) throws IOException {
		PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(data));
		Packet<?> packet;
		try {
			packet = NetworkProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, buf.readVarInt());
		} catch (ReflectiveOperationException e) {
			throw new IOException("Cannot create the packet", e);
		}
		if (packet == null) {
			return null;
		}
		packet.read(buf);
		if (packet instanceof LoginS2CPacket) {
			((LoginS2CPacketAccessor) packet).retroreplay$setEntityId(cameraId);
		}
		return packet;
	}

	public static int entityId(Object entity) {
		return ((net.minecraft.entity.Entity) entity).getNetworkId();
	}

	public static int chatKeyCode() {
		return ((KeyBindingAccessor) minecraft().options.chatKey).retroreplay$getKey().getValue();
	}

	public static Screen confirmScreen(Screen parent, String title, String message, String yes, String no, int id) {
		return new net.minecraft.client.gui.screen.ConfirmScreen(parent::confirmResult, title, message, yes, no, id);
	}

	/** The GUI's width in scaled pixels. */
	public static int scaledWidth() {
		return minecraft().window.getGuiScaledWidth();
	}

	public static void screenshot(String name) {
		Minecraft minecraft = minecraft();
		net.minecraft.client.Screenshot.take(minecraft.gameDir, name, Platform.width(), Platform.height(), minecraft.getRenderTarget(),
			text -> { });
	}

	public static boolean isPlayer(Object entity) {
		return entity instanceof net.minecraft.entity.living.player.PlayerEntity;
	}

	/** A player's name, or null for other entities. */
	public static String playerName(Object entity) {
		return entity instanceof net.minecraft.entity.living.player.PlayerEntity
			? ((net.minecraft.entity.living.player.PlayerEntity) entity).getGameProfile().getName() : null;
	}

	/** Watches the world through that entity's eyes (null: back to the free camera); false if it cannot. */
	public static boolean spectate(Object entity) {
		Minecraft minecraft = minecraft();
		if (entity == null) {
			minecraft.setCamera(minecraft.player);
			return true;
		}
		if (!(entity instanceof net.minecraft.entity.living.LivingEntity)) {
			return false;
		}
		minecraft.setCamera((net.minecraft.entity.Entity) entity);
		return true;
	}

	/** The entity the camera looks through. */
	public static Object cameraEntity() {
		return minecraft().getCamera();
	}

	/** The game draws into its own framebuffer: a video frame is drawn into it at the video's size. */
	public static boolean resizeGameTarget(int width, int height) {
		minecraft().getRenderTarget().resize(width, height);
		return true;
	}

	public static void bindGameTarget() {
		minecraft().getRenderTarget().bindWrite(true);
	}

	/** The light-level-to-brightness table of the world shown now (null without a world). */
	public static float[] brightnessTable() {
		return minecraft().world == null ? null : minecraft().world.dimension.getBrightnessTable();
	}

	/** How far the local player's y is above its feet: 0, y is at the feet since 1.8. */
	public static double feetOffset() {
		return 0;
	}

	public static void swingArm() {
		minecraft().player.swingArm(net.minecraft.world.InteractionHand.MAIN_HAND);
	}

	/** How high the camera's eyes are above its feet. */
	public static double eyeHeight() {
		return minecraft().player.getEyeHeight();
	}

	/** Dev self-test only: opens (or creates, seed 1234) a survival singleplayer world. */
	public static void startSelftestWorld(String name) {
		minecraft().startGame(name, name, new net.minecraft.world.WorldSettings(1234L, net.minecraft.world.GameMode.byId(Integer.parseInt(System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_GAMEMODE", "0"))), true, false,
			net.minecraft.world.gen.WorldGeneratorType.DEFAULT));
	}

	/** Dev self-test only. */
	public static Screen connectScreen(Screen parent, String host, int port) {
		return new ConnectScreen(parent, minecraft(), host, port);
	}

	/** The block state's id at a position, or -1 when its chunk is not loaded (Quick Mode). */
	public static int blockState(int x, int y, int z) {
		net.minecraft.world.World world = minecraft().world;
		net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
		if (world == null || !world.isLoaded(pos)) {
			return -1;
		}
		return net.minecraft.block.Block.serialize(world.getBlockState(pos));
	}

	/** Block cracks in replays: from 1.3 the vanilla packet draws them, nothing to do here. */
	public static void renderMiningCrack(int x, int y, int z, int stage, float tickDelta) {
	}

	/** The vanilla packet for a block's cracks (stage -1 = none), or null before 1.3 (MiningCracks writes its own). */
	public static byte[] miningPacket(int entityId, int x, int y, int z, int stage) {
		try {
			return serialize(new net.minecraft.network.packet.s2c.play.BlockMiningProgressS2CPacket(entityId, new net.minecraft.util.math.BlockPos(x, y, z), stage));
		} catch (java.io.IOException e) {
			throw new IllegalStateException(e); // in memory: cannot happen
		}
	}
}
