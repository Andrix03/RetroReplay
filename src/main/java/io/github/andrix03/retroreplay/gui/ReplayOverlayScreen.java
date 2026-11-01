package io.github.andrix03.retroreplay.gui;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import io.github.andrix03.retroreplay.compat.NoPauseScreen;

/**
 * Frees the mouse during a replay so the replay bar and the pathing row can be used; both are
 * drawn by the HUD hook. The camera stands still while this is open, the replay keeps playing.
 */
public class ReplayOverlayScreen extends NoPauseScreen {

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		// dragging and the mouse wheel are polled: old versions have no mouseDragged
		if (Mouse.isButtonDown(0)) {
			ReplayOverlay.drag(mouseX, mouseY, width);
			PathingOverlay.drag(mouseX, mouseY, width);
		} else {
			PathingOverlay.release();
		}
		PathingOverlay.scroll(mouseX, mouseY, width, Mouse.getDWheel());
		ReplayOverlay.render(width, mouseX, mouseY, true);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (!ReplayOverlay.click(mouseX, mouseY, width, button)) {
			minecraft.openScreen(null);
		}
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == Keyboard.KEY_ESCAPE || key == minecraft.options.chatKey.keyCode) {
			minecraft.openScreen(null);
		} else if (key == Keyboard.KEY_I) {
			PathingOverlay.togglePositionKeyframe();
		} else if (key == Keyboard.KEY_O) {
			PathingOverlay.toggleTimeKeyframe();
		} else if (key == Keyboard.KEY_DELETE) {
			PathingOverlay.removeSelected();
		} else if (key == Keyboard.KEY_C) {
			PathingOverlay.clearKeyframes();
		}
	}
}
