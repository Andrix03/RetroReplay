package io.github.andrix03.retroreplay.render;

import java.nio.ByteBuffer;

/**
 * Puts the pictures of one video frame together for the render modes that draw the world more
 * than once: two eyes side by side, a cube's six faces in a cross, or the cube's faces turned
 * into a 360 picture. Every buffer is BGRA with the bottom row first, as glReadPixels gives it.
 *
 * Cube faces, in order: front, right, back, left (the camera's yaw plus 0, 90, 180, 270), up and
 * down (pitch -90 and 90), each drawn with a 90 degree field of view.
 */
final class FrameComposer {

	static final int FACES = 6;
	static final float[] FACE_YAW = { 0, 90, 180, 270, 0, 0 };
	static final float[] FACE_PITCH = { 0, 0, 0, 0, -90, 90 };
	/** Where each face goes in the cross: column, and row counted from the bottom. */
	private static final int[][] CROSS = { { 1, 1 }, { 2, 1 }, { 3, 1 }, { 0, 1 }, { 1, 2 }, { 1, 0 } };
	/**
	 * Each face's view direction, right and up in the camera's frame (x right, y up, z forward):
	 * looking up, the picture's top points back; looking down, it points forward.
	 */
	private static final float[][][] BASIS = {
		{ { 0, 0, 1 }, { 1, 0, 0 }, { 0, 1, 0 } },
		{ { 1, 0, 0 }, { 0, 0, -1 }, { 0, 1, 0 } },
		{ { 0, 0, -1 }, { -1, 0, 0 }, { 0, 1, 0 } },
		{ { -1, 0, 0 }, { 0, 0, 1 }, { 0, 1, 0 } },
		{ { 0, 1, 0 }, { 1, 0, 0 }, { 0, 0, -1 } },
		{ { 0, -1, 0 }, { 1, 0, 0 }, { 0, 0, 1 } },
	};

	private FrameComposer() {
	}

	/** One eye's picture ({@code eyeWidth} wide) into its half of the frame: left eye 0, right eye 1. */
	static void stereo(ByteBuffer eye, int index, int eyeWidth, int height, ByteBuffer frame) {
		int frameWidth = eyeWidth * 2;
		byte[] row = new byte[eyeWidth * 4];
		for (int y = 0; y < height; y++) {
			eye.position(y * eyeWidth * 4);
			eye.get(row);
			frame.position((y * frameWidth + index * eyeWidth) * 4);
			frame.put(row);
		}
		eye.clear();
		frame.clear();
	}

	/** A face ({@code size} square) into its place in the 4 x 3 cross. */
	static void cross(ByteBuffer face, int index, int size, ByteBuffer frame) {
		int frameWidth = size * 4;
		int column = CROSS[index][0];
		int rowBase = CROSS[index][1] * size;
		byte[] row = new byte[size * 4];
		for (int y = 0; y < size; y++) {
			face.position(y * size * 4);
			face.get(row);
			frame.position(((rowBase + y) * frameWidth + column * size) * 4);
			frame.put(row);
		}
		face.clear();
		frame.clear();
	}

	/**
	 * For each pixel of a {@code width} x {@code height} 360 picture (bottom row first, the
	 * middle column looking forward, longitude growing to the right): face << 24 | the pixel's
	 * index in that face.
	 */
	static int[] equirectangularTable(int width, int height, int size) {
		int[] table = new int[width * height];
		for (int y = 0; y < height; y++) {
			double latitude = -Math.PI / 2 + (y + 0.5) * Math.PI / height;
			for (int x = 0; x < width; x++) {
				double longitude = -Math.PI + (x + 0.5) * 2 * Math.PI / width;
				double dx = Math.sin(longitude) * Math.cos(latitude);
				double dy = Math.sin(latitude);
				double dz = Math.cos(longitude) * Math.cos(latitude);
				table[y * width + x] = lookup(dx, dy, dz, size);
			}
		}
		return table;
	}

	/** The face a direction falls on and the pixel there (face << 24 | index). */
	static int lookup(double dx, double dy, double dz, int size) {
		int best = 0;
		double most = -2;
		for (int f = 0; f < FACES; f++) {
			float[] forward = BASIS[f][0];
			double along = dx * forward[0] + dy * forward[1] + dz * forward[2];
			if (along > most) {
				most = along;
				best = f;
			}
		}
		float[][] b = BASIS[best];
		double u = (dx * b[1][0] + dy * b[1][1] + dz * b[1][2]) / most; // -1 to 1 at 90 degrees
		double v = (dx * b[2][0] + dy * b[2][1] + dz * b[2][2]) / most;
		int px = Math.min(size - 1, Math.max(0, (int) ((u + 1) / 2 * size)));
		int py = Math.min(size - 1, Math.max(0, (int) ((v + 1) / 2 * size)));
		return best << 24 | py * size + px;
	}

	/** The 360 picture from the six faces through the table. */
	static void equirectangular(ByteBuffer[] faces, int[] table, ByteBuffer frame) {
		for (int i = 0; i < table.length; i++) {
			ByteBuffer face = faces[table[i] >>> 24];
			int at = (table[i] & 0xFFFFFF) * 4;
			frame.putInt(i * 4, face.getInt(at));
		}
	}
}
