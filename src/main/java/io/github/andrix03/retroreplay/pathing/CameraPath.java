package io.github.andrix03.retroreplay.pathing;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Replay Mod's "simple pathing" timeline: on a timeline of its own (milliseconds from 0),
 * position keyframes place the camera (Catmull-Rom, alpha 0.5) and time keyframes say which
 * moment of the replay is shown (linear in between, like Replay Mod).
 */
public final class CameraPath {

	public static final double ALPHA = 0.5;

	/** A camera position: feet x, y, z and yaw, pitch, roll in degrees. */
	public static final class Position {
		public final double x, y, z;
		public final float yaw, pitch, roll;

		public Position(double x, double y, double z, float yaw, float pitch, float roll) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.roll = roll;
		}
	}

	private final TreeMap<Long, Position> positions = new TreeMap<>();
	private final TreeMap<Long, Integer> times = new TreeMap<>();
	private CatmullRom[] splines;

	public NavigableMap<Long, Position> positions() {
		return java.util.Collections.unmodifiableNavigableMap(positions);
	}

	public NavigableMap<Long, Integer> times() {
		return java.util.Collections.unmodifiableNavigableMap(times);
	}

	public void putPosition(long time, Position position) {
		positions.put(time, position);
		splines = null;
	}

	public void removePosition(long time) {
		positions.remove(time);
		splines = null;
	}

	public void putTime(long time, int replayTime) {
		times.put(time, replayTime);
	}

	public void removeTime(long time) {
		times.remove(time);
	}

	public boolean isEmpty() {
		return positions.isEmpty() && times.isEmpty();
	}

	/** First and last keyframe of either kind; 0 when there is none. */
	public long start() {
		long start = Long.MAX_VALUE;
		if (!positions.isEmpty()) {
			start = positions.firstKey();
		}
		if (!times.isEmpty()) {
			start = Math.min(start, times.firstKey());
		}
		return start == Long.MAX_VALUE ? 0 : start;
	}

	public long end() {
		long end = 0;
		if (!positions.isEmpty()) {
			end = positions.lastKey();
		}
		if (!times.isEmpty()) {
			end = Math.max(end, times.lastKey());
		}
		return end;
	}

	/** Why the path cannot play (Replay Mod needs two keyframes of each kind), or null when it can. */
	public String playbackProblem(boolean ignoreTime) {
		if (positions.size() < 2) {
			return "At least two position keyframes are required";
		}
		if (ignoreTime) {
			return null;
		}
		// like Replay Mod: the replay can only play forwards along the path
		int last = 0;
		for (int replayTime : times.values()) {
			if (replayTime < last) {
				return "Time keyframes must not go backwards in time (red on the timeline)";
			}
			last = replayTime;
		}
		if (times.size() < 2) {
			return "At least two time keyframes are required";
		}
		return null;
	}

	/** The camera at a timeline time, or null without position keyframes. */
	public Position positionAt(long time) {
		if (positions.isEmpty()) {
			return null;
		}
		if (positions.size() == 1) {
			return positions.firstEntry().getValue();
		}
		if (splines == null) {
			long[] keys = new long[positions.size()];
			double[][] parts = new double[6][positions.size()];
			int i = 0;
			for (Map.Entry<Long, Position> entry : positions.entrySet()) {
				Position p = entry.getValue();
				keys[i] = entry.getKey();
				parts[0][i] = p.x;
				parts[1][i] = p.y;
				parts[2][i] = p.z;
				parts[3][i] = p.yaw;
				parts[4][i] = p.pitch;
				parts[5][i] = p.roll;
				i++;
			}
			splines = new CatmullRom[6];
			for (int part = 0; part < 6; part++) {
				splines[part] = new CatmullRom(keys, parts[part], ALPHA, part < 3 ? Double.NaN : 360);
			}
		}
		return new Position(splines[0].valueAt(time), splines[1].valueAt(time), splines[2].valueAt(time),
			(float) splines[3].valueAt(time), (float) splines[4].valueAt(time), (float) splines[5].valueAt(time));
	}

	/** The replay time at a timeline time (linear between time keyframes, held at the ends), or -1 without any. */
	public int replayTimeAt(long time) {
		if (times.isEmpty()) {
			return -1;
		}
		Map.Entry<Long, Integer> before = times.floorEntry(time);
		Map.Entry<Long, Integer> after = times.ceilingEntry(time);
		if (before == null) {
			return after.getValue();
		}
		if (after == null || after.getKey().equals(before.getKey())) {
			return before.getValue();
		}
		double fraction = (time - before.getKey()) / (double) (after.getKey() - before.getKey());
		return (int) Math.round(before.getValue() + (after.getValue() - before.getValue()) * fraction);
	}
}
