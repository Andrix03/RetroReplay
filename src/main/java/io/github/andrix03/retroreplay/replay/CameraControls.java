package io.github.andrix03.retroreplay.replay;

import java.util.Arrays;

import io.github.andrix03.retroreplay.compat.Platform;
import io.github.andrix03.retroreplay.gui.ReplayOverlay;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.world.dimension.Dimension;

/**
 * Replay Mod's camera speed (mouse wheel) and ambient lighting (Z) for the free camera.
 * The camera flies like a creative player; a faster or slower camera moves that tick's
 * distance times the speed. Ambient lighting sets the world's light-level-to-brightness
 * table to full: the lightmap, rebuilt every tick from it, then lights everything.
 */
public final class CameraControls {

	public static final int MIN_STEP = -12;
	public static final int MAX_STEP = 12;

	private static int speedStep;
	private static boolean ambient;
	/** The world whose brightness table is overwritten, and its original values. */
	private static Dimension brightened;
	private static float[] brightness;
	/** Where the camera was when this tick started (null: not tracked this tick). */
	private static Entity tracked;
	private static double startX, startY, startZ;

	private CameraControls() {
	}

	/** Speed multiplier of a step: doubles every 4 steps, 1/8x to 8x. */
	public static double speed(int step) {
		return Math.pow(2, step / 4.0);
	}

	/** One step per wheel notch, either way, within the range. */
	public static int stepAfterWheel(int step, int wheel) {
		return Math.max(MIN_STEP, Math.min(MAX_STEP, step + Integer.signum(wheel)));
	}

	public static double speed() {
		return speed(speedStep);
	}

	/** Dev self-test only. */
	static void setSpeedStep(int step) {
		speedStep = Math.max(MIN_STEP, Math.min(MAX_STEP, step));
	}

	public static boolean isAmbient() {
		return ambient;
	}

	/** Every replay tick; with no screen open the wheel changes the speed. */
	static void readWheel(boolean apply) {
		int wheel = Platform.wheel();
		if (apply && wheel != 0) {
			speedStep = stepAfterWheel(speedStep, wheel);
			ReplayOverlay.notice("Camera speed: " + format(speed()) + "x");
		}
	}

	static void toggleAmbient() {
		ambient = !ambient;
		ReplayOverlay.notice("Ambient lighting " + (ambient ? "on" : "off"));
	}

	/** Start of a replay tick: remember where the camera is, before the game moves it. */
	static void beforeTick(Minecraft minecraft, boolean freeCamera) {
		tracked = freeCamera && speedStep != 0 ? minecraft.player : null;
		if (tracked != null) {
			startX = tracked.x;
			startY = tracked.y;
			startZ = tracked.z;
		}
		applyLighting(minecraft);
	}

	/** End of a replay tick: stretch the distance the camera flew this tick. */
	static void afterTick(Minecraft minecraft) {
		if (tracked == null || tracked != minecraft.player) {
			return; // the world was rebuilt this tick (a seek): the camera did not fly
		}
		double extra = speed() - 1;
		tracked.setPosition(tracked.x + (tracked.x - startX) * extra, tracked.y + (tracked.y - startY) * extra,
			tracked.z + (tracked.z - startZ) * extra);
		tracked = null;
	}

	private static void applyLighting(Minecraft minecraft) {
		Dimension dimension = minecraft.world == null ? null : minecraft.world.dimension;
		if (ambient && dimension != null && dimension != brightened) {
			brightened = dimension;
			brightness = dimension.brightnessTable.clone();
			Arrays.fill(dimension.brightnessTable, 1.0F);
		} else if (!ambient && brightened != null) {
			if (brightened == dimension) {
				System.arraycopy(brightness, 0, dimension.brightnessTable, 0, brightness.length);
			}
			brightened = null;
			brightness = null;
		}
	}

	private static String format(double speed) {
		return speed >= 1 ? String.format(java.util.Locale.ROOT, "%.1f", speed) : String.format(java.util.Locale.ROOT, "%.2f", speed);
	}
}
