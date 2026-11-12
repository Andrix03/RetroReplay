package io.github.andrix03.retroreplay.render;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.gui.RenderingScreen;
import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathingState;
import io.github.andrix03.retroreplay.pathing.Spectator;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;

/**
 * Replay Mod's VideoRenderer: renders the camera path frame by frame instead of in real time.
 * For each frame the video time gives the replay time (time keyframes) and the camera
 * (position keyframes); the client runs exactly the ticks up to that replay time, the world
 * is drawn with the leftover as partial tick, read back and handed to the FrameWriter.
 * It takes over the game loop from one frame's render call until the video is done.
 */
public final class VideoRenderer {

	private static java.util.List<RenderJob> pending;
	private static boolean rendering;
	/** The settings of the video being rendered. */
	private static RenderSettings current;
	private static volatile boolean cancelled;
	private static String result;

	private VideoRenderer() {
	}

	/** Renders the replay's current path at the next frame. */
	public static void schedule(RenderSettings settings) {
		scheduleAll(java.util.Collections.singletonList(new RenderJob(settings, null, settings.output.getName())));
	}

	/** Replay Mod's render queue: the jobs one after another, Esc stops the rest. */
	public static void scheduleAll(java.util.List<RenderJob> jobs) {
		pending = new java.util.ArrayList<>(jobs);
	}

	/** The render's nametag setting (nametags are drawn only while the GUI is shown, and rendering hides it). */
	public static boolean showsNametags() {
		return current != null && current.nametags;
	}

	public static boolean isRendering() {
		return rendering;
	}

	public static void cancel() {
		cancelled = true;
	}

	/** The message of the last render (saved where, or why it failed), then null. */
	public static String takeResult() {
		String r = result;
		result = null;
		return r;
	}

	/** From GameRenderer.render: returns true when it rendered a video, so that frame is skipped. */
	public static boolean onFrame() {
		if (pending == null || rendering) {
			return false;
		}
		java.util.List<RenderJob> jobs = pending;
		pending = null;
		int done = 0;
		for (RenderJob job : jobs) {
			try {
				result = run(job);
			} catch (Exception e) {
				RetroReplay.LOGGER.error("Rendering failed", e);
				result = "Rendering failed: " + e;
			}
			RetroReplay.LOGGER.info(job.label + ": " + result);
			if (cancelled || !result.startsWith("Video saved")) {
				break; // a failure or Esc stops the queue
			}
			done++;
		}
		if (jobs.size() > 1) {
			result = done == jobs.size() ? "Render queue done: " + done + " videos" : "Render queue stopped after " + done + " of " + jobs.size() + " videos: " + result;
			RetroReplay.LOGGER.info(result);
		}
		return true;
	}

	private static String run(RenderJob job) throws IOException {
		RenderSettings settings = job.settings;
		current = settings;
		Minecraft minecraft = Mc.minecraft();
		ReplayHandler replay = ReplayHandler.get();
		if (replay == null) {
			return "No replay is open";
		}
		CameraPath path = job.path(PathingState.of(replay.getFile()).path);
		String problem = path.playbackProblem(false);
		if (problem != null) {
			return problem;
		}
		File ffmpeg = null;
		if (settings.preset.needsFfmpeg()) {
			ffmpeg = FrameWriter.findFfmpeg(minecraft.gameDir, null);
			if (ffmpeg == null) {
				return "FFmpeg not found: install it (ffmpeg.org) or choose PNG Sequence";
			}
		}
		// a chosen size renders off screen; 0 (or no framebuffer support) means the window's size
		int width = settings.width > 0 ? settings.width : Platform.width();
		int height = settings.height > 0 ? settings.height : Platform.height();
		// the render mode draws the world in pieces (eyes, cube faces) of this size, then puts them together
		RenderSettings.Mode mode = settings.mode;
		int drawWidth = width;
		int drawHeight = height;
		if (mode == RenderSettings.Mode.STEREOSCOPIC) {
			width &= ~1;
			drawWidth = width / 2;
		} else if (mode == RenderSettings.Mode.CUBIC) {
			int size = Math.min(width / 4, height / 3) & ~1;
			width = size * 4;
			height = size * 3;
			drawWidth = drawHeight = size;
		} else if (mode == RenderSettings.Mode.EQUIRECTANGULAR) {
			int size = width / 4 & ~1;
			width = size * 4;
			height = size * 2;
			drawWidth = drawHeight = size;
		}
		OffscreenTarget target = null;
		if (drawWidth != Platform.width() || drawHeight != Platform.height()) {
			target = OffscreenTarget.create(drawWidth, drawHeight);
			if (target == null) {
				if (mode != RenderSettings.Mode.DEFAULT) {
					return "This render mode needs off-screen rendering, which this graphics card cannot do at " + drawWidth + "x" + drawHeight;
				}
				RetroReplay.LOGGER.warn("No off-screen rendering at " + width + "x" + height + ", using the window size");
				width = drawWidth = Platform.width();
				height = drawHeight = Platform.height();
			}
		}
		if (settings.needsEvenSize() && mode == RenderSettings.Mode.DEFAULT) {
			width &= ~1;
			height &= ~1;
			drawWidth = width;
			drawHeight = height;
		}
		settings.width = width;
		settings.height = height;
		File folder = settings.output.getParentFile();
		if (!folder.isDirectory() && !folder.mkdirs()) {
			return "Cannot create " + folder;
		}

		PathPlayer.stop();
		boolean hideGui = minecraft.options.hideGui;
		String title = Platform.getTitle();
		rendering = true;
		cancelled = false;
		minecraft.options.hideGui = true;
		minecraft.openScreen(new RenderingScreen());
		FrameWriter writer = FrameWriter.create(settings, ffmpeg);
		RenderProgress progress = new RenderProgress(settings.output.getName());
		boolean ok = false;
		int frame = 0;
		long start = path.start();
		long end = path.end();
		int frames = (int) ((end - start) * settings.fps / 1000) + 1;
		RenderEffects.setChroma(settings.chromaKey);
		boolean depth = settings.depthMap && settings.preset == RenderSettings.Preset.EXR && mode == RenderSettings.Mode.DEFAULT;
		RenderEffects.setKeepProjection(depth);
		Frame shot = new Frame(settings, target, drawWidth, drawHeight, width, height, depth);
		try {
			ByteBuffer pixels;
			// two ticks before the first frame: sky and fog colors are only updated by ticks
			long tick = goTo(Math.max(0, path.replayTimeAt(start) - 100));
			// a few frames drawn and thrown away: the first ones after the world rebuilt are not clean
			for (int i = 0; i < 5; i++) {
				PathPlayer.applyCamera(path, start);
				shot.take(0);
				progress.showPreparing();
				Platform.updateDisplay();
			}
			for (; frame < frames && !cancelled && !Platform.isCloseRequested(); frame++) {
				long videoTime = start + frame * 1000L / settings.fps;
				int replayTime = path.replayTimeAt(videoTime);
				if (replayTime < ReplayHandler.get().getTime() - 50) {
					tick = goTo(replayTime); // the time path goes backwards: rebuild the world there
				}
				long targetTick = replayTime / 50;
				while (tick < targetTick) {
					tick++;
					ReplayHandler.get().setTimeFromPath(tick * 50);
					minecraft.tick();
				}
				ReplayHandler.get().setTimeFromPath(replayTime);
				float partial = (replayTime - targetTick * 50) / 50f;
				PathPlayer.applyCamera(path, videoTime);
				pixels = shot.take(partial);
				if (depth) {
					writer.setDepth(shot.distances());
				}
				writer.write(pixels, width, height);
				if (!settings.highPerformance || frame % 10 == 0 || frame == frames - 1) {
					progress.show(pixels, width, height, frame + 1, frames);
				}
				Platform.setTitle("Rendering " + (frame + 1) + " / " + frames + " (Esc cancels)");
				Platform.updateDisplay();
			}
			ok = !cancelled && frame == frames;
		} finally {
			RenderEffects.reset();
			progress.delete();
			if (target != null) {
				target.delete();
			}
			boolean closed = writer.close();
			ok &= closed;
			Platform.setTitle(title);
			minecraft.options.hideGui = hideGui;
			Spectator.clear();
			rendering = false;
			current = null;
			minecraft.openScreen(null);
		}
		if (cancelled) {
			return "Rendering cancelled after " + frame + " frames";
		}
		return ok ? "Video saved: " + settings.output.getName() + " (" + frames + " frames)"
			: "Rendering failed, see " + new File(folder, "export.log").getName();
	}

	/**
	 * Like Replay Mod's ForceChunkLoading: a chunk only joins the build queue when a render pass
	 * sees it, so draw, build everything queued, and draw again until a pass queues nothing.
	 */
	private static void begin(OffscreenTarget target) {
		if (target != null) {
			target.begin();
		}
	}

	private static void end(OffscreenTarget target) {
		if (target != null) {
			target.end();
		}
	}

	/**
	 * One video frame in the settings' render mode: the world drawn once, or once per eye or cube
	 * face with the camera moved or turned for each (and put back), then the pieces put together.
	 */
	private static final class Frame {
		private final RenderSettings settings;
		private final OffscreenTarget target;
		private final int drawWidth;
		private final int drawHeight;
		private final ByteBuffer piece;
		private final ByteBuffer frame;
		private final ByteBuffer[] faces;
		private final int[] table;
		private final java.nio.FloatBuffer depth;
		private float[] distances;

		Frame(RenderSettings settings, OffscreenTarget target, int drawWidth, int drawHeight, int width, int height, boolean depth) {
			this.settings = settings;
			this.target = target;
			this.drawWidth = drawWidth;
			this.drawHeight = drawHeight;
			this.piece = BufferUtils.createByteBuffer(drawWidth * drawHeight * 4);
			boolean pieces = settings.mode != RenderSettings.Mode.DEFAULT;
			this.frame = pieces ? BufferUtils.createByteBuffer(width * height * 4) : piece;
			if (settings.mode.isPanoramic()) {
				faces = new ByteBuffer[FrameComposer.FACES];
				for (int f = 0; f < faces.length; f++) {
					faces[f] = BufferUtils.createByteBuffer(drawWidth * drawHeight * 4);
				}
			} else {
				faces = null;
			}
			table = settings.mode == RenderSettings.Mode.EQUIRECTANGULAR ? FrameComposer.equirectangularTable(width, height, drawWidth) : null;
			this.depth = depth ? BufferUtils.createFloatBuffer(drawWidth * drawHeight) : null;
		}

		/** The frame with the camera where the path put it (BGRA, bottom row first). */
		ByteBuffer take(float partial) {
			switch (settings.mode) {
				case STEREOSCOPIC:
					stereo(partial);
					return frame;
				case CUBIC:
				case EQUIRECTANGULAR:
					panorama(partial);
					return frame;
				default:
					draw(partial, piece);
					return piece;
			}
		}

		/** The depth map of the last frame drawn, in blocks from the camera. */
		float[] distances() {
			return distances;
		}

		private void draw(float partial, ByteBuffer into) {
			begin(target);
			renderComplete(partial, settings.highPerformance ? 1 : 10);
			into.clear();
			GL11.glReadPixels(0, 0, drawWidth, drawHeight, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, into);
			if (depth != null) {
				java.nio.FloatBuffer world = RenderEffects.depth(); // read before the game cleared it for the hand
				float[] out = new float[drawWidth * drawHeight];
				for (int i = 0; i < out.length; i++) {
					out[i] = world == null || i >= world.capacity() ? 0 : RenderEffects.distance(world.get(i));
				}
				distances = out;
			}
			end(target);
		}

		/** Each eye half an eye distance to its side of the camera, looking the same way. */
		private void stereo(float partial) {
			net.minecraft.entity.Entity camera = Mc.minecraft().player;
			double x = camera.x;
			double z = camera.z;
			double yaw = Math.toRadians(camera.yaw);
			double rightX = -Math.cos(yaw);
			double rightZ = -Math.sin(yaw);
			for (int eye = 0; eye < 2; eye++) {
				double side = (eye == 0 ? -1 : 1) * EYE_DISTANCE / 2;
				moveCamera(camera, x + rightX * side, z + rightZ * side);
				draw(partial, piece);
				FrameComposer.stereo(piece, eye, drawWidth, drawHeight, frame);
			}
			moveCamera(camera, x, z);
		}

		/** The six cube faces at 90 degrees, level, turned with the camera's yaw unless it is stabilised. */
		private void panorama(float partial) {
			net.minecraft.entity.Entity camera = Mc.minecraft().player;
			float yaw = camera.yaw;
			float pitch = camera.pitch;
			float roll = io.github.andrix03.retroreplay.pathing.CameraRoll.get();
			float base = settings.followYaw ? yaw : 0;
			RenderEffects.setFov(90);
			io.github.andrix03.retroreplay.pathing.CameraRoll.set(0);
			try {
				for (int f = 0; f < FrameComposer.FACES; f++) {
					turnCamera(camera, base + FrameComposer.FACE_YAW[f], FrameComposer.FACE_PITCH[f]);
					draw(partial, faces[f]);
					if (settings.mode == RenderSettings.Mode.CUBIC) {
						FrameComposer.cross(faces[f], f, drawWidth, frame);
					}
				}
				if (table != null) {
					FrameComposer.equirectangular(faces, table, frame);
				}
			} finally {
				RenderEffects.setFov(0);
				io.github.andrix03.retroreplay.pathing.CameraRoll.set(roll);
				turnCamera(camera, yaw, pitch);
			}
		}
	}

	/** Between the eyes of the stereoscopic mode, in blocks (a player is 1.8 high). */
	private static final double EYE_DISTANCE = 0.064;

	private static void moveCamera(net.minecraft.entity.Entity camera, double x, double z) {
		camera.x = camera.prevX = camera.lastX = x;
		camera.z = camera.prevZ = camera.lastZ = z;
	}

	private static void turnCamera(net.minecraft.entity.Entity camera, float yaw, float pitch) {
		camera.yaw = camera.lastYaw = yaw;
		camera.pitch = camera.lastPitch = pitch;
	}

	private static void renderComplete(float partial) {
		renderComplete(partial, 10);
	}

	private static void renderComplete(float partial, int passes) {
		Minecraft minecraft = Mc.minecraft();
		for (int pass = 0; pass < passes; pass++) {
			buildAll();
			Platform.renderFrame(partial);
			if (minecraft.world == null || !Platform.hasChunksToBuild()) {
				return;
			}
		}
	}

	private static void buildAll() {
		Minecraft minecraft = Mc.minecraft();
		for (int i = 0; i < 1000 && minecraft.world != null && !Platform.compileChunks(); i++) {
			// compiles a batch per call, true once the queue is empty
		}
	}

	/** Puts the replay at that time (seeking back if needed) and returns the tick it is at. */
	private static long goTo(int replayTime) {
		Minecraft minecraft = Mc.minecraft();
		ReplayHandler replay = ReplayHandler.get();
		if (replayTime < replay.getTime() - 50 || replay.isLossy()) { // a video starts from an exact world
			replay.jumpTo(replayTime);
			// the seek runs at the end of a tick and rebuilds the world from the start
			for (int i = 0; i < 400 && (ReplayHandler.get() == replay || minecraft.world == null || ReplayHandler.get().getTime() < replayTime - 1); i++) {
				minecraft.tick();
			}
		}
		long tick = (long) (ReplayHandler.get().getTime() / 50);
		long target = replayTime / 50;
		while (tick < target) {
			tick++;
			ReplayHandler.get().setTimeFromPath(tick * 50);
			minecraft.tick();
		}
		if (!(minecraft.screen instanceof RenderingScreen)) {
			minecraft.openScreen(new RenderingScreen()); // the seek showed the terrain screen
		}
		return tick;
	}
}
