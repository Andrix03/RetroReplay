package io.github.andrix03.retroreplay.gui;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import net.minecraft.client.gui.screen.Screen;

/**
 * Frees the mouse during a replay so the replay bar can be clicked; the bar itself is drawn by
 * the HUD hook. The camera stands still while this is open, the replay keeps playing.
 */
public class ReplayOverlayScreen extends Screen {

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		// dragging the speed slider (polled: old versions have no mouseDragged)
		if (Mouse.isButtonDown(0)) {
			ReplayOverlay.drag(mouseX, mouseY, width);
		}
		ReplayOverlay.render(width, mouseX, mouseY, true);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (button != 0 || !ReplayOverlay.click(mouseX, mouseY, width)) {
			minecraft.openScreen(null);
		}
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == Keyboard.KEY_ESCAPE || key == minecraft.options.chatKey.keyCode) {
			minecraft.openScreen(null);
		}
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}
}
