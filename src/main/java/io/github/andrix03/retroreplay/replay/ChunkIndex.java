package io.github.andrix03.retroreplay.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which chunk packets a seek's fast-forward can leave out: chunk data and block changes matter
 * only while the chunk stays loaded, so a packet whose chunks are all unloaded again before the
 * seek's target changes nothing there. Block changes are most of a seek's work (measured on 1.0:
 * each one relights its column), and an explored world loads and unloads chunks all the time.
 * Built in the pass that unpacks the recording; only chunk packets are kept (8 bytes each).
 */
public final class ChunkIndex {

	/** 1.0, 1.1: pre-chunk 0x32 (load / unload), map chunk 0x33 as a block region. */
	public static final int SPLIT_REGION = 0;
	/** 1.2.5: pre-chunk 0x32, map chunk 0x33 as a chunk column. */
	public static final int SPLIT_COLUMN = 1;
	/** 1.3.2: chunk 0x33 (full with no sections = unload), bulk 0x38 without a light flag. */
	public static final int INTEGRATED_NO_LIGHT = 2;
	/** 1.4 - 1.6: like 1.3.2, the bulk packet has a light flag. */
	public static final int INTEGRATED = 3;
	/** 1.7: chunk 0x21, bulk 0x26 (VarInt ids, one byte for these). */
	public static final int NETTY = 4;

	private final int[] packets;
	private final int[] ends;

	private ChunkIndex(int[] packets, int[] ends) {
		this.packets = packets;
		this.ends = ends;
	}

	/** True when packet {@code packet} needs no handling on the way to packet {@code target}. */
	public boolean skippable(int packet, int target) {
		int i = Arrays.binarySearch(packets, packet);
		return i >= 0 && ends[i] < target;
	}

	public int size() {
		return packets.length;
	}

	public static final class Builder {

		private final int format;
		private int count;
		private int[] packets = new int[1024];
		/** Per slot: the last unload of its chunks, and how many of them are still loaded. */
		private int[] ends = new int[1024];
		private int[] pending = new int[1024];
		/** Per slot (block changes): the last overwrite of its blocks, and how many are not overwritten yet (-1: none to wait for). */
		private int[] blockEnds = new int[1024];
		private int[] blockPending = new int[1024];
		/** Chunk key -> slots of the data packets waiting for that chunk's unload. */
		private final Map<Long, List<Integer>> waiting = new HashMap<>();
		/** Block position -> slots of the block changes waiting for the next change there. */
		private final Map<Long, List<Integer>> waitingBlocks = new HashMap<>();

		public Builder(int format) {
			this.format = format;
		}

		/** Packet {@code index} as stored (id + payload, {@code length} bytes of {@code data}). */
		public void packet(int index, byte[] data, int length) {
			if (length < 1) {
				return;
			}
			try {
				classify(index, data, length);
			} catch (ArrayIndexOutOfBoundsException e) {
				// a packet shorter than its format: leave it alone, it is simply never skipped
			}
		}

		private void classify(int index, byte[] d, int length) {
			int id = d[0] & 0xFF;
			// block changes: each one relights its column, together they cost more than the chunk data.
			// One is not needed either once every block it sets is set again.
			if (id == (format == NETTY ? 0x22 : 0x34)) {
				int chunkX = readInt(d, 1);
				int chunkZ = readInt(d, 5);
				int changes = readShort(d, 9);
				long[] blocks = new long[changes];
				for (int i = 0; i < changes; i++) {
					if (format == SPLIT_REGION) {
						int position = readShort(d, 11 + i * 2); // x << 12 | z << 8 | y
						blocks[i] = block(chunkX * 16 + (position >> 12 & 15), position & 255, chunkZ * 16 + (position >> 8 & 15));
					} else {
						int record = readInt(d, 15 + i * 4); // x << 28 | z << 24 | y << 16 | block << 4 | metadata
						blocks[i] = block(chunkX * 16 + (record >>> 28), record >> 16 & 255, chunkZ * 16 + (record >> 24 & 15));
					}
				}
				blockChange(index, key(chunkX, chunkZ), blocks);
				return;
			}
			if (id == (format == NETTY ? 0x23 : 0x35)) {
				int x = readInt(d, 1); // block x, byte y, block z, block
				int z = readInt(d, 6);
				blockChange(index, key(x >> 4, z >> 4), block(x, d[5] & 0xFF, z));
				return;
			}
			switch (format) {
				case SPLIT_REGION:
				case SPLIT_COLUMN:
					if (id == 0x32) {
						long key = key(readInt(d, 1), readInt(d, 5));
						if (d[9] != 0) {
							data(index, key);
						} else {
							end(index, key);
						}
					} else if (id == 0x33 && format == SPLIT_COLUMN) {
						data(index, key(readInt(d, 1), readInt(d, 5)));
					} else if (id == 0x33) {
						// block region: x, short y, z, then sizes minus one
						int x = readInt(d, 1);
						int z = readInt(d, 7);
						int sizeX = (d[11] & 0xFF) + 1;
						int sizeZ = (d[13] & 0xFF) + 1;
						List<Long> keys = new ArrayList<>();
						for (int cx = x >> 4; cx <= (x + sizeX - 1) >> 4; cx++) {
							for (int cz = z >> 4; cz <= (z + sizeZ - 1) >> 4; cz++) {
								keys.add(key(cx, cz));
							}
						}
						data(index, toArray(keys));
					}
					return;
				default:
					int single = format == NETTY ? 0x21 : 0x33;
					int bulk = format == NETTY ? 0x26 : 0x38;
					if (id == single) {
						long key = key(readInt(d, 1), readInt(d, 5));
						boolean full = d[9] != 0;
						int sections = readShort(d, 10);
						if (full && sections == 0) {
							end(index, key);
						} else {
							data(index, key);
						}
					} else if (id == bulk) {
						int chunks = readShort(d, 1);
						int size = readInt(d, 3);
						int entries = (format == INTEGRATED_NO_LIGHT ? 7 : 8) + size;
						long[] keys = new long[chunks];
						for (int i = 0; i < chunks; i++) {
							int at = entries + i * 12;
							keys[i] = key(readInt(d, at), readInt(d, at + 4));
						}
						if (entries + chunks * 12 <= length) {
							data(index, keys);
						}
					}
			}
		}

		private int data(int index, long... keys) {
			if (keys.length == 0) {
				return -1;
			}
			if (count == packets.length) {
				packets = Arrays.copyOf(packets, count * 2);
				ends = Arrays.copyOf(ends, count * 2);
				pending = Arrays.copyOf(pending, count * 2);
				blockEnds = Arrays.copyOf(blockEnds, count * 2);
				blockPending = Arrays.copyOf(blockPending, count * 2);
			}
			int slot = count++;
			packets[slot] = index;
			ends[slot] = -1;
			pending[slot] = keys.length;
			blockEnds[slot] = -1;
			blockPending[slot] = -1;
			for (long key : keys) {
				waiting.computeIfAbsent(key, k -> new ArrayList<>()).add(slot);
			}
			return slot;
		}

		private void blockChange(int index, long chunk, long... blocks) {
			for (long block : blocks) {
				List<Integer> slots = waitingBlocks.remove(block);
				if (slots != null) {
					for (int slot : slots) {
						blockEnds[slot] = Math.max(blockEnds[slot], index);
						blockPending[slot]--;
					}
				}
			}
			int slot = data(index, chunk);
			if (blocks.length == 0) {
				return;
			}
			blockPending[slot] = blocks.length;
			for (long block : blocks) {
				waitingBlocks.computeIfAbsent(block, k -> new ArrayList<>()).add(slot);
			}
		}

		private void end(int index, long key) {
			List<Integer> slots = waiting.remove(key);
			if (slots != null) {
				for (int slot : slots) {
					ends[slot] = Math.max(ends[slot], index);
					pending[slot]--;
				}
			}
		}

		public ChunkIndex build() {
			int[] finalEnds = Arrays.copyOf(ends, count);
			for (int slot = 0; slot < count; slot++) {
				if (pending[slot] > 0) {
					finalEnds[slot] = Integer.MAX_VALUE; // a chunk still loaded at the end: always needed
				}
				if (blockPending[slot] == 0) {
					finalEnds[slot] = Math.min(finalEnds[slot], blockEnds[slot]); // or every block set again
				}
			}
			return new ChunkIndex(Arrays.copyOf(packets, count), finalEnds);
		}

		private static long key(int x, int z) {
			return ((long) x << 32) | (z & 0xFFFFFFFFL);
		}

		private static long block(int x, int y, int z) {
			return ((long) (x & 0x3FFFFFF) << 34) | ((long) (z & 0x3FFFFFF) << 8) | (y & 0xFF);
		}

		private static long[] toArray(List<Long> keys) {
			long[] out = new long[keys.size()];
			for (int i = 0; i < out.length; i++) {
				out[i] = keys.get(i);
			}
			return out;
		}

		private static int readInt(byte[] d, int at) {
			return (d[at] & 0xFF) << 24 | (d[at + 1] & 0xFF) << 16 | (d[at + 2] & 0xFF) << 8 | (d[at + 3] & 0xFF);
		}

		private static int readShort(byte[] d, int at) {
			return (d[at] & 0xFF) << 8 | (d[at + 1] & 0xFF);
		}
	}
}
