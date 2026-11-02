package io.github.andrix03.retroreplay.pathing;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;

/**
 * Plays the camera path in real time like Replay Mod's RealtimeTimelinePlayer: every frame the
 * timeline time moves on, the replay time follows the time keyframes and the camera the
 * position keyframes. It stops at the last keyframe or when stopped.
 */
public final class PathPlayer {

	private static PathPlayer active;

	private final PathingState state;
	private final boolean ignoreTime;
	private final long startTime;
	private final long startNanos = System.nanoTime();
	private long time;

	private PathPlayer(PathingState state, boolean ignoreTime, long startTime) {
		this.state = state;
		this.ignoreTime = ignoreTime;
		this.startTime = startTime;
		this.time = startTime;
	}

	public static boolean isActive() {
		return active != null;
	}

	/** Timeline time being played, or -1. */
	public static long time() {
		return active == null ? -1 : active.time;
	}

	/** Starts at the cursor (fromStart: at 0); returns why it cannot play, or null. */
	public static String start(PathingState state, boolean ignoreTime, boolean fromStart) {
		String problem = state.path.playbackProblem(ignoreTime);
		if (problem != null) {
			return problem;
		}
		long start = fromStart ? 0 : state.cursor;
		if (start >= state.path.end()) {
			start = 0;
		}
		active = new PathPlayer(state, ignoreTime, start);
		RetroReplay.LOGGER.info("Camera path started at " + start + " ms");
		return null;
	}

	public static void stop() {
		if (active != null) {
			active.state.cursor = active.time;
			active = null;
			Spectator.clear();
			RetroReplay.LOGGER.info("Camera path stopped");
		}
	}

	/** Every rendered frame, before the world is drawn. */
	public static void onFrame() {
		PathPlayer player = active;
		ReplayHandler replay = ReplayHandler.get();
		if (player == null) {
			return;
		}
		if (replay == null) {
			active = null;
			return;
		}
		player.time = player.startTime + (System.nanoTime() - player.startNanos) / 1_000_000L;
		if (player.time >= player.state.path.end()) {
			player.time = player.state.path.end();
			player.apply(replay);
			stop();
			if (!replay.isPaused()) {
				replay.togglePause(); // the replay waits at the path's last moment
			}
			return;
		}
		player.apply(replay);
	}

	private void apply(ReplayHandler replay) {
		if (!ignoreTime) {
			int replayTime = state.path.replayTimeAt(time);
			if (replayTime >= 0) {
				replay.setTimeFromPath(replayTime);
			}
		}
		applyCamera(state.path, time);
	}

	/** The camera for a path time: through the spectated entity's eyes, or at the interpolated position. */
	public static void applyCamera(CameraPath path, long time) {
		int spectated = path.spectatedAt(time);
		if (spectated != CameraPath.Position.NO_ENTITY) {
			CameraRoll.reset(); // through someone's eyes the view is level
			Spectator.spectate(spectated);
			if (Spectator.find(spectated) != null) {
				return;
			}
		} else if (Spectator.isSpectating()) {
			Spectator.clear();
		}
		CameraPath.Position position = path.positionAt(time);
		if (position != null) {
			placeCamera(position);
		}
	}

	/** Puts the camera exactly there, with nothing to interpolate from the last tick. */
	public static void placeCamera(CameraPath.Position position) {
		Minecraft minecraft = Mc.minecraft();
		if (minecraft.player == null) {
			return;
		}
		minecraft.player.setPositionAndAngles(position.x, position.y, position.z, position.yaw, position.pitch);
		CameraRoll.set(position.roll);
		minecraft.player.prevX = minecraft.player.lastX = minecraft.player.x;
		minecraft.player.prevY = minecraft.player.lastY = minecraft.player.y;
		minecraft.player.prevZ = minecraft.player.lastZ = minecraft.player.z;
		minecraft.player.lastYaw = minecraft.player.yaw;
		minecraft.player.lastPitch = minecraft.player.pitch;
		minecraft.player.velocityX = 0;
		minecraft.player.velocityY = 0;
		minecraft.player.velocityZ = 0;
	}

	/** The camera now, as a keyframe position (feet y, like setPositionAndAngles takes it). */
	public static CameraPath.Position cameraPosition() {
		Minecraft minecraft = Mc.minecraft();
		return new CameraPath.Position(minecraft.player.x, minecraft.player.y - Mc.feetOffset(), minecraft.player.z,
			minecraft.player.yaw, minecraft.player.pitch, CameraRoll.get());
	}
}
