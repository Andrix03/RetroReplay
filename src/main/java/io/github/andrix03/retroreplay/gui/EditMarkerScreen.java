package io.github.andrix03.retroreplay.gui;

import org.lwjgl.input.Keyboard;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.pathing.PathingState;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Replay Mod's GuiEditMarkerPopup (double click on a marker): its name, Save / Delete / Cancel. */
public class EditMarkerScreen extends NoPauseScreen {

	private final PathingState state;
	private final int time;
	private TextFieldWidget nameField;

	public EditMarkerScreen(PathingState state, int time) {
		this.state = state;
		this.time = time;
	}

	@Override
	public void init() {
		Keyboard.enableRepeatEvents(true);
		buttons.clear();
		nameField = Mc.textField(this, width / 2 - 100, height / 2 - 10, 200, 20);
		String name = state.markers.all().get(time);
		nameField.setText(name == null ? "" : name);
		nameField.setFocused(true);
		buttons.add(new ButtonWidget(0, width / 2 - 100, height / 2 + 20, 64, 20, "Save"));
		buttons.add(new ButtonWidget(1, width / 2 - 32, height / 2 + 20, 64, 20, "Delete"));
		buttons.add(new ButtonWidget(2, width / 2 + 36, height / 2 + 20, 64, 20, "Cancel"));
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == 0) {
			state.markers.put(time, nameField.getText().trim());
		} else if (button.id == 1) {
			state.markers.remove(time);
			state.selectedMarker = -1;
		}
		minecraft.openScreen(new ReplayOverlayScreen());
	}

	@Override
	protected void keyPressed(char chr, int key) {
		nameField.keyPressed(chr, key);
		if (key == Keyboard.KEY_ESCAPE) {
			minecraft.openScreen(new ReplayOverlayScreen());
		} else if (key == Keyboard.KEY_RETURN) {
			buttonClicked((ButtonWidget) buttons.get(0));
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		super.mouseClicked(mouseX, mouseY, button);
		nameField.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void tick() {
		nameField.tick();
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		ReplayGui.fill(0, 0, width, height, 0xA0000000);
		ReplayGui.centered("Edit Marker", width / 2, height / 2 - 44, ReplayGui.WHITE);
		ReplayGui.text("Name (at " + ReplayGui.formatTime(time) + ")", width / 2 - 100, height / 2 - 22, ReplayGui.LIGHT_GRAY);
		nameField.render();
		super.render(mouseX, mouseY, tickDelta);
	}
}
