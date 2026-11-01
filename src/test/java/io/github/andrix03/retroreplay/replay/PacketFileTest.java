package io.github.andrix03.retroreplay.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import org.junit.jupiter.api.Test;

class PacketFileTest {

	@Test
	void unpacksTimesAndReadsPacketsInOrderAgainAndAgain() throws Exception {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(raw);
		byte[][] packets = { { 0x01, 1, 2, 3 }, { 0x0D }, new byte[70_000] }; // the last one bigger than the buffer
		packets[2][0] = 0x33;
		int[] times = { 0, 50, 120 };
		for (int i = 0; i < packets.length; i++) {
			out.writeInt(times[i]);
			out.writeInt(packets[i].length);
			out.write(packets[i]);
		}
		PacketFile file = PacketFile.unpack(new ByteArrayInputStream(raw.toByteArray()));
		assertArrayEquals(times, file.times());
		for (int pass = 0; pass < 2; pass++) { // a seek back reads from the start with a new reader
			try (PacketFile.Reader reader = file.reader()) {
				for (byte[] packet : packets) {
					assertArrayEquals(packet, reader.next());
				}
			}
		}
		file.delete();
	}

	@Test
	void anEmptyRecordingHasNoPackets() throws Exception {
		PacketFile file = PacketFile.unpack(new ByteArrayInputStream(new byte[0]));
		assertEquals(0, file.times().length);
		file.delete();
	}
}
