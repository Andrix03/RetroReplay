package io.github.andrix03.retroreplay.replay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
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
 * Also each entity's equipment, entity data and riding (the last packet of each kind is put back),
 * rain, blocks blown up, and chunks: one unloaded since is rebuilt from its own packets, read back
 * from the file. Still approximate: what a packet does beyond setting state (sounds, particles).
 * Packet layouts are 1.0's, 1.1's (the same for these), 1.2.5's (block changes, chunk data and
 * head turns differ) and 1.3.2-1.6.4's (removals are lists, block ids shorts, chunks load with
 * their data in a full chunk packet or a part of a bulk one) and 1.7.10's (the same with its own
 * packet ids, VarInt ids in spawn packets and block changes, float explosions), read from their
 * sources. Packet ids below are the pre-1.7 ones; 1.7.10's are translated to them and back.
 * 1.13.2 has its own reader and packets (flattened block states, positions in 1/4096 block kept
 * as longs, no bulk chunk packet, an unload packet, riding as a vehicle's passenger list).
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
	private static final int EQUIPMENT = 0x05;
	private static final int RIDE = 0x27;
	private static final int ENTITY_DATA = 0x28;
	private static final int CHUNK_LOAD = 0x32;
	private static final int CHUNK_DATA = 0x33;
	private static final int CHUNK_BULK = 0x38;
	private static final int EXPLOSION = 0x3C;
	private static final int GAME_EVENT = 0x46;

	/** State packet kinds besides the equipment slots (0-4). */
	static final int KIND_DATA = 10;
	static final int KIND_RIDE = 11;
	/** 1.2.5's head turn (0x23), apart from the body's yaw. */
	static final int KIND_HEAD = 12;
	private static final int HEAD = 0x23;

	/** Quick Mode exists for this version (its packet layouts are known). */
	public static boolean supports(String version) {
		return Arrays.asList("1.0.0", "1.1", "1.2.5", "1.3.2", "1.4.7", "1.5.2", "1.6.4", "1.7.10", "1.13.2").contains(version);
	}

	/** 1.13.2's play packet ids (registration order of its clientbound packets). */
	private static final int F_ADD_ENTITY = 0, F_ADD_ORB = 1, F_ADD_MOB = 3, F_ADD_PAINTING = 4, F_ADD_PLAYER = 5,
		F_BLOCK = 11, F_BLOCKS = 15, F_EXPLOSION = 30, F_UNLOAD = 31, F_GAME_EVENT = 32, F_CHUNK = 34, F_LOGIN = 37,
		F_MOVE = 40, F_MOVE_LOOK = 41, F_LOOK = 42, F_REMOVE = 53, F_RESPAWN = 56, F_HEAD = 57, F_DATA = 63,
		F_EQUIPMENT = 66, F_PASSENGERS = 70, F_TIME = 74, F_PICKUP = 79, F_TELEPORT = 80;

	/** 1.7.10's play packet id -> the pre-1.7 id these packets had (0: not used here). */
	private static final int[] FROM_NETTY = new int[64];
	/** And back. */
	private static final int[] TO_NETTY = new int[256];

	static {
		int[][] pairs = { { 1, LOGIN }, { 3, TIME }, { 4, EQUIPMENT }, { 7, RESPAWN }, { 12, ADD_PLAYER }, { 13, PICKUP },
			{ 14, ADD_ENTITY }, { 15, ADD_MOB }, { 16, ADD_PAINTING }, { 17, ADD_ORB }, { 19, REMOVE }, { 21, MOVE },
			{ 22, LOOK }, { 23, MOVE_LOOK }, { 24, TELEPORT }, { 25, HEAD }, { 27, RIDE }, { 28, ENTITY_DATA },
			{ 33, CHUNK_DATA }, { 34, BLOCKS }, { 35, BLOCK }, { 38, CHUNK_BULK }, { 39, EXPLOSION }, { 43, GAME_EVENT } };
		for (int[] pair : pairs) {
			FROM_NETTY[pair[0]] = pair[1];
			TO_NETTY[pair[1]] = pair[0];
		}
	}

	/** A packet made up here (pre-1.7 id) as this version stores it: 1.7.10 gets its own id. */
	byte[] wire(byte[] packet) {
		if (format != ChunkIndex.NETTY) {
			return packet;
		}
		byte[] out = packet.clone();
		out[0] = (byte) TO_NETTY[packet[0] & 0xFF];
		return out;
	}

	/** The teleport that puts a track where its entry {@code e} has it. */
	byte[] teleportPacket(Track track, int e) {
		if (flattened()) {
			return packet(F_TELEPORT, out -> {
				writeVarInt(out, track.id);
				out.writeDouble(track.x(e) / 4096.0);
				out.writeDouble(track.y(e) / 4096.0);
				out.writeDouble(track.z(e) / 4096.0);
				out.writeByte(track.angles(e) >> 8);
				out.writeByte(track.angles(e));
				out.writeBoolean(false);
			});
		}
		return wire(track.teleport(e));
	}

	/** What puts a track's kind of state back to "none", or null. */
	byte[] noStatePacket(Track track, int kind) {
		if (flattened()) {
			if (kind == KIND_RIDE) { // no passengers
				return packet(F_PASSENGERS, out -> {
					writeVarInt(out, track.id);
					writeVarInt(out, 0);
				});
			}
			if (kind == KIND_DATA || kind == KIND_HEAD) {
				return null;
			}
			return packet(F_EQUIPMENT, out -> { // the slot empty
				writeVarInt(out, track.id);
				writeVarInt(out, kind);
				out.writeBoolean(false);
			});
		}
		byte[] packet = track.noState(kind);
		return packet == null ? null : wire(packet);
	}

	boolean flattened() {
		return format == ChunkIndex.FLATTENED;
	}

	/** ChunkIndex's format: SPLIT_REGION (1.0, 1.1), SPLIT_COLUMN (1.2.5), INTEGRATED_NO_LIGHT (1.3.2), INTEGRATED (1.4-1.6). */
	final int format;
	/** 1.6.4: the riding packet ends with a leash flag. */
	final boolean leash;

	/** From 1.3: removals are lists, block ids shorts, no chunk load packet (the data loads it). 1.7.10 too. */
	boolean integrated() {
		return format == ChunkIndex.INTEGRATED_NO_LIGHT || format == ChunkIndex.INTEGRATED || format == ChunkIndex.NETTY;
	}

	/** One entity's life: from its spawn packet to its removal (or the world closing). */
	static final class Track {
		final int id;
		final byte[] spawn;
		final int spawnIndex;
		int removeIndex = Integer.MAX_VALUE;
		/** Paintings: block coordinates, never teleported. */
		final boolean still;
		/**
		 * Per entry: packet index, x, y, z (1/32 block), yaw << 8 | pitch (as bytes). 1.13 ("wide"):
		 * packet index, x, y, z each as two ints (a long in 1/4096 block), angles.
		 */
		int[] entries;
		int count;
		final boolean wide;
		private final int stride;

		/** Equipment (kind = slot), entity data and riding packets in order. */
		int states;
		int[] stateIndex = new int[4];
		int[] stateKind = new int[4];
		byte[][] stateBytes = new byte[4][];

		void addState(int index, int kind, byte[] bytes) {
			if (states == stateIndex.length) {
				stateIndex = Arrays.copyOf(stateIndex, states * 2);
				stateKind = Arrays.copyOf(stateKind, states * 2);
				stateBytes = Arrays.copyOf(stateBytes, states * 2);
			}
			stateIndex[states] = index;
			stateKind[states] = kind;
			stateBytes[states++] = bytes;
		}

		/** The last state packet of that kind among the packets before {@code fed}, as its number, or -1. */
		int stateAt(int kind, int fed) {
			int found = -1;
			for (int i = 0; i < states && stateIndex[i] < fed; i++) {
				if (stateKind[i] == kind) {
					found = i;
				}
			}
			return found;
		}

		/** What puts that kind back to "none": empty slot, on foot; null for entity data (nothing to undo). */
		byte[] noState(int kind) {
			if (kind == KIND_RIDE) {
				return packet(RIDE, out -> {
					out.writeInt(id);
					out.writeInt(-1);
					if (leash) {
						out.writeByte(0); // riding, not a leash
					}
				});
			}
			if (kind == KIND_DATA || kind == KIND_HEAD) {
				return null;
			}
			return packet(EQUIPMENT, out -> {
				out.writeInt(id);
				out.writeShort(kind);
				out.writeShort(-1); // no item (from 1.3 nothing follows it)
				if (!integrated) {
					out.writeShort(0);
				}
			});
		}

		private final boolean integrated;
		private final boolean leash;

		Track(int id, byte[] spawn, int spawnIndex, boolean still) {
			this(id, spawn, spawnIndex, still, false, false);
		}

		Track(int id, byte[] spawn, int spawnIndex, boolean still, boolean integrated, boolean leash) {
			this(id, spawn, spawnIndex, still, integrated, leash, false);
		}

		Track(int id, byte[] spawn, int spawnIndex, boolean still, boolean integrated, boolean leash, boolean wide) {
			this.id = id;
			this.spawn = spawn;
			this.spawnIndex = spawnIndex;
			this.still = still;
			this.integrated = integrated;
			this.leash = leash;
			this.wide = wide;
			this.stride = wide ? 8 : 5;
			this.entries = new int[stride * 4];
		}

		void add(int index, long x, long y, long z, int angles) {
			if ((count + 1) * stride > entries.length) {
				entries = Arrays.copyOf(entries, entries.length * 2);
			}
			int o = count * stride;
			entries[o] = index;
			if (wide) {
				entries[o + 1] = (int) (x >> 32);
				entries[o + 2] = (int) x;
				entries[o + 3] = (int) (y >> 32);
				entries[o + 4] = (int) y;
				entries[o + 5] = (int) (z >> 32);
				entries[o + 6] = (int) z;
				entries[o + 7] = angles;
			} else {
				entries[o + 1] = (int) x;
				entries[o + 2] = (int) y;
				entries[o + 3] = (int) z;
				entries[o + 4] = angles;
			}
			count++;
		}

		private long wide(int o) {
			return (long) entries[o] << 32 | entries[o + 1] & 0xFFFFFFFFL;
		}

		long x(int e) {
			return wide ? wide(e * stride + 1) : entries[e * stride + 1];
		}

		long y(int e) {
			return wide ? wide(e * stride + 3) : entries[e * stride + 2];
		}

		long z(int e) {
			return wide ? wide(e * stride + 5) : entries[e * stride + 3];
		}

		int angles(int e) {
			return entries[e * stride + stride - 1];
		}

		/** The last entry's values: the base the next relative move adds to. */
		int last() {
			return count - 1;
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
				if (entries[mid * stride] < fed) {
					low = mid;
				} else {
					high = mid - 1;
				}
			}
			return low;
		}

		/** Entry {@code e}: x, y, z in blocks where a teleport ends (it lifts by 1/64), yaw, pitch in degrees. */
		double[] place(int e) {
			float yaw = (byte) (angles(e) >> 8) * 360 / 256.0F;
			float pitch = (byte) angles(e) * 360 / 256.0F;
			if (wide) { // 1.13's teleport ends where it says
				return new double[] { x(e) / 4096.0, y(e) / 4096.0, z(e) / 4096.0, yaw, pitch };
			}
			return new double[] { x(e) / 32.0, y(e) / 32.0 + 0.015625, z(e) / 32.0, yaw, pitch };
		}

		/** The teleport packet that puts this entity where entry {@code e} has it (before 1.13). */
		byte[] teleport(int e) {
			return QuickIndex.teleport(id, (int) x(e), (int) y(e), (int) z(e), angles(e));
		}
	}

	final Track[] tracks;
	/** Entity id -> its tracks (an id can come back after a removal), built when first asked. */
	private Map<Integer, List<Track>> byId;
	/** Block changes in packet order: packet index, position, block id << 4 | metadata. */
	private final int[] blockIndex;
	private final long[] blockPosition;
	private final short[] blockValue;
	/** Position -> its changes (indexes into the arrays above), in order. */
	private final Map<Long, int[]> changesAt;
	/** Packets that close the world (login, respawn): Quick Mode does not cross them. */
	private final int[] worldStarts;
	private final int[] timeIndex;
	/** The time packets as recorded (1.4 has two longs in them). */
	private final byte[][] timeBytes;
	private final int[] weatherIndex;
	private final boolean[] weatherRain;
	/** Chunk key -> its loads, unloads and data packets. */
	final Map<Long, ChunkLog> chunks;

	/** One chunk's history: per event the packet index, its kind and (data) where it is in the file. */
	static final class ChunkLog {
		static final byte LOAD = 1;
		static final byte UNLOAD = 0;
		static final byte DATA = 2;
		final int x;
		final int z;
		int count;
		int[] index = new int[4];
		byte[] kind = new byte[4];
		long[] offset = new long[4];
		/** For a load from a bulk chunk packet (1.3+): which of its chunks; -1 otherwise. */
		int[] part = new int[4];
		/** Block changes in this chunk (numbers into the block change arrays), in order. */
		int changes;
		int[] change = new int[4];

		ChunkLog(int x, int z) {
			this.x = x;
			this.z = z;
		}

		void add(int packet, byte what, long at) {
			add(packet, what, at, -1);
		}

		void add(int packet, byte what, long at, int bulkPart) {
			if (count == index.length) {
				index = Arrays.copyOf(index, count * 2);
				kind = Arrays.copyOf(kind, count * 2);
				offset = Arrays.copyOf(offset, count * 2);
				part = Arrays.copyOf(part, count * 2);
			}
			index[count] = packet;
			kind[count] = what;
			part[count] = bulkPart;
			offset[count++] = at;
		}

		void addChange(int number) {
			if (changes == change.length) {
				change = Arrays.copyOf(change, changes * 2);
			}
			change[changes++] = number;
		}

		/** The packet index of the load in force once the packets before {@code fed} are handled, or -1 if unloaded then. */
		int loadedAt(int fed) {
			int found = -1;
			for (int i = 0; i < count && index[i] < fed; i++) {
				if (kind[i] == LOAD) {
					found = index[i];
				} else if (kind[i] == UNLOAD) {
					found = -1;
				}
			}
			return found;
		}

		/** True when a load or a data packet lies between the two points (its contents changed wholesale). */
		boolean reloadedBetween(int from, int to) {
			for (int i = 0; i < count; i++) {
				if (index[i] >= from && index[i] < to && kind[i] != UNLOAD) {
					return true;
				}
			}
			return false;
		}

		boolean anyBetween(int from, int to) {
			for (int i = 0; i < count; i++) {
				if (index[i] >= from && index[i] < to) {
					return true;
				}
			}
			return false;
		}
	}

	private QuickIndex(Builder b) {
		format = b.format;
		leash = b.leash;
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
		timeBytes = Arrays.copyOf(b.timeBytes, b.times);
		weatherIndex = Arrays.copyOf(b.weatherIndex, b.weathers);
		weatherRain = Arrays.copyOf(b.weatherRain, b.weathers);
		chunks = b.chunks;
		for (int i = 0; i < blockIndex.length; i++) {
			long position = blockPosition[i];
			ChunkLog log = chunks.get(chunkKey(x(position) >> 4, z(position) >> 4));
			if (log != null) {
				log.addChange(i);
			}
		}
	}

	static long chunkKey(int chunkX, int chunkZ) {
		return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
	}

	int blockChangeIndexOf(int change) {
		return blockIndex[change];
	}

	byte[] blockChangePacket(int change) {
		return blockPacket(blockPosition[change], blockValue[change] & 0xFFFF);
	}

	/** The rain packet in force once the packets before {@code fed} are handled, or null. */
	byte[] weatherAt(int fed) {
		int found = -1;
		for (int i = 0; i < weatherIndex.length && weatherIndex[i] < fed; i++) {
			found = i;
		}
		if (found < 0) {
			return null;
		}
		boolean rain = weatherRain[found];
		if (flattened()) {
			return packet(F_GAME_EVENT, out -> {
				out.writeByte(rain ? 1 : 2);
				out.writeFloat(0);
			});
		}
		return wire(packet(GAME_EVENT, out -> {
			out.writeByte(rain ? 1 : 2);
			if (format == ChunkIndex.NETTY) {
				out.writeFloat(0);
			} else {
				out.writeByte(0);
			}
		}));
	}

	static byte[] chunkLoad(int chunkX, int chunkZ, boolean load) {
		return packet(CHUNK_LOAD, out -> {
			out.writeInt(chunkX);
			out.writeInt(chunkZ);
			out.writeByte(load ? 1 : 0);
		});
	}

	/** Unloads a chunk: before 1.3 the load packet, from 1.3 a full chunk packet without sections, 1.13 its own. */
	byte[] chunkUnloadPacket(int chunkX, int chunkZ) {
		if (flattened()) {
			return packet(F_UNLOAD, out -> {
				out.writeInt(chunkX);
				out.writeInt(chunkZ);
			});
		}
		if (!integrated()) {
			return chunkLoad(chunkX, chunkZ, false);
		}
		return wire(singleChunk(chunkX, chunkZ, 0, 0, new byte[256]));
	}

	/**
	 * The packet that loads the chunk as event {@code event} of its log did: before 1.3 a made-up
	 * load packet (the data follows in its own packets), from 1.3 the full chunk packet read back,
	 * or its part of a bulk packet turned into one.
	 */
	byte[] loadPacket(ChunkLog log, int event, QuickOverlay.PacketReader file) throws IOException {
		if (log.offset[event] < 0) {
			return chunkLoad(log.x, log.z, true);
		}
		byte[] stored = file.read(log.offset[event]);
		if (log.part[event] < 0) {
			return stored;
		}
		return wire(bulkPart(stored, log.part[event]));
	}

	/**
	 * Chunk {@code part} of a bulk chunk packet (0x38: short count, int size, from 1.4 a sky light
	 * flag, the compressed data of every chunk, then x, z, sections, upper id sections each) as a
	 * full chunk packet of its own, the way the game slices the data.
	 */
	byte[] bulkPart(byte[] d, int part) throws IOException {
		int chunks = readShort(d, 1);
		int size = readInt(d, 3);
		boolean light = format == ChunkIndex.INTEGRATED_NO_LIGHT || d[7] != 0;
		int start = format == ChunkIndex.INTEGRATED_NO_LIGHT ? 7 : 8;
		int entries = start + size;
		byte[] all = new byte[196864 * chunks];
		Inflater inflater = new Inflater();
		inflater.setInput(d, start, size);
		try {
			inflater.inflate(all);
		} catch (DataFormatException e) {
			throw new IOException("Bad bulk chunk data", e);
		} finally {
			inflater.end();
		}
		int at = 0;
		for (int k = 0; k < chunks; k++) {
			int e = entries + k * 12;
			int sections = readShort(d, e + 8);
			int upper = readShort(d, e + 10);
			int length = chunkDataLength(sections, upper, light);
			if (k == part) {
				return singleChunk(readInt(d, e), readInt(d, e + 4), sections, upper, Arrays.copyOfRange(all, at, at + length));
			}
			at += length;
		}
		throw new IOException("No chunk " + part + " in a bulk of " + chunks);
	}

	/** A chunk's data size in a bulk packet: 1.3.2 counts five arrays a section, from 1.4 the real ones. */
	int chunkDataLength(int sections, int upper, boolean light) {
		int count = Integer.bitCount(sections & 0xFFFF);
		if (format == ChunkIndex.INTEGRATED_NO_LIGHT) {
			return 2048 * 5 * count + 256;
		}
		return 2048 * 4 * count + 256 + 2048 * Integer.bitCount(upper & 0xFFFF) + (light ? 2048 * count : 0);
	}

	/** A full chunk packet (0x33: x, z, full, sections, upper id sections, compressed size and data). */
	static byte[] singleChunk(int chunkX, int chunkZ, int sections, int upper, byte[] data) {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
		deflater.setInput(data);
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream(data.length / 2 + 64);
		byte[] chunk = new byte[8192];
		while (!deflater.finished()) {
			compressed.write(chunk, 0, deflater.deflate(chunk));
		}
		deflater.end();
		byte[] bytes = compressed.toByteArray();
		return packet(CHUNK_DATA, out -> {
			out.writeInt(chunkX);
			out.writeInt(chunkZ);
			out.writeBoolean(true);
			out.writeShort(sections);
			out.writeShort(upper);
			out.writeInt(bytes.length);
			out.write(bytes);
		});
	}

	/**
	 * Where entity {@code id} was once the packets before {@code fed} were handled: x, y, z (as the
	 * client keeps them), yaw, pitch; null when it did not exist then.
	 */
	double[] positionOf(int id, int fed) {
		if (byId == null) {
			Map<Integer, List<Track>> map = new HashMap<>();
			for (Track track : tracks) {
				map.computeIfAbsent(track.id, k -> new ArrayList<>()).add(track);
			}
			byId = map;
		}
		List<Track> list = byId.get(id);
		if (list != null) {
			for (Track track : list) {
				if (track.aliveAt(fed)) {
					return track.place(track.entryAt(fed));
				}
			}
		}
		return null;
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
				value = blockValue[list[i]] & 0xFFFF;
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

	/** RETROREPLAY_QUICK_DEBUG: logs each old block value read. */
	private static final boolean DEBUG = System.getenv("RETROREPLAY_QUICK_DEBUG") != null;

	/** True when packet {@code packet} changes blocks (its old values are to be read first). */
	boolean changesBlocks(int packet) {
		int c = firstBlockChange(packet);
		return c < blockIndex.length && blockIndex[c] == packet;
	}

	/** Before packet {@code packet} is handled (or skipped): keeps the values its first changes replace. */
	void beforePacket(int packet, BlockReader world) {
		for (int c = firstBlockChange(packet); c < blockIndex.length && blockIndex[c] == packet; c++) {
			long position = blockPosition[c];
			if (firstChangeIndex(position) == packet && !base.containsKey(position)) {
				int value = world.read(x(position), y(position), z(position));
				if (DEBUG) {
					io.github.andrix03.retroreplay.RetroReplay.LOGGER.info("Quick base " + x(position) + "," + y(position) + "," + z(position) + " before packet " + packet + ": " + value);
				}
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
		return found < 0 ? null : timeBytes[found];
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

	/** Removes an entity: from 1.3 a list of one. */
	byte[] removePacket(int id) {
		if (flattened()) {
			return packet(F_REMOVE, out -> {
				writeVarInt(out, 1);
				writeVarInt(out, id);
			});
		}
		if (!integrated()) {
			return remove(id);
		}
		return wire(packet(REMOVE, out -> {
			out.writeByte(1);
			out.writeInt(id);
		}));
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

	/** Sets a block: from 1.3 its id is a short. */
	byte[] blockPacket(long position, int value) {
		if (flattened()) { // packed position, block state id
			return packet(F_BLOCK, out -> {
				out.writeLong(((long) x(position) & 0x3FFFFFF) << 38 | ((long) y(position) & 0xFFF) << 26 | (long) z(position) & 0x3FFFFFF);
				writeVarInt(out, value);
			});
		}
		if (!integrated()) {
			return block(position, value);
		}
		return wire(packet(BLOCK, out -> {
			out.writeInt(x(position));
			out.writeByte(y(position));
			out.writeInt(z(position));
			if (format == ChunkIndex.NETTY) {
				writeVarInt(out, value >> 4);
			} else {
				out.writeShort(value >> 4);
			}
			out.writeByte(value & 15);
		}));
	}

	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte(value & 0x7F | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
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

		/** ChunkIndex's format of the version. */
		private final int format;
		private final boolean leash;

		public Builder() {
			this(ChunkIndex.SPLIT_REGION);
		}

		public Builder(int format) {
			this(format, "");
		}

		public Builder(int format, String version) {
			this.format = format;
			this.leash = "1.6.4".equals(version) || format == ChunkIndex.NETTY;
		}

		private boolean integrated() {
			return format == ChunkIndex.INTEGRATED_NO_LIGHT || format == ChunkIndex.INTEGRATED || format == ChunkIndex.NETTY;
		}

		private boolean netty() {
			return format == ChunkIndex.NETTY;
		}

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
		private byte[][] timeBytes = new byte[256][];
		private int weathers;
		private int[] weatherIndex = new int[16];
		private boolean[] weatherRain = new boolean[16];
		private final Map<Long, ChunkLog> chunks = new HashMap<>();
		/** Where the next packet starts in the unpacked file (time, length, then its bytes). */
		private long offset;

		public void packet(int index, byte[] data, int length) {
			if (length < 1) {
				offset += 8; // still takes its time and length in the file
				return;
			}
			try {
				read(index, data, length);
			} catch (ArrayIndexOutOfBoundsException e) {
				// shorter than its format: left out of Quick Mode, normal playback still has it
			} finally {
				offset += 8 + length;
			}
		}

		private ChunkLog chunk(int chunkX, int chunkZ) {
			long key = chunkKey(chunkX, chunkZ);
			ChunkLog log = chunks.get(key);
			if (log == null) {
				log = new ChunkLog(chunkX, chunkZ);
				chunks.put(key, log);
			}
			return log;
		}

		private void state(byte[] d, int length, int index, int kind) {
			Track track = live.get(readInt(d, 1));
			if (track != null) {
				track.addState(index, kind, Arrays.copyOf(d, length));
			}
		}

		private void read(int index, byte[] d, int length) {
			int id = d[0] & 0xFF;
			if (format == ChunkIndex.FLATTENED) {
				readFlattened(index, d, length, id);
				return;
			}
			if (netty()) {
				if (id >= FROM_NETTY.length || FROM_NETTY[id] == 0) {
					return;
				}
				id = FROM_NETTY[id];
				if (readNetty(index, d, length, id)) {
					return;
				}
			}
			switch (id) {
				case LOGIN:
				case RESPAWN:
					worldStart(index);
					break;
				case TIME:
					time(index, d, length);
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
					if (integrated()) {
						// a list: byte count, then the ids
						for (int i = 0; i < (d[1] & 0xFF); i++) {
							removed(readInt(d, 2 + i * 4), index);
						}
						break;
					}
					removed(readInt(d, 1), index);
					break;
				case PICKUP:
					removed(readInt(d, 1), index);
					break;
				case MOVE:
				case LOOK:
				case MOVE_LOOK: {
					Track track = live.get(readInt(d, 1));
					if (track == null || track.still) {
						break;
					}
					int last = track.last();
					long x = track.x(last);
					long y = track.y(last);
					long z = track.z(last);
					int angles = track.angles(last);
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
					if (integrated()) {
						change(index, position(readInt(d, 1), d[5] & 0xFF, readInt(d, 6)), readShort(d, 10) << 4 | (d[12] & 15));
						break;
					}
					change(index, position(readInt(d, 1), d[5] & 0xFF, readInt(d, 6)), (d[10] & 0xFF) << 4 | (d[11] & 15));
					break;
				case EQUIPMENT:
					state(d, length, index, readShort(d, 5));
					break;
				case ENTITY_DATA:
					state(d, length, index, KIND_DATA);
					break;
				case RIDE:
					state(d, length, index, KIND_RIDE);
					break;
				case GAME_EVENT:
					if (d[1] == 1 || d[1] == 2) {
						weather(index, d[1] == 1);
					}
					break;
				case EXPLOSION: {
					// the client clears these blocks itself: block changes to air like any other
					int x = (int) Double.longBitsToDouble(readLong(d, 1));
					int y = (int) Double.longBitsToDouble(readLong(d, 9));
					int z = (int) Double.longBitsToDouble(readLong(d, 17));
					int count = readInt(d, 29);
					for (int i = 0; i < count; i++) {
						change(index, position(x + d[33 + i * 3], y + d[34 + i * 3], z + d[35 + i * 3]), 0);
					}
					break;
				}
				case CHUNK_LOAD:
					if (!integrated()) {
						chunk(readInt(d, 1), readInt(d, 5)).add(index, d[9] != 0 ? ChunkLog.LOAD : ChunkLog.UNLOAD, -1);
					}
					break;
				case HEAD:
					if (format != ChunkIndex.SPLIT_REGION) {
						state(d, length, index, KIND_HEAD);
					}
					break;
				case CHUNK_BULK:
					if (integrated()) {
						int count = readShort(d, 1);
						int entries = (format == ChunkIndex.INTEGRATED_NO_LIGHT ? 7 : 8) + readInt(d, 3); // 1.7.10 as 1.4
						for (int k = 0; k < count; k++) {
							int e = entries + k * 12;
							chunk(readInt(d, e), readInt(d, e + 4)).add(index, ChunkLog.LOAD, offset, k);
						}
					}
					break;
				case CHUNK_DATA: {
					if (integrated()) {
						// x, z, full, sections: a full one without sections unloads, a full one loads
						boolean full = d[9] != 0;
						int sections = readShort(d, 10);
						chunk(readInt(d, 1), readInt(d, 5)).add(index,
							full && sections == 0 ? ChunkLog.UNLOAD : full ? ChunkLog.LOAD : ChunkLog.DATA, full && sections == 0 ? -1 : offset);
						break;
					}
					if (format == ChunkIndex.SPLIT_COLUMN) {
						chunk(readInt(d, 1), readInt(d, 5)).add(index, ChunkLog.DATA, offset); // a chunk column
						break;
					}
					// a block region (x, short y, z, sizes - 1): every chunk it covers
					int x = readInt(d, 1);
					int z = readInt(d, 7);
					int sizeX = (d[11] & 0xFF) + 1;
					int sizeZ = (d[13] & 0xFF) + 1;
					for (int cx = x >> 4; cx <= (x + sizeX - 1) >> 4; cx++) {
						for (int cz = z >> 4; cz <= (z + sizeZ - 1) >> 4; cz++) {
							chunk(cx, cz).add(index, ChunkLog.DATA, offset);
						}
					}
					break;
				}
				case BLOCKS: {
					int chunkX = readInt(d, 1);
					int chunkZ = readInt(d, 5);
					int count = readShort(d, 9);
					if (format != ChunkIndex.SPLIT_REGION) {
						// int data length, then a record each: x << 28 | z << 24 | y << 16 | block << 4 | metadata
						for (int i = 0; i < count; i++) {
							int record = readInt(d, 15 + i * 4);
							change(index, position(chunkX * 16 + (record >>> 28), record >> 16 & 255, chunkZ * 16 + (record >> 24 & 15)), record & 0xFFFF);
						}
						break;
					}
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

		/** 1.7.10's spawn packets, block change, explosion and game event; false for the rest (as 1.6). */
		private boolean readNetty(int index, byte[] d, int length, int id) {
			int[] at = { 1 };
			switch (id) {
				case ADD_PLAYER: {
					int entity = readVarInt(d, at);
					skipString(d, at); // uuid
					skipString(d, at); // name
					int properties = readVarInt(d, at);
					for (int i = 0; i < properties * 3; i++) {
						skipString(d, at);
					}
					int o = at[0];
					spawn(index, d, length, entity, readInt(d, o), readInt(d, o + 4), readInt(d, o + 8), angles(d[o + 12], d[o + 13]), false);
					return true;
				}
				case ADD_ENTITY: {
					int entity = readVarInt(d, at);
					int o = at[0] + 1; // type
					spawn(index, d, length, entity, readInt(d, o), readInt(d, o + 4), readInt(d, o + 8), 0, false);
					return true;
				}
				case ADD_MOB: {
					int entity = readVarInt(d, at);
					int o = at[0] + 1;
					spawn(index, d, length, entity, readInt(d, o), readInt(d, o + 4), readInt(d, o + 8), angles(d[o + 12], d[o + 13]), false);
					return true;
				}
				case ADD_PAINTING: {
					int entity = readVarInt(d, at);
					skipString(d, at);
					int o = at[0];
					spawn(index, d, length, entity, readInt(d, o), readInt(d, o + 4), readInt(d, o + 8), 0, true);
					return true;
				}
				case ADD_ORB: {
					int entity = readVarInt(d, at);
					int o = at[0];
					spawn(index, d, length, entity, readInt(d, o), readInt(d, o + 4), readInt(d, o + 8), 0, false);
					return true;
				}
				case BLOCK: {
					at[0] = 10;
					int block = readVarInt(d, at);
					change(index, position(readInt(d, 1), d[5] & 0xFF, readInt(d, 6)), block << 4 | (d[at[0]] & 15));
					return true;
				}
				case EXPLOSION: {
					int x = (int) Float.intBitsToFloat(readInt(d, 1));
					int y = (int) Float.intBitsToFloat(readInt(d, 5));
					int z = (int) Float.intBitsToFloat(readInt(d, 9));
					int count = readInt(d, 17);
					for (int i = 0; i < count; i++) {
						change(index, position(x + d[21 + i * 3], y + d[22 + i * 3], z + d[23 + i * 3]), 0);
					}
					return true;
				}
				default:
					return false; // the rest is laid out as in 1.6 (game event: its first byte too)
			}
		}

		/** The world closes (login, respawn): every entity and chunk in it goes. */
		private void worldStart(int index) {
			for (Track track : live.values()) {
				track.removeIndex = index;
			}
			live.clear();
			for (ChunkLog log : chunks.values()) {
				if (log.count > 0 && log.kind[log.count - 1] != ChunkLog.UNLOAD) {
					log.add(index, ChunkLog.UNLOAD, -1);
				}
			}
			if (worldStartCount == worldStarts.length) {
				worldStarts = Arrays.copyOf(worldStarts, worldStartCount * 2);
			}
			worldStarts[worldStartCount++] = index;
		}

		private void time(int index, byte[] d, int length) {
			if (times == timeIndex.length) {
				timeIndex = Arrays.copyOf(timeIndex, times * 2);
				timeBytes = Arrays.copyOf(timeBytes, times * 2);
			}
			timeIndex[times] = index;
			timeBytes[times++] = Arrays.copyOf(d, length);
		}

		private void weather(int index, boolean rain) {
			if (weathers == weatherIndex.length) {
				weatherIndex = Arrays.copyOf(weatherIndex, weathers * 2);
				weatherRain = Arrays.copyOf(weatherRain, weathers * 2);
			}
			weatherIndex[weathers] = index;
			weatherRain[weathers++] = rain;
		}

		/** 1.13.2: VarInt ids everywhere, doubles in spawns and teleports, short moves in 1/4096 block. */
		private void readFlattened(int index, byte[] d, int length, int id) {
			int[] at = { 1 };
			switch (id) {
				case F_LOGIN:
				case F_RESPAWN:
					worldStart(index);
					break;
				case F_TIME:
					time(index, d, length);
					break;
				case F_ADD_ENTITY: { // id, uuid, type, x, y, z, pitch, yaw
					int entity = readVarInt(d, at);
					int o = at[0] + 16 + 1;
					spawnWide(index, d, length, entity, o, angles(d[o + 25], d[o + 24]), false);
					break;
				}
				case F_ADD_ORB: {
					int entity = readVarInt(d, at);
					spawnWide(index, d, length, entity, at[0], 0, false);
					break;
				}
				case F_ADD_MOB: { // id, uuid, VarInt type, x, y, z, yaw, pitch
					int entity = readVarInt(d, at);
					at[0] += 16;
					readVarInt(d, at);
					int o = at[0];
					spawnWide(index, d, length, entity, o, angles(d[o + 24], d[o + 25]), false);
					break;
				}
				case F_ADD_PLAYER: { // id, uuid, x, y, z, yaw, pitch
					int entity = readVarInt(d, at);
					int o = at[0] + 16;
					spawnWide(index, d, length, entity, o, angles(d[o + 24], d[o + 25]), false);
					break;
				}
				case F_ADD_PAINTING: {
					int entity = readVarInt(d, at);
					spawn(index, d, length, entity, 0, 0, 0, 0, true);
					break;
				}
				case F_REMOVE: {
					int count = readVarInt(d, at);
					for (int i = 0; i < count; i++) {
						removed(readVarInt(d, at), index);
					}
					break;
				}
				case F_PICKUP:
					removed(readVarInt(d, at), index);
					break;
				case F_MOVE:
				case F_MOVE_LOOK:
				case F_LOOK: {
					Track track = live.get(readVarInt(d, at));
					if (track == null || track.still) {
						break;
					}
					int o = at[0];
					int last = track.last();
					long x = track.x(last);
					long y = track.y(last);
					long z = track.z(last);
					int angles = track.angles(last);
					if (id != F_LOOK) {
						x += (short) readShort(d, o);
						y += (short) readShort(d, o + 2);
						z += (short) readShort(d, o + 4);
					}
					if (id == F_MOVE_LOOK) {
						angles = angles(d[o + 6], d[o + 7]);
					} else if (id == F_LOOK) {
						angles = angles(d[o], d[o + 1]);
					}
					track.add(index, x, y, z, angles);
					break;
				}
				case F_TELEPORT: {
					Track track = live.get(readVarInt(d, at));
					if (track != null && !track.still) {
						int o = at[0];
						track.add(index, pack(d, o), pack(d, o + 8), pack(d, o + 16), angles(d[o + 24], d[o + 25]));
					}
					break;
				}
				case F_HEAD:
					wideState(d, length, index, at, KIND_HEAD);
					break;
				case F_DATA:
					wideState(d, length, index, at, KIND_DATA);
					break;
				case F_PASSENGERS:
					wideState(d, length, index, at, KIND_RIDE); // kept on the vehicle
					break;
				case F_EQUIPMENT: {
					Track track = live.get(readVarInt(d, at));
					if (track != null) {
						track.addState(index, readVarInt(d, at), Arrays.copyOf(d, length));
					}
					break;
				}
				case F_GAME_EVENT:
					if (d[1] == 1 || d[1] == 2) {
						weather(index, d[1] == 1);
					}
					break;
				case F_BLOCK: {
					long packed = readLong(d, 1);
					at[0] = 9;
					change(index, position((int) (packed >> 38), (int) (packed << 26 >> 52), (int) (packed << 38 >> 38)), readVarInt(d, at));
					break;
				}
				case F_BLOCKS: { // chunk x, z, count, then (x << 4 | z, y, state) each
					int chunkX = readInt(d, 1);
					int chunkZ = readInt(d, 5);
					at[0] = 9;
					int count = readVarInt(d, at);
					for (int i = 0; i < count; i++) {
						int horizontal = d[at[0]] & 0xFF;
						int y = d[at[0] + 1] & 0xFF;
						at[0] += 2;
						change(index, position(chunkX * 16 + (horizontal >> 4), y, chunkZ * 16 + (horizontal & 15)), readVarInt(d, at));
					}
					break;
				}
				case F_EXPLOSION: {
					int x = (int) Float.intBitsToFloat(readInt(d, 1));
					int y = (int) Float.intBitsToFloat(readInt(d, 5));
					int z = (int) Float.intBitsToFloat(readInt(d, 9));
					int count = readInt(d, 17);
					for (int i = 0; i < count; i++) {
						change(index, position(x + d[21 + i * 3], y + d[22 + i * 3], z + d[23 + i * 3]), 0);
					}
					break;
				}
				case F_CHUNK: // x, z, full: a full one loads (with its data), the others change it
					chunk(readInt(d, 1), readInt(d, 5)).add(index, d[9] != 0 ? ChunkLog.LOAD : ChunkLog.DATA, offset);
					break;
				case F_UNLOAD:
					chunk(readInt(d, 1), readInt(d, 5)).add(index, ChunkLog.UNLOAD, -1);
					break;
				default:
					break;
			}
		}

		/** A double position in 1/4096 block, as the client packs it (floor). */
		private long pack(byte[] d, int o) {
			return (long) Math.floor(Double.longBitsToDouble(readLong(d, o)) * 4096.0);
		}

		private void spawnWide(int index, byte[] d, int length, int id, int o, int angles, boolean still) {
			Track old = live.get(id);
			if (old != null) {
				old.removeIndex = index;
			}
			Track track = new Track(id, Arrays.copyOf(d, length), index, still, true, true, true);
			track.add(index, pack(d, o), pack(d, o + 8), pack(d, o + 16), angles);
			tracks.add(track);
			live.put(id, track);
		}

		private void wideState(byte[] d, int length, int index, int[] at, int kind) {
			Track track = live.get(readVarInt(d, at));
			if (track != null) {
				track.addState(index, kind, Arrays.copyOf(d, length));
			}
		}

		private void removed(int id, int index) {
			Track track = live.remove(id);
			if (track != null) {
				track.removeIndex = index;
			}
		}

		private void spawn(int index, byte[] d, int length, int id, int x, int y, int z, int angles, boolean still) {
			Track old = live.get(id);
			if (old != null) {
				old.removeIndex = index; // the client replaces it too
			}
			Track track = new Track(id, Arrays.copyOf(d, length), index, still, integrated(), leash);
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

	private static int readVarInt(byte[] d, int[] at) {
		int value = 0;
		for (int shift = 0;; shift += 7) {
			byte b = d[at[0]++];
			value |= (b & 0x7F) << shift;
			if (b >= 0) {
				return value;
			}
		}
	}

	/** A 1.7 string: VarInt byte length, then UTF-8. */
	private static void skipString(byte[] d, int[] at) {
		int length = readVarInt(d, at);
		at[0] += length;
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
