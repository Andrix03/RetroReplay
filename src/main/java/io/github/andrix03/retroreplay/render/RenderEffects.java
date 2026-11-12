package io.github.andrix03.retroreplay.render;

import java.nio.FloatBuffer;

import io.github.andrix03.retroreplay.compat.Platform;

/**
 * What a video's render mode changes in the game's own drawing, read by the version mixins: a
 * forced field of view (90 for a cube's faces), the sky replaced by a chroma key color, and the
 * world's projection kept for the depth map. All off outside a render.
 */
public final class RenderEffects {

	/** Degrees, or 0 for the game's own. */
	private static float fov;
	/** Red, green, blue (0-1) of the chroma key, or null. */
	private static float[] chroma;
	private static boolean keepProjection;
	private static float near = 0.05F;
	private static float far = 256;
	/** The world's depth buffer, read before the game clears it for the hand (null when not wanted). */
	private static FloatBuffer depth;

	private RenderEffects() {
	}

	public static boolean hasFov() {
		return fov > 0;
	}

	public static float fov() {
		return fov;
	}

	static void setFov(float degrees) {
		fov = degrees;
	}

	public static float[] chroma() {
		return chroma;
	}

	static void setChroma(int rgb) {
		chroma = rgb < 0 ? null : new float[] { (rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F };
	}

	static void setKeepProjection(boolean keep) {
		keepProjection = keep;
	}

	/** The depth buffer read at the last worldDrawn(), sized like the frame drawn then. */
	static FloatBuffer depth() {
		return depth;
	}

	/** After the world was drawn, before the hand: its near and far planes, for the depth map. */
	public static void worldDrawn() {
		if (!keepProjection) {
			return;
		}
		FloatBuffer matrix = Platform.projectionMatrix();
		float m10 = matrix.get(10);
		float m14 = matrix.get(14);
		near = m14 / (m10 - 1);
		far = m14 / (m10 + 1);
		int width = Platform.width();
		int height = Platform.height();
		if (depth == null || depth.capacity() != width * height) {
			depth = org.lwjgl.BufferUtils.createFloatBuffer(width * height);
		}
		Platform.readDepth(width, height, depth);
	}

	/** A depth buffer value (0-1) as the distance from the camera in blocks. */
	static float distance(float depth) {
		float ndc = depth * 2 - 1;
		return 2 * near * far / (far + near - ndc * (far - near));
	}

	static void reset() {
		fov = 0;
		chroma = null;
		keepProjection = false;
		depth = null;
	}
}
