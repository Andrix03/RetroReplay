package io.github.andrix03.retroreplay.recording;

/**
 * Multiplayer packets keep their bytes off the wire: the decoder notes the last frame it decoded
 * on its thread, and the connection, which takes that packet right after on the same thread,
 * records those bytes (1.13's Packet is an interface: nothing can be stored in it).
 */
public final class WireFrames {

	private static final ThreadLocal<Object> PACKET = new ThreadLocal<>();
	private static final ThreadLocal<byte[]> BYTES = new ThreadLocal<>();

	private WireFrames() {
	}

	public static void decoded(Object packet, byte[] bytes) {
		PACKET.set(packet);
		BYTES.set(bytes);
	}

	/** The bytes of that packet if it is the one just decoded on this thread, else null. */
	public static byte[] take(Object packet) {
		byte[] bytes = PACKET.get() == packet ? BYTES.get() : null;
		PACKET.remove();
		BYTES.remove();
		return bytes;
	}
}
