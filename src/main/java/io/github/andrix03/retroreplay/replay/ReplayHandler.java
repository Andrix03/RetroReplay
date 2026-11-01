package io.github.andrix03.retroreplay.replay;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.lwjgl.input.Keyboard;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.mixin.MinecraftAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.LocalConnection;
import net.minecraft.network.packet.LoginPacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.integrated.IntegratedServer;

/**
 * Plays a recording back: a ClientNetworkHandler is built like for singleplayer, but its
 * LocalConnection is bound to a sink instead of the integrated server, and the recorded
 * packets are fed into it at their recorded times. The local player is the free camera.
 */
public final class ReplayHandler {

	private static final double[] SPEEDS = { 0.1, 0.25, 0.5, 1, 2, 4, 8 };

	/**
	 * The camera gets its own entity id, so the recorded player (spawned by the packets
	 * LocalPlayerRecorder wrote) is an ordinary entity that every recorded packet can address.
	 */
	public static final int CAMERA_ID = -1_000_000;

	private static ReplayHandler active;
	private static boolean starting;

	private final File file;
	private final int[] times;
	private final byte[][] packets;
	private LocalConnection client;
	private int index;
	private double timeMs;
	private long lastNanos;
	private int speedIndex = 3;
	private boolean paused;
	private boolean positioned;
	private final boolean[] keyWasDown = new boolean[256];

	private ReplayHandler(File file, int[] times, byte[][] packets) {
		this.file = file;
		this.times = times;
		this.packets = packets;
	}

	public static boolean isStarting() {
		return starting;
	}

	public static boolean isActive() {
		return active != null;
	}

	public static void open(File file) {
		ReplayHandler handler;
		try {
			handler = load(file);
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot open replay " + file, e);
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.setWorld(null);
		active = handler;
		starting = true;
		try {
			ClientNetworkHandler networkHandler = new ClientNetworkHandler(minecraft, (IntegratedServer) null);
			((MinecraftAccessor) minecraft).retroreplay$setConnection(networkHandler.getConnection());
			handler.client = (LocalConnection) networkHandler.getConnection();
		} catch (IOException e) {
			active = null;
			RetroReplay.LOGGER.error("Cannot start replay", e);
			return;
		} finally {
			starting = false;
		}
		handler.lastNanos = System.nanoTime();
		RetroReplay.LOGGER.info("Replay opened: " + file.getName() + " (" + handler.times.length + " packets)");
	}

	/** Called when the client binds its connection: give it a sink as the other end. */
	public static void bind(LocalConnection clientConnection) {
		new SinkConnection(Minecraft.getInstance().getLogger()).bind(clientConnection);
	}

	public static void stop() {
		if (active != null && !starting) {
			RetroReplay.LOGGER.info("Replay closed: " + active.file.getName());
			active = null;
		}
	}

	public static void tick() {
		if (active != null && active.client != null) {
			active.update();
		}
	}

	private void update() {
		Minecraft minecraft = Minecraft.getInstance();
		long now = System.nanoTime();
		if (!paused) {
			timeMs += (now - lastNanos) / 1_000_000.0 * SPEEDS[speedIndex];
		}
		lastNanos = now;

		if (minecraft.screen == null) {
			if (pressed(Keyboard.KEY_P)) {
				paused = !paused;
				chat(paused ? "Replay paused" : "Replay playing");
			}
			if (pressed(Keyboard.KEY_RBRACKET) && speedIndex < SPEEDS.length - 1) {
				speedIndex++;
				chat("Replay speed " + SPEEDS[speedIndex] + "x");
			}
			if (pressed(Keyboard.KEY_LBRACKET) && speedIndex > 0) {
				speedIndex--;
				chat("Replay speed " + SPEEDS[speedIndex] + "x");
			}
		}

		while (index < times.length && times[index] <= timeMs) {
			feed(packets[index++]);
		}

		if (minecraft.player != null) {
			minecraft.player.abilities.canFly = true;
			minecraft.player.abilities.flying = true;
			minecraft.player.abilities.invulnerable = true;
		}
		DevAutoplay.afterTick(minecraft, timeMs, index, times.length);
	}

	private void feed(byte[] data) {
		int id = data[0] & 0xFF;
		if (drop(id, data)) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Packet packet = Packet.create(minecraft.getLogger(), id);
		if (packet == null) {
			return;
		}
		try {
			packet.read(new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1)));
		} catch (IOException e) {
			RetroReplay.LOGGER.warn("Cannot read replay packet " + id, e);
			return;
		}
		if (packet instanceof LoginPacket) {
			((LoginPacket) packet).gameVersion = CAMERA_ID; // the field is the player's entity id
		}
		client.accept(packet);
	}

	private boolean drop(int id, byte[] data) {
		switch (id) {
			case 0x08: // health: no death screen for the camera
			case 0xCA: // abilities: keep the camera flying
				return true;
			case 0x0D: // position: only the first one places the camera
				if (positioned) {
					return true;
				}
				positioned = true;
				return false;
			case 0x46: // game state: keep rain, drop game mode and credits
				return data.length > 1 && (data[1] == 3 || data[1] == 4);
			case 0x09: // respawn: the camera stays where it is, the world reloads around it
				positioned = false;
				return false;
			default:
				return false;
		}
	}

	private boolean pressed(int key) {
		boolean down = Keyboard.isKeyDown(key);
		boolean was = keyWasDown[key];
		keyWasDown[key] = down;
		return down && !was;
	}

	private static void chat(String message) {
		Minecraft.getInstance().gui.getChat().addMessage(message);
	}

	private static int readInt(byte[] data, int offset) {
		return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | data[offset + 3] & 0xFF;
	}

	private static ReplayHandler load(File file) throws IOException {
		List<Integer> times = new ArrayList<>();
		List<byte[]> packets = new ArrayList<>();
		try (ZipFile zip = new ZipFile(file)) {
			ZipEntry meta = zip.getEntry("metaData.json");
			ZipEntry recording = zip.getEntry("recording.tmcpr");
			if (meta == null || recording == null) {
				throw new IOException("Not a replay file");
			}
			String json = new String(readAll(zip.getInputStream(meta)), StandardCharsets.UTF_8);
			if (!json.contains("\"fileFormat\":\"MCPR_LEGACY\"") || !json.contains("\"mcversion\":\"1.6.4\"")) {
				throw new IOException("Not a RetroReplay 1.6.4 recording");
			}
			try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(zip.getInputStream(recording)))) {
				while (true) {
					int time;
					try {
						time = in.readInt();
					} catch (EOFException end) {
						break;
					}
					byte[] data = new byte[in.readInt()];
					in.readFully(data);
					times.add(time);
					packets.add(data);
				}
			}
		}
		int[] timeArray = new int[times.size()];
		for (int i = 0; i < timeArray.length; i++) {
			timeArray[i] = times.get(i);
		}
		return new ReplayHandler(file, timeArray, packets.toArray(new byte[0][]));
	}

	private static byte[] readAll(InputStream in) throws IOException {
		try (InputStream input = in) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int n;
			while ((n = input.read(chunk)) > 0) {
				out.write(chunk, 0, n);
			}
			return out.toByteArray();
		}
	}

	/** The newest .mcpr in the recordings folder, or null. */
	public static File latestRecording() {
		File[] files = new File(Minecraft.getInstance().gameDir, "replay_recordings").listFiles((dir, name) -> name.endsWith(".mcpr"));
		File latest = null;
		if (files != null) {
			for (File f : files) {
				if (latest == null || f.lastModified() > latest.lastModified()) {
					latest = f;
				}
			}
		}
		return latest;
	}
}
