package io.github.andrix03.retroreplay.recording;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.gui.RecordingIndicator;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

/** Keys while recording: M adds an event marker, like Replay Mod. */
public final class RecordingHotkeys {

	private static boolean markerKeyWasDown;

	private RecordingHotkeys() {
	}

	public static void tick() {
		boolean down = Platform.isKeyDown(Platform.KEY_M);
		if (down && !markerKeyWasDown && Mc.minecraft().screen == null && !ReplayHandler.isActive() && Recorder.addMarker()) {
			RecordingIndicator.markerAdded();
		}
		markerKeyWasDown = down;
	}
}
