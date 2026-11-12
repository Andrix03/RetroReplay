package io.github.andrix03.retroreplay.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class QuickIndexTest {

	private interface Body {
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] packet(int id, Body body) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(id);
		body.write(out);
		return bytes.toByteArray();
	}

	private static QuickIndex index(List<byte[]> packets) {
		QuickIndex.Builder builder = new QuickIndex.Builder();
		for (int i = 0; i < packets.size(); i++) {
			builder.packet(i, packets.get(i), packets.get(i).length);
		}
		return builder.build();
	}

	@Test
	void positionsPackBothSigns() {
		int[][] points = { { 0, 0, 0 }, { -1, 255, -1 }, { 29_999_999, 64, -29_999_999 }, { -12345, 7, 678 } };
		for (int[] p : points) {
			long position = QuickIndex.position(p[0], p[1], p[2]);
			assertEquals(p[0], QuickIndex.x(position));
			assertEquals(p[1], QuickIndex.y(position));
			assertEquals(p[2], QuickIndex.z(position));
		}
	}

	@Test
	void movesResolveToTheClientsCoordinates() throws IOException {
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(0x01, out -> out.writeInt(0))); // 0 login (only its id matters)
		packets.add(packet(0x14, out -> { // 1 named player "Ab" at 32, 64*32, -32
			out.writeInt(7);
			out.writeShort(2);
			out.writeChars("Ab");
			out.writeInt(32);
			out.writeInt(64 * 32);
			out.writeInt(-32);
			out.writeByte(10);
			out.writeByte(20);
			out.writeShort(0);
		}));
		packets.add(packet(0x1F, out -> { // 2 move +3, -2, +1
			out.writeInt(7);
			out.writeByte(3);
			out.writeByte(-2);
			out.writeByte(1);
		}));
		packets.add(packet(0x20, out -> { // 3 look
			out.writeInt(7);
			out.writeByte(-128);
			out.writeByte(5);
		}));
		packets.add(packet(0x1D, out -> out.writeInt(7))); // 4 removed
		QuickIndex index = index(packets);

		assertEquals(1, index.tracks.length);
		QuickIndex.Track track = index.tracks[0];
		assertFalse(track.aliveAt(1)); // spawn packet not handled yet
		assertTrue(track.aliveAt(2));
		assertTrue(track.aliveAt(4));
		assertFalse(track.aliveAt(5));
		assertEquals(0, track.entryAt(2));
		assertEquals(1, track.entryAt(3));
		assertEquals(2, track.entryAt(5));
		// after the look: position from the move, angles from the look
		assertArrayEquals(QuickIndex.teleport(7, 35, 64 * 32 - 2, -31, 0x80 << 8 | 5), track.teleport(2));
		assertArrayEquals(QuickIndex.teleport(7, 35, 64 * 32 - 2, -31, 10 << 8 | 20), track.teleport(1));
	}

	@Test
	void theWorldClosingRemovesItsEntities() throws IOException {
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(0x18, out -> { // 0 mob
			out.writeInt(3);
			out.writeByte(90);
			out.writeInt(0);
			out.writeInt(0);
			out.writeInt(0);
			out.writeByte(0);
			out.writeByte(0);
			out.writeByte(127); // empty entity data
		}));
		packets.add(packet(0x09, out -> out.writeByte(0))); // 1 respawn
		QuickIndex index = index(packets);
		assertTrue(index.tracks[0].aliveAt(1));
		assertFalse(index.tracks[0].aliveAt(2));
		assertTrue(index.sameWorld(0, 1));
		assertFalse(index.sameWorld(1, 2));
	}

	@Test
	void blockValuesFollowTheChanges() throws IOException {
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(0x35, out -> { // 0 stone at -5, 70, 9
			out.writeInt(-5);
			out.writeByte(70);
			out.writeInt(9);
			out.writeByte(1);
			out.writeByte(0);
		}));
		packets.add(packet(0x34, out -> { // 1 chunk (-1, 0): two changes, one of them the same block
			out.writeInt(-1);
			out.writeInt(0);
			out.writeShort(2);
			out.writeShort(11 << 12 | 9 << 8 | 70); // -16 + 11 = -5, z 9, y 70
			out.writeShort(0 << 12 | 0 << 8 | 3);
			out.writeByte(0); // air
			out.writeByte(35);
			out.writeByte(0);
			out.writeByte(14); // red wool
		}));
		packets.add(packet(0x04, out -> out.writeLong(6000)));
		QuickIndex index = index(packets);

		long stone = QuickIndex.position(-5, 70, 9);
		assertEquals(-1, index.valueAt(stone, 0));
		assertEquals(1 << 4, index.valueAt(stone, 1));
		assertEquals(0, index.valueAt(stone, 2));
		assertEquals(35 << 4 | 14, index.valueAt(QuickIndex.position(-16, 3, 0), 2));
		assertEquals(0, index.firstChangeIndex(stone));

		// the value before the first change comes from the world, read once just before it
		int[] reads = { 0 };
		index.beforePacket(0, (x, y, z) -> {
			reads[0]++;
			return x == -5 && y == 70 && z == 9 ? 2 << 4 : -1;
		});
		index.beforePacket(1, (x, y, z) -> {
			reads[0]++;
			return 3 << 4;
		});
		assertEquals(2 << 4, index.baseValue(stone));
		assertEquals(3 << 4, index.baseValue(QuickIndex.position(-16, 3, 0)));
		assertEquals(2, reads[0]); // the stone's second change is not its first: not read again

		assertNull(index.timeAt(2));
		assertArrayEquals(packet(0x04, out -> out.writeLong(6000)), index.timeAt(3));
	}
}
