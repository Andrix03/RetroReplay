package io.github.andrix03.retroreplay.replay;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;
import net.minecraft.client.gui.screen.menu.TitleScreen;

/**
 * Development self-test, only when RETROREPLAY_AUTORECORD is set: opens the singleplayer
 * world "rr_selftest", walks the player in a circle (jumping, swinging) for 12 seconds while
 * it is recorded, then returns to the title screen (where DevAutoplay can take over).
 */
public final class DevAutorecord {

	public static final boolean ENABLED = System.getenv("RETROREPLAY_AUTORECORD") != null;
	/** host:port to record a multiplayer session instead of the singleplayer test world. */
	private static final String SERVER = System.getenv("RETROREPLAY_AUTORECORD_SERVER");
	/** Singleplayer test world, created with seed 1234 if missing. */
	private static final String WORLD = System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_WORLD", "rr_selftest");
	/** Die (/kill) and respawn during the recording. */
	private static final boolean DIE = System.getenv("RETROREPLAY_AUTORECORD_DIE") != null;
	private static int deathTicks;
	/** "quit" or "crash": end the game in the middle of the world instead of leaving it. */
	private static final String EXIT = System.getenv("RETROREPLAY_AUTORECORD_EXIT");
	/** Seconds spent in the pause menu (default 3; long pauses test connection timeouts). */
	private static final long PAUSE_NANOS = Long.parseLong(System.getenv().getOrDefault("RETROREPLAY_AUTORECORD_PAUSE", "3")) * 1_000_000_000L;

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
		Minecraft minecraft = Mc.minecraft();
		if (!started) {
			// the first title screen opens before the game has finished starting up: wait a second
			if (minecraft.screen instanceof TitleScreen && ++titleTicks > 20) {
				started = true;
				if (SERVER != null) {
					RetroReplay.LOGGER.info("Autorecord: connecting to " + SERVER);
					String[] hostPort = SERVER.split(":");
					minecraft.openScreen(Mc.connectScreen(minecraft.screen, hostPort[0], Integer.parseInt(hostPort[1])));
				} else {
					RetroReplay.LOGGER.info("Autorecord: opening " + WORLD);
					Mc.startSelftestWorld(WORLD);
				}
			}
			return;
		}
		if (minecraft.screen instanceof GameMenuScreen && System.nanoTime() - pauseStart > PAUSE_NANOS) {
			Screenshot.take(minecraft.gameDir, "autorecord_paused.png", minecraft.width, minecraft.height);
			RetroReplay.LOGGER.info("Autorecord: closing the pause menu");
			minecraft.openScreen(null);
		}
		if (DIE && minecraft.screen != null && minecraft.screen.getClass().getSimpleName().equals("DeathScreen") && ++deathTicks == 40) {
			Screenshot.take(minecraft.gameDir, "autorecord_death.png", minecraft.width, minecraft.height);
			RetroReplay.LOGGER.info("Autorecord: respawning");
			callPlayer(minecraft, "respawn");
			minecraft.openScreen(null);
		}
		if (minecraft.player == null || minecraft.world == null || minecraft.screen != null) {
			return;
		}
		ticks++;
		if (ticks == 60) {
			Screenshot.take(minecraft.gameDir, "autorecord_recording.png", minecraft.width, minecraft.height);
		}
		if (ticks == 80 && DIE) {
			RetroReplay.LOGGER.info("Autorecord: /kill");
			callPlayer(minecraft, "sendChat", "/kill");
		}
		if (ticks == 100 && EXIT != null) {
			// leaving the game from inside a world: "quit" like closing the window, "crash" without any cleanup
			RetroReplay.LOGGER.info("Autorecord: " + EXIT + " while in the world");
			if (EXIT.equals("crash")) {
				Runtime.getRuntime().halt(1);
			}
			minecraft.stop(); // what closing the window does: the game loop ends and shuts down
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

	/** By name (dev mappings only): the method sits on a player class whose package differs between versions. */
	private static void callPlayer(Minecraft minecraft, String method, Object... args) {
		try {
			Class<?>[] types = new Class<?>[args.length];
			for (int i = 0; i < args.length; i++) {
				types[i] = args[i].getClass();
			}
			minecraft.player.getClass().getMethod(method, types).invoke(minecraft.player, args);
		} catch (ReflectiveOperationException e) {
			RetroReplay.LOGGER.error("Autorecord: cannot call " + method, e);
		}
	}
}
