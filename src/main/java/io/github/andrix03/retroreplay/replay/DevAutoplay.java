package io.github.andrix03.retroreplay.replay;

import java.io.File;

import io.github.andrix03.retroreplay.RetroReplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development self-test, only when the RETROREPLAY_AUTOPLAY environment variable is set:
 * opens the newest recording from the title screen, takes screenshots at 5 s and 15 s of
 * replay time, then quits the game.
 */
public final class DevAutoplay {

	public static final boolean ENABLED = System.getenv("RETROREPLAY_AUTOPLAY") != null;

	private static boolean opened;
	private static int shots;

	private DevAutoplay() {
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
		if (shots == 0 && timeMs > 5_000 || shots == 1 && timeMs > 15_000) {
			shots++;
			RetroReplay.LOGGER.info("Autoplay at " + (int) timeMs + " ms: " + fed + "/" + total + " packets, world=" + (minecraft.world != null)
				+ ", player=" + (minecraft.player == null ? "none" : (int) minecraft.player.x + "," + (int) minecraft.player.y + "," + (int) minecraft.player.z)
				+ ", screen=" + (minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName()));
			Screenshot.take(minecraft.gameDir, "autoplay_" + shots + ".png", minecraft.width, minecraft.height);
		} else if (shots == 2) {
			shots++;
			minecraft.stop();
		}
	}
}
