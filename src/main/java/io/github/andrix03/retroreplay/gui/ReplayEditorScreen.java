package io.github.andrix03.retroreplay.gui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Button;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.compat.RetroScreen;
import io.github.andrix03.retroreplay.compat.TextField;
import io.github.andrix03.retroreplay.replay.ReplayEditor;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Replay Mod's Replay Editor for the replay chosen in the viewer: Trim keeps a part of it (start
 * and end as m:ss), Connect puts another replay after it. Both write a new replay next to it.
 */
public class ReplayEditorScreen extends RetroScreen {

	private static final int TRIM = 0;
	private static final int OTHER = 1;
	private static final int CONNECT = 2;
	private static final int BACK = 3;

	private final Screen parent;
	private final File file;
	private final List<File> others = new ArrayList<>();
	private int other;
	private int duration;
	private TextField startField;
	private TextField endField;
	private ButtonWidget otherButton;
	private String status = "";

	public ReplayEditorScreen(Screen parent, File file) {
		this.parent = parent;
		this.file = file;
	}

	@Override
	public void init() {
		Platform.enableRepeatEvents(true);
		buttons.clear();
		try {
			duration = ReplayEditor.duration(file);
		} catch (IOException e) {
			status = "Cannot read this replay: " + e.getMessage();
		}
		others.clear();
		File[] files = file.getParentFile().listFiles((dir, name) -> name.endsWith(".mcpr"));
		if (files != null) {
			for (File f : files) {
				if (!f.equals(file)) {
					others.add(f);
				}
			}
		}
		others.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
		int x = width / 2 - 100;
		int y = height / 2 - 60;
		startField = TextField.create(this, x, y, 98, 20);
		startField.setText(format(0));
		startField.setFocused(true);
		endField = TextField.create(this, x + 102, y, 98, 20);
		endField.setText(format(duration));
		endField.setFocused(false);
		buttons.add(new Button(TRIM, x, y + 24, 200, 20, "Trim"));
		buttons.add(otherButton = new Button(OTHER, x, y + 64, 200, 20, otherLabel()));
		ButtonWidget connect = new Button(CONNECT, x, y + 88, 200, 20, "Connect");
		connect.active = !others.isEmpty();
		otherButton.active = !others.isEmpty();
		buttons.add(connect);
		buttons.add(new Button(BACK, x, y + 120, 200, 20, "Back"));
	}

	@Override
	public void removed() {
		Platform.enableRepeatEvents(false);
	}

	private String otherLabel() {
		if (others.isEmpty()) {
			return "No other replay to connect";
		}
		String name = others.get(other).getName();
		name = name.substring(0, name.length() - ".mcpr".length());
		return "After it: " + (name.length() > 22 ? name.substring(0, 21) + "..." : name);
	}

	private static String format(int ms) {
		int seconds = ms / 1000;
		return seconds / 60 + ":" + String.format("%02d", seconds % 60);
	}

	/** m:ss (or plain seconds) as ms, or -1. */
	static int parse(String text) {
		try {
			String[] parts = text.trim().split(":");
			if (parts.length == 1) {
				return (int) (Double.parseDouble(parts[0]) * 1000);
			}
			return (Integer.parseInt(parts[0].trim()) * 60 + (int) Double.parseDouble(parts[1].trim())) * 1000 + 0;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private File free(String name) {
		File target = new File(file.getParentFile(), name + ".mcpr");
		for (int i = 2; target.exists(); i++) {
			target = new File(file.getParentFile(), name + " " + i + ".mcpr");
		}
		return target;
	}

	private String baseName(File f) {
		return f.getName().substring(0, f.getName().length() - ".mcpr".length());
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (!button.active) {
			return;
		}
		switch (button.id) {
			case TRIM: {
				int start = parse(startField.getText());
				int end = Math.min(duration, parse(endField.getText()));
				if (start < 0 || end <= start) {
					status = "The end must come after the start (m:ss)";
					return;
				}
				File out = free(baseName(file) + " trimmed");
				run(() -> ReplayEditor.trim(file, out, start, end), out);
				break;
			}
			case OTHER:
				other = (other + 1) % others.size();
				otherButton.message = otherLabel();
				break;
			case CONNECT: {
				File second = others.get(other);
				File out = free(baseName(file) + " + " + baseName(second));
				run(() -> ReplayEditor.connect(file, second, out), out);
				break;
			}
			default:
				minecraft.openScreen(parent);
				break;
		}
	}

	private interface Edit {
		void run() throws IOException;
	}

	private void run(Edit edit, File out) {
		try {
			edit.run();
			status = "Saved as " + out.getName();
			RetroReplay.LOGGER.info("Replay Editor: saved " + out);
		} catch (IOException e) {
			if (!out.delete()) {
				out.deleteOnExit();
			}
			status = "Failed: " + e.getMessage();
			RetroReplay.LOGGER.warn("Replay Editor failed", e);
		}
	}

	@Override
	protected void keyPressed(char chr, int key) {
		startField.keyPressed(chr, key);
		endField.keyPressed(chr, key);
		if (key == Platform.KEY_ESCAPE) {
			minecraft.openScreen(parent);
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		super.mouseClicked(mouseX, mouseY, button);
		startField.mouseClicked(mouseX, mouseY, button);
		endField.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void tick() {
		startField.tick();
		endField.tick();
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		renderBackground();
		int x = width / 2 - 100;
		int y = height / 2 - 60;
		ReplayGui.centered("Replay Editor: " + baseName(file) + " (" + format(duration) + ")", width / 2, y - 30, ReplayGui.WHITE);
		ReplayGui.text("Start", x, y - 10, ReplayGui.LIGHT_GRAY);
		ReplayGui.text("End", x + 102, y - 10, ReplayGui.LIGHT_GRAY);
		startField.render();
		endField.render();
		ReplayGui.text("Connect another replay after this one", x, y + 54, ReplayGui.LIGHT_GRAY);
		ReplayGui.centered(status, width / 2, y + 146, ReplayGui.WHITE);
		super.render(mouseX, mouseY, tickDelta);
	}
}
