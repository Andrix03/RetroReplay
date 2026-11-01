package io.github.andrix03.retroreplay.replay;

import java.io.File;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.gui.EditKeyframeScreen;
import io.github.andrix03.retroreplay.gui.PathingOverlay;
import io.github.andrix03.retroreplay.gui.RenderSettingsScreen;
import io.github.andrix03.retroreplay.gui.ReplayOverlayScreen;
import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathingState;
import io.github.andrix03.retroreplay.pathing.Spectator;
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
	/** RETROREPLAY_AUTOPATH_SPECTATE: the middle of the path spectates the recorded player. */
	private static final boolean SPECTATE = System.getenv("RETROREPLAY_AUTOPATH_SPECTATE") != null;
	private static int spectated = -1;
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
					RetroReplay.LOGGER.info("Autopath: the replay came with " + state.path.positions().size() + " position and "
						+ state.path.times().size() + " time keyframes and markers at " + state.markers.all().keySet());
					CameraPath.Position a = PathPlayer.cameraPosition();
					CameraPath.Position b = new CameraPath.Position(a.x + 10, a.y + 2, a.z, a.yaw + 90, a.pitch, 30); // rolled 30 degrees
					state.path.putPosition(0, a);
					state.path.putPosition(2000, new CameraPath.Position(a.x + 5, a.y + 3, a.z + 4, a.yaw + 45, a.pitch, 0));
					state.path.putPosition(4000, b);
					if (SPECTATE && !Spectator.players().isEmpty()) {
						// the middle of the path through the recorded player's eyes
						net.minecraft.entity.Entity player = Spectator.players().get(0);
						spectated = player.networkId;
						state.path.removePosition(2000);
						state.path.putPosition(1000, Spectator.positionOf(player, spectated));
						state.path.putPosition(3000, Spectator.positionOf(player, spectated));
						RetroReplay.LOGGER.info("Autopath: spectator keyframes on entity " + spectated);
					}
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
					minecraft.openScreen(new EditKeyframeScreen(PathingState.current(), PathingState.Kind.POSITION, 4000));
					stage = 8;
					waitTicks = 0;
				}
				break;
			case 8:
				if (waitTicks > 15) {
					shot(minecraft, "edit_keyframe");
					minecraft.openScreen(null);
					PathingState state = PathingState.current();
					// V: with time keyframes 0 -> 3000 ms and 4000 -> 7000 ms the cursor lands at replay time - 3000
					int now = (int) ReplayHandler.get().getTime();
					PathingOverlay.syncTimeline();
					RetroReplay.LOGGER.info("Autopath: sync timeline at replay " + now + " ms put the cursor at " + state.cursor + " ms (expected about " + (now - 3000) + ")");
					state.beforeChange();
					state.path.removePosition(2000);
					int removed = state.path.positions().size();
					state.undo();
					int undone = state.path.positions().size();
					state.redo();
					int redone = state.path.positions().size();
					state.undo();
					RetroReplay.LOGGER.info("Autopath: undo test " + removed + " -> undo " + undone + " -> redo " + redone + " -> undo "
						+ state.path.positions().size() + " position keyframes");
					// look at the whole path from the side for the preview picture
					CameraPath.Position a = state.path.positions().firstEntry().getValue();
					state.cursor = 1000;
					PathPlayer.placeCamera(new CameraPath.Position(a.x + 5, a.y + 8, a.z - 10, 0, 35, 0));
					state.presets.put("Autopath", state.path);
					minecraft.openScreen(new io.github.andrix03.retroreplay.gui.KeyframeRepositoryScreen(state));
					stage = 9;
					waitTicks = 0;
				}
				break;
			case 9:
				if (waitTicks > 15) {
					shot(minecraft, "repository");
					minecraft.openScreen(null);
					PathingState state = PathingState.current();
					CameraPath copy = new CameraPath();
					state.presets.load("Autopath", copy);
					RetroReplay.LOGGER.info("Autopath: preset holds " + copy.positions().size() + " position and " + copy.times().size() + " time keyframes");
					stage = 3;
					waitTicks = 0;
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
					settings.preset = renderPreset();
					settings.fps = 30;
					String size = System.getenv("RETROREPLAY_AUTORENDER_SIZE"); // e.g. 1920x1080
					if (size != null) {
						settings.width = Integer.parseInt(size.split("x")[0]);
						settings.height = Integer.parseInt(size.split("x")[1]);
					}
					settings.output = renderOutput(minecraft);
					RetroReplay.LOGGER.info("Autopath: rendering " + settings.preset.title);
					renderStart = System.currentTimeMillis();
					VideoRenderer.schedule(settings);
					stage = 6;
					waitTicks = 0;
				}
				break;
			case 6:
				if (!VideoRenderer.isRendering() && waitTicks > 5) {
					File out = renderOutput(minecraft);
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
					if (SPECTATE) {
						Object camera = Mc.cameraEntity();
						RetroReplay.LOGGER.info("Autopath: at the middle the camera is " + (camera == minecraft.player ? "the free camera"
							: "entity " + ((net.minecraft.entity.Entity) camera).networkId) + " (expected " + spectated + ")");
					}
					shot(minecraft, "path_middle");
				}
				if (!PathPlayer.isActive()) {
					check(minecraft, "end", 4000);
					RetroReplay.LOGGER.info("Autopath: camera roll at the end " + io.github.andrix03.retroreplay.pathing.CameraRoll.get() + " (keyframe 30)");
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

	/** RETROREPLAY_AUTORENDER: png, mp4, potato, webm or mkv. */
	private static RenderSettings.Preset renderPreset() {
		switch (RENDER) {
			case "mp4":
				return RenderSettings.Preset.MP4_CUSTOM;
			case "potato":
				return RenderSettings.Preset.MP4_POTATO;
			case "webm":
				return RenderSettings.Preset.WEBM_CUSTOM;
			case "mkv":
				return RenderSettings.Preset.MKV_LOSSLESS;
			default:
				return RenderSettings.Preset.PNG;
		}
	}

	private static File renderOutput(Minecraft minecraft) {
		RenderSettings.Preset preset = renderPreset();
		String name = "autorender_" + RENDER;
		File folder = new File(minecraft.gameDir, "replay_videos");
		return preset == RenderSettings.Preset.PNG ? new File(folder, name) : new File(folder, name + "." + preset.extension);
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
