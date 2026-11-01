package io.github.andrix03.retroreplay.gui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.lwjgl.Sys;
import org.lwjgl.input.Mouse;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Replay Mod's GuiReplayViewer: the list of recordings (thumbnail, name, server, date, duration)
 * and, centered at the bottom, "Load" + "Open Replay Folder..." above "Rename", "Delete", "Cancel".
 */
public class ReplayViewerScreen extends Screen {

	private static final int LOAD = 0, FOLDER = 1, RENAME = 2, DELETE = 3, CANCEL = 4;
	private static final int ENTRY_W = 300, ENTRY_H = 30, ENTRY_GAP = 4, THUMB_W = 30 * 16 / 9;

	private final Screen parent;
	private final List<Entry> entries = new ArrayList<>();
	private int selected = -1;
	private int scroll;
	private ButtonWidget loadButton, renameButton, deleteButton;

	public ReplayViewerScreen(Screen parent) {
		this.parent = parent;
	}

	@Override
	public void init() {
		buttons.clear();
		int panelTop = height - 10 - 45;
		int upperX = width / 2 - (150 + 5 + 150) / 2;
		buttons.add(loadButton = new ButtonWidget(LOAD, upperX, panelTop, 150, 20, "Load"));
		buttons.add(new ButtonWidget(FOLDER, upperX + 155, panelTop, 150, 20, "Open Replay Folder..."));
		int lowerX = width / 2 - (73 * 3 + 5 * 2) / 2;
		buttons.add(renameButton = new ButtonWidget(RENAME, lowerX, panelTop + 25, 73, 20, "Rename"));
		buttons.add(deleteButton = new ButtonWidget(DELETE, lowerX + 78, panelTop + 25, 73, 20, "Delete"));
		buttons.add(new ButtonWidget(CANCEL, lowerX + 156, panelTop + 25, 73, 20, "Cancel"));
		reload();
	}

	private void reload() {
		entries.clear();
		Recorder.recoverLeftovers(folder());
		File[] files = folder().listFiles((dir, name) -> name.endsWith(".mcpr"));
		if (files != null) {
			for (File file : files) {
				entries.add(Entry.read(file));
			}
		}
		entries.sort((a, b) -> Long.compare(b.date, a.date));
		selected = Math.min(selected, entries.size() - 1);
		updateButtons();
	}

	private void updateButtons() {
		boolean one = selected >= 0;
		loadButton.active = one;
		renameButton.active = one;
		deleteButton.active = one;
	}

	private File folder() {
		File folder = new File(minecraft.gameDir, "replay_recordings");
		folder.mkdirs();
		return folder;
	}

	private int listTop() {
		return 30;
	}

	private int listBottom() {
		return height - 10 - 45 - 10;
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (!button.active) {
			return;
		}
		switch (button.id) {
			case LOAD:
				ReplayHandler.open(entries.get(selected).file);
				break;
			case FOLDER:
				Sys.openURL("file://" + folder().getAbsolutePath());
				break;
			case RENAME:
				minecraft.openScreen(new RenameReplayScreen(this, entries.get(selected).file));
				break;
			case DELETE:
				minecraft.openScreen(new ConfirmScreen(this, "Are you sure you want to delete this replay?",
					"'" + entries.get(selected).name + "' will be lost forever! (A long time!)", "Yes", "No", DELETE));
				break;
			case CANCEL:
				minecraft.openScreen(parent);
				break;
			default:
		}
	}

	@Override
	public void confirmResult(boolean result, int id) {
		if (result && id == DELETE && selected >= 0) {
			File file = entries.get(selected).file;
			if (!file.delete()) {
				RetroReplay.LOGGER.warn("Cannot delete " + file);
			}
			selected = -1;
		}
		minecraft.openScreen(this);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		super.mouseClicked(mouseX, mouseY, button);
		int x = width / 2 - ENTRY_W / 2;
		if (button == 0 && mouseX >= x && mouseX < x + ENTRY_W && mouseY >= listTop() && mouseY < listBottom()) {
			int row = (mouseY - listTop() + scroll) / (ENTRY_H + ENTRY_GAP);
			if (row >= 0 && row < entries.size()) {
				if (row == selected && System.currentTimeMillis() - lastClick < 250) {
					ReplayHandler.open(entries.get(row).file);
					return;
				}
				selected = row;
				lastClick = System.currentTimeMillis();
				updateButtons();
			}
		}
	}

	private long lastClick;

	@Override
	public void handleMouse() {
		super.handleMouse();
		int wheel = Mouse.getEventDWheel();
		if (wheel != 0) {
			int max = Math.max(0, entries.size() * (ENTRY_H + ENTRY_GAP) - (listBottom() - listTop()));
			scroll = Math.max(0, Math.min(max, scroll - Integer.signum(wheel) * (ENTRY_H + ENTRY_GAP)));
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		renderBackground();
		ReplayGui.centered("Replay Viewer", width / 2, 10, ReplayGui.WHITE);
		int x = width / 2 - ENTRY_W / 2;
		org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
		scissor(0, listTop(), width, listBottom() - listTop());
		for (int i = 0; i < entries.size(); i++) {
			int y = listTop() + i * (ENTRY_H + ENTRY_GAP) - scroll;
			if (y + ENTRY_H < listTop() || y > listBottom()) {
				continue;
			}
			Entry entry = entries.get(i);
			if (i == selected) {
				ReplayGui.fill(x - 2, y - 2, x + ENTRY_W + 2, y + ENTRY_H + 2, 0xFF808080);
				ReplayGui.fill(x - 1, y - 1, x + ENTRY_W + 1, y + ENTRY_H + 1, 0xFF000000);
			}
			ReplayGui.fill(x, y, x + THUMB_W, y + ENTRY_H, 0xFF2B2B2B);
			ReplayGui.bind(ReplayGui.LOGO);
			ReplayGui.stretched(x + (THUMB_W - 24) / 2, y + 3, 24, 24);
			String duration = ReplayGui.formatTime(entry.duration);
			int dw = ReplayGui.width(duration);
			ReplayGui.fill(x + THUMB_W - dw - 4, y + ENTRY_H - 12, x + THUMB_W, y + ENTRY_H, ReplayGui.HALF_TRANSPARENT);
			ReplayGui.text(duration, x + THUMB_W - dw - 2, y + ENTRY_H - 10, ReplayGui.WHITE);
			ReplayGui.text(entry.name, x + THUMB_W + 5, y, ReplayGui.WHITE);
			ReplayGui.text(entry.server, x + THUMB_W + 5, y + 11, ReplayGui.LIGHT_GRAY);
			ReplayGui.text(entry.dateText, x + THUMB_W + 5, y + 22, ReplayGui.LIGHT_GRAY);
		}
		org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
		super.render(mouseX, mouseY, tickDelta);
	}

	/** GL scissor in GUI coordinates (scaled, top-left origin). */
	private void scissor(int x, int y, int w, int h) {
		int scale = minecraft.width / width;
		org.lwjgl.opengl.GL11.glScissor(x * scale, minecraft.height - (y + h) * scale, w * scale, h * scale);
	}

	private static final class Entry {
		private static final Pattern NUMBER = Pattern.compile("\"(duration|date)\":(\\d+)");
		private static final Pattern SERVER = Pattern.compile("\"serverName\":\"((?:[^\"\\\\]|\\\\.)*)\"");

		final File file;
		final String name;
		String server = "";
		String dateText = "";
		long date;
		long duration;

		private Entry(File file) {
			this.file = file;
			this.name = file.getName().substring(0, file.getName().length() - ".mcpr".length());
		}

		static Entry read(File file) {
			Entry entry = new Entry(file);
			entry.date = file.lastModified();
			try (ZipFile zip = new ZipFile(file)) {
				ZipEntry meta = zip.getEntry("metaData.json");
				byte[] bytes = new byte[(int) meta.getSize()];
				new java.io.DataInputStream(zip.getInputStream(meta)).readFully(bytes);
				String json = new String(bytes, StandardCharsets.UTF_8);
				Matcher m = NUMBER.matcher(json);
				while (m.find()) {
					if (m.group(1).equals("duration")) {
						entry.duration = Long.parseLong(m.group(2));
					} else {
						entry.date = Long.parseLong(m.group(2));
					}
				}
				Matcher s = SERVER.matcher(json);
				String server = s.find() ? s.group(1) : "";
				entry.server = json.contains("\"singleplayer\":true") || server.isEmpty() ? "Singleplayer" : server;
			} catch (Exception e) {
				entry.server = "Unreadable replay file";
			}
			entry.dateText = new SimpleDateFormat("yyyy/MM/dd HH:mm").format(new Date(entry.date));
			return entry;
		}
	}
}
