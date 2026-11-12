package io.github.andrix03.retroreplay.replay;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import io.github.andrix03.retroreplay.RetroReplay;

/**
 * Shows an earlier moment over the world as it is, without rebuilding it: chunks unloaded since
 * are rebuilt from their own packets, entities that did not exist yet go, the ones that existed
 * come back, everything stands where it stood with what it held; blocks, the world time and the
 * rain are put back. Only going back needs this: forward the real packets are fed.
 */
final class QuickOverlay {

	/** Reads a stored packet back from the replay's file. */
	interface PacketReader {
		byte[] read(long offset) throws IOException;
	}

	/** Puts an entity at once where a teleport would take it over three ticks. */
	interface Placer {
		void place(int id, double[] where);
	}

	private static final int[] KINDS = { 0, 1, 2, 3, 4, QuickIndex.KIND_DATA, QuickIndex.KIND_RIDE, QuickIndex.KIND_HEAD };

	private final QuickIndex index;
	private final Consumer<byte[]> feed;
	private final PacketReader file;
	private final Placer placer;
	/** Packets really handled: the world under the overlay. */
	private final int real;
	/** The moment shown (in handled packets). */
	private int shown;
	/** Tracks in the world -> the entry they were last put at. */
	private final Map<QuickIndex.Track, Integer> placed = new HashMap<>();

	QuickOverlay(QuickIndex index, int real, Consumer<byte[]> feed, PacketReader file, Placer placer) {
		this.index = index;
		this.feed = feed;
		this.file = file;
		this.placer = placer;
		this.real = real;
		this.shown = real;
		for (QuickIndex.Track track : index.tracks) {
			if (track.aliveAt(real)) {
				placed.put(track, track.entryAt(real));
			}
		}
	}

	int shown() {
		return shown;
	}

	int real() {
		return real;
	}

	/** Makes the world look as it did once the packets before {@code target} were handled. */
	void showAt(int target) {
		if (target == shown) {
			return;
		}
		int from = Math.min(shown, target);
		int to = Math.max(shown, target);
		chunks(target, from, to);
		entities(target, from, to);
		blocks(target, from, to);
		byte[] time = index.timeAt(target);
		if (time != null) {
			feed.accept(time);
		}
		byte[] rain = index.weatherAt(target);
		if (rain != null) {
			feed.accept(rain);
		}
		shown = target;
	}

	/**
	 * Chunks loaded then but not now, or whose contents came again in between, are rebuilt as they
	 * were: their load packet, then their data packets and block changes since, in order. Chunks
	 * not loaded then are unloaded.
	 */
	private void chunks(int target, int from, int to) {
		for (QuickIndex.ChunkLog log : index.chunks.values()) {
			if (!log.anyBetween(from, to)) {
				continue;
			}
			int load = log.loadedAt(target);
			if (load < 0) {
				if (log.loadedAt(shown) >= 0) {
					feed.accept(index.chunkUnloadPacket(log.x, log.z));
				}
				continue;
			}
			if (log.loadedAt(shown) >= 0 && !log.reloadedBetween(from, to)) {
				continue; // the same chunk: its block changes are put back with the others
			}
			if (System.getenv("RETROREPLAY_QUICK_DEBUG") != null) {
				io.github.andrix03.retroreplay.RetroReplay.LOGGER.info("Quick rebuild chunk " + log.x + "," + log.z + " at " + target + " from load " + load);
			}
			try {
				rebuild(log, load, target);
			} catch (IOException e) {
				RetroReplay.LOGGER.warn("Quick Mode cannot read chunk " + log.x + "," + log.z + " back", e);
			}
		}
	}

	private void rebuild(QuickIndex.ChunkLog log, int load, int target) throws IOException {
		for (int i = 0; i < log.count; i++) {
			if (log.index[i] == load && log.kind[i] == QuickIndex.ChunkLog.LOAD) {
				feed.accept(index.loadPacket(log, i, file)); // from 1.3 with the chunk's data in it
				break;
			}
		}
		int change = 0;
		while (change < log.changes && index.blockChangeIndexOf(log.change[change]) < load) {
			change++;
		}
		for (int i = 0; i < log.count && log.index[i] < target; i++) {
			if (log.index[i] < load || log.kind[i] != QuickIndex.ChunkLog.DATA) {
				continue;
			}
			// block changes before this data packet first, as they were handled
			for (; change < log.changes && index.blockChangeIndexOf(log.change[change]) < log.index[i]; change++) {
				feed.accept(index.blockChangePacket(log.change[change]));
			}
			feed.accept(file.read(log.offset[i]));
		}
		for (; change < log.changes && index.blockChangeIndexOf(log.change[change]) < target; change++) {
			feed.accept(index.blockChangePacket(log.change[change]));
		}
	}

	private void entities(int target, int from, int to) {
		// entities that did not exist then go first: a later one may reuse the id
		for (Iterator<QuickIndex.Track> it = placed.keySet().iterator(); it.hasNext();) {
			QuickIndex.Track track = it.next();
			if (!track.aliveAt(target)) {
				feed.accept(index.removePacket(track.id));
				it.remove();
			}
		}
		for (QuickIndex.Track track : index.tracks) {
			if (!track.aliveAt(target)) {
				continue;
			}
			Integer at = placed.get(track);
			boolean spawned = at == null;
			if (spawned) {
				feed.accept(track.spawn); // stands at its spawn point, entry 0
				at = 0;
			}
			int entry = track.entryAt(target);
			if (entry != at && !track.still) {
				feed.accept(index.teleportPacket(track, entry)); // what the next moves count from
				// and there at once: the game moves it over three ticks, and not at all while its
				// chunk is unloaded, which Quick Mode may just have done
				placer.place(track.id, track.place(entry));
			}
			placed.put(track, entry);
			states(track, target, spawned ? track.spawnIndex + 1 : shown);
		}
	}

	/** Equipment, entity data and riding: the last packet of each kind put back when it differs. */
	private void states(QuickIndex.Track track, int target, int now) {
		if (track.states == 0) {
			return;
		}
		for (int kind : KINDS) {
			int then = track.stateAt(kind, target);
			if (then == track.stateAt(kind, now)) {
				continue;
			}
			byte[] packet = then >= 0 ? track.stateBytes[then] : index.noStatePacket(track, kind);
			if (packet != null) {
				feed.accept(packet);
			}
		}
	}

	/** Blocks changed between the moment shown and the new one. */
	private void blocks(int target, int from, int to) {
		Set<Long> positions = new HashSet<>();
		for (int c = index.firstBlockChange(from); c < index.blockChanges() && index.blockChangeIndex(c) < to; c++) {
			positions.add(index.blockChangePosition(c));
		}
		for (long position : positions) {
			int value = index.valueAt(position, target);
			if (value < 0) {
				value = index.baseValue(position);
			}
			if (value >= 0) {
				feed.accept(index.blockPacket(position, value));
			}
			if (System.getenv("RETROREPLAY_QUICK_DEBUG") != null) {
				io.github.andrix03.retroreplay.RetroReplay.LOGGER.info("Quick block " + QuickIndex.x(position) + "," + QuickIndex.y(position) + "," + QuickIndex.z(position)
					+ " at " + target + " (from " + from + " to " + to + "): " + value);
			}
		}
	}
}
