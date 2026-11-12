package io.github.andrix03.retroreplay.render;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import io.github.andrix03.retroreplay.RetroReplay;

/** Where the captured frames go: ffmpeg's stdin or PNG files. Frames are BGRA, bottom row first. */
public abstract class FrameWriter {

	public abstract void write(ByteBuffer bgra, int width, int height) throws IOException;

	/** Finishes the video; false when it failed. */
	public abstract boolean close();

	public static FrameWriter create(RenderSettings settings, File ffmpeg) throws IOException {
		if (settings.preset == RenderSettings.Preset.EXR) {
			return new Exr(settings.output);
		}
		return settings.preset == RenderSettings.Preset.PNG ? new Png(settings.output) : new Ffmpeg(ffmpeg, settings);
	}

	/** The next frame's distances from the camera in blocks (bottom row first); only OpenEXR keeps them. */
	public void setDepth(float[] distances) {
	}

	/** Replay Mod's FFmpegWriter: one process, frames piped in, its output kept in export.log. */
	static final class Ffmpeg extends FrameWriter {
		private final Process process;
		private final OutputStream input;
		private final Thread logger;
		private final byte[] row;

		Ffmpeg(File executable, RenderSettings settings) throws IOException {
			List<String> command = new ArrayList<>();
			command.add(executable.getPath());
			command.addAll(settings.ffmpegArguments());
			RetroReplay.LOGGER.info("Starting " + String.join(" ", command));
			File log = new File(settings.output.getParentFile(), "export.log");
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			process = builder.start();
			input = process.getOutputStream();
			row = new byte[settings.width * 4];
			InputStream output = process.getInputStream();
			logger = new Thread(() -> {
				try (OutputStream out = new FileOutputStream(log)) {
					byte[] buffer = new byte[8192];
					int n;
					while ((n = output.read(buffer)) > 0) {
						out.write(buffer, 0, n);
					}
				} catch (IOException e) {
					// ffmpeg ended
				}
			}, "RetroReplay ffmpeg log");
			logger.setDaemon(true);
			logger.start();
		}

		@Override
		public void write(ByteBuffer bgra, int width, int height) throws IOException {
			bgra.rewind();
			for (int y = 0; y < height; y++) {
				bgra.get(row, 0, width * 4);
				input.write(row, 0, width * 4);
			}
		}

		@Override
		public boolean close() {
			try {
				input.close();
				int exit = process.waitFor();
				logger.join(5000);
				return exit == 0;
			} catch (IOException | InterruptedException e) {
				RetroReplay.LOGGER.error("ffmpeg did not finish", e);
				process.destroy();
				return false;
			}
		}
	}

	/** Replay Mod's PNGWriter: a folder of numbered frames, no ffmpeg needed. */
	static final class Png extends FrameWriter {
		private final File folder;
		private int frame;

		Png(File folder) throws IOException {
			this.folder = folder;
			if (!folder.isDirectory() && !folder.mkdirs()) {
				throw new IOException("Cannot create " + folder);
			}
		}

		@Override
		public void write(ByteBuffer bgra, int width, int height) throws IOException {
			BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			int[] pixels = new int[width * height];
			for (int y = 0; y < height; y++) {
				int target = (height - 1 - y) * width; // GL rows start at the bottom
				for (int x = 0; x < width; x++) {
					int i = (y * width + x) * 4;
					pixels[target + x] = (bgra.get(i + 2) & 0xFF) << 16 | (bgra.get(i + 1) & 0xFF) << 8 | bgra.get(i) & 0xFF;
				}
			}
			image.setRGB(0, 0, width, height, pixels, 0, width);
			ImageIO.write(image, "png", new File(folder, String.format(Locale.ROOT, "%06d.png", frame++)));
		}

		@Override
		public boolean close() {
			return true;
		}
	}

	/**
	 * Replay Mod's OpenEXR export: a folder of numbered .exr frames, uncompressed scanlines of
	 * 32-bit float channels B, G, R (linear light) and, with the depth map, Z in blocks.
	 */
	static final class Exr extends FrameWriter {
		private final File folder;
		private int frame;
		private float[] depth;
		private static final float[] LINEAR = new float[256];

		static {
			for (int i = 0; i < 256; i++) {
				double c = i / 255.0;
				LINEAR[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
			}
		}

		Exr(File folder) throws IOException {
			this.folder = folder;
			if (!folder.isDirectory() && !folder.mkdirs()) {
				throw new IOException("Cannot create " + folder);
			}
		}

		@Override
		public void setDepth(float[] distances) {
			depth = distances;
		}

		@Override
		public void write(ByteBuffer bgra, int width, int height) throws IOException {
			File file = new File(folder, String.format(Locale.ROOT, "%06d.exr", frame++));
			try (java.io.OutputStream out = new java.io.BufferedOutputStream(new java.io.FileOutputStream(file), 1 << 16)) {
				write(out, bgra, depth, width, height);
			}
			depth = null;
		}

		/** One frame as an .exr file (also used by the tests). */
		static void write(java.io.OutputStream out, ByteBuffer bgra, float[] depth, int width, int height) throws IOException {
			String[] channels = depth != null ? new String[] { "B", "G", "R", "Z" } : new String[] { "B", "G", "R" };
			java.io.ByteArrayOutputStream header = new java.io.ByteArrayOutputStream();
			Le h = new Le(header);
			h.int32(20000630); // magic
			h.int32(2); // version 2, single part scanline
			java.io.ByteArrayOutputStream list = new java.io.ByteArrayOutputStream();
			Le l = new Le(list);
			for (String channel : channels) {
				l.text(channel);
				l.int32(2); // FLOAT
				l.int32(0); // pLinear + reserved
				l.int32(1); // x sampling
				l.int32(1); // y sampling
			}
			list.write(0);
			h.attribute("channels", "chlist", list.toByteArray());
			h.attribute("compression", "compression", new byte[] { 0 });
			byte[] box = box(width, height);
			h.attribute("dataWindow", "box2i", box);
			h.attribute("displayWindow", "box2i", box);
			h.attribute("lineOrder", "lineOrder", new byte[] { 0 });
			h.attribute("pixelAspectRatio", "float", le(Float.floatToIntBits(1)));
			h.attribute("screenWindowCenter", "v2f", new byte[8]);
			h.attribute("screenWindowWidth", "float", le(Float.floatToIntBits(1)));
			header.write(0);
			byte[] head = header.toByteArray();
			int lineBytes = width * channels.length * 4;
			int block = 8 + lineBytes;
			Le o = new Le(out);
			out.write(head);
			long first = head.length + 8L * height;
			for (int y = 0; y < height; y++) {
				o.int64(first + (long) y * block);
			}
			byte[] line = new byte[lineBytes];
			for (int y = 0; y < height; y++) {
				int row = height - 1 - y; // EXR goes top down, GL bottom up
				o.int32(y);
				o.int32(lineBytes);
				int at = 0;
				for (int c = 0; c < channels.length; c++) {
					for (int x = 0; x < width; x++) {
						int i = row * width + x;
						float value = c < 3 ? LINEAR[bgra.get(i * 4 + c) & 0xFF] : depth[i]; // B, G, R are BGRA's first three
						int bits = Float.floatToIntBits(value);
						line[at++] = (byte) bits;
						line[at++] = (byte) (bits >> 8);
						line[at++] = (byte) (bits >> 16);
						line[at++] = (byte) (bits >> 24);
					}
				}
				out.write(line);
			}
		}

		private static byte[] box(int width, int height) {
			byte[] out = new byte[16];
			System.arraycopy(le(width - 1), 0, out, 8, 4);
			System.arraycopy(le(height - 1), 0, out, 12, 4);
			return out;
		}

		private static byte[] le(int value) {
			return new byte[] { (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24) };
		}

		/** Little-endian writes, as EXR wants them. */
		private static final class Le {
			private final java.io.OutputStream out;

			Le(java.io.OutputStream out) {
				this.out = out;
			}

			void int32(int v) throws IOException {
				out.write(le(v));
			}

			void int64(long v) throws IOException {
				int32((int) v);
				int32((int) (v >>> 32));
			}

			void text(String text) throws IOException {
				out.write(text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
				out.write(0);
			}

			void attribute(String name, String type, byte[] value) throws IOException {
				text(name);
				text(type);
				int32(value.length);
				out.write(value);
			}
		}

		@Override
		public boolean close() {
			return true;
		}
	}

	/** ffmpeg from the settings, the PATH, or .minecraft/ffmpeg; null when there is none. */
	public static File findFfmpeg(File gameDir, String configured) {
		List<File> candidates = new ArrayList<>();
		if (configured != null && !configured.isEmpty()) {
			candidates.add(new File(configured));
		}
		boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
		String name = windows ? "ffmpeg.exe" : "ffmpeg";
		candidates.add(new File(gameDir, "ffmpeg/bin/" + name));
		candidates.add(new File(gameDir, "ffmpeg/" + name));
		String path = System.getenv("PATH");
		if (path != null) {
			for (String dir : path.split(File.pathSeparator)) {
				candidates.add(new File(dir, name));
			}
		}
		if (!windows) {
			candidates.add(new File("/usr/local/bin/ffmpeg"));
			candidates.add(new File("/opt/homebrew/bin/ffmpeg"));
		}
		for (File candidate : candidates) {
			if (candidate.isFile() && runs(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean runs(File executable) {
		try {
			Process process = new ProcessBuilder(executable.getPath(), "-version").redirectErrorStream(true).start();
			try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
				String first = reader.readLine();
				while (reader.readLine() != null) {
					// drain
				}
				return process.waitFor() == 0 && first != null && first.startsWith("ffmpeg");
			}
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}
}
