package io.github.andrix03.retroreplay.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathStore;
import io.github.andrix03.retroreplay.pathing.PathingState;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * Replay Mod's keyframe repository (X): camera paths saved under a name in this replay. A click
 * on a name selects it; Save As (the name typed below), Overwrite, Load, Copy, Rename, Remove,
 * Paste: 75 px buttons like Replay Mod's. Copy / Paste move paths between replays as text.
 */
public class KeyframeRepositoryScreen extends NoPauseScreen {

	private static final int SAVE_AS = 1000;
	private static final int OVERWRITE = 1001;
	private static final int LOAD = 1002;
	private static final int RENAME = 1003;
	private static final int REMOVE = 1004;
	private static final int CLOSE = 1005;
	private static final int COPY = 1006;
	private static final int PASTE = 1007;

	private final PathingState state;
	private final List<String> names = new ArrayList<>();
	private String selected;
	private TextFieldWidget nameField;
	private String message;

	public KeyframeRepositoryScreen(PathingState state) {
		this.state = state;
	}

	@Override
	public void init() {
		Keyboard.enableRepeatEvents(true);
		buttons.clear();
		names.clear();
		names.addAll(state.presets.all().keySet());
		if (selected != null && !names.contains(selected)) {
			selected = null;
		}
		int rows = Math.max(1, (height - 120) / 22);
		for (int i = 0; i < names.size() && i < rows; i++) {
			String name = names.get(i);
			buttons.add(new ButtonWidget(i, width / 2 - 100, 34 + i * 22, 200, 20, name.equals(selected) ? "> " + name + " <" : name));
		}
		String text = nameField == null ? "" : nameField.getText();
		nameField = Mc.textField(this, width / 2 - 100, height - 78, 200, 20);
		nameField.setText(text);
		nameField.setFocused(true);
		int x = width / 2 - (75 * 4 + 3 * 5) / 2;
		buttons.add(new ButtonWidget(SAVE_AS, x, height - 52, 75, 20, "Save As..."));
		ButtonWidget overwrite = new ButtonWidget(OVERWRITE, x + 80, height - 52, 75, 20, "Overwrite");
		ButtonWidget load = new ButtonWidget(LOAD, x + 160, height - 52, 75, 20, "Load");
		ButtonWidget copy = new ButtonWidget(COPY, x + 240, height - 52, 75, 20, "Copy");
		ButtonWidget rename = new ButtonWidget(RENAME, x, height - 28, 75, 20, "Rename");
		ButtonWidget remove = new ButtonWidget(REMOVE, x + 80, height - 28, 75, 20, "Remove");
		for (ButtonWidget button : new ButtonWidget[] { overwrite, load, copy, rename, remove }) {
			button.active = selected != null;
			buttons.add(button);
		}
		buttons.add(new ButtonWidget(PASTE, x + 160, height - 28, 75, 20, "Paste"));
		buttons.add(new ButtonWidget(CLOSE, x + 240, height - 28, 75, 20, "Close"));
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		String typed = nameField.getText().trim();
		switch (button.id) {
			case SAVE_AS:
				if (typed.isEmpty()) {
					message = "Type a name below first";
					return;
				}
				state.presets.put(typed, state.path);
				selected = typed;
				message = "Saved as \"" + typed + "\"";
				break;
			case OVERWRITE:
				state.presets.put(selected, state.path);
				message = "\"" + selected + "\" overwritten";
				break;
			case LOAD:
				state.beforeChange(); // Ctrl+Z brings the old path back
				state.presets.load(selected, state.path);
				state.clearSelection();
				minecraft.openScreen(new ReplayOverlayScreen());
				return;
			case RENAME:
				if (typed.isEmpty()) {
					message = "Type the new name below first";
					return;
				}
				state.presets.rename(selected, typed);
				selected = typed;
				break;
			case REMOVE:
				state.presets.remove(selected);
				selected = null;
				break;
			case COPY:
				// like Replay Mod: the path as text, to paste into another replay
				Clipboard.set(state.presets.all().get(selected));
				message = "\"" + selected + "\" copied";
				break;
			case PASTE:
				String pasted = Clipboard.get();
				if (pasted == null || !pasted.contains("\"positions\"")) {
					message = "The clipboard holds no camera path";
					return;
				}
				CameraPath path = new CameraPath();
				PathStore.fromJson(pasted, path);
				String name = typed.isEmpty() ? "Pasted" : typed;
				for (int n = 2; state.presets.all().containsKey(name); n++) {
					name = (typed.isEmpty() ? "Pasted" : typed) + " " + n;
				}
				state.presets.put(name, path);
				selected = name;
				message = "Pasted as \"" + name + "\"";
				break;
			case CLOSE:
				minecraft.openScreen(new ReplayOverlayScreen());
				return;
			default:
				selected = names.get(button.id);
				nameField.setText(selected);
				break;
		}
		init();
	}

	@Override
	protected void keyPressed(char chr, int key) {
		nameField.keyPressed(chr, key);
		if (key == Keyboard.KEY_ESCAPE) {
			minecraft.openScreen(new ReplayOverlayScreen());
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
		ReplayGui.centered("Keyframe Repository", width / 2, 12, ReplayGui.WHITE);
		if (names.isEmpty()) {
			ReplayGui.centered("No saved camera paths yet: type a name and Save As...", width / 2, 50, ReplayGui.LIGHT_GRAY);
		}
		ReplayGui.text("Name", width / 2 - 100, height - 88, ReplayGui.LIGHT_GRAY);
		nameField.render();
		if (message != null) {
			ReplayGui.centered(message, width / 2, 22, ReplayGui.LIGHT_GRAY);
		}
		super.render(mouseX, mouseY, tickDelta);
	}
}
