package io.github.andrix03.retroreplay.compat;

import java.io.File;

import org.lwjgl.Sys;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GLContext;

import net.minecraft.client.Minecraft;

/**
 * Keyboard, mouse and window for the shared code. This is the LWJGL 2 one (Minecraft up to 1.12);
 * 1.13 has its own on LWJGL 3 / GLFW with the same names (its key codes differ).
 */
public final class Platform {

	public static final int KEY_ESCAPE = Keyboard.KEY_ESCAPE;
	public static final int KEY_RETURN = Keyboard.KEY_RETURN;
	public static final int KEY_DELETE = Keyboard.KEY_DELETE;
	public static final int KEY_LEFT = Keyboard.KEY_LEFT;
	public static final int KEY_RIGHT = Keyboard.KEY_RIGHT;
	public static final int KEY_LBRACKET = Keyboard.KEY_LBRACKET;
	public static final int KEY_RBRACKET = Keyboard.KEY_RBRACKET;
	public static final int KEY_LCONTROL = Keyboard.KEY_LCONTROL;
	public static final int KEY_RCONTROL = Keyboard.KEY_RCONTROL;
	public static final int KEY_LSHIFT = Keyboard.KEY_LSHIFT;
	public static final int KEY_RSHIFT = Keyboard.KEY_RSHIFT;
	public static final int KEY_B = Keyboard.KEY_B;
	public static final int KEY_C = Keyboard.KEY_C;
	public static final int KEY_H = Keyboard.KEY_H;
	public static final int KEY_I = Keyboard.KEY_I;
	public static final int KEY_J = Keyboard.KEY_J;
	public static final int KEY_K = Keyboard.KEY_K;
	public static final int KEY_L = Keyboard.KEY_L;
	public static final int KEY_M = Keyboard.KEY_M;
	public static final int KEY_N = Keyboard.KEY_N;
	public static final int KEY_O = Keyboard.KEY_O;
	public static final int KEY_P = Keyboard.KEY_P;
	public static final int KEY_V = Keyboard.KEY_V;
	public static final int KEY_X = Keyboard.KEY_X;
	public static final int KEY_Y = Keyboard.KEY_Y;
	public static final int KEY_Z = Keyboard.KEY_Z;
	/** One more than the highest key code, for per-key tables. */
	public static final int KEY_COUNT = 256;

	private Platform() {
	}

	public static boolean isKeyDown(int key) {
		return Keyboard.isKeyDown(key);
	}

	public static boolean isControlDown() {
		return isKeyDown(KEY_LCONTROL) || isKeyDown(KEY_RCONTROL);
	}

	public static void enableRepeatEvents(boolean enable) {
		Keyboard.enableRepeatEvents(enable);
	}

	/** Wheel movement since the last call (positive: away from the user). */
	public static int wheel() {
		return Mouse.getDWheel();
	}

	/** The wheel movement of the mouse event a screen is handling now. */
	public static int eventWheel() {
		return Mouse.getEventDWheel();
	}

	public static boolean isMouseDown(int button) {
		return Mouse.isButtonDown(button);
	}

	public static String getTitle() {
		return Display.getTitle();
	}

	public static void setTitle(String title) {
		Display.setTitle(title);
	}

	public static boolean isCloseRequested() {
		return Display.isCloseRequested();
	}

	/** Shows the frame drawn so far (a render loop runs outside the game's own). */
	public static void updateDisplay() {
		Display.update();
	}

	public static void openFolder(File folder) {
		Sys.openURL("file://" + folder.getAbsolutePath());
	}

	public static boolean hasFramebuffers() {
		return GLContext.getCapabilities().GL_EXT_framebuffer_object;
	}

	/** The size the game draws at, in pixels. */
	public static int width() {
		return Mc.minecraft().width;
	}

	public static int height() {
		return Mc.minecraft().height;
	}

	/** Makes the game size its viewport and projection for this many pixels (a video frame). */
	public static void setDrawSize(int width, int height) {
		Minecraft minecraft = Mc.minecraft();
		minecraft.width = width;
		minecraft.height = height;
	}
}
