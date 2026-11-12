package io.github.andrix03.retroreplay.replay;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.gui.ReplayOverlay;
import io.github.andrix03.retroreplay.pathing.PathStore;

import net.minecraft.client.Minecraft;

/**
 * Replay Mod's thumbnail (N in a replay): the next frame without the GUI, at most 1280x720,
 * saved in the replay file as the "thumb" entry the way Replay Mod stores it (7 magic bytes,
 * then a JPEG), so the Replay Viewer shows it.
 */
public final class Thumbnail {

	public static final String ENTRY = "thumb";
	private static final byte[] MAGIC = { 0, 1, 1, 2, 3, 5, 8 };
	private static final int MAX_WIDTH = 1280;
	private static final int MAX_HEIGHT = 720;

	private static boolean requested;
	private static boolean capturing;
	private static boolean hideGui;

	private Thumbnail() {
	}

	static void request() {
		requested = true;
	}

	/** Start of a frame: hide the GUI for the requested picture. */
	public static void beforeFrame() {
		if (requested && ReplayHandler.get() != null) {
			requested = false;
			capturing = true;
			Minecraft minecraft = Mc.minecraft();
			hideGui = minecraft.options.hideGui;
			minecraft.options.hideGui = true;
		}
	}

	/** End of that frame: read it back (still in the framebuffer the game drew into) and save it. */
	public static void afterFrame() {
		if (!capturing) {
			return;
		}
		capturing = false;
		Minecraft minecraft = Mc.minecraft();
		minecraft.options.hideGui = hideGui;
		ReplayHandler replay = ReplayHandler.get();
		if (replay == null) {
			return;
		}
		int width = Platform.width();
		int height = Platform.height();
		ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
		GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
		BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = ((height - 1 - y) * width + x) * 4; // GL rows go up
				frame.setRGB(x, y, (pixels.get(i + 2) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | (pixels.get(i) & 0xFF));
			}
		}
		File file = replay.getFile();
		Thread saver = new Thread(() -> save(file, frame), "RetroReplay thumbnail");
		saver.setDaemon(true);
		saver.start();
	}

	private static void save(File file, BufferedImage frame) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bytes.write(MAGIC);
			ImageIO.write(scaled(frame, MAX_WIDTH, MAX_HEIGHT), "jpg", bytes);
			PathStore.saveRawEntries(file, Collections.singletonMap(ENTRY, bytes.toByteArray()));
			ReplayOverlay.notice("Thumbnail saved");
			RetroReplay.LOGGER.info("Thumbnail saved in " + file.getName() + " (" + bytes.size() + " bytes)");
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot save the thumbnail of " + file, e);
			ReplayOverlay.notice("Cannot save the thumbnail (see the log)");
		}
	}

	/** The replay's thumbnail, or null when it has none (or it cannot be read). */
	public static BufferedImage read(ZipFile zip) {
		ZipEntry entry = zip.getEntry(ENTRY);
		if (entry == null) {
			return null;
		}
		try (InputStream in = zip.getInputStream(entry)) {
			byte[] magic = new byte[MAGIC.length];
			int read = 0;
			while (read < magic.length) {
				int n = in.read(magic, read, magic.length - read);
				if (n < 0) {
					return null;
				}
				read += n;
			}
			return java.util.Arrays.equals(magic, MAGIC) ? ImageIO.read(in) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Fitted inside maxWidth x maxHeight, never enlarged. */
	public static BufferedImage scaled(BufferedImage image, int maxWidth, int maxHeight) {
		double scale = Math.min(1, Math.min(maxWidth / (double) image.getWidth(), maxHeight / (double) image.getHeight()));
		if (scale == 1) {
			return image;
		}
		int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, width, height, null);
		g.dispose();
		return out;
	}
}
