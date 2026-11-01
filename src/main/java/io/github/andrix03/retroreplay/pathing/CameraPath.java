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

	/**
	 * A camera position: feet x, y, z and yaw, pitch, roll in degrees. A spectator keyframe also
	 * names the entity whose eyes the camera uses (Replay Mod's SpectatorProperty); its position is
	 * where that entity was, for the preview and as a fallback.
	 */
	public static final class Position {
		public static final int NO_ENTITY = -1;

		public final double x, y, z;
		public final float yaw, pitch, roll;
		public final int spectated;

		public Position(double x, double y, double z, float yaw, float pitch, float roll) {
			this(x, y, z, yaw, pitch, roll, NO_ENTITY);
		}

		public Position(double x, double y, double z, float yaw, float pitch, float roll, int spectated) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.roll = roll;
			this.spectated = spectated;
		}

		public boolean isSpectator() {
			return spectated != NO_ENTITY;
		}
	}

	private final TreeMap<Long, Position> positions = new TreeMap<>();
	private final TreeMap<Long, Integer> times = new TreeMap<>();
	private Spline[] splines;
	private Interpolation interpolation = Interpolation.CATMULL_ROM;

	/** Replay Mod's interpolator types for the position path (time keyframes are always linear). */
	public enum Interpolation {
		CATMULL_ROM("Catmull-Rom"), CUBIC("Cubic"), LINEAR("Linear");

		public final String title;

		Interpolation(String title) {
			this.title = title;
		}
	}

	public Interpolation interpolation() {
		return interpolation;
	}

	public void setInterpolation(Interpolation interpolation) {
		this.interpolation = interpolation;
		splines = null;
	}

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
			splines = new Spline[6];
			for (int part = 0; part < 6; part++) {
				double bound = part < 3 ? Double.NaN : 360;
				splines[part] = interpolation == Interpolation.CUBIC ? Spline.cubic(keys, parts[part], bound)
					: interpolation == Interpolation.LINEAR ? Spline.linear(keys, parts[part], bound)
					: Spline.catmullRom(keys, parts[part], ALPHA, bound);
			}
		}
		return new Position(splines[0].valueAt(time), splines[1].valueAt(time), splines[2].valueAt(time),
			(float) splines[3].valueAt(time), (float) splines[4].valueAt(time), (float) splines[5].valueAt(time));
	}

	/**
	 * The entity the camera spectates at that time, or NO_ENTITY: between two spectator keyframes
	 * of the same entity, on one, and before the first / after the last when that one is.
	 */
	public int spectatedAt(long time) {
		Map.Entry<Long, Position> before = positions.floorEntry(time);
		Map.Entry<Long, Position> after = positions.ceilingEntry(time);
		if (before == null) {
			return after == null ? Position.NO_ENTITY : after.getValue().spectated;
		}
		if (after == null || before.getKey() == time) {
			return before.getValue().spectated;
		}
		if (after.getKey() == time) {
			return after.getValue().spectated;
		}
		int id = before.getValue().spectated;
		return id != Position.NO_ENTITY && id == after.getValue().spectated ? id : Position.NO_ENTITY;
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
