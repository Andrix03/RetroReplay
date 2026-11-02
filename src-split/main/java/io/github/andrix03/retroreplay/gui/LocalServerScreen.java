package io.github.andrix03.retroreplay.gui;

import io.github.andrix03.retroreplay.server.LocalServer;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Shown while the local server for a singleplayer world downloads and starts; joins it once
 * it runs. If it fails, the world can still be played without recording.
 */
public class LocalServerScreen extends Screen {

	private final Runnable playWithoutRecording;
	private int dots;

	/** playWithoutRecording opens the world the vanilla way. */
	public LocalServerScreen(Runnable playWithoutRecording) {
		this.playWithoutRecording = playWithoutRecording;
	}

	@Override
	public void init() {
		buttons.clear();
		if (LocalServer.state() == LocalServer.State.FAILED) {
			buttons.add(new ButtonWidget(0, width / 2 - 100, height / 2 + 30, 200, 20, "Play without recording"));
			buttons.add(new ButtonWidget(1, width / 2 - 100, height / 2 + 54, 200, 20, "Back to title screen"));
		}
	}

	@Override
	public void tick() {
		dots++;
		LocalServer.State state = LocalServer.state();
		if (state == LocalServer.State.RUNNING) {
			minecraft.openScreen(new ConnectScreen(minecraft, "127.0.0.1", LocalServer.port()));
		} else if (state == LocalServer.State.FAILED && buttons.isEmpty()) {
			init();
		}
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == 0) {
			playWithoutRecording.run();
		} else {
			minecraft.openScreen(new TitleScreen());
		}
	}

	@Override
	protected void keyPressed(char chr, int key) {
		// no Esc while starting: the server would be left running without a player
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		renderBackground();
		LocalServer.State state = LocalServer.state();
		String dotsText = "...".substring(0, dots / 10 % 4);
		if (state == LocalServer.State.FAILED) {
			ReplayGui.centered("Cannot start the world for recording", width / 2, height / 2 - 40, ReplayGui.WHITE);
			// the reason, wrapped to the screen: it can be a whole sentence
			int y = height / 2 - 24;
			StringBuilder row = new StringBuilder();
			for (String word : String.valueOf(LocalServer.error()).split(" ")) {
				if (row.length() > 0 && ReplayGui.width(row + " " + word) > width - 40) {
					ReplayGui.centered(row.toString(), width / 2, y, ReplayGui.LIGHT_GRAY);
					y += 10;
					row.setLength(0);
				}
				row.append(row.length() > 0 ? " " : "").append(word);
			}
			ReplayGui.centered(row.toString(), width / 2, y, ReplayGui.LIGHT_GRAY);
		} else if (state == LocalServer.State.DOWNLOADING) {
			ReplayGui.centered("Downloading the Minecraft server" + dotsText, width / 2, height / 2 - 20, ReplayGui.WHITE);
			ReplayGui.centered("Only the first time, it lets singleplayer be recorded", width / 2, height / 2, ReplayGui.LIGHT_GRAY);
		} else {
			ReplayGui.centered("Starting the world" + dotsText, width / 2, height / 2 - 20, ReplayGui.WHITE);
			ReplayGui.centered("Singleplayer runs on a local server so it can be recorded", width / 2, height / 2, ReplayGui.LIGHT_GRAY);
		}
		super.render(mouseX, mouseY, tickDelta);
	}
}
