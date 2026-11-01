package io.github.andrix03.retroreplay.pathing;

import java.io.File;

/**
 * The camera path being edited in the open replay and the pathing timeline's view: cursor,
 * zoom and scroll (Replay Mod: 10 minute timeline, zoom 0.1) and the selected keyframe.
 * Kept per replay file, so it survives the replay restarting for a seek backwards.
 */
public final class PathingState {

	public enum Kind {
		POSITION, TIME
	}

	public static final long LENGTH_MS = 10 * 60 * 1000;

	private static PathingState current;

	public final File replay;
	public final CameraPath path = new CameraPath();
	public long cursor;
	/** Visible fraction of the timeline (Replay Mod starts at 0.1). */
	public double zoom = 0.1;
	/** First visible millisecond. */
	public long scroll;
	public Kind selectedKind;
	public long selectedTime;

	private PathingState(File replay) {
		this.replay = replay;
	}

	/** The state of this replay, a fresh one when another replay was open before. */
	public static PathingState of(File replay) {
		if (current == null || !current.replay.equals(replay)) {
			current = new PathingState(replay);
		}
		return current;
	}

	public static PathingState current() {
		return current;
	}

	public long visibleLength() {
		return Math.max(1, (long) (LENGTH_MS * zoom));
	}

	public void setZoom(double newZoom, long keepTime) {
		double fraction = (keepTime - scroll) / (double) visibleLength();
		zoom = Math.max(0.001, Math.min(1, newZoom));
		scroll = (long) (keepTime - fraction * visibleLength());
		clampScroll();
	}

	public void clampScroll() {
		scroll = Math.max(0, Math.min(LENGTH_MS - visibleLength(), scroll));
	}

	public boolean isSelected(Kind kind, long time) {
		return selectedKind == kind && selectedTime == time;
	}

	public void select(Kind kind, long time) {
		selectedKind = kind;
		selectedTime = time;
	}

	public void clearSelection() {
		selectedKind = null;
	}

	/** The selected keyframe if it still exists, else null. */
	public Kind selection() {
		if (selectedKind == Kind.POSITION && path.positions().containsKey(selectedTime)
			|| selectedKind == Kind.TIME && path.times().containsKey(selectedTime)) {
			return selectedKind;
		}
		return null;
	}
}
