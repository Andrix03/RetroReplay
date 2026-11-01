package io.github.andrix03.retroreplay.replay;

import java.io.File;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.gui.RenderSettingsScreen;
import io.github.andrix03.retroreplay.gui.ReplayOverlayScreen;
import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathingState;
import io.github.andrix03.retroreplay.render.RenderSettings;
import io.github.andrix03.retroreplay.render.VideoRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screen.menu.TitleScreen;

/**
 * Development self-test, only when RETROREPLAY_AUTOPATH is set: opens the newest recording,
 * builds a camera path (two position and two time keyframes), photographs the pathing row,
 * plays the path and checks the camera and the replay time halfway and at the end.
 */
public final class DevAutopath {

	public static final boolean ENABLED = System.getenv("RETROREPLAY_AUTOPATH") != null;

	private static int stage;
	private static int waitTicks;
	private static boolean checkedMiddle;
	/** "png" or "mp4": render the path instead of playing it. */
	private static final String RENDER = System.getenv("RETROREPLAY_AUTORENDER");
	private static long renderStart;

	private DevAutopath() {
	}

	public static void tick() {
		if (!ENABLED) {
			return;
		}
		Minecraft minecraft = Mc.minecraft();
		waitTicks++;
		switch (stage) {
			case 0:
				if (minecraft.screen instanceof TitleScreen && waitTicks > 30) {
					ReplayHandler.open(ReplayHandler.latestRecording());
					next();
				}
				break;
			case 1:
				ReplayHandler replay = ReplayHandler.get();
				if (replay != null && minecraft.world != null && minecraft.player != null && replay.getTime() > 3000 && waitTicks > 40) {
					PathingState state = PathingState.of(replay.getFile());
					CameraPath.Position a = PathPlayer.cameraPosition();
					CameraPath.Position b = new CameraPath.Position(a.x + 10, a.y + 2, a.z, a.yaw + 90, a.pitch, 0);
					state.path.putPosition(0, a);
					state.path.putPosition(2000, new CameraPath.Position(a.x + 5, a.y + 3, a.z + 4, a.yaw + 45, a.pitch, 0));
					state.path.putPosition(4000, b);
					state.path.putTime(0, 3000);
					state.path.putTime(4000, 7000);
					state.cursor = 2000;
					state.select(PathingState.Kind.POSITION, 4000);
					minecraft.openScreen(new ReplayOverlayScreen());
					next();
				}
				break;
			case 2:
				if (waitTicks > 20) {
					shot(minecraft, "path_gui");
					minecraft.openScreen(null);
					// look at the whole path from the side for the preview picture
					PathingState state = PathingState.current();
					CameraPath.Position a = state.path.positions().firstEntry().getValue();
					state.cursor = 1000;
					PathPlayer.placeCamera(new CameraPath.Position(a.x + 5, a.y + 8, a.z - 10, 0, 35, 0));
					next();
				}
				break;
			case 3:
				if (waitTicks == 5) {
					PathingState state = PathingState.current();
					CameraPath.Position a = state.path.positions().firstEntry().getValue();
					PathPlayer.placeCamera(new CameraPath.Position(a.x + 5, a.y + 8, a.z - 10, 0, 35, 0));
				}
				if (waitTicks > 10) {
					shot(minecraft, "path_preview");
					if (RENDER != null) {
						minecraft.openScreen(new RenderSettingsScreen());
						stage = 7;
						waitTicks = 0;
						break;
					}
					String problem = PathPlayer.start(PathingState.current(), false, true);
					RetroReplay.LOGGER.info("Autopath: path started" + (problem == null ? "" : ": " + problem));
					next();
				}
				break;
			case 7:
				if (waitTicks > 15) {
					shot(minecraft, "render_settings");
					minecraft.openScreen(null);
					RenderSettings settings = new RenderSettings();
					settings.preset = RENDER.equals("mp4") ? RenderSettings.Preset.MP4_CUSTOM : RenderSettings.Preset.PNG;
					settings.fps = 30;
					File folder = new File(minecraft.gameDir, "replay_videos");
					settings.output = RENDER.equals("mp4") ? new File(folder, "autorender.mp4") : new File(folder, "autorender");
					RetroReplay.LOGGER.info("Autopath: rendering " + settings.preset.title);
					renderStart = System.currentTimeMillis();
					VideoRenderer.schedule(settings);
					stage = 6;
					waitTicks = 0;
				}
				break;
			case 6:
				if (!VideoRenderer.isRendering() && waitTicks > 5) {
					File out = new File(minecraft.gameDir, "replay_videos/" + (RENDER.equals("mp4") ? "autorender.mp4" : "autorender"));
					String[] frames = out.isDirectory() ? out.list() : null;
					RetroReplay.LOGGER.info("Autopath: render done in " + (System.currentTimeMillis() - renderStart) + " ms, output "
						+ out.getName() + (frames != null ? " with " + frames.length + " frames" : " of " + out.length() + " bytes"));
					stage = 5;
					waitTicks = 0;
				}
				break;
			case 4:
				if (!checkedMiddle && PathPlayer.time() >= 2000) {
					checkedMiddle = true;
					check(minecraft, "middle", PathPlayer.time());
					shot(minecraft, "path_middle");
				}
				if (!PathPlayer.isActive()) {
					check(minecraft, "end", 4000);
					shot(minecraft, "path_end");
					next();
				}
				break;
			case 5:
				if (waitTicks > 20) {
					minecraft.stop();
					next();
				}
				break;
			default:
				break;
		}
	}

	/** Logs where the camera and the replay are against what the path says for that time. */
	private static void check(Minecraft minecraft, String name, long time) {
		PathingState state = PathingState.current();
		CameraPath.Position expected = state.path.positionAt(time);
		CameraPath.Position actual = PathPlayer.cameraPosition();
		double error = Math.abs(expected.x - actual.x) + Math.abs(expected.y - actual.y) + Math.abs(expected.z - actual.z);
		RetroReplay.LOGGER.info(String.format("Autopath %s at %d ms: camera %.2f,%.2f,%.2f yaw %.1f, path %.2f,%.2f,%.2f yaw %.1f, error %.3f, replay %d ms (path %d ms)",
			name, time, actual.x, actual.y, actual.z, actual.yaw, expected.x, expected.y, expected.z, expected.yaw, error,
			(int) ReplayHandler.get().getTime(), state.path.replayTimeAt(time)));
	}

	private static void next() {
		stage++;
		waitTicks = 0;
	}

	private static void shot(Minecraft minecraft, String name) {
		RetroReplay.LOGGER.info("Autopath screenshot " + name);
		Screenshot.take(minecraft.gameDir, "autopath_" + name + ".png", minecraft.width, minecraft.height);
	}
}
