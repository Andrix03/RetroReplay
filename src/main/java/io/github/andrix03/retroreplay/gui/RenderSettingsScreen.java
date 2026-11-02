package io.github.andrix03.retroreplay.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import io.github.andrix03.retroreplay.compat.Button;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.compat.TextField;
import io.github.andrix03.retroreplay.pathing.PathStore;
import io.github.andrix03.retroreplay.pathing.PathingState;
import io.github.andrix03.retroreplay.render.FrameWriter;
import io.github.andrix03.retroreplay.render.RenderJob;
import io.github.andrix03.retroreplay.render.RenderSettings;
import io.github.andrix03.retroreplay.render.VideoRenderer;

import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * The render button's settings (a reduced GuiRenderSettings), in two columns: encoding preset,
 * bitrate, frame rate and resolution on the left, nametags and file name on the right, then
 * ffmpeg's command line arguments (the preset's, editable). Videos go to .minecraft/replay_videos.
 */
public class RenderSettingsScreen extends NoPauseScreen {

	private static final int PRESET = 0;
	private static final int BITRATE = 1;
	private static final int FPS = 2;
	private static final int RENDER = 3;
	private static final int CANCEL = 4;
	private static final int RESOLUTION = 5;
	private static final int ADD_TO_QUEUE = 6;
	private static final int RENDER_QUEUE = 7;
	private static final int NAMETAGS = 8;
	/** width, height; 0 = the window's size. Replay Mod renders 1920x1080 by default. */
	private static final int[][] RESOLUTIONS = { { 1920, 1080 }, { 1280, 720 }, { 2560, 1440 }, { 3840, 2160 }, { 0, 0 } };
	private static int resolution;

	/** Kept between renders like Replay Mod remembers its last settings. */
	private static final RenderSettings LAST = new RenderSettings();

	/** A preset's path from the keyframe repository, or null for the current path. */
	private final String pathJson;
	private final String pathLabel;
	private TextField nameField;
	private TextField argumentsField;
	private ButtonWidget bitrateButton;
	private String ffmpegStatus;

	public RenderSettingsScreen() {
		this(null, null);
	}

	/** For a repository preset: it can only be queued (the current path stays as it is). */
	public RenderSettingsScreen(String pathJson, String pathLabel) {
		this.pathJson = pathJson;
		this.pathLabel = pathLabel;
	}

	private static List<RenderJob> queue() {
		return PathingState.current().renderQueue;
	}

	/** First button row: title (16 px above) to the queue buttons (196 px below) centered. */
	private int top() {
		return Math.max(18, (height - 196 + 16) / 2);
	}

	private int left() {
		return width / 2 - 202;
	}

	private static String safeName(String name) {
		return name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
	}

	@Override
	public void init() {
		Platform.enableRepeatEvents(true);
		buttons.clear();
		int x = left();
		int right = x + 204;
		int y = top();
		buttons.add(new Button(PRESET, x, y, 200, 20, presetLabel()));
		buttons.add(bitrateButton = new Button(BITRATE, x, y + 24, 200, 20, bitrateLabel()));
		buttons.add(new Button(FPS, x, y + 48, 200, 20, "Frame Rate: " + LAST.fps + " fps"));
		buttons.add(new Button(RESOLUTION, x, y + 72, 200, 20, resolutionLabel()));
		buttons.add(new Button(NAMETAGS, right, y, 200, 20, nametagsLabel()));
		nameField = TextField.create(this, right, y + 36, 200, 20);
		nameField.setMaxLength(128);
		String stamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date());
		nameField.setText(pathLabel == null ? stamp : safeName(pathLabel) + "_" + stamp);
		nameField.setFocused(true);
		argumentsField = TextField.create(this, x, y + 108, 404, 20);
		argumentsField.setMaxLength(1024);
		argumentsField.setText(argumentsText());
		argumentsField.setFocused(false);
		int buttonsX = width / 2 - 100;
		ButtonWidget render = new Button(RENDER, buttonsX, y + 152, 98, 20, "Render");
		render.active = pathJson == null; // a preset is queued, not rendered instead of the current path
		buttons.add(render);
		buttons.add(new Button(CANCEL, buttonsX + 102, y + 152, 98, 20, "Cancel"));
		buttons.add(new Button(ADD_TO_QUEUE, buttonsX, y + 176, 98, 20, "Add to Queue"));
		ButtonWidget renderQueue = new Button(RENDER_QUEUE, buttonsX + 102, y + 176, 98, 20, "Render Queue (" + queue().size() + ")");
		renderQueue.active = !queue().isEmpty();
		buttons.add(renderQueue);
		updateBitrate();
		File ffmpeg = FrameWriter.findFfmpeg(minecraft.gameDir, null);
		ffmpegStatus = ffmpeg != null ? "FFmpeg: " + ffmpeg.getPath() : "FFmpeg not found: only PNG Sequence works";
	}

	@Override
	public void removed() {
		Platform.enableRepeatEvents(false);
	}

	private static String presetLabel() {
		return "Preset: " + LAST.preset.title;
	}

	private String resolutionLabel() {
		int[] r = RESOLUTIONS[resolution];
		return "Resolution: " + (r[0] == 0 ? "Window (" + Platform.width() + "x" + Platform.height() + ")" : r[0] + "x" + r[1]);
	}

	private static String nametagsLabel() {
		return "Render Nametags: " + (LAST.nametags ? "On" : "Off");
	}

	/** The arguments shown in the field: the custom ones, else the preset's (PNG has none). */
	private static String argumentsText() {
		String arguments = LAST.arguments();
		return arguments == null ? "" : arguments;
	}

	/** An edit that differs from the preset becomes the custom arguments. */
	private void readArguments() {
		String text = argumentsField.getText().trim();
		LAST.customArguments = text.isEmpty() || text.equals(LAST.preset.arguments) || !LAST.preset.needsFfmpeg() ? null : text;
	}

	private static String bitrateLabel() {
		return "Bitrate: " + LAST.bitrateKbps / 1000 + " Mbps";
	}

	private void updateBitrate() {
		bitrateButton.active = LAST.preset.hasBitrate();
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		switch (button.id) {
			case PRESET:
				RenderSettings.Preset[] presets = RenderSettings.Preset.values();
				LAST.preset = presets[(LAST.preset.ordinal() + 1) % presets.length];
				LAST.customArguments = null; // a new preset brings its own arguments
				argumentsField.setText(argumentsText());
				button.message = presetLabel();
				updateBitrate();
				break;
			case BITRATE:
				LAST.bitrateKbps = next(RenderSettings.BITRATES, LAST.bitrateKbps);
				button.message = bitrateLabel();
				break;
			case RESOLUTION:
				resolution = (resolution + 1) % RESOLUTIONS.length;
				button.message = resolutionLabel();
				break;
			case NAMETAGS:
				LAST.nametags = !LAST.nametags;
				button.message = nametagsLabel();
				break;
			case FPS:
				LAST.fps = next(RenderSettings.FRAME_RATES, LAST.fps);
				button.message = "Frame Rate: " + LAST.fps + " fps";
				break;
			case RENDER:
				RenderSettings settings = settings();
				if (settings != null) {
					VideoRenderer.schedule(settings);
					minecraft.openScreen(null);
				}
				break;
			case ADD_TO_QUEUE:
				RenderSettings queued = settings();
				if (queued == null) {
					return;
				}
				// a copy of the path as it is now, like Replay Mod's queue
				String path = pathJson != null ? pathJson : PathStore.toJson(PathingState.current().path);
				queue().add(new RenderJob(queued, path, pathLabel != null ? pathLabel : queued.output.getName()));
				minecraft.openScreen(new ReplayOverlayScreen());
				break;
			case RENDER_QUEUE:
				VideoRenderer.scheduleAll(queue());
				queue().clear();
				minecraft.openScreen(null);
				break;
			default:
				minecraft.openScreen(new ReplayOverlayScreen());
				break;
		}
	}

	/** The chosen settings with the output file, or null without a file name. */
	private RenderSettings settings() {
		String name = safeName(nameField.getText());
		if (name.isEmpty()) {
			return null;
		}
		File folder = new File(minecraft.gameDir, "replay_videos");
		readArguments();
		RenderSettings settings = new RenderSettings();
		settings.preset = LAST.preset;
		settings.nametags = LAST.nametags;
		settings.customArguments = LAST.customArguments;
		settings.fps = LAST.fps;
		settings.bitrateKbps = LAST.bitrateKbps;
		settings.width = RESOLUTIONS[resolution][0];
		settings.height = RESOLUTIONS[resolution][1];
		// a PNG sequence is a folder of frames
		settings.output = LAST.preset == RenderSettings.Preset.PNG ? new File(folder, name) : new File(folder, name + "." + LAST.preset.extension);
		return settings;
	}

	private static int next(int[] values, int current) {
		for (int i = 0; i < values.length; i++) {
			if (values[i] == current) {
				return values[(i + 1) % values.length];
			}
		}
		return values[0];
	}

	@Override
	protected void keyPressed(char chr, int key) {
		nameField.keyPressed(chr, key);
		argumentsField.keyPressed(chr, key);
		if (key == Platform.KEY_ESCAPE) {
			minecraft.openScreen(new ReplayOverlayScreen());
		} else if (key == Platform.KEY_RETURN) {
			for (Object button : buttons) {
				if (((ButtonWidget) button).id == RENDER) {
					buttonClicked((ButtonWidget) button);
					return;
				}
			}
		}
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		super.mouseClicked(mouseX, mouseY, button);
		nameField.mouseClicked(mouseX, mouseY, button);
		argumentsField.mouseClicked(mouseX, mouseY, button);
	}

	@Override
	public void tick() {
		nameField.tick();
		argumentsField.tick();
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		ReplayGui.fill(0, 0, width, height, 0xA0000000);
		int x = left();
		int y = top();
		ReplayGui.centered("Render Settings", width / 2, y - 16, ReplayGui.WHITE);
		ReplayGui.text("File name", x + 204, y + 26, ReplayGui.LIGHT_GRAY);
		nameField.render();
		ReplayGui.text(LAST.preset.needsFfmpeg() ? "Command line arguments (%BITRATE% = the bitrate)" : "Command line arguments (not used for PNG)",
			x, y + 98, ReplayGui.LIGHT_GRAY);
		argumentsField.render();
		ReplayGui.text(ffmpegStatus, x, y + 134, ReplayGui.LIGHT_GRAY);
		super.render(mouseX, mouseY, tickDelta);
	}
}
