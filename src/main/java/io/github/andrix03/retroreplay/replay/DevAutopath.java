package io.github.andrix03.retroreplay.replay;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.gui.ReplayOverlayScreen;
import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathingState;

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
					PathingState state = PathingState.current();
					String problem = PathPlayer.start(state, false, true);
					RetroReplay.LOGGER.info("Autopath: path started" + (problem == null ? "" : ": " + problem));
					next();
				}
				break;
			case 3:
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
			case 4:
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
