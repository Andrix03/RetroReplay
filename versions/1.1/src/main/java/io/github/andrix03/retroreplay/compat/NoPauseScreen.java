package io.github.andrix03.retroreplay.compat;


/** A screen that leaves the game running behind it (the method name differs between versions). */
public class NoPauseScreen extends RetroScreen {

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
