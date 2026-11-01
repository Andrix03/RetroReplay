package io.github.andrix03.retroreplay.gui;

import org.lwjgl.input.Keyboard;

import io.github.andrix03.retroreplay.compat.NoPauseScreen;

import net.minecraft.client.gui.widget.ButtonWidget;

/** Replay Mod asks before the C key removes every keyframe. */
public class ClearKeyframesScreen extends NoPauseScreen {

	@Override
	public void init() {
		buttons.clear();
		buttons.add(new ButtonWidget(0, width / 2 - 105, height / 2 + 10, 100, 20, "Yes"));
		buttons.add(new ButtonWidget(1, width / 2 + 5, height / 2 + 10, 100, 20, "No"));
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == 0) {
			PathingOverlay.doClearKeyframes();
		}
		minecraft.openScreen(null);
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == Keyboard.KEY_ESCAPE) {
			minecraft.openScreen(null);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		ReplayGui.fill(0, 0, width, height, 0x80000000);
		ReplayGui.centered("Do you really want to remove all keyframes?", width / 2, height / 2 - 20, ReplayGui.WHITE);
		super.render(mouseX, mouseY, tickDelta);
	}
}
