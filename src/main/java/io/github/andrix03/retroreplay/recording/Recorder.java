package io.github.andrix03.retroreplay.recording;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import io.github.andrix03.retroreplay.RetroReplay;

import net.minecraft.client.Minecraft;
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
	private final Set<Integer> failedIds = new HashSet<>();
	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	private final DataOutputStream bufferData = new DataOutputStream(buffer);
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
		File folder = new File(Minecraft.getInstance().gameDir, "replay_recordings");
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

	private void write(Packet packet) {
		int id = packet.getId();
		try {
			buffer.reset();
			bufferData.writeByte(id);
			packet.write(bufferData);
		} catch (Exception e) {
			if (failedIds.add(id)) {
				RetroReplay.LOGGER.warn("Cannot serialize packet " + id + " (" + packet.getClass().getSimpleName() + ")", e);
			}
			return;
		}
		append(buffer.toByteArray());
	}

	/** A multiplayer packet exactly as it came off the wire (id byte + payload). */
	public static synchronized void recordRaw(byte[] data) {
		if (current != null) {
			current.append(data);
		}
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
			ReplayFiles.pack(tmcpr, target, metadata());
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
		meta.mcversion = "1.6.4";
		meta.protocol = 78;
		return meta;
	}
}
