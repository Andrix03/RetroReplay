package io.github.andrix03.retroreplay.replay;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shows an earlier moment over the world as it is, without rebuilding it: entities that did not
 * exist yet go, the ones that existed come back, everything stands where it stood; blocks and the
 * world time are put back. Only going back needs this: forward the real packets are fed.
 */
final class QuickOverlay {

	private final QuickIndex index;
	private final Consumer<byte[]> feed;
	/** Packets really handled: the world under the overlay. */
	private final int real;
	/** The moment shown (in handled packets). */
	private int shown;
	/** Tracks in the world -> the entry they were last put at. */
	private final Map<QuickIndex.Track, Integer> placed = new HashMap<>();

	QuickOverlay(QuickIndex index, int real, Consumer<byte[]> feed) {
		this.index = index;
		this.feed = feed;
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
		// entities that did not exist then go first: a later one may reuse the id
		for (Iterator<QuickIndex.Track> it = placed.keySet().iterator(); it.hasNext();) {
			QuickIndex.Track track = it.next();
			if (!track.aliveAt(target)) {
				feed.accept(QuickIndex.remove(track.id));
				it.remove();
			}
		}
		for (QuickIndex.Track track : index.tracks) {
			if (!track.aliveAt(target)) {
				continue;
			}
			Integer at = placed.get(track);
			if (at == null) {
				feed.accept(track.spawn); // stands at its spawn point, entry 0
				at = 0;
			}
			int entry = track.entryAt(target);
			if (entry != at && !track.still) {
				feed.accept(track.teleport(entry));
			}
			placed.put(track, entry);
		}
		// blocks changed between the moment shown and the new one
		int from = Math.min(shown, target);
		int to = Math.max(shown, target);
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
				feed.accept(QuickIndex.block(position, value));
			}
		}
		byte[] time = index.timeAt(target);
		if (time != null) {
			feed.accept(time);
		}
		shown = target;
	}
}
