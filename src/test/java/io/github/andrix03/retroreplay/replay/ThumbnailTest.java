package io.github.andrix03.retroreplay.replay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.github.andrix03.retroreplay.pathing.PathStore;

class ThumbnailTest {

	@Test
	void picturesAreFittedNeverEnlarged() {
		BufferedImage wide = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
		BufferedImage fitted = Thumbnail.scaled(wide, 1280, 720);
		assertEquals(1280, fitted.getWidth());
		assertEquals(720, fitted.getHeight());
		BufferedImage tall = Thumbnail.scaled(new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB), 1280, 720);
		assertEquals(720, tall.getWidth());
		BufferedImage small = new BufferedImage(854, 480, BufferedImage.TYPE_INT_RGB);
		assertSame(small, Thumbnail.scaled(small, 1280, 720));
	}

	@Test
	void theThumbEntryIsReplayModsMagicBytesThenAJpeg() throws Exception {
		File replay = File.createTempFile("thumbtest", ".mcpr");
		try {
			try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(replay))) {
				out.putNextEntry(new ZipEntry("metaData.json"));
				out.write("{}".getBytes("UTF-8"));
				out.closeEntry();
			}
			try (ZipFile zip = new ZipFile(replay)) {
				assertNull(Thumbnail.read(zip), "no thumbnail yet");
			}
			BufferedImage red = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < 36; y++) {
				for (int x = 0; x < 64; x++) {
					red.setRGB(x, y, 0xFF0000);
				}
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bytes.write(new byte[] { 0, 1, 1, 2, 3, 5, 8 });
			ImageIO.write(red, "jpg", bytes);
			PathStore.saveRawEntries(replay, Collections.singletonMap(Thumbnail.ENTRY, bytes.toByteArray()));
			try (ZipFile zip = new ZipFile(replay)) {
				assertNotNull(zip.getEntry("metaData.json"), "the other entries stay");
				byte[] magic = new byte[7];
				try (InputStream in = zip.getInputStream(zip.getEntry(Thumbnail.ENTRY))) {
					assertEquals(7, in.read(magic));
				}
				assertArrayEquals(new byte[] { 0, 1, 1, 2, 3, 5, 8 }, magic);
				BufferedImage back = Thumbnail.read(zip);
				assertEquals(64, back.getWidth());
				assertEquals(0xFF, back.getRGB(32, 18) >> 16 & 0xFF, 1);
			}
		} finally {
			replay.delete();
		}
	}
}
