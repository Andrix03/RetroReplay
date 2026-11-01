package io.github.andrix03.retroreplay.gui;

import io.github.andrix03.retroreplay.replay.ReplayHandler;

/**
 * Replay Mod's in-replay bar (GuiReplayOverlay): at (10, 10), 20 px high, a play/pause button,
 * a 100 px speed slider and the timeline filling the rest, 5 px apart.
 */
public final class ReplayOverlay {

	private static final int TOP = 10;
	private static final int SIDE = 10;
	private static final int PLAY_X = SIDE;
	private static final int SLIDER_X = PLAY_X + 20 + 5;
	private static final int SLIDER_W = 100;
	private static final int TIMELINE_X = SLIDER_X + SLIDER_W + 5;

	// jGui AbstractGuiTimeline
	private static final int BORDER_LEFT = 4;
	private static final int BORDER_RIGHT = 4;
	private static final int BORDER_TOP = 4;
	private static final int BORDER_BOTTOM = 3;

	private ReplayOverlay() {
	}

	public static void render(int width, int mouseX, int mouseY, boolean mouseFree) {
		ReplayHandler replay = ReplayHandler.get();
		if (replay == null) {
			return;
		}
		// play/pause: sprite (0, 0) is "play" (shown while paused), (0, 20) is "pause"
		ReplayGui.button(PLAY_X, TOP, 20, 20, mouseFree && inside(mouseX, mouseY, PLAY_X, TOP, 20, 20));
		ReplayGui.bind(ReplayGui.REPLAY_GUI);
		ReplayGui.texture(PLAY_X, TOP, 0, replay.isPaused() ? 0 : 20, 20, 20);

		int step = replay.getSpeedStep();
		ReplayGui.slider(SLIDER_X, TOP, SLIDER_W, 20, step / (double) (ReplayHandler.SPEED_STEPS - 1),
			"Speed: " + ReplayHandler.speed(step) + "x", mouseFree && inside(mouseX, mouseY, SLIDER_X, TOP, SLIDER_W, 20));

		int timelineW = timelineWidth(width);
		ReplayGui.bind(ReplayGui.JGUI);
		ReplayGui.dynamicRect(TIMELINE_X, TOP, timelineW, 20, 0, 16, 64, 22, BORDER_TOP + 1, BORDER_BOTTOM, BORDER_LEFT + 1, BORDER_RIGHT);
		drawTimeMarkers(replay.getDuration(), timelineW);
		ReplayGui.bind(ReplayGui.JGUI);
		double fraction = replay.getDuration() == 0 ? 0 : replay.getTime() / replay.getDuration();
		int cursorX = TIMELINE_X + (int) (BORDER_LEFT + fraction * (timelineW - BORDER_LEFT - BORDER_RIGHT));
		ReplayGui.texture(cursorX - 2, TOP + BORDER_TOP - 1, 64, 0, 5, 4);
		for (int y = BORDER_TOP - 1; y < 20 - BORDER_BOTTOM; y += 11) {
			ReplayGui.texture(cursorX - 2, TOP + y, 64, 4, 5, Math.min(11, 20 - BORDER_BOTTOM - y));
		}

		if (mouseFree && inside(mouseX, mouseY, TIMELINE_X, TOP, timelineW, 20)) {
			String time = ReplayGui.formatTime(timeAt(mouseX, width, replay.getDuration()));
			int w = ReplayGui.width(time);
			ReplayGui.fill(mouseX + 6, mouseY + 6, mouseX + 12 + w, mouseY + 18, 0xF0100010);
			ReplayGui.text(time, mouseX + 9, mouseY + 8, ReplayGui.WHITE);
		}
		PathingOverlay.render(width, mouseX, mouseY, mouseFree);
	}

	/** jGui draws a line every few seconds, a longer one each larger interval. */
	private static void drawTimeMarkers(int duration, int timelineW) {
		int body = timelineW - BORDER_LEFT - BORDER_RIGHT;
		if (duration <= 0 || body <= 0) {
			return;
		}
		int[] steps = { 1_000, 2_000, 5_000, 10_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000 };
		int small = steps[steps.length - 1];
		for (int s : steps) {
			if ((long) body * s / duration >= 8) {
				small = s;
				break;
			}
		}
		int big = small * 5;
		for (int t = small; t < duration; t += small) {
			int x = TIMELINE_X + BORDER_LEFT + (int) ((long) body * t / duration);
			boolean isBig = t % big == 0;
			int h = isBig ? 5 : 3;
			ReplayGui.fill(x, TOP + 20 - BORDER_BOTTOM - h, x + 1, TOP + 20 - BORDER_BOTTOM, isBig ? ReplayGui.LIGHT_GRAY : ReplayGui.WHITE);
		}
	}

	/** Handles a click while the mouse is free; returns true when the bar or the pathing row used it. */
	public static boolean click(int mouseX, int mouseY, int width, int button) {
		ReplayHandler replay = ReplayHandler.get();
		if (replay == null) {
			return false;
		}
		if (PathingOverlay.click(mouseX, mouseY, width, button)) {
			return true;
		}
		if (button != 0) {
			return false;
		}
		if (inside(mouseX, mouseY, PLAY_X, TOP, 20, 20)) {
			replay.togglePause();
			return true;
		}
		if (inside(mouseX, mouseY, SLIDER_X, TOP, SLIDER_W, 20)) {
			drag(mouseX, mouseY, width);
			return true;
		}
		if (inside(mouseX, mouseY, TIMELINE_X, TOP, timelineWidth(width), 20)) {
			replay.jumpTo(timeAt(mouseX, width, replay.getDuration()));
			return true;
		}
		return false;
	}

	/** Dragging moves the speed slider. */
	public static void drag(int mouseX, int mouseY, int width) {
		ReplayHandler replay = ReplayHandler.get();
		if (replay != null && mouseY >= TOP && mouseY < TOP + 20 && mouseX >= SLIDER_X - 4 && mouseX < SLIDER_X + SLIDER_W + 4) {
			double value = (mouseX - SLIDER_X - 4) / (double) (SLIDER_W - 8);
			replay.setSpeedStep((int) Math.round(Math.max(0, Math.min(1, value)) * (ReplayHandler.SPEED_STEPS - 1)));
		}
	}

	private static int timelineWidth(int width) {
		return width - SIDE - TIMELINE_X;
	}

	private static long timeAt(int mouseX, int width, int duration) {
		int body = timelineWidth(width) - BORDER_LEFT - BORDER_RIGHT;
		double fraction = (mouseX - TIMELINE_X - BORDER_LEFT) / (double) body;
		return (long) (Math.max(0, Math.min(1, fraction)) * duration);
	}

	private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
	}
}
