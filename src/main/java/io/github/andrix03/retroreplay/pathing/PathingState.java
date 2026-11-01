package io.github.andrix03.retroreplay.pathing;

import java.io.File;
import java.io.IOException;

import io.github.andrix03.retroreplay.RetroReplay;

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
	public final Markers markers = new Markers();
	public final PathPresets presets = new PathPresets();
	/** Replay Mod's render queue for this replay, kept while the game runs. */
	public final java.util.List<io.github.andrix03.retroreplay.render.RenderJob> renderQueue = new java.util.ArrayList<>();
	/** The marker clicked last on the replay bar, or -1. */
	public int selectedMarker = -1;
	public long cursor;
	/** Visible fraction of the timeline (Replay Mod starts at 0.1). */
	public double zoom = 0.1;
	/** First visible millisecond. */
	public long scroll;
	public Kind selectedKind;
	public long selectedTime;

	/** The path as last loaded or saved, to save only real changes. */
	private String savedJson;
	/** Snapshots of the path for Ctrl+Z / Ctrl+Y, like Replay Mod's timeline undo. */
	private final java.util.ArrayDeque<String> undo = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<String> redo = new java.util.ArrayDeque<>();

	/** Call before changing the path. */
	public void beforeChange() {
		undo.push(PathStore.toJson(path));
		if (undo.size() > 100) {
			undo.removeLast();
		}
		redo.clear();
	}

	public boolean undo() {
		if (undo.isEmpty()) {
			return false;
		}
		redo.push(PathStore.toJson(path));
		restore(undo.pop());
		return true;
	}

	public boolean redo() {
		if (redo.isEmpty()) {
			return false;
		}
		undo.push(PathStore.toJson(path));
		restore(redo.pop());
		return true;
	}

	private void restore(String json) {
		for (Long time : path.positions().keySet().toArray(new Long[0])) {
			path.removePosition(time);
		}
		for (Long time : path.times().keySet().toArray(new Long[0])) {
			path.removeTime(time);
		}
		PathStore.fromJson(json, path);
		clearSelection();
	}

	private PathingState(File replay) {
		this.replay = replay;
		try {
			CameraPath saved = PathStore.load(replay);
			PathStore.fromJson(PathStore.toJson(saved), path);
			String markerJson = PathStore.readEntry(replay, Markers.ENTRY);
			if (markerJson != null) {
				markers.readJson(markerJson);
			}
			String presetJson = PathStore.readEntry(replay, PathPresets.ENTRY);
			if (presetJson != null) {
				presets.readJson(presetJson);
			}
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot read the camera path of " + replay, e);
		}
		savedJson = savedForm();
	}

	/** The state of this replay, a fresh one (with the path saved in it) when another replay was open before. */
	public static PathingState of(File replay) {
		if (current == null || !current.replay.equals(replay)) {
			current = new PathingState(replay);
		}
		return current;
	}

	/** The replay closes: its camera path goes into the replay file, like Replay Mod's timelines. */
	public static void saveCurrent() {
		PathingState state = current;
		if (state == null) {
			return;
		}
		String json = state.savedForm();
		if (json.equals(state.savedJson)) {
			return;
		}
		try {
			java.util.Map<String, String> entries = new java.util.HashMap<>();
			entries.put(PathStore.ENTRY, state.path.isEmpty() ? null : PathStore.toJson(state.path));
			entries.put(Markers.ENTRY, state.markers.isEmpty() ? null : state.markers.toJson());
			entries.put(PathPresets.ENTRY, state.presets.isEmpty() ? null : state.presets.toJson());
			PathStore.saveEntries(state.replay, entries);
			state.savedJson = json;
			RetroReplay.LOGGER.info("Camera path and markers saved in " + state.replay.getName());
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot save the camera path in " + state.replay, e);
		}
	}

	/** Everything saved in the replay file, to notice changes. */
	private String savedForm() {
		return PathStore.toJson(path) + markers.toJson() + presets.toJson();
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
