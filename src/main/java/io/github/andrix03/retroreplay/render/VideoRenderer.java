package io.github.andrix03.retroreplay.render;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.gui.RenderingScreen;
import io.github.andrix03.retroreplay.mixin.WorldRendererAccessor;
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

	private static RenderSettings pending;
	private static boolean rendering;
	private static volatile boolean cancelled;
	private static String result;

	private VideoRenderer() {
	}

	public static void schedule(RenderSettings settings) {
		pending = settings;
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
		RenderSettings settings = pending;
		pending = null;
		try {
			result = run(settings);
		} catch (Exception e) {
			RetroReplay.LOGGER.error("Rendering failed", e);
			result = "Rendering failed: " + e;
		}
		RetroReplay.LOGGER.info(result);
		return true;
	}

	private static String run(RenderSettings settings) throws IOException {
		Minecraft minecraft = Mc.minecraft();
		ReplayHandler replay = ReplayHandler.get();
		if (replay == null) {
			return "No replay is open";
		}
		CameraPath path = PathingState.of(replay.getFile()).path;
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
		int width = settings.width > 0 ? settings.width : minecraft.width;
		int height = settings.height > 0 ? settings.height : minecraft.height;
		OffscreenTarget target = null;
		if (width != minecraft.width || height != minecraft.height) {
			target = OffscreenTarget.create(width, height);
			if (target == null) {
				RetroReplay.LOGGER.warn("No off-screen rendering at " + width + "x" + height + ", using the window size");
				width = minecraft.width;
				height = minecraft.height;
			}
		}
		if (settings.preset.needsEvenSize()) {
			width &= ~1;
			height &= ~1;
		}
		settings.width = width;
		settings.height = height;
		File folder = settings.output.getParentFile();
		if (!folder.isDirectory() && !folder.mkdirs()) {
			return "Cannot create " + folder;
		}

		PathPlayer.stop();
		boolean hideGui = minecraft.options.hideGui;
		String title = Display.getTitle();
		rendering = true;
		cancelled = false;
		minecraft.options.hideGui = true;
		minecraft.openScreen(new RenderingScreen());
		FrameWriter writer = FrameWriter.create(settings, ffmpeg);
		boolean ok = false;
		int frame = 0;
		long start = path.start();
		long end = path.end();
		int frames = (int) ((end - start) * settings.fps / 1000) + 1;
		try {
			ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
			// two ticks before the first frame: sky and fog colors are only updated by ticks
			long tick = goTo(Math.max(0, path.replayTimeAt(start) - 100));
			// a few frames drawn and thrown away: the first ones after the world rebuilt are not clean
			for (int i = 0; i < 5; i++) {
				PathPlayer.applyCamera(path, start);
				begin(target);
				renderComplete(0);
				end(target);
				Display.update();
			}
			for (; frame < frames && !cancelled && !Display.isCloseRequested(); frame++) {
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
				begin(target);
				renderComplete(partial);
				pixels.clear();
				GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
				end(target);
				writer.write(pixels, width, height);
				Display.setTitle("Rendering " + (frame + 1) + " / " + frames + " (Esc cancels)");
				Display.update();
			}
			ok = !cancelled && frame == frames;
		} finally {
			if (target != null) {
				target.delete();
			}
			boolean closed = writer.close();
			ok &= closed;
			Display.setTitle(title);
			minecraft.options.hideGui = hideGui;
			Spectator.clear();
			rendering = false;
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

	private static void renderComplete(float partial) {
		Minecraft minecraft = Mc.minecraft();
		List<?> dirty = ((WorldRendererAccessor) minecraft.worldRenderer).retroreplay$getDirtyChunks();
		for (int pass = 0; pass < 10; pass++) {
			buildAll();
			minecraft.gameRenderer.render(partial);
			if (minecraft.world == null || dirty.isEmpty()) {
				return;
			}
		}
	}

	private static void buildAll() {
		Minecraft minecraft = Mc.minecraft();
		for (int i = 0; i < 1000 && minecraft.world != null && !minecraft.worldRenderer.compileChunks(minecraft.player, false); i++) {
			// compiles a batch per call, true once the queue is empty
		}
	}

	/** Puts the replay at that time (seeking back if needed) and returns the tick it is at. */
	private static long goTo(int replayTime) {
		Minecraft minecraft = Mc.minecraft();
		ReplayHandler replay = ReplayHandler.get();
		if (replayTime < replay.getTime() - 50) {
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
