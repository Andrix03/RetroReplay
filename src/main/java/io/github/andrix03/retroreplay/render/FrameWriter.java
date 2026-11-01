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
		return settings.preset == RenderSettings.Preset.PNG ? new Png(settings.output) : new Ffmpeg(ffmpeg, settings);
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
