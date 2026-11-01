package io.github.andrix03.retroreplay.recording;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.pathing.Markers;

import net.minecraft.network.packet.Packet;

/**
 * Records the packets the client receives into a .tmcpr stream (int32 time ms, int32 length,
 * packet id byte + payload) and packs it with metadata into a .mcpr zip when the world closes.
 * Packets arrive on the network thread (multiplayer) or the server thread (singleplayer).
 */
public final class Recorder {

	private static Recorder current;

	private final File tmcpr;
	private final File target;
	private final boolean singleplayer;
	private final String serverName;
	private final long startDate = System.currentTimeMillis();
	private final long startNanos = System.nanoTime();
	private final Set<Class<?>> failedTypes = new HashSet<>();
	private final Markers markers = new Markers();
	private DataOutputStream out;
	private int lastTime;
	private int packetCount;
	private long pausedSince;
	private long pausedNanos;

	private Recorder(File folder, boolean singleplayer, String serverName) throws IOException {
		String name = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date(startDate));
		this.target = new File(folder, name + ".mcpr");
		this.tmcpr = new File(folder, name + ".tmcpr");
		this.singleplayer = singleplayer;
		this.serverName = serverName;
		this.out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmcpr)));
	}

	public static synchronized void start(boolean singleplayer, String serverName) {
		stop();
		File folder = new File(Mc.minecraft().gameDir, "replay_recordings");
		if (!folder.isDirectory() && !folder.mkdirs()) {
			RetroReplay.LOGGER.error("Cannot create " + folder);
			return;
		}
		try {
			current = new Recorder(folder, singleplayer, serverName);
			RetroReplay.LOGGER.info("Recording started: " + current.target.getName());
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot start recording", e);
		}
	}

	public static synchronized boolean isRecording() {
		return current != null;
	}

	public static synchronized boolean isPaused() {
		return current != null && current.pausedSince != 0;
	}

	public static synchronized void record(Packet packet) {
		if (current != null) {
			current.write(packet);
		}
	}

	/** Singleplayer pause menu: the world stands still, so the recording clock does too. */
	public static synchronized void setPaused(boolean paused) {
		if (current == null || !current.singleplayer || paused == (current.pausedSince != 0)) {
			return;
		}
		long now = System.nanoTime();
		if (paused) {
			current.pausedSince = now;
		} else {
			current.pausedNanos += now - current.pausedSince;
			current.pausedSince = 0;
		}
	}

	private int elapsedMs() {
		long now = pausedSince != 0 ? pausedSince : System.nanoTime();
		return (int) ((now - startNanos - pausedNanos) / 1_000_000L);
	}

	public static synchronized void stop() {
		if (current != null) {
			Recorder recorder = current;
			current = null;
			recorder.finish();
		}
	}

	/**
	 * A crash leaves the raw .tmcpr of the recording behind: pack its complete packets into a
	 * .mcpr, like Replay Mod recovers interrupted replays. Whether it was singleplayer is lost.
	 */
	public static synchronized void recoverLeftovers(File folder) {
		File[] raw = folder.listFiles((dir, name) -> name.endsWith(".tmcpr"));
		if (raw == null) {
			return;
		}
		for (File tmcpr : raw) {
			if (current != null && tmcpr.equals(current.tmcpr)) {
				continue;
			}
			String base = tmcpr.getName().substring(0, tmcpr.getName().length() - ".tmcpr".length());
			File target = new File(folder, base + ".mcpr");
			if (target.exists()) {
				continue; // packed already, the raw file only failed to be deleted
			}
			try {
				long valid = 0;
				int lastTime = 0;
				long length = tmcpr.length();
				try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(tmcpr)))) {
					while (valid + 8 <= length) {
						int time = in.readInt();
						int size = in.readInt();
						if (size < 1 || valid + 8 + size > length) {
							break; // cut off in the middle of a packet
						}
						in.readFully(new byte[size]);
						valid += 8 + size;
						lastTime = time;
					}
				}
				if (valid == 0) {
					// nothing was recorded (the game stopped before the first packet): no replay to make
					if (!tmcpr.delete()) {
						tmcpr.deleteOnExit();
					}
					RetroReplay.LOGGER.info("Removed the empty leftover " + tmcpr.getName());
					continue;
				}
				if (valid < length) {
					try (RandomAccessFile file = new RandomAccessFile(tmcpr, "rw")) {
						file.setLength(valid);
					}
				}
				long recordedAt = tmcpr.lastModified();
				ReplayMetadata meta = new ReplayMetadata();
				meta.serverName = "Recovered";
				meta.duration = lastTime;
				meta.date = recordedAt - lastTime;
				meta.mcversion = Mc.VERSION;
				meta.protocol = Mc.PROTOCOL;
				ReplayFiles.pack(tmcpr, target, meta);
				target.setLastModified(recordedAt); // sorted with the time it was recorded, not today
				if (!tmcpr.delete()) {
					tmcpr.deleteOnExit();
				}
				RetroReplay.LOGGER.info("Recovered recording " + target.getName() + " (" + lastTime + " ms)");
			} catch (IOException e) {
				RetroReplay.LOGGER.error("Cannot recover " + tmcpr, e);
			}
		}
	}

	private void write(Packet packet) {
		byte[] data;
		try {
			data = Mc.serialize(packet);
		} catch (Exception e) {
			if (failedTypes.add(packet.getClass())) {
				RetroReplay.LOGGER.warn("Cannot serialize packet " + Mc.packetName(packet), e);
			}
			return;
		}
		append(data);
	}

	/** Replay Mod's M key while recording: an event marker at this moment; false when not recording. */
	public static synchronized boolean addMarker() {
		if (current == null) {
			return false;
		}
		current.markers.put(current.elapsedMs(), "");
		return true;
	}

	/** A multiplayer packet exactly as it came off the wire (id byte + payload). */
	public static synchronized void recordRaw(byte[] data) {
		if (current != null) {
			current.append(data);
		}
	}

	/** Entity event statuses (the same in every pre-Netty version). */
	public static final byte EVENT_HURT = 2;
	public static final byte EVENT_DEATH = 3;

	/** Entity event 0x26: int entity id, byte status. Written by hand, client jars may lack its constructor. */
	public static void recordEntityEvent(int entityId, byte status) {
		recordRaw(new byte[] { 0x26, (byte) (entityId >> 24), (byte) (entityId >> 16), (byte) (entityId >> 8), (byte) entityId, status });
	}

	private void append(byte[] data) {
		try {
			lastTime = Math.max(lastTime, elapsedMs());
			out.writeInt(lastTime);
			out.writeInt(data.length);
			out.write(data);
			packetCount++;
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Recording write failed, stopping", e);
			current = null;
			finish();
		}
	}

	private void finish() {
		try {
			out.close();
			ReplayFiles.pack(tmcpr, target, metadata(), markers.isEmpty() ? null : markers.toJson());
			if (!tmcpr.delete()) {
				tmcpr.deleteOnExit();
			}
			RetroReplay.LOGGER.info("Recording saved: " + target + " (" + packetCount + " packets, " + lastTime + " ms)");
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot save recording, raw data kept in " + tmcpr, e);
		}
	}

	private ReplayMetadata metadata() {
		ReplayMetadata meta = new ReplayMetadata();
		meta.singleplayer = singleplayer;
		meta.serverName = serverName;
		meta.duration = lastTime;
		meta.date = startDate;
		meta.mcversion = Mc.VERSION;
		meta.protocol = Mc.PROTOCOL;
		return meta;
	}
}
