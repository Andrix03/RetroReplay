package io.github.andrix03.retroreplay.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MarkersTest {

	@Test
	void roundTripWithNamesAndQuotes() {
		Markers markers = new Markers();
		markers.put(1500, "");
		markers.put(9000, "the \"big\" fight \\ here");
		Markers loaded = new Markers();
		loaded.readJson(markers.toJson());
		assertEquals(markers.toJson(), loaded.toJson());
		assertEquals("the \"big\" fight \\ here", loaded.all().get(9000));
		assertEquals("", loaded.all().get(1500));
	}

	@Test
	void readsReplayModStyleMarkers() {
		Markers loaded = new Markers();
		loaded.readJson("[{\"realTimestamp\":4200,\"value\":{\"name\":\"Base\"}},{\"realTimestamp\":12000,\"value\":{}}]");
		assertEquals(2, loaded.all().size());
		assertEquals("Base", loaded.all().get(4200));
		assertEquals("", loaded.all().get(12000));
	}
}
