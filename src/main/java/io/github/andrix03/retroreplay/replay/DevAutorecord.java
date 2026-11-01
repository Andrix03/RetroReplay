package io.github.andrix03.retroreplay.replay;

import io.github.andrix03.retroreplay.RetroReplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.screen.menu.multiplayer.ConnectScreen;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.WorldGeneratorType;

/**
 * Development self-test, only when RETROREPLAY_AUTORECORD is set: opens the singleplayer
 * world "rr_selftest", walks the player in a circle (jumping, swinging) for 12 seconds while
 * it is recorded, then returns to the title screen (where DevAutoplay can take over).
 */
public final class DevAutorecord {

	public static final boolean ENABLED = System.getenv("RETROREPLAY_AUTORECORD") != null;
	/** host:port to record a multiplayer session instead of the singleplayer test world. */
	private static final String SERVER = System.getenv("RETROREPLAY_AUTORECORD_SERVER");

	private static boolean started;
	private static boolean finished;
	private static int ticks;
	private static int titleTicks;
	private static long pauseStart;

	private DevAutorecord() {
	}

	/** Returns true while the self-test owns the title screen. */
	public static boolean onTitleScreen() {
		return ENABLED && !finished;
	}

	public static void tick() {
		if (!ENABLED || finished) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (!started) {
			// the first title screen opens before the game has finished starting up: wait a second
			if (minecraft.screen instanceof TitleScreen && ++titleTicks > 20) {
				started = true;
				if (SERVER != null) {
					RetroReplay.LOGGER.info("Autorecord: connecting to " + SERVER);
					String[] hostPort = SERVER.split(":");
					minecraft.openScreen(new ConnectScreen(minecraft.screen, minecraft, hostPort[0], Integer.parseInt(hostPort[1])));
				} else {
					RetroReplay.LOGGER.info("Autorecord: opening rr_selftest");
					minecraft.startGame("rr_selftest", "rr_selftest",
						new WorldSettings(1234L, WorldSettings.GameMode.SURVIVAL, true, false, WorldGeneratorType.DEFAULT));
				}
			}
			return;
		}
		if (minecraft.screen instanceof GameMenuScreen && System.nanoTime() - pauseStart > 3_000_000_000L) {
			Screenshot.take(minecraft.gameDir, "autorecord_paused.png", minecraft.width, minecraft.height);
			RetroReplay.LOGGER.info("Autorecord: closing the pause menu");
			minecraft.openScreen(null);
		}
		if (minecraft.player == null || minecraft.world == null || minecraft.screen != null) {
			return;
		}
		ticks++;
		if (ticks == 60) {
			Screenshot.take(minecraft.gameDir, "autorecord_recording.png", minecraft.width, minecraft.height);
		}
		if (ticks == 120 && pauseStart == 0) {
			// 3 s in the pause menu: the recording clock must not count them
			RetroReplay.LOGGER.info("Autorecord: opening the pause menu");
			pauseStart = System.nanoTime();
			minecraft.openScreen(new GameMenuScreen());
			return;
		}
		minecraft.player.yaw += 3.0F;
		double rad = Math.toRadians(minecraft.player.yaw);
		minecraft.player.velocityX = -Math.sin(rad) * 0.2;
		minecraft.player.velocityZ = Math.cos(rad) * 0.2;
		if (ticks % 50 == 0 && minecraft.player.onGround) {
			minecraft.player.velocityY = 0.42;
		}
		if (ticks % 30 == 0) {
			minecraft.player.swingArm();
		}
		if (ticks >= 240) {
			finished = true;
			RetroReplay.LOGGER.info("Autorecord: done at " + (int) minecraft.player.x + "," + (int) minecraft.player.y + "," + (int) minecraft.player.z);
			minecraft.world.disconnect();
			minecraft.setWorld(null);
			minecraft.openScreen(new TitleScreen());
		}
	}
}
