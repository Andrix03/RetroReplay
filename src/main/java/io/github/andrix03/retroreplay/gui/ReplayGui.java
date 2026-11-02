package io.github.andrix03.retroreplay.gui;

import org.lwjgl.opengl.GL11;

import io.github.andrix03.retroreplay.compat.Draw;
import io.github.andrix03.retroreplay.compat.Mc;

import net.minecraft.client.gui.GuiElement;

/**
 * Drawing helpers with Replay Mod's own textures (replay_gui.png from Replay Mod, gui.png from
 * jGui), so the screens look like Replay Mod's on newer versions.
 */
public final class ReplayGui {

	public static final String REPLAY_GUI = "replay_gui";
	public static final String JGUI = "jgui";
	public static final String LOGO = "logo";
	public static final String CAMERA_HEAD = "camera_head";
	public static final String WIDGETS = "widgets";

	public static final int WHITE = 0xFFFFFFFF;
	public static final int LIGHT_GRAY = 0xFFC0C0C0;
	public static final int HALF_TRANSPARENT = 0x80000000;

	private static final GuiElement DRAW = new GuiElement() {
	};

	private ReplayGui() {
	}

	/** Binds a texture for drawing, whatever the frame left behind (texturing off or lighting on draws it black). */
	public static void bind(String texture) {
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glColor4f(1, 1, 1, 1);
		Mc.bindTexture(texture);
	}

	/** A part of the bound 256x256 texture at 1:1 scale. */
	public static void texture(int x, int y, int u, int v, int width, int height) {
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		DRAW.drawTexture(x, y, u, v, width, height);
	}

	/** The whole bound texture stretched over a rectangle (used for the logo). */
	public static void stretched(int x, int y, int width, int height) {
		GL11.glEnable(GL11.GL_BLEND);
		Draw.texturedQuad(x, y, width, height);
	}

	/** A picture as a GL texture (linear filtering); the caller deletes it. */
	public static int upload(java.awt.image.BufferedImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb = image.getRGB(x, y);
				pixels.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) 0xFF);
			}
		}
		pixels.flip();
		int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int texture = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
		return texture;
	}

	/**
	 * Draws a texture made by upload(), then binds back what was bound: old versions remember
	 * the last texture they bound and would skip binding it again.
	 */
	public static void drawTexture(int texture, int x, int y, int width, int height) {
		int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL11.glColor4f(1, 1, 1, 1);
		stretched(x, y, width, height);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
	}

	/** jGui's Utils.drawDynamicRect: a 9-slice of the bound texture, edges and middle tiled. */
	public static void dynamicRect(int x, int y, int width, int height, int u, int v, int texW, int texH, int top, int bottom, int left, int right) {
		int midTexW = texW - left - right;
		int midTexH = texH - top - bottom;
		int midW = width - left - right;
		int midH = height - top - bottom;
		texture(x, y, u, v, left, top);
		texture(x + width - right, y, u + texW - right, v, right, top);
		texture(x, y + height - bottom, u, v + texH - bottom, left, bottom);
		texture(x + width - right, y + height - bottom, u + texW - right, v + texH - bottom, right, bottom);
		for (int dx = 0; dx < midW; dx += midTexW) {
			int w = Math.min(midTexW, midW - dx);
			texture(x + left + dx, y, u + left, v, w, top);
			texture(x + left + dx, y + height - bottom, u + left, v + texH - bottom, w, bottom);
			for (int dy = 0; dy < midH; dy += midTexH) {
				texture(x + left + dx, y + top + dy, u + left, v + top, w, Math.min(midTexH, midH - dy));
			}
		}
		for (int dy = 0; dy < midH; dy += midTexH) {
			int h = Math.min(midTexH, midH - dy);
			texture(x, y + top + dy, u, v + top, left, h);
			texture(x + width - right, y + top + dy, u + texW - right, v + top, right, h);
		}
	}

	/** A vanilla button background (widgets.png), the same way ButtonWidget draws it. */
	public static void button(int x, int y, int width, int height, boolean hovered) {
		bind(WIDGETS);
		int v = 46 + (hovered ? 2 : 1) * 20;
		texture(x, y, 0, v, width / 2, height);
		texture(x + width / 2, y, 200 - width / 2, v, width / 2, height);
	}

	/** A button background of any size: the vanilla button 9-sliced (for the 9x9 zoom buttons). */
	public static void smallButton(int x, int y, int width, int height, boolean hovered) {
		bind(WIDGETS);
		dynamicRect(x, y, width, height, 0, 46 + (hovered ? 2 : 1) * 20, 200, 20, 2, 3, 2, 2);
	}

	/** A vanilla slider: disabled-button track plus the 8 px knob. */
	public static void slider(int x, int y, int width, int height, double value, String text, boolean hovered) {
		bind(WIDGETS);
		texture(x, y, 0, 46, width / 2, height);
		texture(x + width / 2, y, 200 - width / 2, 46, width / 2, height);
		int knob = x + (int) (value * (width - 8));
		texture(knob, y, 0, 66, 4, 20);
		texture(knob + 4, y, 196, 66, 4, 20);
		centered(text, x + width / 2, y + (height - 8) / 2, hovered ? 0xFFFFFFA0 : 0xFFE0E0E0);
	}

	/** Like GuiElement.fill (protected and not static before 1.2): a blended ARGB rectangle. */
	public static void fill(int x1, int y1, int x2, int y2, int color) {
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glColor4f((color >> 16 & 0xFF) / 255.0F, (color >> 8 & 0xFF) / 255.0F, (color & 0xFF) / 255.0F, (color >>> 24) / 255.0F);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex2f(x1, y2);
		GL11.glVertex2f(x2, y2);
		GL11.glVertex2f(x2, y1);
		GL11.glVertex2f(x1, y1);
		GL11.glEnd();
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND);
	}

	public static void text(String text, int x, int y, int color) {
		DRAW.drawString(Mc.minecraft().textRenderer, text, x, y, color);
	}

	public static void centered(String text, int x, int y, int color) {
		DRAW.drawCenteredString(Mc.minecraft().textRenderer, text, x, y, color);
	}

	public static int width(String text) {
		return Mc.minecraft().textRenderer.getWidth(text);
	}

	public static String formatTime(long ms) {
		long s = ms / 1000;
		return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
	}
}
