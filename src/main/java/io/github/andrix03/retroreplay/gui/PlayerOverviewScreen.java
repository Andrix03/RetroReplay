package io.github.andrix03.retroreplay.gui;

import java.util.List;

import org.lwjgl.input.Keyboard;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.pathing.Spectator;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;

/**
 * Replay Mod's player overview (B): the players in the replay, a click spectates one (Shift
 * leaves again). Up to two columns of 150 px buttons.
 */
public class PlayerOverviewScreen extends NoPauseScreen {

	private static final int FREE_CAMERA = 1000;

	private List<Entity> players;

	@Override
	public void init() {
		buttons.clear();
		players = Spectator.players();
		int perColumn = Math.max(1, (height - 90) / 24);
		int columns = players.size() > perColumn ? 2 : 1;
		int left = width / 2 - (columns == 2 ? 152 : 75);
		for (int i = 0; i < players.size() && i < perColumn * 2; i++) {
			String name = Mc.playerName(players.get(i));
			int x = left + (i / perColumn) * 154;
			int y = 40 + (i % perColumn) * 24;
			buttons.add(new ButtonWidget(i, x, y, 150, 20, name == null ? "?" : name));
		}
		buttons.add(new ButtonWidget(FREE_CAMERA, width / 2 - 75, height - 30, 150, 20, Spectator.isSpectating() ? "Free Camera" : "Done"));
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == FREE_CAMERA) {
			Spectator.stop();
		} else if (button.id < players.size()) {
			Spectator.spectate(Mc.entityId(players.get(button.id)));
		}
		minecraft.openScreen(null);
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_B) {
			minecraft.openScreen(null);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		ReplayGui.fill(0, 0, width, height, 0xA0000000);
		ReplayGui.centered("Player Overview", width / 2, 16, ReplayGui.WHITE);
		if (players.isEmpty()) {
			ReplayGui.centered("No players in sight", width / 2, height / 2, ReplayGui.LIGHT_GRAY);
		} else {
			ReplayGui.centered("Click a player to spectate, Shift to leave", width / 2, 26, ReplayGui.LIGHT_GRAY);
		}
		super.render(mouseX, mouseY, tickDelta);
	}
}
