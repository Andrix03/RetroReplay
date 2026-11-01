package io.github.andrix03.retroreplay.gui;

/** Replay Mod's GuiRecordingOverlay: red dot at (10, 10) and "RECORDING" or "PAUSED" next to it. */
public final class RecordingIndicator {

	private RecordingIndicator() {
	}

	private static long markerShownUntil;

	/** "Marker added" under the indicator for two seconds. */
	public static void markerAdded() {
		markerShownUntil = System.currentTimeMillis() + 2000;
	}

	public static void render(boolean paused) {
		int fontHeight = 9;
		ReplayGui.text(paused ? "PAUSED" : "RECORDING", 30, 18 - fontHeight / 2, ReplayGui.WHITE);
		if (System.currentTimeMillis() < markerShownUntil) {
			ReplayGui.text("Marker added", 30, 30, ReplayGui.LIGHT_GRAY);
		}
		ReplayGui.bind(ReplayGui.REPLAY_GUI);
		ReplayGui.texture(10, 10, 58, 20, 16, 16);
	}
}
