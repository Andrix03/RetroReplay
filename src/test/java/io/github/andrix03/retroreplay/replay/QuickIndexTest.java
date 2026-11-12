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
	@Test
	void chunkPacketsAreReadBackFromTheirOffsets() throws IOException {
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(0x32, out -> { // 0 load chunk (2, -1)
			out.writeInt(2);
			out.writeInt(-1);
			out.writeByte(1);
		}));
		packets.add(new byte[0]); // 1 empty: still takes 8 bytes in the file
		packets.add(packet(0x33, out -> { // 2 data for the whole chunk (2, -1)
			out.writeInt(32);
			out.writeShort(0);
			out.writeInt(-16);
			out.writeByte(15);
			out.writeByte(127);
			out.writeByte(15);
			out.writeInt(3);
			out.write(new byte[] { 9, 8, 7 });
		}));
		packets.add(packet(0x35, out -> { // 3 a block in it
			out.writeInt(33);
			out.writeByte(64);
			out.writeInt(-10);
			out.writeByte(4);
			out.writeByte(0);
		}));
		packets.add(packet(0x32, out -> { // 4 unloaded
			out.writeInt(2);
			out.writeInt(-1);
			out.writeByte(0);
		}));
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(stream);
		for (int i = 0; i < packets.size(); i++) {
			out.writeInt(i * 50);
			out.writeInt(packets.get(i).length);
			out.write(packets.get(i));
		}
		QuickIndex.Builder builder = new QuickIndex.Builder();
		PacketFile file = PacketFile.unpack(new java.io.ByteArrayInputStream(stream.toByteArray()), builder::packet);
		try {
			QuickIndex index = builder.build();
			QuickIndex.ChunkLog log = index.chunks.get(QuickIndex.chunkKey(2, -1));
			assertEquals(3, log.count);
			assertEquals(-1, log.loadedAt(0));
			assertEquals(0, log.loadedAt(1));
			assertEquals(0, log.loadedAt(4));
			assertEquals(-1, log.loadedAt(5));
			assertTrue(log.reloadedBetween(1, 3)); // the data packet
			assertFalse(log.reloadedBetween(3, 5)); // only the unload
			assertEquals(QuickIndex.ChunkLog.DATA, log.kind[1]);
			assertArrayEquals(packets.get(2), file.readAt(log.offset[1]));
			assertEquals(1, log.changes); // the block at 33, 64, -10 belongs to it
		} finally {
			file.delete();
		}
	}

	@Test
	void explosionsEquipmentAndRain() throws IOException {
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(0x18, out -> { // 0 mob 5
			out.writeInt(5);
			out.writeByte(54);
			out.writeInt(0);
			out.writeInt(0);
			out.writeInt(0);
			out.writeByte(0);
			out.writeByte(0);
			out.writeByte(127);
		}));
		packets.add(packet(0x05, out -> { // 1 holds a sword
			out.writeInt(5);
			out.writeShort(0);
			out.writeShort(267);
			out.writeShort(0);
		}));
		packets.add(packet(0x3C, out -> { // 2 explosion at -0.5 (int: 0), 70.9 (70), 3.2 (3): two blocks
			out.writeDouble(-0.5);
			out.writeDouble(70.9);
			out.writeDouble(3.2);
			out.writeFloat(4);
			out.writeInt(2);
			out.write(new byte[] { -1, 0, 0, 1, -2, 3 });
		}));
		packets.add(packet(0x46, out -> { // 3 rain starts
			out.writeByte(1);
			out.writeByte(0);
		}));
		QuickIndex index = index(packets);
		assertEquals(0, index.valueAt(QuickIndex.position(-1, 70, 3), 3));
		assertEquals(0, index.valueAt(QuickIndex.position(1, 68, 6), 3));
		QuickIndex.Track mob = index.tracks[0];
		assertEquals(-1, mob.stateAt(0, 1));
		assertEquals(0, mob.stateAt(0, 2));
		assertArrayEquals(packets.get(1), mob.stateBytes[0]);
		assertArrayEquals(packet(0x05, out -> {
			out.writeInt(5);
			out.writeShort(0);
			out.writeShort(-1);
			out.writeShort(0);
		}), mob.noState(0));
		assertNull(index.weatherAt(3));
		assertArrayEquals(new byte[] { 0x46, 1, 0 }, index.weatherAt(4));
	}
}
