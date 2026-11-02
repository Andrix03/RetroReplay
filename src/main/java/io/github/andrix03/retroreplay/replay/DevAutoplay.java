package io.github.andrix03.retroreplay.replay;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.gui.ReplayViewerScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.entity.Entity;

/**
 * Development self-test, only when the RETROREPLAY_AUTOPLAY environment variable is set:
 * photographs the title screen and the Replay Viewer, opens the newest recording, frames the
 * recorded player three times (seeking back once in between), photographs the pause menu,
 * then quits the game. Screenshots go to run/screenshots/autoplay_*.png.
 */
public final class DevAutoplay {

	public static final boolean ENABLED = System.getenv("RETROREPLAY_AUTOPLAY") != null;

	private static final int[] SHOT_TIMES = { 4_000, 8_000, 11_000 };
	/** Where the seek after the second picture goes (default 3 s, back); RETROREPLAY_AUTOPLAY_SEEK. */
	private static final int SEEK_TARGET = Integer.parseInt(System.getenv().getOrDefault("RETROREPLAY_AUTOPLAY_SEEK", "3000"));
	/** Dev A/B check only: seeks handle every chunk packet, to compare the world with the skipping one. */
	public static final boolean NO_CHUNK_SKIP = System.getenv("RETROREPLAY_NO_CHUNK_SKIP") != null;
	/** RETROREPLAY_AUTOPLAY_CAMERA: first checks the camera speed (2x flies twice as far) and ambient lighting. */
	private static final boolean CAMERA_TEST = System.getenv("RETROREPLAY_AUTOPLAY_CAMERA") != null;
	private static int cameraTicks;
	private static double cameraFrom;
	private static double cameraNormal;

	private static boolean armed;
	private static int stage;
	private static long stageStart;
	private static boolean framed;
	private static int shots;
	private static boolean seeked;

	private DevAutoplay() {
	}

	public static void onTitleScreen() {
		if (ENABLED && !armed) {
			armed = true;
			next();
		}
	}

	/** Title screen and viewer stages, driven from the client tick. */
	public static void tick() {
		if (!ENABLED || !armed) {
			return;
		}
		Minecraft minecraft = Mc.minecraft();
		long waited = (System.nanoTime() - stageStart) / 1_000_000L;
		if (stage == 1 && minecraft.screen instanceof TitleScreen && waited > 1500) {
			shot(minecraft, "title");
			minecraft.openScreen(new ReplayViewerScreen(minecraft.screen));
			next();
		} else if (stage == 2 && waited > 1500) {
			shot(minecraft, "viewer");
			java.io.File latest = ReplayHandler.latestRecording();
			RetroReplay.LOGGER.info("Autoplay: " + latest);
			if (latest != null) {
				ReplayHandler.open(latest);
			}
			next();
		} else if (stage == 4 && waited > 1000) {
			shot(minecraft, "pausemenu");
			minecraft.stop();
			next();
		}
	}

	private static void next() {
		stage++;
		stageStart = System.nanoTime();
	}

	/** 20 ticks pushed upwards at 1x, 20 at 2x (the distances logged), then a picture with ambient lighting. */
	private static void cameraTest(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.world == null || minecraft.screen != null) {
			return;
		}
		int tick = cameraTicks++;
		if (tick == 0 || tick == 20) {
			if (tick == 20) {
				cameraNormal = minecraft.player.y - cameraFrom;
			}
			CameraControls.setSpeedStep(tick == 0 ? 0 : 4);
			cameraFrom = minecraft.player.y;
		}
		if (tick < 40) {
			// straight up into the empty sky (nothing to collide with), pushed again every tick before the player moves
			minecraft.player.velocityX = 0;
			minecraft.player.velocityY = 0.5;
			minecraft.player.velocityZ = 0;
		} else if (tick == 40) {
			double fast = minecraft.player.y - cameraFrom;
			CameraControls.setSpeedStep(0);
			RetroReplay.LOGGER.info("Autoplay camera: 20 ticks flew " + String.format(java.util.Locale.ROOT, "%.2f", cameraNormal) + " blocks at 1x, "
				+ String.format(java.util.Locale.ROOT, "%.2f", fast) + " at 2x (ratio " + String.format(java.util.Locale.ROOT, "%.2f", fast / cameraNormal) + ")");
			minecraft.player.velocityY = 0;
			CameraControls.toggleAmbient();
		} else if (tick == 43) {
			Thumbnail.request(); // saved into the replay, shown by the viewer on the next run
		} else if (tick == 45) {
			shot(minecraft, "ambient");
		} else if (tick == 46) {
			CameraControls.toggleAmbient();
		} else if (tick == 49) {
			shot(minecraft, "ambient_off");
		}
	}

	private static void shot(Minecraft minecraft, String name) {
		RetroReplay.LOGGER.info("Autoplay screenshot " + name);
		Mc.screenshot("autoplay_" + name + ".png");
	}

	/** After the second picture, jump back to 3 s once so the third one checks the rebuilt world. */
	static double seekRequest(double timeMs) {
		if (ENABLED && !seeked && shots == 2) {
			seeked = true;
			RetroReplay.LOGGER.info("Autoplay: seeking from " + (int) timeMs + " ms to " + SEEK_TARGET + " ms");
			return SEEK_TARGET;
		}
		return -1;
	}

	static void afterTick(Minecraft minecraft, double timeMs, int fed, int total) {
		if (!ENABLED || stage != 3) {
			return;
		}
		if (CAMERA_TEST && cameraTicks < 50) {
			cameraTest(minecraft);
			return;
		}
		if (framed) {
			// the camera moved last tick and has been rendered since: take the picture now
			framed = false;
			shots++;
			shot(minecraft, "replay_" + shots);
		} else if (shots < SHOT_TIMES.length && timeMs > (shots == 2 ? Math.max(SHOT_TIMES[2], SEEK_TARGET + 3000) : SHOT_TIMES[shots])) {
			// the third picture 3 s after the seek target: a long seek lands well past 11 s
			Entity recorded = findRecordedPlayer(minecraft);
			RetroReplay.LOGGER.info("Autoplay at " + (int) timeMs + " ms: " + fed + "/" + total + " packets, world=" + (minecraft.world != null)
				+ ", recorded player=" + (recorded == null ? "none" : recorded.getClass().getSimpleName() + " at " + (int) recorded.x + "," + (int) recorded.y + "," + (int) recorded.z)
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
			minecraft.openScreen(new GameMenuScreen());
			next();
		}
	}

	/**
	 * The recorded player is the only other player entity. Found without class names (they are
	 * obfuscated in real installs): the camera's class is two levels below a class every
	 * client-side player shares (PlayerEntity before 1.6, AbstractClientPlayerEntity in 1.6).
	 */
	private static Entity findRecordedPlayer(Minecraft minecraft) {
		if (minecraft.world != null && minecraft.player != null) {
			Class<?> players = minecraft.player.getClass().getSuperclass().getSuperclass();
			for (Object entity : minecraft.world.entities) {
				if (entity != minecraft.player && players.isInstance(entity)) {
					return (Entity) entity;
				}
			}
		}
		return null;
	}
}
