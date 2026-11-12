package io.github.andrix03.retroreplay.gui;

import java.util.Map;

import org.lwjgl.opengl.GL11;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathingState;
import io.github.andrix03.retroreplay.pathing.Spectator;
import io.github.andrix03.retroreplay.render.VideoRenderer;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.entity.Entity;

/**
 * Replay Mod's pathing row (GuiPathingKt) under the replay bar: play path, render, position and
 * time keyframe buttons, then the keyframe timeline (10 minutes, zoomable) with time labels
 * above and zoom buttons plus scrollbar below.
 */
public final class PathingOverlay {

	static final int ROW_Y = 10 + 20 + 13;
	private static final int SIDE = 10;
	private static final int PLAY_X = SIDE;
	private static final int RENDER_X = PLAY_X + 25;
	private static final int POSITION_X = RENDER_X + 25;
	private static final int TIME_X = POSITION_X + 25;
	/** After an unused 25 px slot, like Replay Mod. */
	private static final int TIMELINE_X = TIME_X + 25 + 25 + 5;
	private static final int INSET = 3;
	private static final int BELOW_Y = ROW_Y + 20 + 1;
	private static final int[] INTERVALS = { 1_000, 2_000, 5_000, 10_000, 15_000, 20_000, 30_000, 60_000, 120_000, 300_000, 600_000 };
	private static final int MIN_DISTANCE = 40;

	private static final int SEGMENT_BACKWARDS = 0xFFFF0000;
	private static final int SEGMENT_PAUSED = 0xFFFFFFFF;
	private static final int SEGMENT_SLOWER = 0xFF00FF00;
	private static final int SEGMENT_FASTER = 0xFFFFFF00;
	private static final int SEGMENT_SPECTATOR = 0xFF0088FF;

	private static String message;
	private static long messageUntil;
	/** Keyframe being dragged along the timeline, if any. */
	private static PathingState.Kind dragKind;
	private static long dragTime;
	private static boolean dragChanged;
	private static boolean draggingCursor;
	/** The replay time follows the dragged cursor (Quick Mode, time keyframes). */
	private static boolean cursorFollowed;
	private static long lastClick;

	private PathingOverlay() {
	}

	private static PathingState state() {
		ReplayHandler replay = ReplayHandler.get();
		return replay == null ? null : PathingState.of(replay.getFile());
	}

	public static void render(int width, int mouseX, int mouseY, boolean mouseFree) {
		PathingState state = state();
		if (state == null) {
			return;
		}
		if (PathPlayer.isActive()) {
			state.cursor = PathPlayer.time();
			if (state.cursor < state.scroll || state.cursor > state.scroll + state.visibleLength()) {
				state.scroll = state.cursor - state.visibleLength() / 10;
				state.clampScroll();
			}
		}
		PathingState.Kind selection = state.selection();

		iconButton(PLAY_X, 0, PathPlayer.isActive() ? 20 : 0, mouseX, mouseY, mouseFree);
		iconButton(RENDER_X, 40, 0, mouseX, mouseY, mouseFree);
		// Replay Mod: the spectator icon while spectating or with a spectator keyframe selected
		boolean spectator = selection == PathingState.Kind.POSITION ? state.path.positions().get(state.selectedTime).isSpectator() : Spectator.isSpectating();
		iconButton(POSITION_X, spectator ? 40 : 0, selection == PathingState.Kind.POSITION ? 60 : 40, mouseX, mouseY, mouseFree);
		iconButton(TIME_X, 0, selection == PathingState.Kind.TIME ? 100 : 80, mouseX, mouseY, mouseFree);

		int timelineW = width - SIDE - TIMELINE_X;
		ReplayGui.bind(ReplayGui.JGUI);
		ReplayGui.dynamicRect(TIMELINE_X, ROW_Y, timelineW, 20, 0, 16, 64, 22, 5, 5, 5, 5);
		int contentX = TIMELINE_X + INSET;
		int contentW = timelineW - 2 * INSET;
		int contentY = ROW_Y + INSET;
		int contentH = 20 - 2 * INSET;
		drawIndicatorsAndLabels(state, contentX, contentW, contentY, contentH);
		drawTimeLines(state, width, contentX, contentW, contentY);
		drawKeyframes(state, contentX, contentW, contentY);
		drawCursor(state, contentX, contentW, contentY, contentH);

		// below: zoom in, zoom out, scrollbar
		ReplayGui.smallButton(TIMELINE_X, BELOW_Y, 9, 9, mouseFree && inside(mouseX, mouseY, TIMELINE_X, BELOW_Y, 9, 9));
		ReplayGui.smallButton(TIMELINE_X + 11, BELOW_Y, 9, 9, mouseFree && inside(mouseX, mouseY, TIMELINE_X + 11, BELOW_Y, 9, 9));
		ReplayGui.bind(ReplayGui.REPLAY_GUI);
		ReplayGui.texture(TIMELINE_X, BELOW_Y, 40, 20, 9, 9);
		ReplayGui.texture(TIMELINE_X + 11, BELOW_Y, 40, 30, 9, 9);
		drawScrollbar(state, TIMELINE_X + 22, timelineW - 22);

		String rendered = VideoRenderer.takeResult();
		if (rendered != null) {
			show(rendered);
			messageUntil += 5000;
		}
		if (message != null && System.currentTimeMillis() < messageUntil) {
			ReplayGui.text(message, PLAY_X, BELOW_Y + 12, ReplayGui.WHITE);
		}
		if (mouseFree && inside(mouseX, mouseY, TIMELINE_X, ROW_Y, timelineW, 20)) {
			String time = ReplayGui.formatTime(timeAt(state, mouseX, width));
			int w = ReplayGui.width(time);
			ReplayGui.fill(mouseX + 6, mouseY + 6, mouseX + 12 + w, mouseY + 18, 0xF0100010);
			ReplayGui.text(time, mouseX + 9, mouseY + 8, ReplayGui.WHITE);
		}
	}

	private static void iconButton(int x, int u, int v, int mouseX, int mouseY, boolean mouseFree) {
		ReplayGui.button(x, ROW_Y, 20, 20, mouseFree && inside(mouseX, mouseY, x, ROW_Y, 20, 20));
		ReplayGui.bind(ReplayGui.REPLAY_GUI);
		ReplayGui.texture(x, ROW_Y, u, v, 20, 20);
	}

	/** UITimelineIndicators + UITimelineTime: ticks every interval / 5, labels every interval. */
	private static void drawIndicatorsAndLabels(PathingState state, int x, int w, int y, int h) {
		long visible = state.visibleLength();
		int interval = INTERVALS[INTERVALS.length - 1];
		double maxIndicators = w / (double) MIN_DISTANCE;
		for (int candidate : INTERVALS) {
			if (candidate > visible / maxIndicators) {
				interval = candidate;
				break;
			}
		}
		int small = interval / 5;
		long time = state.scroll / small * small;
		for (; time <= state.scroll + visible; time += small) {
			if (time < state.scroll) {
				continue;
			}
			int px = xOf(state, time, x, w);
			boolean big = time % interval == 0;
			int tickH = big ? 6 : 3;
			ReplayGui.fill(px, y + h - tickH, px + 1, y + h, big ? ReplayGui.LIGHT_GRAY : ReplayGui.WHITE);
			if (big) {
				String label = String.format("%02d:%02d", time / 60_000, time / 1000 % 60); // UITimelineTime
				int half = ReplayGui.width(label) / 2;
				int lx = Math.max(x - INSET + half, Math.min(x + w + INSET - half, px));
				ReplayGui.text(label, lx - half, ROW_Y - 8, ReplayGui.WHITE);
			}
		}
	}

	/**
	 * LineToReplayTimelineEffect: from each time keyframe up to its replay time on the replay
	 * bar, a 2 px blue line: down the replay bar, across to the keyframe, into the border.
	 */
	private static void drawTimeLines(PathingState state, int width, int x, int w, int contentTop) {
		int duration = ReplayHandler.get().getDuration();
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glEnable(GL11.GL_LINE_SMOOTH);
		GL11.glLineWidth(2);
		GL11.glColor4f(0, 0, 1, 1);
		for (Map.Entry<Long, Integer> keyframe : state.path.times().entrySet()) {
			int keyX = xOf(state, keyframe.getKey(), x, w);
			if (keyX < x || keyX > x + w) {
				continue; // scrolled out of view
			}
			int barX = ReplayOverlay.barX(keyframe.getValue(), duration, width);
			GL11.glBegin(GL11.GL_LINE_STRIP);
			GL11.glVertex2f(barX + 0.5f, ReplayOverlay.barTop());
			GL11.glVertex2f(barX + 0.5f, ReplayOverlay.barBottom());
			GL11.glVertex2f(keyX + 0.5f, ROW_Y);
			GL11.glVertex2f(keyX + 0.5f, contentTop);
			GL11.glEnd();
		}
		GL11.glDisable(GL11.GL_LINE_SMOOTH);
		GL11.glLineWidth(1);
		GL11.glColor4f(1, 1, 1, 1);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
	}

	/** UITimelineKeyframes: position row on top, time row below, 5x5 icons, colored time segments. */
	private static void drawKeyframes(PathingState state, int x, int w, int y) {
		double speed = ReplayHandler.speed(ReplayHandler.get().getSpeedStep());
		long prevTime = 0;
		int prevReplayTime = 0;
		boolean first = true;
		for (Map.Entry<Long, Integer> entry : state.path.times().entrySet()) {
			long time = entry.getKey();
			int replayTime = entry.getValue();
			if (!first) {
				double segmentSpeed = (replayTime - prevReplayTime) / (double) (time - prevTime);
				int color = 0;
				if (prevReplayTime > replayTime) {
					color = SEGMENT_BACKWARDS;
				} else if (Math.abs(segmentSpeed / speed - 1) > 0.01) {
					color = segmentSpeed == 0 ? SEGMENT_PAUSED : segmentSpeed < speed ? SEGMENT_SLOWER : SEGMENT_FASTER;
				}
				if (color != 0) {
					drawSegment(state, prevTime, time, x, w, y + 6, color);
				}
			}
			first = false;
			prevTime = time;
			prevReplayTime = replayTime;
		}
		// blue between two spectator keyframes of the same entity
		Map.Entry<Long, CameraPath.Position> previous = null;
		for (Map.Entry<Long, CameraPath.Position> entry : state.path.positions().entrySet()) {
			if (previous != null && previous.getValue().isSpectator() && previous.getValue().spectated == entry.getValue().spectated) {
				drawSegment(state, previous.getKey(), entry.getKey(), x, w, y + 1, SEGMENT_SPECTATOR);
			}
			previous = entry;
		}
		ReplayGui.bind(ReplayGui.REPLAY_GUI);
		for (Map.Entry<Long, CameraPath.Position> entry : state.path.positions().entrySet()) {
			long time = entry.getKey();
			drawIcon(state, time, x, w, y + 1, 74, entry.getValue().isSpectator() ? 30 : 20, state.isSelected(PathingState.Kind.POSITION, time));
		}
		for (long time : state.path.times().keySet()) {
			drawIcon(state, time, x, w, y + 6, 74, 25, state.isSelected(PathingState.Kind.TIME, time));
		}
	}

	private static void drawSegment(PathingState state, long from, long to, int x, int w, int rowY, int color) {
		int left = xOf(state, from, x, w) + 3;
		int right = Math.max(left + 1, xOf(state, to, x, w) - 2);
		if (right < x || left > x + w) {
			return;
		}
		ReplayGui.fill(Math.max(left, x), rowY + 1, Math.min(right, x + w), rowY + 4, color);
	}

	private static void drawIcon(PathingState state, long time, int x, int w, int rowY, int u, int v, boolean selected) {
		int px = xOf(state, time, x, w);
		if (px < x - 2 || px > x + w + 2) {
			return;
		}
		ReplayGui.texture(px - 2, rowY, u + (selected ? 5 : 0), v, 5, 5);
	}

	private static void drawCursor(PathingState state, int x, int w, int y, int h) {
		if (state.cursor < state.scroll || state.cursor > state.scroll + state.visibleLength()) {
			return;
		}
		int px = xOf(state, state.cursor, x, w);
		ReplayGui.bind(ReplayGui.JGUI);
		ReplayGui.texture(px - 2, y - 1, 64, 0, 5, 4);
		for (int dy = 3; dy < h; dy += 11) {
			ReplayGui.texture(px - 2, y + dy, 64, 4, 5, Math.min(11, h - dy));
		}
	}

	private static void drawScrollbar(PathingState state, int x, int w) {
		ReplayGui.fill(x, BELOW_Y, x + w, BELOW_Y + 9, 0xFF000000);
		int grip = Math.max(6, (int) (w * state.zoom));
		int gripX = x + (int) ((w - grip) * (state.scroll / (double) Math.max(1, PathingState.LENGTH_MS - state.visibleLength())));
		ReplayGui.fill(gripX, BELOW_Y, gripX + grip, BELOW_Y + 9, 0xFF808080);
		ReplayGui.fill(gripX, BELOW_Y, gripX + grip - 1, BELOW_Y + 8, 0xFFC6C6C6);
		ReplayGui.fill(gripX + 1, BELOW_Y + 1, gripX + grip - 1, BELOW_Y + 8, 0xFF8B8B8B);
	}

	private static int xOf(PathingState state, long time, int x, int w) {
		return x + (int) Math.round((time - state.scroll) * w / (double) state.visibleLength());
	}

	private static long timeAt(PathingState state, int mouseX, int width) {
		int w = width - SIDE - TIMELINE_X - 2 * INSET;
		double fraction = Math.max(0, Math.min(1, (mouseX - TIMELINE_X - INSET) / (double) w));
		return state.scroll + (long) (fraction * state.visibleLength());
	}

	/** A click while the mouse is free; returns true when the pathing row used it. */
	public static boolean click(int mouseX, int mouseY, int width, int button) {
		PathingState state = state();
		if (state == null) {
			return false;
		}
		int timelineW = width - SIDE - TIMELINE_X;
		if (button == 0 && inside(mouseX, mouseY, PLAY_X, ROW_Y, 20, 20)) {
			playOrStop();
			return true;
		}
		if (button == 0 && inside(mouseX, mouseY, RENDER_X, ROW_Y, 20, 20)) {
			String problem = state.path.playbackProblem(false);
			if (problem != null) {
				show(problem);
			} else {
				Mc.minecraft().openScreen(new RenderSettingsScreen());
			}
			return true;
		}
		if (button == 0 && inside(mouseX, mouseY, POSITION_X, ROW_Y, 20, 20)) {
			togglePositionKeyframe();
			return true;
		}
		if (button == 0 && inside(mouseX, mouseY, TIME_X, ROW_Y, 20, 20)) {
			toggleTimeKeyframe();
			return true;
		}
		if (button == 0 && inside(mouseX, mouseY, TIMELINE_X, BELOW_Y, 9, 9)) {
			state.setZoom(state.zoom * 2 / 3, state.cursor);
			return true;
		}
		if (button == 0 && inside(mouseX, mouseY, TIMELINE_X + 11, BELOW_Y, 9, 9)) {
			state.setZoom(state.zoom * 3 / 2, state.cursor);
			return true;
		}
		if (button == 0 && inside(mouseX, mouseY, TIMELINE_X + 22, BELOW_Y, timelineW - 22, 9)) {
			scrollTo(state, mouseX, TIMELINE_X + 22, timelineW - 22);
			return true;
		}
		if (!inside(mouseX, mouseY, TIMELINE_X, ROW_Y, timelineW, 20)) {
			return false;
		}
		int contentX = TIMELINE_X + INSET;
		int contentW = timelineW - 2 * INSET;
		int contentY = ROW_Y + INSET;
		PathingState.Kind kind = mouseY < contentY + 6 ? PathingState.Kind.POSITION : PathingState.Kind.TIME;
		Long hit = keyframeAt(state, kind, mouseX, contentX, contentW);
		if (hit != null) {
			if (button == 1) {
				applyKeyframe(state, kind, hit);
			} else if (button == 0) {
				state.select(kind, hit);
				dragKind = kind;
				dragTime = hit;
				dragChanged = false;
				long now = System.currentTimeMillis();
				if (now - lastClick < 300) {
					dragKind = null; // double click: Replay Mod's keyframe editor
					Mc.minecraft().openScreen(new EditKeyframeScreen(state, kind, hit));
				}
				lastClick = now;
			}
			return true;
		}
		if (button == 0) {
			state.cursor = timeAt(state, mouseX, width);
			state.clearSelection();
			draggingCursor = true;
			followCursor(state);
		}
		return true;
	}

	/** Mouse held after a click: moves the dragged keyframe or the cursor. */
	public static void drag(int mouseX, int mouseY, int width) {
		PathingState state = state();
		if (state == null || PathPlayer.isActive()) {
			return;
		}
		long time = timeAt(state, mouseX, width);
		if (dragKind != null && time != dragTime) {
			if (!dragChanged) {
				dragChanged = true;
				state.beforeChange(); // one undo step per drag
			}
			if (dragKind == PathingState.Kind.POSITION) {
				CameraPath.Position position = state.path.positions().get(dragTime);
				if (position == null || state.path.positions().containsKey(time)) {
					return;
				}
				state.path.removePosition(dragTime);
				state.path.putPosition(time, position);
			} else {
				Integer replayTime = state.path.times().get(dragTime);
				if (replayTime == null || state.path.times().containsKey(time)) {
					return;
				}
				state.path.removeTime(dragTime);
				state.path.putTime(time, replayTime);
			}
			dragTime = time;
			state.select(dragKind, time);
		} else if (draggingCursor) {
			state.cursor = time;
			followCursor(state);
		}
	}

	/**
	 * With Quick Mode the replay follows the cursor while it is dragged: it shows the moment the
	 * time keyframes give for it (the camera stays where it is).
	 */
	private static void followCursor(PathingState state) {
		ReplayHandler replay = ReplayHandler.get();
		if (replay == null || !replay.hasQuickMode() || !ReplayHandler.isQuickMode()) {
			return;
		}
		int replayTime = state.path.replayTimeAt(state.cursor);
		if (replayTime >= 0) {
			replay.scrubTo(replayTime);
			cursorFollowed = true;
		}
	}

	public static void release() {
		dragKind = null;
		draggingCursor = false;
		if (cursorFollowed) {
			cursorFollowed = false;
			ReplayHandler replay = ReplayHandler.get();
			if (replay != null) {
				replay.endScrub(); // the replay goes on from the cursor's moment
			}
		}
	}

	/** Mouse wheel over the timeline: scrolls it, with Ctrl zooms around the mouse. */
	public static void scroll(int mouseX, int mouseY, int width, int wheel) {
		PathingState state = state();
		if (state == null || wheel == 0 || !inside(mouseX, mouseY, TIMELINE_X, ROW_Y, width - SIDE - TIMELINE_X, 20 + 1 + 9)) {
			return;
		}
		int steps = wheel > 0 ? 1 : -1;
		if (Platform.isKeyDown(Platform.KEY_LCONTROL) || Platform.isKeyDown(Platform.KEY_RCONTROL)) {
			state.setZoom(state.zoom * Math.pow(1.2, -steps), timeAt(state, mouseX, width));
		} else {
			state.scroll -= steps * state.visibleLength() / 10;
			state.clampScroll();
		}
	}

	private static void scrollTo(PathingState state, int mouseX, int x, int w) {
		double fraction = Math.max(0, Math.min(1, (mouseX - x) / (double) w));
		state.scroll = (long) (fraction * PathingState.LENGTH_MS - state.visibleLength() / 2);
		state.clampScroll();
	}

	private static Long keyframeAt(PathingState state, PathingState.Kind kind, int mouseX, int x, int w) {
		Iterable<Long> times = kind == PathingState.Kind.POSITION ? state.path.positions().keySet() : state.path.times().keySet();
		Long best = null;
		int bestDistance = 4;
		for (long time : times) {
			int distance = Math.abs(xOf(state, time, x, w) - mouseX);
			if (distance < bestDistance) {
				best = time;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Replay Mod's right click on a keyframe: the camera goes there / the replay jumps to its time. */
	private static void applyKeyframe(PathingState state, PathingState.Kind kind, long time) {
		if (kind == PathingState.Kind.POSITION) {
			CameraPath.Position position = state.path.positions().get(time);
			if (position.isSpectator()) {
				Spectator.spectate(position.spectated);
			} else {
				Spectator.clear();
				PathPlayer.placeCamera(position);
			}
		} else {
			ReplayHandler.get().quickJumpTo(state.path.times().get(time));
		}
	}

	public static void playOrStop() {
		PathingState state = state();
		if (state == null) {
			return;
		}
		if (PathPlayer.isActive()) {
			PathPlayer.stop();
			show("Camera path stopped");
			return;
		}
		boolean shift = Platform.isKeyDown(Platform.KEY_LSHIFT) || Platform.isKeyDown(Platform.KEY_RSHIFT);
		boolean ctrl = Platform.isKeyDown(Platform.KEY_LCONTROL) || Platform.isKeyDown(Platform.KEY_RCONTROL);
		String problem = PathPlayer.start(state, shift, ctrl);
		show(problem != null ? problem : "Camera path started");
	}

	/** Replay Mod's position keyframe button / I key: adds one at the cursor, or removes the selected one. */
	public static void togglePositionKeyframe() {
		PathingState state = state();
		if (state == null || Mc.minecraft().player == null) {
			return;
		}
		state.beforeChange();
		if (state.selection() == PathingState.Kind.POSITION) {
			state.path.removePosition(state.selectedTime);
			state.clearSelection();
		} else {
			Entity spectated = Spectator.isSpectating() ? Spectator.find(Spectator.target()) : null;
			state.path.putPosition(state.cursor, spectated != null ? Spectator.positionOf(spectated, Spectator.target()) : PathPlayer.cameraPosition());
			state.select(PathingState.Kind.POSITION, state.cursor);
		}
	}

	/** Replay Mod's time keyframe button / O key: the current replay time at the cursor. */
	public static void toggleTimeKeyframe() {
		PathingState state = state();
		if (state == null) {
			return;
		}
		state.beforeChange();
		if (state.selection() == PathingState.Kind.TIME) {
			state.path.removeTime(state.selectedTime);
			state.clearSelection();
		} else {
			state.path.putTime(state.cursor, (int) ReplayHandler.get().getTime());
			state.select(PathingState.Kind.TIME, state.cursor);
		}
	}

	/** Ctrl+Z / Ctrl+Y. */
	public static void undo(boolean redo) {
		PathingState state = state();
		if (state != null && !PathPlayer.isActive()) {
			show(redo ? (state.redo() ? "Redo" : "Nothing to redo") : (state.undo() ? "Undo" : "Nothing to undo"));
		}
	}

	/**
	 * Replay Mod's V (sync timeline): the cursor moves to where the path reaches the replay's
	 * current time, counting from the last time keyframe before it at the current speed
	 * (Shift: 1x). Repeated until it stops moving, and given up if it would go backwards.
	 */
	public static void syncTimeline() {
		PathingState state = state();
		if (state == null || PathPlayer.isActive()) {
			return;
		}
		Long cursor = syncTime(state, state.cursor);
		if (cursor == null) {
			show("No time keyframe before the cursor");
			return;
		}
		for (int i = 0; i < 100; i++) {
			Long updated = syncTime(state, cursor);
			if (updated == null || updated.equals(cursor)) {
				break;
			}
			if (updated < cursor) {
				return;
			}
			cursor = updated;
		}
		state.cursor = Math.max(0, Math.min(PathingState.LENGTH_MS, cursor));
		if (state.cursor < state.scroll || state.cursor > state.scroll + state.visibleLength()) {
			state.scroll = state.cursor - state.visibleLength() / 10;
			state.clampScroll();
		}
		state.clearSelection(); // so a keyframe can be added right away
	}

	private static Long syncTime(PathingState state, long cursor) {
		Map.Entry<Long, Integer> keyframe = state.path.times().floorEntry(cursor);
		if (keyframe == null) {
			return null;
		}
		double replayPassed = ReplayHandler.get().getTime() - keyframe.getValue();
		boolean shift = Platform.isKeyDown(Platform.KEY_LSHIFT) || Platform.isKeyDown(Platform.KEY_RSHIFT);
		double speed = shift ? 1 : ReplayHandler.speed(ReplayHandler.get().getSpeedStep());
		return keyframe.getKey() + Math.round(replayPassed / speed);
	}

	/** X: Replay Mod's keyframe repository. */
	public static void openRepository() {
		PathingState state = state();
		if (state != null && !PathPlayer.isActive()) {
			Mc.minecraft().openScreen(new KeyframeRepositoryScreen(state));
		}
	}

	/** M in a replay: an event marker at the replay's current time. */
	public static void addMarker() {
		PathingState state = state();
		if (state != null) {
			int time = (int) ReplayHandler.get().getTime();
			state.markers.put(time, "");
			state.selectedMarker = time;
			show("Marker added at " + ReplayGui.formatTime(time));
		}
	}

	public static void removeSelected() {
		PathingState state = state();
		if (state == null) {
			return;
		}
		if (state.selectedMarker >= 0 && state.markers.all().containsKey(state.selectedMarker)) {
			state.markers.remove(state.selectedMarker); // a selected marker goes first, like Replay Mod
			state.selectedMarker = -1;
			return;
		}
		PathingState.Kind selection = state.selection();
		if (selection != null) {
			state.beforeChange();
		}
		if (selection == PathingState.Kind.POSITION) {
			state.path.removePosition(state.selectedTime);
		} else if (selection == PathingState.Kind.TIME) {
			state.path.removeTime(state.selectedTime);
		}
		state.clearSelection();
	}

	/** C key: asks first, like Replay Mod. */
	public static void clearKeyframes() {
		PathingState state = state();
		if (state != null && !state.path.isEmpty()) {
			Mc.minecraft().openScreen(new ClearKeyframesScreen());
		}
	}

	static void doClearKeyframes() {
		PathingState state = state();
		if (state == null) {
			return;
		}
		state.beforeChange();
		for (Long time : state.path.positions().keySet().toArray(new Long[0])) {
			state.path.removePosition(time);
		}
		for (Long time : state.path.times().keySet().toArray(new Long[0])) {
			state.path.removeTime(time);
		}
		state.clearSelection();
	}

	private static void show(String text) {
		message = text;
		messageUntil = System.currentTimeMillis() + 3000;
	}

	private static boolean inside(int mouseX, int mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;
	}
}
