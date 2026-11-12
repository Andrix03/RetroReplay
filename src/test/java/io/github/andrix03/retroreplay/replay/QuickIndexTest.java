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
	@Test
	void columnFormatBlocksChunksAndHeads() throws IOException {
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(0x34, out -> { // 0 chunk (-1, 2): two records (1.2.5)
			out.writeInt(-1);
			out.writeInt(2);
			out.writeShort(2);
			out.writeInt(8);
			out.writeInt(3 << 28 | 4 << 24 | 200 << 16 | 1 << 4 | 0);
			out.writeInt(15 << 28 | 0 << 24 | 5 << 16 | 35 << 4 | 14);
		}));
		packets.add(packet(0x33, out -> { // 1 chunk column (-1, 2)
			out.writeInt(-1);
			out.writeInt(2);
			out.writeBoolean(true);
		}));
		packets.add(packet(0x18, out -> { // 2 mob 9
			out.writeInt(9);
			out.writeByte(90);
			out.writeInt(0);
			out.writeInt(0);
			out.writeInt(0);
			out.writeByte(0);
			out.writeByte(0);
			out.writeByte(0);
			out.writeByte(127);
		}));
		packets.add(packet(0x23, out -> { // 3 its head turns
			out.writeInt(9);
			out.writeByte(64);
		}));
		QuickIndex.Builder builder = new QuickIndex.Builder(ChunkIndex.SPLIT_COLUMN);
		for (int i = 0; i < packets.size(); i++) {
			builder.packet(i, packets.get(i), packets.get(i).length);
		}
		QuickIndex index = builder.build();
		assertEquals(1 << 4, index.valueAt(QuickIndex.position(-16 + 3, 200, 32 + 4), 1));
		assertEquals(35 << 4 | 14, index.valueAt(QuickIndex.position(-1, 5, 32), 1));
		QuickIndex.ChunkLog log = index.chunks.get(QuickIndex.chunkKey(-1, 2));
		assertEquals(QuickIndex.ChunkLog.DATA, log.kind[0]);
		assertEquals(2, log.changes);
		assertEquals(0, index.tracks[0].stateAt(QuickIndex.KIND_HEAD, 4));
	}
	@Test
	void integratedRemovalsBlocksAndBulkChunks() throws IOException {
		// two chunks in a 1.4 bulk packet with sky light: (3, 4) one section, (-2, 7) two sections and an upper one
		byte[] first = new byte[2048 * 5 + 256];
		byte[] second = new byte[2048 * 5 * 2 + 2048 + 256];
		for (int i = 0; i < first.length; i++) {
			first[i] = (byte) (i * 7);
		}
		for (int i = 0; i < second.length; i++) {
			second[i] = (byte) (i * 13 + 1);
		}
		byte[] both = new byte[first.length + second.length];
		System.arraycopy(first, 0, both, 0, first.length);
		System.arraycopy(second, 0, both, first.length, second.length);
		java.util.zip.Deflater deflater = new java.util.zip.Deflater();
		deflater.setInput(both);
		deflater.finish();
		byte[] compressed = new byte[both.length + 1024];
		int size = deflater.deflate(compressed);
		deflater.end();
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(0x38, out -> { // 0 bulk
			out.writeShort(2);
			out.writeInt(size);
			out.writeBoolean(true);
			out.write(compressed, 0, size);
			out.writeInt(3);
			out.writeInt(4);
			out.writeShort(0x1);
			out.writeShort(0);
			out.writeInt(-2);
			out.writeInt(7);
			out.writeShort(0x5);
			out.writeShort(0x4);
		}));
		packets.add(packet(0x35, out -> { // 1 block 300:2 (a short id from 1.3)
			out.writeInt(-30);
			out.writeByte(70);
			out.writeInt(120);
			out.writeShort(300);
			out.writeByte(2);
		}));
		packets.add(packet(0x18, out -> { // 2 mob 4
			out.writeInt(4);
			out.writeByte(90);
			out.writeInt(0);
			out.writeInt(0);
			out.writeInt(0);
			out.writeByte(0);
			out.writeByte(0);
			out.writeByte(0);
			out.writeShort(0);
			out.writeShort(0);
			out.writeShort(0);
			out.writeByte(127);
		}));
		packets.add(packet(0x1D, out -> { // 3 removed in a list of two
			out.writeByte(2);
			out.writeInt(99);
			out.writeInt(4);
		}));
		packets.add(packet(0x33, out -> { // 4 (3, 4) unloaded: full without sections
			out.writeInt(3);
			out.writeInt(4);
			out.writeBoolean(true);
			out.writeShort(0);
			out.writeShort(0);
			out.writeInt(0);
		}));
		QuickIndex.Builder builder = new QuickIndex.Builder(ChunkIndex.INTEGRATED, "1.4.7");
		long offset = 0;
		long[] offsets = new long[packets.size()];
		for (int i = 0; i < packets.size(); i++) {
			offsets[i] = offset;
			builder.packet(i, packets.get(i), packets.get(i).length);
			offset += 8 + packets.get(i).length;
		}
		QuickIndex index = builder.build();
		assertEquals(300 << 4 | 2, index.valueAt(QuickIndex.position(-30, 70, 120), 2));
		assertTrue(index.tracks[0].aliveAt(3));
		assertFalse(index.tracks[0].aliveAt(4));
		assertArrayEquals(new byte[] { 0x1D, 1, 0, 0, 0, 4 }, index.removePacket(4));

		QuickIndex.ChunkLog a = index.chunks.get(QuickIndex.chunkKey(3, 4));
		assertEquals(0, a.loadedAt(1));
		assertEquals(-1, a.loadedAt(5));
		QuickIndex.ChunkLog b = index.chunks.get(QuickIndex.chunkKey(-2, 7));
		assertEquals(1, b.part[0]);
		assertEquals(offsets[0], b.offset[0]);
		byte[] single = index.loadPacket(b, 0, at -> packets.get(java.util.Arrays.binarySearch(offsets, at)));
		// 0x33: x, z, full, sections, upper, size, compressed data
		java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(single, 1, single.length - 1));
		assertEquals(-2, in.readInt());
		assertEquals(7, in.readInt());
		assertTrue(in.readBoolean());
		assertEquals(0x5, in.readShort());
		assertEquals(0x4, in.readShort());
		byte[] data = new byte[in.readInt()];
		in.readFully(data);
		java.util.zip.Inflater inflater = new java.util.zip.Inflater();
		inflater.setInput(data);
		byte[] out = new byte[second.length + 10];
		int n;
		try {
			n = inflater.inflate(out);
		} catch (java.util.zip.DataFormatException e) {
			throw new IOException(e);
		}
		inflater.end();
		assertEquals(second.length, n);
		assertArrayEquals(second, java.util.Arrays.copyOf(out, n));
	}
	@Test
	void nettyIdsVarIntsAndFloats() throws IOException {
		List<byte[]> packets = new ArrayList<>();
		packets.add(packet(12, out -> { // 0 1.7.10 named player: VarInt 300, uuid, name, one property, then the position
			out.write(new byte[] { (byte) 0xAC, 0x02 });
			byte[] uuid = "00000000-0000-0000-0000-000000000000".getBytes("UTF-8");
			out.writeByte(uuid.length);
			out.write(uuid);
			out.writeByte(2);
			out.write("Ab".getBytes("UTF-8"));
			out.writeByte(1);
			for (String text : new String[] { "textures", "v", "s" }) {
				out.writeByte(text.length());
				out.write(text.getBytes("UTF-8"));
			}
			out.writeInt(64);
			out.writeInt(2048);
			out.writeInt(-64);
			out.writeByte(1);
			out.writeByte(2);
		}));
		packets.add(packet(21, out -> { // 1 relative move (int id as before)
			out.writeInt(300);
			out.writeByte(4);
			out.writeByte(0);
			out.writeByte(-4);
		}));
		packets.add(packet(35, out -> { // 2 block 260:3, the id a VarInt
			out.writeInt(5);
			out.writeByte(64);
			out.writeInt(6);
			out.write(new byte[] { (byte) 0x84, 0x02 });
			out.writeByte(3);
		}));
		packets.add(packet(39, out -> { // 3 explosion at floats 10.5, 64.2, -3.5 (ints 10, 64, -3): one block
			out.writeFloat(10.5F);
			out.writeFloat(64.2F);
			out.writeFloat(-3.5F);
			out.writeFloat(4);
			out.writeInt(1);
			out.write(new byte[] { 1, -1, 0 });
		}));
		packets.add(packet(43, out -> { // 4 rain starts: unsigned byte, float
			out.writeByte(1);
			out.writeFloat(0);
		}));
		QuickIndex.Builder builder = new QuickIndex.Builder(ChunkIndex.NETTY, "1.7.10");
		for (int i = 0; i < packets.size(); i++) {
			builder.packet(i, packets.get(i), packets.get(i).length);
		}
		QuickIndex index = builder.build();
		QuickIndex.Track player = index.tracks[0];
		assertEquals(300, player.id);
		byte[] teleport = index.teleportPacket(player, player.entryAt(2));
		assertEquals(24, teleport[0]); // 1.7.10's teleport id
		assertArrayEquals(java.util.Arrays.copyOfRange(QuickIndex.teleport(300, 68, 2048, -68, 1 << 8 | 2), 1, 19), java.util.Arrays.copyOfRange(teleport, 1, 19));
		assertEquals(260 << 4 | 3, index.valueAt(QuickIndex.position(5, 64, 6), 3));
		assertArrayEquals(packets.get(2), index.blockPacket(QuickIndex.position(5, 64, 6), 260 << 4 | 3));
		assertEquals(0, index.valueAt(QuickIndex.position(11, 63, -3), 4));
		assertArrayEquals(packets.get(4), index.weatherAt(5));
		assertEquals(19, index.removePacket(300)[0]);
	}
}
