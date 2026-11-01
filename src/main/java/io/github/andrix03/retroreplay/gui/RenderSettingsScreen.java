package io.github.andrix03.retroreplay.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.lwjgl.input.Keyboard;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.render.FrameWriter;
import io.github.andrix03.retroreplay.render.RenderSettings;
import io.github.andrix03.retroreplay.render.VideoRenderer;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * The render button's settings (a reduced GuiRenderSettings): encoding preset, bitrate, frame
 * rate and file name. Videos go to .minecraft/replay_videos at the window's resolution.
 */
public class RenderSettingsScreen extends NoPauseScreen {

	private static final int PRESET = 0;
	private static final int BITRATE = 1;
	private static final int FPS = 2;
	private static final int RENDER = 3;
	private static final int CANCEL = 4;

	/** Kept between renders like Replay Mod remembers its last settings. */
	private static final RenderSettings LAST = new RenderSettings();

	private TextFieldWidget nameField;
	private ButtonWidget bitrateButton;
	private String ffmpegStatus;

	@Override
	public void init() {
		Keyboard.enableRepeatEvents(true);
		buttons.clear();
		int x = width / 2 - 100;
		int y = height / 2 - 70;
		buttons.add(new ButtonWidget(PRESET, x, y, 200, 20, presetLabel()));
		buttons.add(bitrateButton = new ButtonWidget(BITRATE, x, y + 24, 200, 20, bitrateLabel()));
		buttons.add(new ButtonWidget(FPS, x, y + 48, 200, 20, "Frame Rate: " + LAST.fps + " fps"));
		nameField = Mc.textField(this, x, y + 84, 200, 20);
		nameField.setText(new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date()));
		nameField.setFocused(true);
		buttons.add(new ButtonWidget(RENDER, x, y + 140, 98, 20, "Render"));
		buttons.add(new ButtonWidget(CANCEL, x + 102, y + 140, 98, 20, "Cancel"));
		updateBitrate();
		File ffmpeg = FrameWriter.findFfmpeg(minecraft.gameDir, null);
		ffmpegStatus = ffmpeg != null ? "FFmpeg: " + ffmpeg.getPath() : "FFmpeg not found: only PNG Sequence works";
	}

	@Override
	public void removed() {
		Keyboard.enableRepeatEvents(false);
	}

	private static String presetLabel() {
		return "Preset: " + LAST.preset.title;
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
				button.message = presetLabel();
				updateBitrate();
				break;
			case BITRATE:
				LAST.bitrateKbps = next(RenderSettings.BITRATES, LAST.bitrateKbps);
				button.message = bitrateLabel();
				break;
			case FPS:
				LAST.fps = next(RenderSettings.FRAME_RATES, LAST.fps);
				button.message = "Frame Rate: " + LAST.fps + " fps";
				break;
			case RENDER:
				String name = nameField.getText().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
				if (name.isEmpty()) {
					return;
				}
				File folder = new File(minecraft.gameDir, "replay_videos");
				RenderSettings settings = new RenderSettings();
				settings.preset = LAST.preset;
				settings.fps = LAST.fps;
				settings.bitrateKbps = LAST.bitrateKbps;
				// a PNG sequence is a folder of frames
				settings.output = LAST.preset == RenderSettings.Preset.PNG ? new File(folder, name) : new File(folder, name + "." + LAST.preset.extension);
				VideoRenderer.schedule(settings);
				minecraft.openScreen(null);
				break;
			default:
				minecraft.openScreen(new ReplayOverlayScreen());
				break;
		}
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
		if (key == Keyboard.KEY_ESCAPE) {
			minecraft.openScreen(new ReplayOverlayScreen());
		} else if (key == Keyboard.KEY_RETURN) {
			buttonClicked((ButtonWidget) buttons.get(RENDER));
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
		int x = width / 2 - 100;
		int y = height / 2 - 70;
		ReplayGui.centered("Render Settings", width / 2, y - 16, ReplayGui.WHITE);
		ReplayGui.text("File name", x, y + 74, ReplayGui.LIGHT_GRAY);
		nameField.render();
		ReplayGui.text("Resolution: " + minecraft.width + "x" + minecraft.height + " (window size)", x, y + 110, ReplayGui.LIGHT_GRAY);
		ReplayGui.text(ffmpegStatus, x, y + 122, ReplayGui.LIGHT_GRAY);
		super.render(mouseX, mouseY, tickDelta);
	}
}
