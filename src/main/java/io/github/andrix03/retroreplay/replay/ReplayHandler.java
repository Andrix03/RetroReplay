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
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.gui.PathingOverlay;
import io.github.andrix03.retroreplay.gui.PlayerOverviewScreen;
import io.github.andrix03.retroreplay.gui.ReplayOverlayScreen;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathPreview;
import io.github.andrix03.retroreplay.pathing.PathingState;
import io.github.andrix03.retroreplay.pathing.Spectator;
import io.github.andrix03.retroreplay.render.VideoRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.game.ChatScreen;
import net.minecraft.client.gui.screen.menu.multiplayer.DownloadingTerrainScreen;
import net.minecraft.network.packet.LoginPacket;
import net.minecraft.network.packet.Packet;

/**
 * Plays a recording back: a ClientNetworkHandler is built with nothing on the other end
 * (ReplayConnection, per version) and the recorded packets are fed into it at their
 * recorded times. The local player is the free camera.
 */
public final class ReplayHandler {

	/** Replay Mod's speed slider: 37 steps, 0.1x to 1x by 0.1, then up to 7.75x by 0.25. */
	public static final int SPEED_STEPS = 37;
	public static final int DEFAULT_SPEED_STEP = 9;

	/**
	 * The camera gets its own entity id, so the recorded player (spawned by the packets
	 * LocalPlayerRecorder wrote) is an ordinary entity that every recorded packet can address.
	 */
	public static final int CAMERA_ID = -1_000_000;

	private static final double SEEK_STEP_MS = 10_000;

	private static ReplayHandler active;
	private static boolean starting;

	private final File file;
	private final int[] times;
	private final byte[][] packets;
	private ReplayConnection client;
	private int index;
	private double timeMs;
	private long lastNanos;
	private int speedStep = DEFAULT_SPEED_STEP;
	private boolean paused;
	private boolean positioned;
	/** Camera x, feet y, z, yaw, pitch to restore after a seek rebuilt the world. */
	private double[] camera;
	private double pendingSeek = -1;
	private final boolean[] keyWasDown = new boolean[256];

	private ReplayHandler(File file, int[] times, byte[][] packets) {
		this.file = file;
		this.times = times;
		this.packets = packets;
	}

	public static ReplayHandler get() {
		return active;
	}

	public static double speed(int step) {
		return step < 10 ? (step + 1) / 10.0 : 1 + (step - 9) * 0.25;
	}

	public boolean isPaused() {
		return paused;
	}

	public void togglePause() {
		paused = !paused;
	}

	public int getSpeedStep() {
		return speedStep;
	}

	public void setSpeedStep(int step) {
		speedStep = Math.max(0, Math.min(SPEED_STEPS - 1, step));
	}

	public double getTime() {
		return timeMs;
	}

	public int getDuration() {
		return duration();
	}

	/** Jumps there at the end of this tick (going back rebuilds the world). */
	public void jumpTo(double target) {
		pendingSeek = Math.max(0, Math.min(target, duration()));
	}

	/** A playing camera path sets the replay time: forwards plays the packets, backwards seeks. */
	public void setTimeFromPath(double target) {
		target = Math.max(0, Math.min(target, duration()));
		if (target < timeMs - 1) {
			jumpTo(target);
		} else {
			timeMs = target;
		}
	}

	public File getFile() {
		return file;
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
		Minecraft minecraft = Mc.minecraft();
		minecraft.setWorld(null);
		start(handler);
		if (active == handler) {
			RetroReplay.LOGGER.info("Replay opened: " + file.getName() + " (" + handler.times.length + " packets)");
		}
	}

	private static void start(ReplayHandler handler) {
		Minecraft minecraft = Mc.minecraft();
		active = handler;
		starting = true;
		try {
			handler.client = ReplayConnection.open(minecraft);
		} catch (Exception e) {
			active = null;
			RetroReplay.LOGGER.error("Cannot start replay", e);
			return;
		} finally {
			starting = false;
		}
		handler.lastNanos = System.nanoTime();
		// without a world the client expects a screen until the login packet builds one
		minecraft.openScreen(new DownloadingTerrainScreen(handler.client.handler));
	}

	/**
	 * Packets only move the world forward, so going back rebuilds it: the replay restarts
	 * and every packet up to the target is fed at once. The camera keeps its place.
	 */
	private void seek(double target) {
		Minecraft minecraft = Mc.minecraft();
		target = Math.max(0, Math.min(target, duration()));
		if (target >= timeMs) {
			timeMs = target;
			return;
		}
		ReplayHandler fresh = new ReplayHandler(file, times, packets);
		fresh.speedStep = speedStep;
		fresh.paused = paused;
		fresh.timeMs = target;
		if (minecraft.player != null) {
			fresh.camera = new double[] { minecraft.player.x, minecraft.player.y - minecraft.player.eyeHeight, minecraft.player.z,
				minecraft.player.yaw, minecraft.player.pitch };
		}
		active = null;
		minecraft.setWorld(null);
		start(fresh);
	}

	private int duration() {
		return times.length == 0 ? 0 : times[times.length - 1];
	}

	public static void stop() {
		if (active != null && !starting) {
			PathPlayer.stop();
			Spectator.clear();
			PathingState.saveCurrent();
			RetroReplay.LOGGER.info("Replay closed: " + active.file.getName());
			active = null;
		}
	}

	public static void tick() {
		if (active != null && active.client != null) {
			active.update();
		}
	}

	/** End of the client tick: the only safe moment to tear the world down for a seek. */
	public static void tickEnd() {
		if (active != null && active.pendingSeek >= 0) {
			double target = active.pendingSeek;
			active.pendingSeek = -1;
			active.seek(target);
		}
	}

	private void update() {
		Minecraft minecraft = Mc.minecraft();
		long now = System.nanoTime();
		if (!paused && !PathPlayer.isActive() && !VideoRenderer.isRendering()) { // a camera path sets the time itself
			timeMs = Math.min(timeMs + (now - lastNanos) / 1_000_000.0 * speed(speedStep), duration());
		}
		lastNanos = now;

		if (minecraft.screen instanceof ChatScreen) {
			// chat is useless in a replay: its key frees the mouse for the replay controls instead
			minecraft.openScreen(new ReplayOverlayScreen());
		}
		if (minecraft.screen == null) {
			if (pressed(Keyboard.KEY_P)) {
				togglePause();
			}
			if (pressed(Keyboard.KEY_RBRACKET)) {
				setSpeedStep(speedStep + 1);
			}
			if (pressed(Keyboard.KEY_LBRACKET)) {
				setSpeedStep(speedStep - 1);
			}
			if (pressed(Keyboard.KEY_LEFT)) {
				jumpTo(timeMs - SEEK_STEP_MS);
			}
			if (pressed(Keyboard.KEY_RIGHT)) {
				jumpTo(timeMs + SEEK_STEP_MS);
			}
			// Replay Mod's pathing keys
			if (pressed(Keyboard.KEY_I)) {
				PathingOverlay.togglePositionKeyframe();
			}
			if (pressed(Keyboard.KEY_O)) {
				PathingOverlay.toggleTimeKeyframe();
			}
			if (pressed(Keyboard.KEY_DELETE)) {
				PathingOverlay.removeSelected();
			}
			if (pressed(Keyboard.KEY_C)) {
				PathingOverlay.clearKeyframes();
			}
			if (pressed(Keyboard.KEY_H)) {
				PathPreview.toggle();
			}
			if (pressed(Keyboard.KEY_B)) {
				minecraft.openScreen(new PlayerOverviewScreen());
			}
			if (pressed(Keyboard.KEY_LSHIFT) && Spectator.isSpectating() && !PathPlayer.isActive()) {
				Spectator.stop(); // like Replay Mod: sneak to leave spectating
			}
			boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
			if (pressed(Keyboard.KEY_Z) && ctrl) {
				PathingOverlay.undo(false);
			}
			if (pressed(Keyboard.KEY_Y) && ctrl) {
				PathingOverlay.undo(true);
			}
		}
		double devSeek = DevAutoplay.seekRequest(timeMs);
		if (devSeek >= 0) {
			pendingSeek = devSeek;
		}

		while (index < times.length && times[index] <= timeMs) {
			feed(packets[index++]);
		}
		Spectator.follow();

		if (minecraft.player != null) {
			minecraft.player.abilities.canFly = true;
			minecraft.player.abilities.flying = true;
			minecraft.player.abilities.invulnerable = true;
			minecraft.player.noClip = true;
			if (camera != null && positioned) {
				minecraft.player.setPositionAndAngles(camera[0], camera[1], camera[2], (float) camera[3], (float) camera[4]);
				camera = null;
			}
		}
		DevAutoplay.afterTick(minecraft, timeMs, index, times.length);
	}

	public static double currentTime() {
		return active == null ? -1 : active.timeMs;
	}

	private void feed(byte[] data) {
		int id = data[0] & 0xFF;
		if (drop(id, data)) {
			return;
		}
		Minecraft minecraft = Mc.minecraft();
		Packet packet = Mc.createPacket(id);
		if (packet == null) {
			return;
		}
		try {
			packet.read(new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1)));
		} catch (Exception e) {
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
			case 0x02: // handshake (before 1.3): would log in to minecraft.net for online servers
			case 0x08: // health: no death screen for the camera
			case 0xCA: // abilities: keep the camera flying
			case 0xFA: // custom payload: mod handshakes and trade/book screens, nothing to show
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
			if (!json.contains("\"fileFormat\":\"MCPR_LEGACY\"") || !json.contains("\"mcversion\":\"" + Mc.VERSION + "\"")) {
				throw new IOException("Not a RetroReplay " + Mc.VERSION + " recording");
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
		File[] files = new File(Mc.minecraft().gameDir, "replay_recordings").listFiles((dir, name) -> name.endsWith(".mcpr"));
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
