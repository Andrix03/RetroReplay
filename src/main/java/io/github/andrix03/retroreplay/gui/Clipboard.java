package io.github.andrix03.retroreplay.gui;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;

import io.github.andrix03.retroreplay.RetroReplay;

/** The system clipboard through AWT, the same in every version (their own helpers differ). */
final class Clipboard {

	private Clipboard() {
	}

	static void set(String text) {
		try {
			StringSelection selection = new StringSelection(text);
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
		} catch (Exception e) {
			RetroReplay.LOGGER.warn("Cannot copy to the clipboard", e);
		}
	}

	static String get() {
		try {
			return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
		} catch (Exception e) {
			return null;
		}
	}
}
