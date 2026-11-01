package io.github.andrix03.retroreplay.gui;

import java.io.File;

import org.lwjgl.input.Keyboard;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Replay Mod's rename popup: a 200 px name field with Done / Cancel. */
public class RenameReplayScreen extends Screen {

	private final Screen parent;
	private final File file;
	private TextFieldWidget nameField;

	public RenameReplayScreen(Screen parent, File file) {
		this.parent = parent;
		this.file = file;
	}

	@Override
	public void init() {
		Keyboard.enableRepeatEvents(true);
		buttons.clear();
		nameField = Mc.textField(this, width / 2 - 100, height / 2 - 20, 200, 20);
		nameField.setFocused(true);
		nameField.setText(file.getName().substring(0, file.getName().length() - ".mcpr".length()));
		buttons.add(new ButtonWidget(0, width / 2 - 100, height / 2 + 10, 98, 20, "Done"));
		buttons.add(new ButtonWidget(1, width / 2 + 2, height / 2 + 10, 98, 20, "Cancel"));
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == 0) {
			String name = nameField.getText().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
			File target = new File(file.getParentFile(), name + ".mcpr");
			if (!name.isEmpty() && !target.exists() && !file.renameTo(target)) {
				RetroReplay.LOGGER.warn("Cannot rename " + file + " to " + target);
			}
		}
		minecraft.openScreen(parent);
	}

	@Override
	protected void keyPressed(char chr, int key) {
		nameField.keyPressed(chr, key);
		if (key == Keyboard.KEY_RETURN) {
			buttonClicked((ButtonWidget) buttons.get(0));
		} else if (key == Keyboard.KEY_ESCAPE) {
			minecraft.openScreen(parent);
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
		renderBackground();
		ReplayGui.centered("Rename Replay", width / 2, height / 2 - 50, ReplayGui.WHITE);
		ReplayGui.text("Name", width / 2 - 100, height / 2 - 32, ReplayGui.LIGHT_GRAY);
		nameField.render();
		super.render(mouseX, mouseY, tickDelta);
	}
}
