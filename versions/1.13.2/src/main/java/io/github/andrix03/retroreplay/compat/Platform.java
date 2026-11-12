package io.github.andrix03.retroreplay.compat;

import java.io.File;

import org.lwjgl.glfw.GLFW;

import io.github.andrix03.retroreplay.mixin.WindowAccessor;
import io.github.andrix03.retroreplay.mixin.WorldRendererAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GLX;
import net.minecraft.client.render.platform.Window;
import net.minecraft.util.Utils;

/**
 * Keyboard, mouse and window for the shared code. This is the 1.13 one: LWJGL 3 / GLFW, so the
 * key codes are GLFW's (the shared code only uses these names). Wheel movement is collected by
 * MouseHandlerMixin, in LWJGL 2's units (120 a notch).
 */
public final class Platform {

	public static final int KEY_ESCAPE = GLFW.GLFW_KEY_ESCAPE;
	public static final int KEY_RETURN = GLFW.GLFW_KEY_ENTER;
	public static final int KEY_DELETE = GLFW.GLFW_KEY_DELETE;
	public static final int KEY_LEFT = GLFW.GLFW_KEY_LEFT;
	public static final int KEY_RIGHT = GLFW.GLFW_KEY_RIGHT;
	public static final int KEY_LBRACKET = GLFW.GLFW_KEY_LEFT_BRACKET;
	public static final int KEY_RBRACKET = GLFW.GLFW_KEY_RIGHT_BRACKET;
	public static final int KEY_LCONTROL = GLFW.GLFW_KEY_LEFT_CONTROL;
	public static final int KEY_RCONTROL = GLFW.GLFW_KEY_RIGHT_CONTROL;
	public static final int KEY_LSHIFT = GLFW.GLFW_KEY_LEFT_SHIFT;
	public static final int KEY_RSHIFT = GLFW.GLFW_KEY_RIGHT_SHIFT;
	public static final int KEY_B = GLFW.GLFW_KEY_B;
	public static final int KEY_C = GLFW.GLFW_KEY_C;
	public static final int KEY_H = GLFW.GLFW_KEY_H;
	public static final int KEY_I = GLFW.GLFW_KEY_I;
	public static final int KEY_J = GLFW.GLFW_KEY_J;
	public static final int KEY_K = GLFW.GLFW_KEY_K;
	public static final int KEY_L = GLFW.GLFW_KEY_L;
	public static final int KEY_M = GLFW.GLFW_KEY_M;
	public static final int KEY_N = GLFW.GLFW_KEY_N;
	public static final int KEY_O = GLFW.GLFW_KEY_O;
	public static final int KEY_P = GLFW.GLFW_KEY_P;
	public static final int KEY_Q = GLFW.GLFW_KEY_Q;
	public static final int KEY_V = GLFW.GLFW_KEY_V;
	public static final int KEY_X = GLFW.GLFW_KEY_X;
	public static final int KEY_Y = GLFW.GLFW_KEY_Y;
	public static final int KEY_Z = GLFW.GLFW_KEY_Z;
	/** One more than the highest key code, for per-key tables. */
	public static final int KEY_COUNT = GLFW.GLFW_KEY_LAST + 1;

	private static double scrolled;
	private static int eventWheel;
	private static String title;

	private Platform() {
	}

	private static long window() {
		return Minecraft.getInstance().window.getWindow();
	}

	public static boolean isKeyDown(int key) {
		return GLFW.glfwGetKey(window(), key) == GLFW.GLFW_PRESS;
	}

	public static boolean isControlDown() {
		return isKeyDown(KEY_LCONTROL) || isKeyDown(KEY_RCONTROL);
	}

	public static void enableRepeatEvents(boolean enable) {
		Minecraft.getInstance().keyboardHandler.setSendRepeatsToGui(enable);
	}

	/** MouseHandlerMixin: every wheel movement, in the game or in a screen. */
	public static void onScroll(double amount) {
		scrolled += amount;
	}

	/** Wheel movement since the last call (positive: away from the user). */
	public static int wheel() {
		int notches = (int) Math.round(scrolled * 120);
		scrolled = 0;
		return notches;
	}

	/** RetroScreen: the wheel movement of the event its handleMouse() is handling now. */
	public static void setEventWheel(int wheel) {
		eventWheel = wheel;
	}

	public static int eventWheel() {
		return eventWheel;
	}

	public static boolean isMouseDown(int button) {
		return GLFW.glfwGetMouseButton(window(), button) == GLFW.GLFW_PRESS;
	}

	/** GLFW cannot read the title back: the one set here, else the game's own. */
	public static String getTitle() {
		return title != null ? title : "Minecraft " + Mc.VERSION;
	}

	public static void setTitle(String newTitle) {
		title = newTitle;
		GLFW.glfwSetWindowTitle(window(), newTitle);
	}

	public static boolean isCloseRequested() {
		return GLFW.glfwWindowShouldClose(window());
	}

	/** Shows the frame drawn so far (a render loop runs outside the game's own). */
	private static final java.nio.FloatBuffer MATRIX = org.lwjgl.BufferUtils.createFloatBuffer(16);

	/** The projection matrix in use (column-major, the buffer is reused). */
	public static java.nio.FloatBuffer projectionMatrix() {
		MATRIX.clear();
		org.lwjgl.opengl.GL11.glGetFloatv(org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX, MATRIX);
		return MATRIX;
	}

	/** The depth buffer of the frame drawn (0-1, bottom row first). */
	public static void readDepth(int width, int height, java.nio.FloatBuffer into) {
		into.clear();
		org.lwjgl.opengl.GL11.glReadPixels(0, 0, width, height, org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT, org.lwjgl.opengl.GL11.GL_FLOAT, into);
	}

	public static void updateDisplay() {
		Minecraft.getInstance().window.updateDisplay(false);
	}

	public static void openFolder(File folder) {
		Utils.getOS().openFile(folder);
	}

	/** The system clipboard's text, through GLFW: 1.13 runs Java's AWT headless. */
	public static void setClipboard(String text) {
		GLFW.glfwSetClipboardString(window(), text);
	}

	/** Null when the clipboard holds no text. */
	public static String getClipboard() {
		return GLFW.glfwGetClipboardString(window());
	}

	public static boolean hasFramebuffers() {
		return GLX.useFbo();
	}

	/** The size the game draws at, in pixels. */
	public static int width() {
		return Minecraft.getInstance().window.getWidth();
	}

	public static int height() {
		return Minecraft.getInstance().window.getHeight();
	}

	/** Draws one frame of the game (world, then the GUI unless it is hidden). */
	public static void renderFrame(float partial) {
		Minecraft.getInstance().gameRenderer.render(partial, System.nanoTime(), true);
	}

	/** Chunks waiting for their mesh: a video frame waits until they are built. */
	public static boolean hasChunksToBuild() {
		return !((WorldRendererAccessor) Minecraft.getInstance().worldRenderer).retroreplay$getDirtyChunks().isEmpty();
	}

	/** Builds chunk meshes for up to 50 ms; true once none are waiting. */
	public static boolean compileChunks() {
		Minecraft.getInstance().worldRenderer.compileChunksUntil(System.nanoTime() + 50_000_000L);
		return !hasChunksToBuild();
	}

	/** Makes the game size its viewport and projection for this many pixels (a video frame). */
	public static void setDrawSize(int width, int height) {
		Window window = Minecraft.getInstance().window;
		((WindowAccessor) (Object) window).retroreplay$setFramebufferWidth(width);
		((WindowAccessor) (Object) window).retroreplay$setFramebufferHeight(height);
	}
}
