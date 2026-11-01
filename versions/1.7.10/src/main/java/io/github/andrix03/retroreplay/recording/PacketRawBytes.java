package io.github.andrix03.retroreplay.recording;

/** A received packet's bytes as they came off the wire (null for packets of a local channel). */
public interface PacketRawBytes {

	byte[] retroreplay$raw();

	void retroreplay$setRaw(byte[] raw);
}
