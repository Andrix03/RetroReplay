package io.github.andrix03.retroreplay.gui;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.compat.Platform;

/**
 * Frees the mouse during a replay so the replay bar and the pathing row can be used; both are
 * drawn by the HUD hook. The camera stands still while this is open, the replay keeps playing.
 */
public class ReplayOverlayScreen extends NoPauseScreen {

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		// dragging and the mouse wheel are polled: old versions have no mouseDragged
		if (Platform.isMouseDown(0)) {
			ReplayOverlay.drag(mouseX, mouseY, width);
			PathingOverlay.drag(mouseX, mouseY, width);
		} else {
			ReplayOverlay.release();
			PathingOverlay.release();
		}
		PathingOverlay.scroll(mouseX, mouseY, width, Platform.wheel());
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
		if (key == Platform.KEY_ESCAPE || key == Mc.chatKeyCode()) {
			minecraft.openScreen(null);
		} else if (key == Platform.KEY_I) {
			PathingOverlay.togglePositionKeyframe();
		} else if (key == Platform.KEY_O) {
			PathingOverlay.toggleTimeKeyframe();
		} else if (key == Platform.KEY_DELETE) {
			PathingOverlay.removeSelected();
		} else if (key == Platform.KEY_C) {
			PathingOverlay.clearKeyframes();
		} else if ((key == Platform.KEY_Z || key == Platform.KEY_Y) && (Platform.isKeyDown(Platform.KEY_LCONTROL) || Platform.isKeyDown(Platform.KEY_RCONTROL))) {
			PathingOverlay.undo(key == Platform.KEY_Y);
		} else if (key == Platform.KEY_X) {
			PathingOverlay.openRepository();
		} else if (key == Platform.KEY_V) {
			PathingOverlay.syncTimeline();
		} else if (key == Platform.KEY_M) {
			PathingOverlay.addMarker();
		} else if (key == Platform.KEY_B) {
			minecraft.openScreen(new PlayerOverviewScreen());
		} else if (key == Platform.KEY_H) {
			io.github.andrix03.retroreplay.pathing.PathPreview.toggle();
		}
	}
}
