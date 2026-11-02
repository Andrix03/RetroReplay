package io.github.andrix03.retroreplay.gui;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Platform;

/** The system clipboard through AWT, the same in every version (their own helpers differ). */
final class Clipboard {

	private Clipboard() {
	}

	static void set(String text) {
		try {
			Platform.setClipboard(text);
		} catch (RuntimeException e) {
			RetroReplay.LOGGER.warn("Cannot copy to the clipboard", e);
		}
	}

	static String get() {
		try {
			return Platform.getClipboard();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
