package io.github.andrix03.retroreplay.render;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Video settings, with Replay Mod's encoding presets and ffmpeg arguments (RenderSettings):
 * raw BGRA frames go to ffmpeg's stdin, or PNG files are written without ffmpeg.
 */
public final class RenderSettings {

	public enum Preset {
		MP4_CUSTOM("MP4 - Custom Bitrate", "mp4", "-an -c:v libx264 -b:v %BITRATE% -pix_fmt yuv420p"),
		MP4_POTATO("MP4 - Potato Quality", "mp4", "-an -c:v libx264 -preset ultrafast -crf 51 -pix_fmt yuv420p"),
		WEBM_CUSTOM("WebM - Custom Bitrate", "webm", "-an -c:v libvpx -b:v %BITRATE% -pix_fmt yuv420p"),
		MKV_LOSSLESS("MKV - Lossless", "mkv", "-an -c:v libx264 -preset ultrafast -qp 0"),
		PNG("PNG Sequence", "png", null),
		/** Float frames (linear color, and a Z channel with the depth map on), no ffmpeg. */
		EXR("OpenEXR Sequence", "exr", null);

		public final String title;
		public final String extension;
		/** What goes after the input arguments, %BITRATE% replaced (null: PNG, no ffmpeg). */
		public final String arguments;

		Preset(String title, String extension, String arguments) {
			this.title = title;
			this.extension = extension;
			this.arguments = arguments;
		}

		public boolean hasBitrate() {
			return arguments != null && arguments.contains("%BITRATE%");
		}

		public boolean needsFfmpeg() {
			return arguments != null;
		}

		/** yuv420p needs an even width and height. */
		public boolean needsEvenSize() {
			return arguments != null && arguments.contains("yuv420p");
		}

		/** A folder of numbered frames instead of one video file. */
		public boolean isSequence() {
			return arguments == null;
		}
	}

	/** Replay Mod's render methods. */
	public enum Mode {
		DEFAULT("Default"),
		/** Both eyes side by side, each half the width. */
		STEREOSCOPIC("Stereoscopic"),
		/** The six faces of a cube around the camera, in a cross 4 faces wide and 3 high. */
		CUBIC("Cubic"),
		/** A 360 picture twice as wide as high, made from the cube's faces. */
		EQUIRECTANGULAR("360 Equirectangular");

		public final String title;

		Mode(String title) {
			this.title = title;
		}

		public boolean isPanoramic() {
			return this == CUBIC || this == EQUIRECTANGULAR;
		}
	}

	/** Chroma key colors offered, like Replay Mod's color picker presets (0xRRGGBB). */
	public static final int[] CHROMA_COLORS = { 0x00FF00, 0x0000FF, 0xFF00FF };

	public static final int[] FRAME_RATES = { 24, 25, 30, 50, 60, 120 };
	/** kbps */
	public static final int[] BITRATES = { 5_000, 10_000, 20_000, 50_000, 100_000 };

	public Preset preset = Preset.MP4_CUSTOM;
	public int fps = 60;
	public int bitrateKbps = 10_000;
	public File output;
	public int width;
	public int height;
	/** Replay Mod's "Render Nametags" (players' names above their heads); on by default like there. */
	public boolean nametags = true;
	/** Replay Mod's editable command line arguments: replaces the preset's when set. */
	public String customArguments;
	public Mode mode = Mode.DEFAULT;
	/**
	 * 360 and cubic: the cube turns with the camera's yaw (off: always faces south, Replay Mod's
	 * yaw stabilisation). Pitch and roll are always level there, so the horizon stays straight.
	 */
	public boolean followYaw = true;
	/** Sky and clouds replaced by this color (0xRRGGBB), or -1 for none. */
	public int chromaKey = -1;
	/** OpenEXR: a Z channel with each pixel's distance from the camera in blocks. */
	public boolean depthMap;
	/** Replay Mod's high performance mode: no waiting for every chunk to be built, fewer previews. */
	public boolean highPerformance;

	/** The output arguments in use: the custom ones, else the preset's. */
	public String arguments() {
		return customArguments != null && !customArguments.trim().isEmpty() ? customArguments.trim() : preset.arguments;
	}

	/** yuv420p (most presets) needs an even width and height. */
	public boolean needsEvenSize() {
		return preset.needsFfmpeg() && arguments().contains("yuv420p");
	}

	/** ffmpeg arguments after the executable, like Replay Mod: rawvideo bgra from stdin, flipped. */
	public List<String> ffmpegArguments() {
		List<String> args = new ArrayList<>();
		for (String arg : ("-y -f rawvideo -pix_fmt bgra -s " + width + "x" + height + " -r " + fps + " -i - -vf vflip").split(" ")) {
			args.add(arg);
		}
		for (String arg : arguments().replace("%BITRATE%", bitrateKbps + "K").split(" +")) {
			args.add(arg);
		}
		args.add(output.getAbsolutePath());
		return args;
	}
}
