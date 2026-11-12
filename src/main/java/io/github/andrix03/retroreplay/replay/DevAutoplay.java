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
	/**
	 * RETROREPLAY_AUTOPLAY_SCRUB: the seek back goes through Quick Mode in five steps, one a tick,
	 * like a dragged replay bar; logs whether the world was kept and where the recorded player is.
	 */
	/** RETROREPLAY_AUTOPLAY_STAY: only opens the newest replay and leaves it to the mouse and keys (driven from outside). */
	private static final boolean STAY = System.getenv("RETROREPLAY_AUTOPLAY_STAY") != null;
	/** RETROREPLAY_AUTOPLAY_CRACK: logs each crack stage shown, photographs stage 5 from above the block. */
	private static final boolean CRACK = System.getenv("RETROREPLAY_AUTOPLAY_CRACK") != null;
	private static int lastCrack = -2;
	private static int crackShot = -1;
	/** Blocks whose stage 6 was seen. */
	private static int crackBlocks;
	private static int[] crackSaved;
	private static final boolean SCRUB = System.getenv("RETROREPLAY_AUTOPLAY_SCRUB") != null;
	private static int scrubStep = -1;
	/** RETROREPLAY_AUTOPLAY_SCRUB_BLOCK=x,y,z: a block whose id each scrub step logs. */
	private static final int[] SCRUB_BLOCK = System.getenv("RETROREPLAY_AUTOPLAY_SCRUB_BLOCK") == null ? null
		: java.util.Arrays.stream(System.getenv("RETROREPLAY_AUTOPLAY_SCRUB_BLOCK").split(",")).mapToInt(Integer::parseInt).toArray();
	private static double scrubFrom;
	private static ReplayHandler scrubHandler;

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
			if (STAY) {
				armed = false; // the rest of the self-test stays off
				stage = 0;
			}
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
		if (ENABLED && SCRUB && (scrubStep >= 0 || !seeked && shots == 2)) {
			seeked = true;
			scrub(timeMs);
			return -1;
		}
		if (ENABLED && !seeked && shots == 2) {
			seeked = true;
			RetroReplay.LOGGER.info("Autoplay: seeking from " + (int) timeMs + " ms to " + SEEK_TARGET + " ms");
			return SEEK_TARGET;
		}
		return -1;
	}

	private static void crack(Minecraft minecraft, double timeMs) {
		int stage = MiningCracks.stage();
		if (stage != lastCrack) {
			lastCrack = stage;
			RetroReplay.LOGGER.info("Autoplay crack at " + (int) timeMs + " ms: stage " + stage + " at " + MiningCracks.x() + "," + MiningCracks.y() + "," + MiningCracks.z());
			if (stage >= 6 && crackBlocks++ == 1 && crackShot < 0 && minecraft.player != null) {
				// the second block dug: paused, the recorded player lifted off it (it stands on the block), camera above
				ReplayHandler replay = ReplayHandler.get();
				if (!replay.isPaused()) {
					replay.togglePause();
				}
				Entity recorded = findRecordedPlayer(minecraft);
				if (recorded != null) {
					recorded.y += 40;
					recorded.prevY += 40;
					recorded.lastY += 40;
				}
				minecraft.player.setPositionAndAngles(MiningCracks.x() + 0.5, MiningCracks.y() + 3.0, MiningCracks.z() - 0.8, 0.0F, 75.0F);
				CameraControls.toggleAmbient();
				crackShot = 20; // after the move is rendered
			}
		}
		if (crackShot > 0) {
			crackShot--;
			if (crackShot == 10) {
				shot(minecraft, "crack");
				crackSaved = new int[] { MiningCracks.x(), MiningCracks.y(), MiningCracks.z(), MiningCracks.stage() };
				MiningCracks.clear(); // the same frame without it: the difference is the crack
			} else if (crackShot == 0) {
				shot(minecraft, "nocrack");
				MiningCracks.set(crackSaved[0], crackSaved[1], crackSaved[2], crackSaved[3]);
				CameraControls.toggleAmbient();
				ReplayHandler.get().togglePause();
			}
		}
	}

	private static void scrub(double timeMs) {
		ReplayHandler replay = ReplayHandler.get();
		if (scrubStep < 0) {
			scrubStep = 0;
			scrubFrom = timeMs;
			scrubHandler = replay;
			RetroReplay.LOGGER.info("Autoplay: Quick Mode back from " + (int) timeMs + " ms to " + SEEK_TARGET + " ms in 5 steps");
		}
		Entity recorded = findRecordedPlayer(Mc.minecraft());
		RetroReplay.LOGGER.info("Autoplay scrub step " + scrubStep + " at " + (int) replay.getTime() + " ms: same world=" + (replay == scrubHandler)
			+ ", lossy=" + replay.isLossy() + (SCRUB_BLOCK == null ? "" : ", block=" + (Mc.blockState(SCRUB_BLOCK[0], SCRUB_BLOCK[1], SCRUB_BLOCK[2]) >> 4)) + ", recorded player=" + (recorded == null ? "none" : String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", recorded.x, recorded.y, recorded.z)));
		if (scrubStep < 5) {
			scrubStep++;
			replay.quickJumpTo(scrubFrom + (SEEK_TARGET - scrubFrom) * scrubStep / 5);
		} else if (scrubStep == 5) {
			// paused at the target, the camera above the recorded player: the picture of the moment shown
			scrubStep++;
			if (!replay.isPaused()) {
				replay.togglePause();
			}
			Minecraft minecraft = Mc.minecraft();
			if (recorded != null && minecraft.player != null) {
				minecraft.player.setPositionAndAngles(recorded.x + 3, recorded.y + 3, recorded.z + 3, 135.0F, 40.0F);
			}
		} else if (scrubStep < 25) {
			scrubStep++; // a second for the chunks around the camera to be built
		} else {
			shot(Mc.minecraft(), "scrub");
			replay.togglePause();
			scrubStep = -1;
		}
	}

	static void afterTick(Minecraft minecraft, double timeMs, int fed, int total) {
		if (ENABLED && CRACK) {
			crack(minecraft, timeMs);
		}
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
