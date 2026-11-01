package io.github.andrix03.retroreplay.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PathPresetsTest {

	@Test
	void saveLoadRenameAndRoundTrip() {
		CameraPath path = new CameraPath();
		path.putPosition(0, new CameraPath.Position(1, 64, 2, 10, 5, 0));
		path.putPosition(3000, new CameraPath.Position(9, 70, 2, 90, 5, 0, 12));
		path.putTime(0, 500);
		path.putTime(3000, 3500);
		path.setInterpolation(CameraPath.Interpolation.LINEAR);
		PathPresets presets = new PathPresets();
		presets.put("Intro \"fly\"", path);
		presets.put("empty", new CameraPath());

		PathPresets loaded = new PathPresets();
		loaded.readJson(presets.toJson());
		assertEquals(presets.toJson(), loaded.toJson());

		CameraPath target = new CameraPath();
		target.putPosition(100, new CameraPath.Position(0, 0, 0, 0, 0, 0));
		assertTrue(loaded.load("Intro \"fly\"", target));
		assertEquals(PathStore.toJson(path), PathStore.toJson(target));
		assertEquals(12, target.positions().get(3000L).spectated);

		loaded.rename("empty", "Outro");
		assertTrue(loaded.all().containsKey("Outro"));
		assertEquals(2, loaded.all().size());
	}
}
