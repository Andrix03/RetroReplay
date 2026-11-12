package io.github.andrix03.retroreplay.replay;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.recording.Recorder;

/**
 * The cracks on a block the recording player was breaking. A server tells the other clients
 * nothing about it before 1.3 (no packet for it), and never tells the breaking client, so the
 * recording has it only if written here: a custom payload packet (0xFA, channel {@link #CHANNEL})
 * each time the crack stage changes. Old clients ignore it; the replay draws it with the game's
 * own crack rendering.
 */
public final class MiningCracks {

	public static final String CHANNEL = "RR|Mine";
	private static final int CUSTOM_PAYLOAD = 0xFA;

	private static int lastX, lastY, lastZ;
	private static int lastStage = -1;

	/** In the replay: the block cracking and its stage (0-9), or -1. */
	private static int x, y, z;
	private static int stage = -1;

	private MiningCracks() {
	}

	/** A new recording (or the player spawned again): the next stage is written whatever it is. */
	public static void resetRecording() {
		lastStage = -1;
	}

	/**
	 * Each recorded tick: where the player is breaking and how far (0 to 1), written when the stage
	 * changes. From 1.3 as the vanilla packet other clients get (the game draws it), before as ours.
	 */
	public static void recordTick(int entityId, boolean mining, int blockX, int blockY, int blockZ, float progress) {
		int now = mining && progress > 0 ? Math.min(9, (int) (progress * 10)) : -1;
		if (now == lastStage && (now < 0 || blockX == lastX && blockY == lastY && blockZ == lastZ)) {
			return;
		}
		lastStage = now;
		lastX = blockX;
		lastY = blockY;
		lastZ = blockZ;
		byte[] vanilla = Mc.miningPacket(entityId, blockX, blockY, blockZ, now);
		Recorder.recordRaw(vanilla != null ? vanilla : packet(blockX, blockY, blockZ, now));
	}

	static byte[] packet(int blockX, int blockY, int blockZ, int crackStage) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(40);
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeByte(CUSTOM_PAYLOAD);
			out.writeShort(CHANNEL.length()); // Packet.writeString before 1.7: short length, UTF-16 chars
			out.writeChars(CHANNEL);
			out.writeShort(13);
			out.writeInt(blockX);
			out.writeInt(blockY);
			out.writeInt(blockZ);
			out.writeByte(crackStage);
		} catch (IOException e) {
			throw new IllegalStateException(e); // in memory: cannot happen
		}
		return bytes.toByteArray();
	}

	/** A custom payload packet from the replay: true if it was a crack update (now in force). */
	static boolean read(byte[] data) {
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, 1, data.length - 1));
			int length = in.readShort();
			if (length != CHANNEL.length()) {
				return false;
			}
			for (int i = 0; i < length; i++) {
				if (in.readChar() != CHANNEL.charAt(i)) {
					return false;
				}
			}
			in.readShort();
			x = in.readInt();
			y = in.readInt();
			z = in.readInt();
			stage = in.readByte();
			return true;
		} catch (IOException e) {
			return false; // shorter than ours: someone else's payload
		}
	}

	/** Puts a crack back (self-tests). */
	static void set(int blockX, int blockY, int blockZ, int crackStage) {
		x = blockX;
		y = blockY;
		z = blockZ;
		stage = crackStage;
	}

	/** No crack shown (a new world, a Quick Mode jump). */
	public static void clear() {
		stage = -1;
	}

	public static boolean active() {
		return stage >= 0;
	}

	public static int x() {
		return x;
	}

	public static int y() {
		return y;
	}

	public static int z() {
		return z;
	}

	public static int stage() {
		return stage;
	}
}
