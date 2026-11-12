package io.github.andrix03.retroreplay.gui;

import io.github.andrix03.retroreplay.compat.Button;
import io.github.andrix03.retroreplay.compat.NoPauseScreen;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.render.RenderSettings;

import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Replay Mod's advanced render settings: render method (default, stereoscopic, cubic, 360),
 * the 360 yaw stabilisation, chroma key, the depth map (OpenEXR) and high performance mode.
 * Changes go straight into the last settings; Done goes back to the render settings.
 */
public class RenderAdvancedScreen extends NoPauseScreen {

	private static final int MODE = 0;
	private static final int YAW = 1;
	private static final int CHROMA = 2;
	private static final int DEPTH = 3;
	private static final int PERFORMANCE = 4;
	private static final int DONE = 5;
	private static final String[] CHROMA_NAMES = { "Green", "Blue", "Magenta" };

	private final RenderSettingsScreen parent;
	private ButtonWidget yawButton;
	private ButtonWidget depthButton;

	public RenderAdvancedScreen(RenderSettingsScreen parent) {
		this.parent = parent;
	}

	private static RenderSettings last() {
		return RenderSettingsScreen.LAST;
	}

	private int top() {
		return Math.max(30, height / 2 - 70);
	}

	@Override
	public void init() {
		buttons.clear();
		int x = width / 2 - 100;
		int y = top();
		buttons.add(new Button(MODE, x, y, 200, 20, modeLabel()));
		buttons.add(yawButton = new Button(YAW, x, y + 24, 200, 20, yawLabel()));
		buttons.add(new Button(CHROMA, x, y + 48, 200, 20, chromaLabel()));
		buttons.add(depthButton = new Button(DEPTH, x, y + 72, 200, 20, depthLabel()));
		buttons.add(new Button(PERFORMANCE, x, y + 96, 200, 20, performanceLabel()));
		buttons.add(new Button(DONE, x, y + 128, 200, 20, "Done"));
		update();
	}

	private void update() {
		yawButton.active = last().mode.isPanoramic();
		// the depth is a channel of the OpenEXR frames, drawn in one piece
		depthButton.active = last().preset == RenderSettings.Preset.EXR && last().mode == RenderSettings.Mode.DEFAULT;
	}

	private static String modeLabel() {
		return "Method: " + last().mode.title;
	}

	private static String yawLabel() {
		return "360: " + (last().followYaw ? "Turns with the camera" : "Stabilised (yaw)");
	}

	private static String chromaLabel() {
		int at = indexOf(last().chromaKey);
		return "Chroma Key: " + (at < 0 ? "Off" : CHROMA_NAMES[at]);
	}

	private static String depthLabel() {
		return "Depth Map (OpenEXR): " + (last().depthMap ? "On" : "Off");
	}

	private static String performanceLabel() {
		return "High Performance: " + (last().highPerformance ? "On" : "Off");
	}

	private static int indexOf(int color) {
		for (int i = 0; i < RenderSettings.CHROMA_COLORS.length; i++) {
			if (RenderSettings.CHROMA_COLORS[i] == color) {
				return i;
			}
		}
		return -1;
	}

	@Override
	protected void buttonClicked(ButtonWidget button) {
		switch (button.id) {
			case MODE:
				RenderSettings.Mode[] modes = RenderSettings.Mode.values();
				last().mode = modes[(last().mode.ordinal() + 1) % modes.length];
				button.message = modeLabel();
				break;
			case YAW:
				last().followYaw = !last().followYaw;
				button.message = yawLabel();
				break;
			case CHROMA:
				int next = indexOf(last().chromaKey) + 1;
				last().chromaKey = next >= RenderSettings.CHROMA_COLORS.length ? -1 : RenderSettings.CHROMA_COLORS[next];
				button.message = chromaLabel();
				break;
			case DEPTH:
				last().depthMap = !last().depthMap;
				button.message = depthLabel();
				break;
			case PERFORMANCE:
				last().highPerformance = !last().highPerformance;
				button.message = performanceLabel();
				break;
			default:
				minecraft.openScreen(parent);
				return;
		}
		update();
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == Platform.KEY_ESCAPE) {
			minecraft.openScreen(parent);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		ReplayGui.fill(0, 0, width, height, 0xA0000000);
		int y = top();
		ReplayGui.centered("Advanced Render Settings", width / 2, y - 16, ReplayGui.WHITE);
		String hint = last().mode == RenderSettings.Mode.CUBIC ? "Cubic: 4 x 3 faces from the resolution"
			: last().mode == RenderSettings.Mode.EQUIRECTANGULAR ? "360: twice as wide as high, from the resolution's width"
			: last().mode == RenderSettings.Mode.STEREOSCOPIC ? "Stereoscopic: left and right eye side by side" : "";
		ReplayGui.centered(hint, width / 2, y + 152, ReplayGui.LIGHT_GRAY);
		super.render(mouseX, mouseY, tickDelta);
	}
}
