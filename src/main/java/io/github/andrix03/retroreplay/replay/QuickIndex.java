package io.github.andrix03.retroreplay.replay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replay Mod's Quick Mode: what the world looked like at any moment, so the replay bar can be
 * dragged back with no world reload. Built in the pass that unpacks the recording: for each
 * entity its spawn packet, its removal and every position it took (moves resolved to the
 * absolute fixed-point coordinates the client keeps); every block change with the value it set;
 * the world time. Going back is then done with ordinary packets made up from it (teleport,
 * remove, block update, time) fed to the game's own handler.
 *
 * Lossy like Replay Mod's: equipment, entity data, riding, explosions, weather and chunks
 * unloaded later keep their present state. Packet layouts are 1.0's (read from its sources).
 */
public final class QuickIndex {

	private static final int LOGIN = 0x01;
	private static final int TIME = 0x04;
	private static final int RESPAWN = 0x09;
	private static final int ADD_PLAYER = 0x14;
	private static final int ADD_ITEM = 0x15;
	private static final int PICKUP = 0x16;
	private static final int ADD_ENTITY = 0x17;
	private static final int ADD_MOB = 0x18;
	private static final int ADD_PAINTING = 0x19;
	private static final int ADD_ORB = 0x1A;
	private static final int REMOVE = 0x1D;
	private static final int MOVE = 0x1F;
	private static final int LOOK = 0x20;
	private static final int MOVE_LOOK = 0x21;
	private static final int TELEPORT = 0x22;
	private static final int BLOCKS = 0x34;
	private static final int BLOCK = 0x35;

	/** Quick Mode exists for this version (its packet layouts are known). */
	public static boolean supports(String version) {
		return "1.0.0".equals(version);
	}

	/** One entity's life: from its spawn packet to its removal (or the world closing). */
	static final class Track {
		final int id;
		final byte[] spawn;
		final int spawnIndex;
		int removeIndex = Integer.MAX_VALUE;
		/** Paintings: block coordinates, never teleported. */
		final boolean still;
		/** Per entry: packet index, x, y, z (1/32 block), yaw << 8 | pitch (as bytes). */
		int[] entries = new int[5 * 4];
		int count;

		Track(int id, byte[] spawn, int spawnIndex, boolean still) {
			this.id = id;
			this.spawn = spawn;
			this.spawnIndex = spawnIndex;
			this.still = still;
		}

		void add(int index, int x, int y, int z, int angles) {
			if (count * 5 == entries.length) {
				entries = Arrays.copyOf(entries, entries.length * 2);
			}
			int o = count * 5;
			entries[o] = index;
			entries[o + 1] = x;
			entries[o + 2] = y;
			entries[o + 3] = z;
			entries[o + 4] = angles;
			count++;
		}

		/** Alive once the packets before {@code fed} are handled. */
		boolean aliveAt(int fed) {
			return spawnIndex < fed && removeIndex >= fed;
		}

		/** The last entry among the packets before {@code fed} (needs aliveAt). */
		int entryAt(int fed) {
			int low = 0;
			int high = count - 1;
			while (low < high) {
				int mid = (low + high + 1) >>> 1;
				if (entries[mid * 5] < fed) {
					low = mid;
				} else {
					high = mid - 1;
				}
			}
			return low;
		}

		/** The teleport packet that puts this entity where entry {@code e} has it. */
		byte[] teleport(int e) {
			int o = e * 5;
			return QuickIndex.teleport(id, entries[o + 1], entries[o + 2], entries[o + 3], entries[o + 4]);
		}
	}

	final Track[] tracks;
	/** Block changes in packet order: packet index, position, block id << 4 | metadata. */
	private final int[] blockIndex;
	private final long[] blockPosition;
	private final short[] blockValue;
	/** Position -> its changes (indexes into the arrays above), in order. */
	private final Map<Long, int[]> changesAt;
	/** Packets that close the world (login, respawn): Quick Mode does not cross them. */
	private final int[] worldStarts;
	private final int[] timeIndex;
	private final long[] timeValue;

	private QuickIndex(Builder b) {
		tracks = b.tracks.toArray(new Track[0]);
		blockIndex = Arrays.copyOf(b.blockIndex, b.blocks);
		blockPosition = Arrays.copyOf(b.blockPosition, b.blocks);
		blockValue = Arrays.copyOf(b.blockValue, b.blocks);
		Map<Long, int[]> counts = new HashMap<>();
		for (int i = 0; i < b.blocks; i++) {
			int[] list = counts.get(blockPosition[i]);
			if (list == null) {
				list = new int[] { 0, 0, 0 };
				counts.put(blockPosition[i], list);
			} else if (list[0] + 1 == list.length) {
				list = Arrays.copyOf(list, list.length * 2);
				counts.put(blockPosition[i], list);
			}
			list[++list[0]] = i; // slot 0 holds the count
		}
		changesAt = counts;
		worldStarts = Arrays.copyOf(b.worldStarts, b.worldStartCount);
		timeIndex = Arrays.copyOf(b.timeIndex, b.times);
		timeValue = Arrays.copyOf(b.timeValue, b.times);
	}

	/** False when a login or respawn lies between the two points: the world itself changed there. */
	public boolean sameWorld(int fedA, int fedB) {
		int low = Math.min(fedA, fedB);
		int high = Math.max(fedA, fedB);
		for (int start : worldStarts) {
			if (start >= low && start < high) {
				return false;
			}
		}
		return true;
	}

	/** The first block change of packet index {@code from} or later. */
	int firstBlockChange(int from) {
		int low = 0;
		int high = blockIndex.length;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (blockIndex[mid] < from) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	int blockChanges() {
		return blockIndex.length;
	}

	int blockChangeIndex(int change) {
		return blockIndex[change];
	}

	long blockChangePosition(int change) {
		return blockPosition[change];
	}

	/** The value at {@code position} once the packets before {@code fed} are handled, or -1 before its first change. */
	int valueAt(long position, int fed) {
		int[] list = changesAt.get(position);
		int value = -1;
		if (list != null) {
			for (int i = 1; i <= list[0] && blockIndex[list[i]] < fed; i++) {
				value = blockValue[list[i]];
			}
		}
		return value;
	}

	/** The packet index of the first change at {@code position}. */
	int firstChangeIndex(long position) {
		int[] list = changesAt.get(position);
		return list == null ? Integer.MAX_VALUE : blockIndex[list[1]];
	}

	/**
	 * Block values before their first change, read from the world just before that change was
	 * handled (the recording has only the new values). Kept across seeks: the replayed world is
	 * the same every time.
	 */
	private final Map<Long, Integer> base = new HashMap<>();

	/** What a block reader gives: block id << 4 | metadata, or -1 when its chunk is not loaded. */
	public interface BlockReader {
		int read(int x, int y, int z);
	}

	/** Before packet {@code packet} is handled (or skipped): keeps the values its first changes replace. */
	void beforePacket(int packet, BlockReader world) {
		for (int c = firstBlockChange(packet); c < blockIndex.length && blockIndex[c] == packet; c++) {
			long position = blockPosition[c];
			if (firstChangeIndex(position) == packet && !base.containsKey(position)) {
				int value = world.read(x(position), y(position), z(position));
				if (value >= 0) {
					base.put(position, value);
				}
			}
		}
	}

	/** The value before the first change at {@code position}, or -1 if it was never seen. */
	int baseValue(long position) {
		Integer value = base.get(position);
		return value == null ? -1 : value;
	}

	/** The world time packet in force once the packets before {@code fed} are handled, or null. */
	byte[] timeAt(int fed) {
		int found = -1;
		int low = 0;
		int high = timeIndex.length - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (timeIndex[mid] < fed) {
				found = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		if (found < 0) {
			return null;
		}
		long time = timeValue[found];
		return packet(TIME, out -> out.writeLong(time));
	}

	public int size() {
		return tracks.length + blockIndex.length;
	}

	static long position(int x, int y, int z) {
		return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF);
	}

	static int x(long position) {
		return (int) (position >> 38 << 6) >> 6; // sign of the 26 bits
	}

	static int y(long position) {
		return (int) (position & 0xFFF);
	}

	static int z(long position) {
		return (int) (position << 26 >> 38);
	}

	static byte[] teleport(int id, int x, int y, int z, int angles) {
		return packet(TELEPORT, out -> {
			out.writeInt(id);
			out.writeInt(x);
			out.writeInt(y);
			out.writeInt(z);
			out.writeByte(angles >> 8);
			out.writeByte(angles);
		});
	}

	static byte[] remove(int id) {
		return packet(REMOVE, out -> out.writeInt(id));
	}

	static byte[] block(long position, int value) {
		return packet(BLOCK, out -> {
			out.writeInt(x(position));
			out.writeByte(y(position));
			out.writeInt(z(position));
			out.writeByte(value >> 4);
			out.writeByte(value & 15);
		});
	}

	private interface Body {
		void write(DataOutputStream out) throws IOException;
	}

	private static byte[] packet(int id, Body body) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(24);
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeByte(id);
			body.write(out);
		} catch (IOException e) {
			throw new IllegalStateException(e); // in memory: cannot happen
		}
		return bytes.toByteArray();
	}

	public static final class Builder {

		private final List<Track> tracks = new ArrayList<>();
		/** Entity id -> its live track while building. */
		private final Map<Integer, Track> live = new HashMap<>();
		private int blocks;
		private int[] blockIndex = new int[1024];
		private long[] blockPosition = new long[1024];
		private short[] blockValue = new short[1024];
		private int worldStartCount;
		private int[] worldStarts = new int[8];
		private int times;
		private int[] timeIndex = new int[256];
		private long[] timeValue = new long[256];

		public void packet(int index, byte[] data, int length) {
			if (length < 1) {
				return;
			}
			try {
				read(index, data, length);
			} catch (ArrayIndexOutOfBoundsException e) {
				// shorter than its format: left out of Quick Mode, normal playback still has it
			}
		}

		private void read(int index, byte[] d, int length) {
			int id = d[0] & 0xFF;
			switch (id) {
				case LOGIN:
				case RESPAWN:
					// the world closes: every entity in it goes
					for (Track track : live.values()) {
						track.removeIndex = index;
					}
					live.clear();
					if (worldStartCount == worldStarts.length) {
						worldStarts = Arrays.copyOf(worldStarts, worldStartCount * 2);
					}
					worldStarts[worldStartCount++] = index;
					break;
				case TIME:
					if (times == timeIndex.length) {
						timeIndex = Arrays.copyOf(timeIndex, times * 2);
						timeValue = Arrays.copyOf(timeValue, times * 2);
					}
					timeIndex[times] = index;
					timeValue[times++] = readLong(d, 1);
					break;
				case ADD_PLAYER: {
					int o = 5 + 2 + 2 * readShort(d, 5); // name: short length, UTF-16 chars
					spawn(index, d, length, readInt(d, 1), readInt(d, o), readInt(d, o + 4), readInt(d, o + 8), angles(d[o + 12], d[o + 13]), false);
					break;
				}
				case ADD_ITEM:
					spawn(index, d, length, readInt(d, 1), readInt(d, 10), readInt(d, 14), readInt(d, 18), 0, false);
					break;
				case ADD_ENTITY:
					spawn(index, d, length, readInt(d, 1), readInt(d, 6), readInt(d, 10), readInt(d, 14), 0, false);
					break;
				case ADD_MOB:
					spawn(index, d, length, readInt(d, 1), readInt(d, 6), readInt(d, 10), readInt(d, 14), angles(d[18], d[19]), false);
					break;
				case ADD_PAINTING: {
					int o = 5 + 2 + 2 * readShort(d, 5);
					spawn(index, d, length, readInt(d, 1), readInt(d, o), readInt(d, o + 4), readInt(d, o + 8), 0, true);
					break;
				}
				case ADD_ORB:
					spawn(index, d, length, readInt(d, 1), readInt(d, 5), readInt(d, 9), readInt(d, 13), 0, false);
					break;
				case REMOVE:
				case PICKUP: {
					Track track = live.remove(readInt(d, 1));
					if (track != null) {
						track.removeIndex = index;
					}
					break;
				}
				case MOVE:
				case LOOK:
				case MOVE_LOOK: {
					Track track = live.get(readInt(d, 1));
					if (track == null || track.still) {
						break;
					}
					int o = (track.count - 1) * 5;
					int x = track.entries[o + 1];
					int y = track.entries[o + 2];
					int z = track.entries[o + 3];
					int angles = track.entries[o + 4];
					if (id != LOOK) {
						x += d[5];
						y += d[6];
						z += d[7];
					}
					if (id == LOOK) {
						angles = angles(d[5], d[6]);
					} else if (id == MOVE_LOOK) {
						angles = angles(d[8], d[9]);
					}
					track.add(index, x, y, z, angles);
					break;
				}
				case TELEPORT: {
					Track track = live.get(readInt(d, 1));
					if (track != null && !track.still) {
						track.add(index, readInt(d, 5), readInt(d, 9), readInt(d, 13), angles(d[17], d[18]));
					}
					break;
				}
				case BLOCK:
					change(index, position(readInt(d, 1), d[5] & 0xFF, readInt(d, 6)), (d[10] & 0xFF) << 4 | (d[11] & 15));
					break;
				case BLOCKS: {
					int chunkX = readInt(d, 1);
					int chunkZ = readInt(d, 5);
					int count = readShort(d, 9);
					int ids = 11 + count * 2;
					for (int i = 0; i < count; i++) {
						int at = readShort(d, 11 + i * 2); // x << 12 | z << 8 | y
						long position = position(chunkX * 16 + (at >> 12 & 15), at & 255, chunkZ * 16 + (at >> 8 & 15));
						change(index, position, (d[ids + i] & 0xFF) << 4 | (d[ids + count + i] & 15));
					}
					break;
				}
				default:
					break;
			}
		}

		private void spawn(int index, byte[] d, int length, int id, int x, int y, int z, int angles, boolean still) {
			Track old = live.get(id);
			if (old != null) {
				old.removeIndex = index; // the client replaces it too
			}
			Track track = new Track(id, Arrays.copyOf(d, length), index, still);
			track.add(index, x, y, z, angles);
			tracks.add(track);
			live.put(id, track);
		}

		private void change(int index, long position, int value) {
			if (blocks == blockIndex.length) {
				blockIndex = Arrays.copyOf(blockIndex, blocks * 2);
				blockPosition = Arrays.copyOf(blockPosition, blocks * 2);
				blockValue = Arrays.copyOf(blockValue, blocks * 2);
			}
			blockIndex[blocks] = index;
			blockPosition[blocks] = position;
			blockValue[blocks++] = (short) value;
		}

		public QuickIndex build() {
			return new QuickIndex(this);
		}
	}

	private static int angles(byte yaw, byte pitch) {
		return (yaw & 0xFF) << 8 | (pitch & 0xFF);
	}

	private static int readShort(byte[] d, int o) {
		return (d[o] & 0xFF) << 8 | (d[o + 1] & 0xFF);
	}

	private static int readInt(byte[] d, int o) {
		return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16 | (d[o + 2] & 0xFF) << 8 | (d[o + 3] & 0xFF);
	}

	private static long readLong(byte[] d, int o) {
		return (long) readInt(d, o) << 32 | (readInt(d, o + 4) & 0xFFFFFFFFL);
	}
}
