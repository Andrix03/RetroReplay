package io.github.andrix03.retroreplay.gui;

import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.render.VideoRenderer;

/**
 * Open while a video renders: draws nothing (the frames must show only the world), keeps the
 * mouse from turning the camera, and Esc cancels. The progress screen is drawn by the renderer
 * itself after each frame (RenderProgress), and the count is in the window title too.
 */
public class RenderingScreen extends NoPauseScreen {

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == Platform.KEY_ESCAPE) {
			VideoRenderer.cancel();
		}
	}
}
