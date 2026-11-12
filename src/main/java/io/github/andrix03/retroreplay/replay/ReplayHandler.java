package io.github.andrix03.retroreplay.replay;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.gui.PathingOverlay;
import io.github.andrix03.retroreplay.gui.PlayerOverviewScreen;
import io.github.andrix03.retroreplay.gui.ReplayOverlay;
import io.github.andrix03.retroreplay.gui.ReplayOverlayScreen;
import io.github.andrix03.retroreplay.pathing.CameraRoll;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathPreview;
import io.github.andrix03.retroreplay.pathing.PathingState;
import io.github.andrix03.retroreplay.pathing.Spectator;
import io.github.andrix03.retroreplay.render.VideoRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.game.ChatScreen;
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
	/** More packets than this in one tick: a seek's fast-forward. */
	private static final int FAST_FORWARD_PACKETS = 1000;

	private static ReplayHandler active;
	private static boolean starting;
	/** Between closing the old world and starting the new one: 1.13 draws a loading frame in there. */
	private static boolean seeking;

	private final File file;
	private final int[] times;
	/** The packets on disk, read in order; shared with the handler a seek back starts. */
	private final PacketFile packets;
	/** Chunk packets a fast-forward can leave out; shared like the packets. */
	private final ChunkIndex chunks;
	/** Quick Mode's index (null on versions without it); shared like the packets. */
	private final QuickIndex quick;
	/** Replay Mod's Quick Mode (Q): going back shows the earlier moment over the world, no reload. */
	private static boolean quickMode = true;
	/** An earlier moment shown while the replay bar is dragged back. */
	private QuickOverlay overlay;
	private boolean scrubbing;
	/** A single quick jump: the scrub ends as soon as it was shown. */
	private boolean commitAfterScrub;
	private double scrubTarget;
	/** The world was put back by Quick Mode since the last exact build: approximate in places. */
	private boolean lossy;
	private PacketFile.Reader reader;
	private ReplayConnection client;
	private int index;
	private double timeMs;
	private long lastNanos;
	private int speedStep = DEFAULT_SPEED_STEP;
	private boolean paused;
	private boolean positioned;
	/** The client's "downloading terrain" screen class: open again, a new world started (a connected replay's next part). */
	private Class<?> terrainScreen;
	/** A later login is waiting for its position; once it came the terrain screen is closed here (the client only does it once). */
	private boolean relogin;
	/** Ticks left to close the terrain screen of a later login (from 1.3 it opens a tick or two later). */
	private int closeTerrain;
	private boolean loggedIn;
	/** Camera x, feet y, z, yaw, pitch to restore after a seek rebuilt the world. */
	private double[] camera;
	private double pendingSeek = -1;
	private boolean forceRebuild;
	private final boolean[] keyWasDown = new boolean[Platform.KEY_COUNT];

	private ReplayHandler(File file, int[] times, PacketFile packets, ChunkIndex chunks, QuickIndex quick) {
		this.file = file;
		this.times = times;
		this.packets = packets;
		this.chunks = chunks;
		this.quick = quick;
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
		return scrubbing ? scrubTarget : timeMs;
	}

	/**
	 * Where an entity was at a replay time (x, y, z as the client keeps them, yaw, pitch), from
	 * Quick Mode's index: the path preview draws a spectated entity's way with it. Null if unknown.
	 */
	public double[] entityPositionAt(int id, double replayTime) {
		return quick == null ? null : quick.positionOf(id, packetsUpTo(replayTime));
	}

	/** Quick Mode can be used on this version. */
	public boolean hasQuickMode() {
		return quick != null;
	}

	public static boolean isQuickMode() {
		return quickMode;
	}

	public void toggleQuickMode() {
		quickMode = !quickMode;
		ReplayOverlay.notice("Quick Mode " + (quickMode ? "on" : "off"));
		if (!quickMode && lossy) {
			forceExact(); // leaving Quick Mode: the world is rebuilt exactly where it is
		}
	}

	/**
	 * The replay bar is being dragged to {@code target}: forward the packets are played at once
	 * (no reload), back Quick Mode shows that moment live. Without Quick Mode going back waits
	 * for the release.
	 */
	public void scrubTo(double target) {
		scrubbing = true;
		scrubTarget = Math.max(0, Math.min(target, duration()));
	}

	/** The replay bar was let go: the replay goes on from there. */
	public void endScrub() {
		if (!scrubbing) {
			return;
		}
		scrubbing = false;
		if (overlay != null) {
			commitOverlay();
		} else if (scrubTarget < timeMs - 1) {
			jumpTo(scrubTarget); // exact, rebuilding the world
		} else {
			timeMs = Math.max(timeMs, scrubTarget);
		}
	}

	/** Called each tick while dragging: shows the moment under the mouse. */
	private void applyScrub() {
		double target = scrubTarget;
		int fed = packetsUpTo(target);
		if (fed >= index) {
			if (overlay != null) {
				overlay.showAt(overlay.real()); // back to the real world, then forward as usual
				overlay = null;
			}
			timeMs = Math.max(timeMs, target);
			return;
		}
		if (quick == null || !quickMode || !quick.sameWorld(fed, index)) {
			return; // the release does an exact seek
		}
		if (overlay == null) {
			overlay = new QuickOverlay(quick, index, this::feed, packets::readAt, this::placeEntity);
			MiningCracks.clear(); // not in Quick Mode's index: the next stage written brings it back
		}
		overlay.showAt(fed);
	}

	/**
	 * The moment shown becomes the replay's: playback goes on from its packets. Those up to the
	 * real world are handled a second time, which is exact for positions and blocks.
	 */
	private void commitOverlay() {
		int fed = overlay.shown();
		overlay = null;
		lossy = true;
		closeReader();
		try {
			reader = packets.reader();
			for (int i = 0; i < fed; i++) {
				reader.skip();
			}
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot read " + file.getName() + " for Quick Mode, rebuilding the world", e);
			index = 0;
			forceExact();
			return;
		}
		index = fed;
		timeMs = scrubTarget;
	}

	/** Rebuilds the world exactly at the present time (after Quick Mode, before a video). */
	public void forceExact() {
		lossy = false;
		forceRebuild = true; // a seek to the present time rebuilds only with this
		pendingSeek = timeMs;
	}

	/** True when Quick Mode changed the world since it was last built exactly. */
	public boolean isLossy() {
		return lossy;
	}

	/** How many packets lie at or before {@code time}. */
	private int packetsUpTo(double time) {
		int low = 0;
		int high = times.length;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (times[mid] <= time) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	public int getDuration() {
		return duration();
	}

	/** Jumps there at the end of this tick, exactly (going back, or from a Quick Mode world, rebuilds it). */
	public void jumpTo(double target) {
		pendingSeek = Math.max(0, Math.min(target, duration()));
		if (lossy) {
			forceRebuild = true;
		}
	}

	/** A jump asked from the controls: with Quick Mode going back needs no reload. */
	public void quickJumpTo(double target) {
		if (quick != null && quickMode) {
			scrubTo(target);
			commitAfterScrub = true;
		} else {
			jumpTo(target);
		}
	}

	/**
	 * A playing camera path sets the replay time: forwards plays the packets, backwards goes
	 * through Quick Mode when it is on (a video always seeks exactly).
	 */
	public void setTimeFromPath(double target) {
		target = Math.max(0, Math.min(target, duration()));
		if (scrubbing && commitAfterScrub) {
			scrubTarget = target; // a quick jump is on its way: it goes there instead
		} else if (target < timeMs - 1) {
			if (VideoRenderer.isRendering()) {
				jumpTo(target);
			} else {
				quickJumpTo(target);
			}
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

	/** Opens a replay; returns why it cannot be opened, or null. */
	public static String open(File file) {
		ReplayHandler handler;
		try {
			handler = load(file);
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot open replay " + file, e);
			return e.getMessage() == null ? e.toString() : e.getMessage();
		}
		Minecraft minecraft = Mc.minecraft();
		minecraft.setWorld(null);
		start(handler);
		if (active != handler) {
			return "The replay could not start (see the log)";
		}
		RetroReplay.LOGGER.info("Replay opened: " + file.getName() + " (" + handler.times.length + " packets)");
		return null;
	}

	private static void start(ReplayHandler handler) {
		Minecraft minecraft = Mc.minecraft();
		active = handler;
		starting = true;
		MiningCracks.clear(); // the new world has none yet
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
		net.minecraft.client.gui.screen.Screen terrain = handler.client.terrainScreen();
		handler.terrainScreen = terrain.getClass();
		minecraft.openScreen(terrain);
	}

	/**
	 * Packets only move the world forward, so going back rebuilds it: the replay restarts
	 * and every packet up to the target is fed at once. The camera keeps its place.
	 */
	private void seek(double target) {
		Minecraft minecraft = Mc.minecraft();
		target = Math.max(0, Math.min(target, duration()));
		if (target >= timeMs && !forceRebuild) {
			timeMs = target;
			return;
		}
		forceRebuild = false;
		RetroReplay.LOGGER.info("Exact seek to " + (int) target + " ms: the world is rebuilt");
		closeReader();
		ReplayHandler fresh = new ReplayHandler(file, times, packets, chunks, quick);
		fresh.speedStep = speedStep;
		fresh.paused = paused;
		fresh.timeMs = target;
		if (minecraft.player != null) {
			fresh.camera = new double[] { minecraft.player.x, minecraft.player.y - Mc.feetOffset(), minecraft.player.z,
				minecraft.player.yaw, minecraft.player.pitch };
		}
		active = null;
		seeking = true;
		try {
			minecraft.setWorld(null);
			start(fresh);
		} finally {
			seeking = false;
		}
	}

	/** A seek is rebuilding the world: get() is null for a moment, the replay goes on. */
	public static boolean isSeeking() {
		return seeking;
	}

	private int duration() {
		return times.length == 0 ? 0 : times[times.length - 1];
	}

	public static void stop() {
		if (active != null && !starting) {
			PathPlayer.stop();
			Spectator.clear();
			CameraRoll.reset();
			PathingState.saveCurrent();
			RetroReplay.LOGGER.info("Replay closed: " + active.file.getName());
			active.closeReader();
			active.packets.delete();
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
		if (active != null) {
			CameraControls.afterTick(Mc.minecraft());
		}
		if (active != null && active.pendingSeek >= 0) {
			double target = active.pendingSeek;
			active.pendingSeek = -1;
			active.seek(target);
		}
	}

	private void update() {
		Minecraft minecraft = Mc.minecraft();
		long now = System.nanoTime();
		if (scrubbing) {
			applyScrub();
			if (commitAfterScrub || !(minecraft.screen instanceof ReplayOverlayScreen)) {
				commitAfterScrub = false;
				endScrub(); // a quick jump, or the bar's screen closed while dragging
			}
		} else if (!paused && !PathPlayer.isActive() && !VideoRenderer.isRendering()) { // a camera path sets the time itself
			timeMs = Math.min(timeMs + (now - lastNanos) / 1_000_000.0 * speed(speedStep), duration());
		}
		lastNanos = now;

		if (minecraft.screen instanceof ChatScreen) {
			// chat is useless in a replay: its key frees the mouse for the replay controls instead
			minecraft.openScreen(new ReplayOverlayScreen());
		}
		if (minecraft.screen == null) {
			if (pressed(Platform.KEY_P)) {
				togglePause();
			}
			if (pressed(Platform.KEY_RBRACKET)) {
				setSpeedStep(speedStep + 1);
			}
			if (pressed(Platform.KEY_LBRACKET)) {
				setSpeedStep(speedStep - 1);
			}
			if (pressed(Platform.KEY_LEFT)) {
				quickJumpTo(timeMs - SEEK_STEP_MS);
			}
			if (pressed(Platform.KEY_RIGHT)) {
				quickJumpTo(timeMs + SEEK_STEP_MS);
			}
			// Replay Mod's pathing keys
			if (pressed(Platform.KEY_I)) {
				PathingOverlay.togglePositionKeyframe();
			}
			if (pressed(Platform.KEY_O)) {
				PathingOverlay.toggleTimeKeyframe();
			}
			if (pressed(Platform.KEY_DELETE)) {
				PathingOverlay.removeSelected();
			}
			if (pressed(Platform.KEY_C)) {
				PathingOverlay.clearKeyframes();
			}
			if (pressed(Platform.KEY_H)) {
				PathPreview.toggle();
			}
			if (!PathPlayer.isActive() && !Spectator.isSpectating()) {
				// Replay Mod's roll keys: J / L while held, K back to level
				CameraRoll.turn((Platform.isKeyDown(Platform.KEY_L) ? 1 : 0) - (Platform.isKeyDown(Platform.KEY_J) ? 1 : 0));
				if (pressed(Platform.KEY_K)) {
					CameraRoll.reset();
				}
			}
			if (pressed(Platform.KEY_X)) {
				PathingOverlay.openRepository();
			}
			if (pressed(Platform.KEY_V)) {
				PathingOverlay.syncTimeline();
			}
			if (pressed(Platform.KEY_M)) {
				PathingOverlay.addMarker();
			}
			if (pressed(Platform.KEY_Q) && quick != null) {
				toggleQuickMode();
			}
			if (pressed(Platform.KEY_N)) {
				Thumbnail.request(); // Replay Mod's thumbnail key
			}
			if (pressed(Platform.KEY_B)) {
				minecraft.openScreen(new PlayerOverviewScreen());
			}
			if (pressed(Platform.KEY_LSHIFT) && Spectator.isSpectating() && !PathPlayer.isActive()) {
				Spectator.stop(); // like Replay Mod: sneak to leave spectating
			}
			boolean ctrl = Platform.isKeyDown(Platform.KEY_LCONTROL) || Platform.isKeyDown(Platform.KEY_RCONTROL);
			boolean z = pressed(Platform.KEY_Z);
			if (z && ctrl) {
				PathingOverlay.undo(false);
			} else if (z) {
				CameraControls.toggleAmbient(); // Replay Mod's ambient lighting key
			}
			if (pressed(Platform.KEY_Y) && ctrl) {
				PathingOverlay.undo(true);
			}
		}
		// a wheel turned in a screen (lists scroll) must not change the speed once it closes
		CameraControls.readWheel(minecraft.screen == null);
		double devSeek = DevAutoplay.seekRequest(timeMs);
		if (devSeek >= 0) {
			pendingSeek = devSeek;
		}

		int before = index;
		int skipped = 0;
		long feedStart = System.nanoTime();
		int target = firstAfter(timeMs);
		while (index < target) {
			if (!DevAutoplay.NO_CHUNK_SKIP && chunks.skippable(index, target)) {
				// chunk data unloaded again before this tick's last packet: the world there is the same without it
				if (quick != null) {
					readOldBlocks();
				}
				if (!skipPacket()) {
					break;
				}
				index++;
				skipped++;
				continue;
			}
			byte[] packet = nextPacket();
			if (packet == null) {
				break;
			}
			if (quick != null) {
				readOldBlocks();
			}
			index++;
			feed(packet);
		}
		if (index - before > FAST_FORWARD_PACKETS) {
			// a seek's fast-forward (measured: ~15 us a packet handled, chunk data the most)
			RetroReplay.LOGGER.info("Fast-forwarded " + (index - before) + " packets (" + skipped + " chunk packets left out) in "
				+ (System.nanoTime() - feedStart) / 1_000_000 + " ms");
		}
		if (closeTerrain > 0) {
			closeTerrain--;
			if (terrainScreen != null && terrainScreen.isInstance(minecraft.screen)) {
				minecraft.openScreen(null); // the client closes it only for its first login
				closeTerrain = 0;
			}
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
		CameraControls.beforeTick(minecraft, !PathPlayer.isActive() && !Spectator.isSpectating() && !VideoRenderer.isRendering());
		DevAutoplay.afterTick(minecraft, timeMs, index, times.length);
	}

	public static double currentTime() {
		return active == null ? -1 : active.timeMs;
	}

	/** Quick Mode: an entity put where a teleport ends, at once (x, y, z, yaw, pitch). */
	private void placeEntity(int id, double[] where) {
		Minecraft minecraft = Mc.minecraft();
		client.flush(); // from 1.3 the teleport waits in a queue: handled first, or it would win
		if (minecraft.world == null) {
			return;
		}
		for (Object object : minecraft.world.entities) {
			net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) object;
			if (Mc.entityId(entity) == id) {
				entity.setPosition(where[0], where[1], where[2]);
				entity.prevX = entity.lastX = where[0];
				entity.prevY = entity.lastY = where[1];
				entity.prevZ = entity.lastZ = where[2];
				entity.yaw = (float) where[3];
				entity.pitch = (float) where[4];
				return;
			}
		}
	}

	/**
	 * Quick Mode: the blocks the next packet changes, read before it is handled. From 1.3 the
	 * packets before it wait in a queue (their chunk may be in there): handled first.
	 */
	private void readOldBlocks() {
		if (quick.changesBlocks(index)) {
			client.flush();
			quick.beforePacket(index, Mc::blockState);
		}
	}

	/** Self-tests: a packet handled as if it came from the recording. */
	void feedForTest(byte[] data) {
		feed(data);
	}

	private void feed(byte[] data) {
		int id = data[0] & 0xFF;
		if (drop(id, data)) {
			return;
		}
		Packet packet;
		try {
			packet = Mc.readPacket(data, CAMERA_ID);
		} catch (Exception e) {
			RetroReplay.LOGGER.warn("Cannot read replay packet " + id, e);
			return;
		}
		if (packet != null) {
			client.accept(packet);
		}
	}

	private boolean drop(int id, byte[] data) {
		switch (id) {
			case Mc.PACKET_CUSTOM_PAYLOAD: // custom payload: ours shows the recording player's block cracks;
				MiningCracks.read(data); // mod handshakes and trade/book screens have nothing to show
				return true;
			case Mc.PACKET_HANDSHAKE: // handshake (before 1.3): would log in to minecraft.net for online servers
			case Mc.PACKET_HEALTH: // health: no death screen for the camera
			case Mc.PACKET_ABILITIES: // abilities: keep the camera flying
				return true;
			case Mc.PACKET_POSITION: // position: only the first one places the camera
				if (positioned) {
					return true;
				}
				positioned = true;
				if (relogin) {
					relogin = false;
					closeTerrain = 100;
				}
				return false;
			case Mc.PACKET_LOGIN: // a later one: the next part of a connected replay, placed like the first
				if (loggedIn) {
					positioned = false;
					relogin = true;
				}
				loggedIn = true;
				return false;
			case Mc.PACKET_GAME_STATE: // game state: keep rain, drop game mode and credits
				return data.length > 1 && (data[1] == 3 || data[1] == 4);
			case Mc.PACKET_RESPAWN: // respawn: the camera stays where it is, the world reloads around it
				positioned = false;
				return false;
			default:
				return false;
		}
	}

	private boolean pressed(int key) {
		boolean down = Platform.isKeyDown(key);
		boolean was = keyWasDown[key];
		keyWasDown[key] = down;
		return down && !was;
	}

	/** The index of the first packet later than {@code time}. */
	private int firstAfter(double time) {
		int low = index;
		int high = times.length;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (times[mid] <= time) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	/** Passes over the next packet on disk; false when the file cannot be read (playback then stops). */
	private boolean skipPacket() {
		try {
			if (reader == null) {
				reader = packets.reader();
			}
			reader.skip();
			return true;
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot read packet " + index + " of " + file.getName(), e);
			index = times.length;
			return false;
		}
	}

	/** The next packet from disk, or null when the file cannot be read (playback then stops). */
	private byte[] nextPacket() {
		try {
			if (reader == null) {
				reader = packets.reader();
			}
			return reader.next();
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot read packet " + index + " of " + file.getName(), e);
			index = times.length;
			return null;
		}
	}

	private void closeReader() {
		if (reader != null) {
			try {
				reader.close();
			} catch (IOException e) {
				// nothing left to do with it
			}
			reader = null;
		}
	}

	private static ReplayHandler load(File file) throws IOException {
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
			ChunkIndex.Builder chunks = new ChunkIndex.Builder(Mc.CHUNK_FORMAT);
			QuickIndex.Builder quick = QuickIndex.supports(Mc.VERSION) ? new QuickIndex.Builder(Mc.CHUNK_FORMAT, Mc.VERSION) : null;
			PacketFile packets = PacketFile.unpack(zip.getInputStream(recording), (i, data, length) -> {
				chunks.packet(i, data, length);
				if (quick != null) {
					quick.packet(i, data, length);
				}
			});
			if (packets.times().length == 0) {
				// without even the login packet the world never loads: the terrain screen would stay forever
				packets.delete();
				throw new IOException("This replay is empty");
			}
			return new ReplayHandler(file, packets.times(), packets, chunks.build(), quick == null ? null : quick.build());
		}
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
