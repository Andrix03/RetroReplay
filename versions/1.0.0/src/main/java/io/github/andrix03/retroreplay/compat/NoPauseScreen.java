package io.github.andrix03.retroreplay.compat;

import net.minecraft.client.gui.screen.Screen;

/** A screen that leaves the game running behind it (the method name differs between versions). */
public class NoPauseScreen extends Screen {

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
