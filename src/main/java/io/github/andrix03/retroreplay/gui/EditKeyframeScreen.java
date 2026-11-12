package io.github.andrix03.retroreplay.gui;

import java.util.ArrayList;
import java.util.List;

import io.github.andrix03.retroreplay.compat.Button;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.compat.TextField;
import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathingState;

import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Replay Mod's GuiEditKeyframe (double click on a keyframe): the timeline position as
 * min / sec / ms, then for a time keyframe the replay timestamp the same way, for a position
 * keyframe X, Y, Z and yaw, pitch, roll in two columns. Save / Cancel.
 */
public class EditKeyframeScreen extends NoPauseScreen {

	private final PathingState state;
	private final PathingState.Kind kind;
	private final long time;
	private final List<TextField> fields = new ArrayList<>();
	private String error;
	private CameraPath.Interpolation interpolation;
	/** This keyframe's segment (to the next one): its own interpolation, or null for the path's. */
	private CameraPath.Interpolation segment;

	public EditKeyframeScreen(PathingState state, PathingState.Kind kind, long time) {
		this.state = state;
		this.kind = kind;
		this.time = time;
	}

	@Override
	public void init() {
		Platform.enableRepeatEvents(true);
		buttons.clear();
		fields.clear();
		int top = top();
		int x = width / 2 - 77;
		addTime(x, top + 14, time);
		if (kind == PathingState.Kind.TIME) {
			addTime(x, top + 54, state.path.times().get(time));
		} else {
			CameraPath.Position p = state.path.positions().get(time);
			int left = width / 2 - 95;
			int right = width / 2 + 5;
			double[] values = { p.x, p.yaw, p.y, p.pitch, p.z, p.roll };
			for (int i = 0; i < 6; i++) {
				TextField field = TextField.create(this, i % 2 == 0 ? left : right, top + 54 + (i / 2) * 32, 90, 20);
				field.setText(format(values[i]));
				fields.add(field);
			}
		}
		int buttonsY = kind == PathingState.Kind.TIME ? top + 90 : top + 174;
		if (kind == PathingState.Kind.POSITION) {
			interpolation = state.path.interpolation();
			segment = state.path.segmentInterpolation(time);
			// Replay Mod's interpolator choice: the whole path's, and this segment's own
			buttons.add(new Button(2, width / 2 - 152, top + 148, 150, 20, interpolationLabel()));
			buttons.add(new Button(3, width / 2 + 2, top + 148, 150, 20, segmentLabel()));
		}
		buttons.add(new Button(0, width / 2 - 152, buttonsY, 150, 20, "Save"));
		buttons.add(new Button(1, width / 2 + 2, buttonsY, 150, 20, "Cancel"));
	}

	private String interpolationLabel() {
		return "Path: " + interpolation.title;
	}

	private String segmentLabel() {
		return "Next: " + (segment == null ? "Path's" : segment.title);
	}

	/** Top of the fields: the whole popup (title to Save / Cancel) centered, at least 20 px down. */
	private int top() {
		int contentHeight = kind == PathingState.Kind.TIME ? 110 : 194;
		return Math.max(20, (height - contentHeight) / 2);
	}

	private void addTime(int x, int y, long ms) {
		String[] parts = { Long.toString(ms / 60_000), Long.toString(ms / 1000 % 60), Long.toString(ms % 1000) };
		for (int i = 0; i < 3; i++) {
			TextField field = TextField.create(this, x + i * 54, y, 50, 20);
			field.setText(parts[i]);
			fields.add(field);
		}
	}

	private static String format(double value) {
		String text = String.format(java.util.Locale.ROOT, "%.3f", value);
		return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
	}

	@Override
	public void removed() {
		Platform.enableRepeatEvents(false);
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		if (button.id == 3) {
			CameraPath.Interpolation[] types = CameraPath.Interpolation.values();
			segment = segment == null ? types[0] : segment.ordinal() + 1 < types.length ? types[segment.ordinal() + 1] : null;
			button.message = segmentLabel();
			return;
		}
		if (button.id == 2) {
			CameraPath.Interpolation[] types = CameraPath.Interpolation.values();
			interpolation = types[(interpolation.ordinal() + 1) % types.length];
			button.message = interpolationLabel();
			return;
		}
		if (button.id == 0) {
			try {
				save();
			} catch (NumberFormatException e) {
				error = "Not a number: " + e.getMessage();
				return;
			}
		}
		minecraft.openScreen(new ReplayOverlayScreen());
	}

	private void save() {
		long newTime = millis(0);
		state.beforeChange();
		if (kind == PathingState.Kind.TIME) {
			int replayTime = (int) millis(3);
			state.path.removeTime(time);
			state.path.putTime(newTime, replayTime);
		} else {
			double[] v = new double[6];
			for (int i = 0; i < 6; i++) {
				v[i] = Double.parseDouble(fields.get(3 + i).getText().trim().replace(',', '.'));
			}
			int spectated = state.path.positions().get(time).spectated; // a spectator keyframe stays one
			state.path.removePosition(time);
			state.path.putPosition(newTime, new CameraPath.Position(v[0], v[2], v[4], (float) v[1], (float) v[3], (float) v[5], spectated));
			state.path.setInterpolation(interpolation);
			state.path.setSegmentInterpolation(newTime, segment);
		}
		state.select(kind, newTime);
	}

	/** Three fields from that index: minutes, seconds, milliseconds. */
	private long millis(int index) {
		long min = Long.parseLong(fields.get(index).getText().trim());
		long sec = Long.parseLong(fields.get(index + 1).getText().trim());
		long ms = Long.parseLong(fields.get(index + 2).getText().trim());
		return Math.max(0, min * 60_000 + sec * 1000 + ms);
	}

	@Override
	protected void keyPressed(char chr, int key) {
		for (TextField field : fields) {
			field.keyPressed(chr, key);
		}
		if (key == Platform.KEY_ESCAPE) {
			minecraft.openScreen(new ReplayOverlayScreen());
		} else if (key == Platform.KEY_RETURN) {
			buttonClicked((ButtonWidget) buttons.get(0));
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		super.mouseClicked(mouseX, mouseY, button);
		for (TextField field : fields) {
			field.mouseClicked(mouseX, mouseY, button);
		}
	}

	@Override
	public void tick() {
		for (TextField field : fields) {
			field.tick();
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		ReplayGui.fill(0, 0, width, height, 0xA0000000);
		int top = top();
		ReplayGui.centered(kind == PathingState.Kind.TIME ? "Edit Time Keyframe" : "Edit Position Keyframe", width / 2, top - 16, ReplayGui.WHITE);
		ReplayGui.text("Timeline Position (min / sec / ms)", width / 2 - 77, top + 3, ReplayGui.LIGHT_GRAY);
		if (kind == PathingState.Kind.TIME) {
			ReplayGui.text("Timestamp (min / sec / ms)", width / 2 - 77, top + 43, ReplayGui.LIGHT_GRAY);
		} else {
			String[] labels = { "X Position", "Camera Yaw", "Y Position", "Camera Pitch", "Z Position", "Camera Roll" };
			for (int i = 0; i < 6; i++) {
				ReplayGui.text(labels[i], i % 2 == 0 ? width / 2 - 95 : width / 2 + 5, top + 43 + (i / 2) * 32, ReplayGui.LIGHT_GRAY);
			}
		}
		for (TextField field : fields) {
			field.render();
		}
		if (error != null) {
			ReplayGui.centered(error, width / 2, height - 20, 0xFFFF5555);
		}
		super.render(mouseX, mouseY, tickDelta);
	}
}
