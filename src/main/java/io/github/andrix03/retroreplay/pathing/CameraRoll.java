package io.github.andrix03.retroreplay.pathing;

/**
 * The replay camera's roll in degrees (Minecraft has none): J / L roll while held, K resets,
 * like Replay Mod. Keyframes store it, paths play it back; applied in GameRenderer.transformCamera.
 */
public final class CameraRoll {

	private static final float DEGREES_PER_TICK = 2;

	private static float roll;

	private CameraRoll() {
	}

	public static float get() {
		return roll;
	}

	public static void set(float degrees) {
		roll = degrees;
	}

	/** Every replay tick with no screen open: direction -1 (J), +1 (L) or 0. */
	public static void turn(int direction) {
		roll += direction * DEGREES_PER_TICK;
	}

	public static void reset() {
		roll = 0;
	}
}
