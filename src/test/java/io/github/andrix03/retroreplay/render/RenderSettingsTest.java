package io.github.andrix03.retroreplay.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;

class RenderSettingsTest {

	@Test
	void ffmpegArgumentsMatchReplayMod() {
		RenderSettings settings = new RenderSettings();
		settings.preset = RenderSettings.Preset.MP4_CUSTOM;
		settings.width = 1920;
		settings.height = 1080;
		settings.fps = 60;
		settings.bitrateKbps = 10_000;
		settings.output = new File("out.mp4");
		String args = String.join(" ", settings.ffmpegArguments());
		assertEquals("-y -f rawvideo -pix_fmt bgra -s 1920x1080 -r 60 -i - -vf vflip -an -c:v libx264 -b:v 10000K -pix_fmt yuv420p "
			+ new File("out.mp4").getAbsolutePath(), args);
	}

	@Test
	void customArgumentsReplaceThePresetsButKeepTheInput() {
		RenderSettings settings = new RenderSettings();
		settings.width = 1280;
		settings.height = 720;
		settings.fps = 30;
		settings.bitrateKbps = 5_000;
		settings.output = new File("out.mp4");
		settings.customArguments = "  -an  -c:v libx264 -crf 18 -b:v %BITRATE% -pix_fmt yuv444p ";
		assertEquals("-y -f rawvideo -pix_fmt bgra -s 1280x720 -r 30 -i - -vf vflip -an -c:v libx264 -crf 18 -b:v 5000K -pix_fmt yuv444p "
			+ new File("out.mp4").getAbsolutePath(), String.join(" ", settings.ffmpegArguments()));
		assertFalse(settings.needsEvenSize(), "yuv444p takes any size");
		settings.customArguments = " ";
		assertTrue(settings.needsEvenSize(), "blank: back to the preset");
		assertTrue(settings.nametags, "on by default like Replay Mod");
	}

	@Test
	void presetsKnowWhatTheyNeed() {
		assertTrue(RenderSettings.Preset.MP4_CUSTOM.hasBitrate());
		assertFalse(RenderSettings.Preset.MKV_LOSSLESS.hasBitrate());
		assertFalse(RenderSettings.Preset.PNG.needsFfmpeg());
		assertTrue(RenderSettings.Preset.WEBM_CUSTOM.needsEvenSize());
		assertFalse(RenderSettings.Preset.MKV_LOSSLESS.needsEvenSize());
	}
}
