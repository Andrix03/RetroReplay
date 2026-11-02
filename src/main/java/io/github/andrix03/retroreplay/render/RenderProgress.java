package io.github.andrix03.retroreplay.render;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.gui.ReplayGui;

/**
 * Replay Mod's render screen: while a video renders, the window shows the last frame (fitted,
 * black bars around it) with the progress over it: frames, percentage, elapsed and remaining
 * time. Drawn after the frame was read, straight to the window, so the video itself only ever
 * holds the world. The frame goes to the screen through a texture made from the pixels already
 * read for the video, so showing it costs no extra conversion.
 */
final class RenderProgress {

	private static final int BAR_HEIGHT = 12;

	private final String name;
	private final long startNanos = System.nanoTime();
	private int texture;
	private int textureWidth;
	private int textureHeight;

	RenderProgress(String name) {
		this.name = name;
	}

	/** Before the first frame: the world is being prepared. */
	void showPreparing() {
		draw(null, 0, 0, "Preparing the world...", 0);
	}

	/** After frame {@code done} of {@code frames} was written; {@code bgra} holds it (rows bottom up). */
	void show(ByteBuffer bgra, int width, int height, int done, int frames) {
		long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
		long remaining = done == 0 ? 0 : elapsed * (frames - done) / done;
		String status = "Frame " + done + " / " + frames + " (" + done * 100 / frames + "%)   Elapsed " + ReplayGui.formatTime(elapsed)
			+ "   Remaining " + ReplayGui.formatTime(remaining);
		bgra.rewind();
		draw(bgra, width, height, status, done / (double) frames);
		if (DEV_CAPTURE && done == frames / 2) {
			captureWindow();
		}
	}

	/** Dev self-test only (RETROREPLAY_AUTORENDER): the window halfway, to run/screenshots/render_progress.png. */
	private static final boolean DEV_CAPTURE = System.getenv("RETROREPLAY_AUTORENDER") != null;

	private static void captureWindow() {
		int width = Platform.width();
		int height = Platform.height();
		ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
		GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, pixels);
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int i = ((height - 1 - y) * width + x) * 4;
				image.setRGB(x, y, (pixels.get(i + 2) & 0xFF) << 16 | (pixels.get(i + 1) & 0xFF) << 8 | (pixels.get(i) & 0xFF));
			}
		}
		try {
			java.io.File folder = new java.io.File(Mc.minecraft().gameDir, "screenshots");
			folder.mkdirs();
			javax.imageio.ImageIO.write(image, "png", new java.io.File(folder, "render_progress.png"));
		} catch (java.io.IOException e) {
			io.github.andrix03.retroreplay.RetroReplay.LOGGER.warn("Cannot save the progress capture", e);
		}
	}

	void delete() {
		if (texture != 0) {
			GL11.glDeleteTextures(texture);
			texture = 0;
		}
	}

	private void draw(ByteBuffer bgra, int width, int height, String status, double fraction) {
		int windowWidth = Platform.width();
		int windowHeight = Platform.height();
		int guiWidth = Mc.scaledWidth();
		int guiHeight = windowHeight * guiWidth / windowWidth;

		GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();
		GL11.glOrtho(0, guiWidth, guiHeight, 0, 1000, 3000);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();
		GL11.glLoadIdentity();
		GL11.glTranslatef(0, 0, -2000);
		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		try {
			if (Platform.hasFramebuffers()) {
				EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, 0); // the window
			}
			GL11.glViewport(0, 0, windowWidth, windowHeight);
			GL11.glClearColor(0, 0, 0, 1);
			GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			GL11.glDisable(GL11.GL_DEPTH_TEST);
			GL11.glDisable(GL11.GL_LIGHTING);
			GL11.glDisable(GL11.GL_FOG);
			GL11.glDisable(GL11.GL_CULL_FACE);
			GL11.glDisable(GL11.GL_ALPHA_TEST);
			GL11.glEnable(GL11.GL_BLEND);
			GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

			int top = 20;
			int bottom = guiHeight - 10 - BAR_HEIGHT - 14;
			if (bgra != null) {
				drawFrame(bgra, width, height, top, bottom, guiWidth);
			}
			ReplayGui.centered("Rendering " + name, guiWidth / 2, 6, ReplayGui.WHITE);
			int barX = 20;
			int barWidth = guiWidth - 40;
			int barY = guiHeight - 10 - BAR_HEIGHT;
			ReplayGui.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + BAR_HEIGHT + 1, 0xFF808080);
			ReplayGui.fill(barX, barY, barX + barWidth, barY + BAR_HEIGHT, 0xFF000000);
			ReplayGui.fill(barX, barY, barX + (int) (barWidth * fraction), barY + BAR_HEIGHT, 0xFF3C8527);
			ReplayGui.centered(status, guiWidth / 2, barY + 2, ReplayGui.WHITE);
			ReplayGui.centered("Esc cancels", guiWidth / 2, barY - 11, ReplayGui.LIGHT_GRAY);
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
			GL11.glMatrixMode(GL11.GL_PROJECTION);
			GL11.glPopMatrix();
			GL11.glMatrixMode(GL11.GL_MODELVIEW);
			GL11.glPopMatrix();
			GL11.glPopAttrib();
		}
	}

	/** The frame fitted between top and bottom, centered; GL rows go up, so the texture is drawn flipped. */
	private void drawFrame(ByteBuffer bgra, int width, int height, int top, int bottom, int guiWidth) {
		if (texture == 0 || textureWidth != width || textureHeight != height) {
			delete();
			texture = GL11.glGenTextures();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, bgra);
			textureWidth = width;
			textureHeight = height;
		} else {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, bgra);
		}
		int areaWidth = guiWidth - 40;
		int areaHeight = bottom - top;
		double scale = Math.min(areaWidth / (double) width, areaHeight / (double) height);
		int w = (int) (width * scale);
		int h = (int) (height * scale);
		int x = (guiWidth - w) / 2;
		int y = top + (areaHeight - h) / 2;
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND); // the frame's alpha is meaningless
		GL11.glColor4f(1, 1, 1, 1);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(0, 1);
		GL11.glVertex2f(x, y);
		GL11.glTexCoord2f(0, 0);
		GL11.glVertex2f(x, y + h);
		GL11.glTexCoord2f(1, 0);
		GL11.glVertex2f(x + w, y + h);
		GL11.glTexCoord2f(1, 1);
		GL11.glVertex2f(x + w, y);
		GL11.glEnd();
		GL11.glEnable(GL11.GL_BLEND);
	}
}
