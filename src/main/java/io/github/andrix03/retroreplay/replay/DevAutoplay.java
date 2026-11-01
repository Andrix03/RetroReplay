package io.github.andrix03.retroreplay.replay;

import java.io.File;

import io.github.andrix03.retroreplay.RetroReplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.entity.living.player.RemoteClientPlayerEntity;
import net.minecraft.entity.Entity;

/**
 * Development self-test, only when the RETROREPLAY_AUTOPLAY environment variable is set:
 * opens the newest recording from the title screen, frames the recorded player and takes
 * three screenshots, then quits the game.
 */
public final class DevAutoplay {

	public static final boolean ENABLED = System.getenv("RETROREPLAY_AUTOPLAY") != null;

	private static final int[] SHOT_TIMES = { 4_000, 8_000, 11_000 };

	private static boolean opened;
	private static boolean framed;
	private static int shots;

	private static boolean seeked;

	private DevAutoplay() {
	}

	/** After the second picture, jump back to 3 s once so the third one checks the rebuilt world. */
	static double seekRequest(double timeMs) {
		if (ENABLED && !seeked && shots == 2) {
			seeked = true;
			RetroReplay.LOGGER.info("Autoplay: seeking back from " + (int) timeMs + " ms to 3000 ms");
			return 3_000;
		}
		return -1;
	}

	public static void onTitleScreen() {
		if (ENABLED && !opened) {
			opened = true;
			File latest = ReplayHandler.latestRecording();
			RetroReplay.LOGGER.info("Autoplay: " + latest);
			if (latest != null) {
				ReplayHandler.open(latest);
			}
		}
	}

	static void afterTick(Minecraft minecraft, double timeMs, int fed, int total) {
		if (!ENABLED) {
			return;
		}
		if (framed) {
			// the camera moved last tick and has been rendered since: take the picture now
			framed = false;
			shots++;
			Screenshot.take(minecraft.gameDir, "autoplay_" + shots + ".png", minecraft.width, minecraft.height);
		} else if (shots < SHOT_TIMES.length && timeMs > SHOT_TIMES[shots]) {
			RemoteClientPlayerEntity recorded = findRecordedPlayer(minecraft);
			RetroReplay.LOGGER.info("Autoplay at " + (int) timeMs + " ms: " + fed + "/" + total + " packets, world=" + (minecraft.world != null)
				+ ", camera=" + (minecraft.player == null ? "none" : (int) minecraft.player.x + "," + (int) minecraft.player.y + "," + (int) minecraft.player.z)
				+ ", recorded player=" + (recorded == null ? "none" : recorded.getCommandSourceName() + " at " + (int) recorded.x + "," + (int) recorded.y + "," + (int) recorded.z)
				+ ", screen=" + (minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName()));
			if (recorded != null && minecraft.player != null) {
				double x = recorded.x + 4;
				double z = recorded.z + 2;
				float yaw = (float) Math.toDegrees(Math.atan2(recorded.z - z, recorded.x - x)) - 90.0F;
				minecraft.player.setPositionAndAngles(x, recorded.y + 1, z, yaw, 15.0F);
			}
			framed = true;
		} else if (shots == SHOT_TIMES.length) {
			shots++;
			minecraft.stop();
		}
	}

	private static RemoteClientPlayerEntity findRecordedPlayer(Minecraft minecraft) {
		if (minecraft.world != null) {
			for (Entity entity : minecraft.world.entities) {
				if (entity instanceof RemoteClientPlayerEntity) {
					return (RemoteClientPlayerEntity) entity;
				}
			}
		}
		return null;
	}
}
