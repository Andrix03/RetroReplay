package io.github.andrix03.retroreplay.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

class PathStoreTest {

	@Test
	void jsonRoundTrip() {
		CameraPath path = new CameraPath();
		path.putPosition(0, new CameraPath.Position(1.5, 64.25, -3, 90.5f, -10f, 0));
		path.putPosition(2500, new CameraPath.Position(-7, 70, 12.125, 180f, 30f, 5f));
		path.putTime(0, 1000);
		path.putTime(2500, 9000);
		CameraPath loaded = new CameraPath();
		PathStore.fromJson(PathStore.toJson(path), loaded);
		assertEquals(PathStore.toJson(path), PathStore.toJson(loaded));
		assertEquals(64.25, loaded.positions().get(0L).y, 0);
		assertEquals(9000, (int) loaded.times().get(2500L));
	}

	@Test
	void emptyArraysParse() {
		CameraPath path = new CameraPath();
		path.putTime(100, 200);
		CameraPath loaded = new CameraPath();
		PathStore.fromJson(PathStore.toJson(path), loaded);
		assertTrue(loaded.positions().isEmpty());
		assertEquals(200, (int) loaded.times().get(100L));
	}

	@Test
	void savedInsideTheReplayKeepingTheOtherEntries() throws Exception {
		File replay = Files.createTempFile("path", ".mcpr").toFile();
		try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(replay))) {
			out.putNextEntry(new ZipEntry("metaData.json"));
			out.write("{}".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		CameraPath path = new CameraPath();
		path.putPosition(0, new CameraPath.Position(0, 64, 0, 0, 0, 0));
		PathStore.save(replay, path);
		PathStore.save(replay, path); // replaces, does not duplicate
		try (ZipFile zip = new ZipFile(replay)) {
			assertEquals(2, zip.size());
			assertTrue(zip.getEntry("metaData.json") != null);
		}
		assertEquals(1, PathStore.load(replay).positions().size());
		replay.delete();
	}
}
