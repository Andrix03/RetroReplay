package io.github.andrix03.retroreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.jupiter.api.Test;

class ChunkIndexTest {

	private static byte[] chunk(int id, int x, int z, boolean full, int sections) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(id);
		out.writeInt(x);
		out.writeInt(z);
		out.writeBoolean(full);
		out.writeShort(sections);
		out.writeShort(0);
		out.writeInt(3);
		out.write(new byte[3]);
		return bytes.toByteArray();
	}

	private static byte[] bulk(int id, boolean light, int[]... chunks) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(id);
		out.writeShort(chunks.length);
		out.writeInt(5);
		if (light) {
			out.writeBoolean(true);
		}
		out.write(new byte[5]);
		for (int[] c : chunks) {
			out.writeInt(c[0]);
			out.writeInt(c[1]);
			out.writeShort(0xFFFF);
			out.writeShort(0);
		}
		return bytes.toByteArray();
	}

	private static byte[] preChunk(int x, int z, boolean load) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(0x32);
		out.writeInt(x);
		out.writeInt(z);
		out.writeBoolean(load);
		return bytes.toByteArray();
	}

	private static byte[] region(int x, int y, int z, int sizeX, int sizeY, int sizeZ) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(0x33);
		out.writeInt(x);
		out.writeShort(y);
		out.writeInt(z);
		out.writeByte(sizeX - 1);
		out.writeByte(sizeY - 1);
		out.writeByte(sizeZ - 1);
		out.writeInt(0);
		return bytes.toByteArray();
	}

	private static ChunkIndex index(int format, byte[]... packets) {
		ChunkIndex.Builder builder = new ChunkIndex.Builder(format);
		for (int i = 0; i < packets.length; i++) {
			builder.packet(i, packets[i], packets[i].length);
		}
		return builder.build();
	}

	@Test
	void aChunkUnloadedBeforeTheTargetIsSkipped() throws IOException {
		ChunkIndex index = index(ChunkIndex.NETTY,
			chunk(0x21, 1, 2, true, 0xFFFF), // 0: load
			new byte[] { 0x03, 0 }, // 1: something else
			chunk(0x21, 1, 2, true, 0xFFFF), // 2: data again while loaded
			chunk(0x21, 1, 2, true, 0), // 3: unload
			chunk(0x21, 1, 2, true, 0xFFFF)); // 4: loaded again, never unloaded
		assertFalse(index.skippable(0, 3), "the target is the unload itself: still loaded on the way");
		assertTrue(index.skippable(0, 4));
		assertTrue(index.skippable(2, 4));
		assertFalse(index.skippable(1, 4), "not a chunk packet");
		assertFalse(index.skippable(3, 4), "the unload is kept");
		assertFalse(index.skippable(4, 100));
	}

	@Test
	void aBulkPacketIsSkippedOnlyWhenAllItsChunksAreUnloaded() throws IOException {
		ChunkIndex index = index(ChunkIndex.INTEGRATED,
			bulk(0x38, true, new int[] { 0, 0 }, new int[] { -3, 7 }), // 0
			chunk(0x33, 0, 0, true, 0), // 1: first one unloaded
			chunk(0x33, -3, 7, true, 0)); // 2: second one too
		assertFalse(index.skippable(0, 2));
		assertTrue(index.skippable(0, 3));
		ChunkIndex noLight = index(ChunkIndex.INTEGRATED_NO_LIGHT, bulk(0x38, false, new int[] { 4, 5 }), chunk(0x33, 4, 5, true, 0));
		assertTrue(noLight.skippable(0, 2));
		assertEquals(1, noLight.size());
	}

	private static byte[] blockChange(int id, int x, int y, int z) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(id);
		out.writeInt(x);
		out.writeByte(y);
		out.writeInt(z);
		out.writeShort(1);
		out.writeByte(0);
		return bytes.toByteArray();
	}

	@Test
	void blockChangesGoWithTheirChunk() throws IOException {
		ChunkIndex index = index(ChunkIndex.NETTY,
			chunk(0x21, -1, 2, true, 0xFFFF), // 0
			blockChange(0x23, -5, 64, 40), // 1: block -5,40 is in chunk -1,2
			blockChange(0x23, 5, 64, 40), // 2: chunk 0,2 stays loaded
			chunk(0x22, -1, 2, false, 1), // 3: multi block change, chunk x and z first
			chunk(0x21, -1, 2, true, 0)); // 4: unload
		assertTrue(index.skippable(1, 5));
		assertFalse(index.skippable(2, 5));
		assertTrue(index.skippable(3, 5));
		ChunkIndex old = index(ChunkIndex.SPLIT_REGION, blockChange(0x35, 17, 64, -1), preChunk(1, -1, false));
		assertTrue(old.skippable(0, 2));
	}

	/** Multi block change since 1.2: chunk x, z, count, size, then x << 28 | z << 24 | y << 16 | block << 4 | meta records. */
	private static byte[] multiChange(int id, int chunkX, int chunkZ, int[]... blocks) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(id);
		out.writeInt(chunkX);
		out.writeInt(chunkZ);
		out.writeShort(blocks.length);
		out.writeInt(blocks.length * 4);
		for (int[] b : blocks) {
			out.writeInt(b[0] << 28 | b[2] << 24 | b[1] << 16 | 1 << 4);
		}
		return bytes.toByteArray();
	}

	/** Multi block change of 1.0 and 1.1: chunk x, z, count, x << 12 | z << 8 | y shorts, ids, metadata. */
	private static byte[] oldMultiChange(int chunkX, int chunkZ, int[]... blocks) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeByte(0x34);
		out.writeInt(chunkX);
		out.writeInt(chunkZ);
		out.writeShort(blocks.length);
		for (int[] b : blocks) {
			out.writeShort(b[0] << 12 | b[2] << 8 | b[1]);
		}
		out.write(new byte[blocks.length * 2]);
		return bytes.toByteArray();
	}

	@Test
	void aBlockChangeSetAgainLaterIsSkipped() throws IOException {
		ChunkIndex index = index(ChunkIndex.INTEGRATED,
			chunk(0x33, 0, 0, true, 0xFFFF), // 0: loaded for good
			blockChange(0x35, 3, 70, 4), // 1
			multiChange(0x34, 0, 0, new int[] { 3, 70, 4 }, new int[] { 5, 10, 6 }), // 2: sets 3,70,4 again and 5,10,6
			blockChange(0x35, 3, 70, 4), // 3: 3,70,4 once more
			blockChange(0x35, 5, 10, 6)); // 4: and 5,10,6
		assertFalse(index.skippable(1, 2));
		assertTrue(index.skippable(1, 3), "set again by packet 2");
		assertFalse(index.skippable(2, 4), "5,10,6 is not set again before packet 4");
		assertTrue(index.skippable(2, 5));
		assertFalse(index.skippable(3, 100), "the last change of a block stays");
		assertFalse(index.skippable(0, 100));
		ChunkIndex old = index(ChunkIndex.SPLIT_REGION,
			oldMultiChange(-1, 0, new int[] { 15, 64, 2 }), // block -1,64,2
			blockChange(0x35, -1, 64, 2));
		assertTrue(old.skippable(0, 2));
	}

	@Test
	void splitVersionsUsePreChunkPacketsAndRegions() throws IOException {
		ChunkIndex column = index(ChunkIndex.SPLIT_COLUMN,
			preChunk(2, 3, true), // 0
			chunk(0x33, 2, 3, true, 0xFFFF), // 1
			preChunk(2, 3, false)); // 2
		assertTrue(column.skippable(0, 3));
		assertTrue(column.skippable(1, 3));
		ChunkIndex region = index(ChunkIndex.SPLIT_REGION,
			region(16, 0, 0, 16, 128, 16), // 0: chunk 1,0
			region(8, 0, 0, 16, 128, 16), // 1: spans chunks 0,0 and 1,0
			preChunk(1, 0, false)); // 2
		assertTrue(region.skippable(0, 3));
		assertFalse(region.skippable(1, 3), "chunk 0,0 is still loaded");
	}
}
