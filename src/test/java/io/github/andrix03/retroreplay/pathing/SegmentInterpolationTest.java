package io.github.andrix03.retroreplay.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SegmentInterpolationTest {

	private static CameraPath path() {
		CameraPath path = new CameraPath();
		path.putPosition(0, new CameraPath.Position(0, 0, 0, 0, 0, 0));
		path.putPosition(1000, new CameraPath.Position(10, 10, 0, 0, 0, 0));
		path.putPosition(2000, new CameraPath.Position(10, 0, 30, 0, 0, 0));
		path.putPosition(3000, new CameraPath.Position(0, 0, 0, 0, 0, 0));
		return path;
	}

	@Test
	void aSegmentHasItsOwnInterpolation() {
		CameraPath path = path();
		double catmull = path.positionAt(500).y;
		path.setSegmentInterpolation(0, CameraPath.Interpolation.LINEAR);
		assertEquals(5, path.positionAt(500).x, 1e-9); // straight halfway
		assertEquals(5, path.positionAt(500).y, 1e-9);
		assertNotEquals(5, catmull, 1e-6); // the path's Catmull-Rom curves there
		// the next segment keeps the path's interpolation, and keyframes are still met
		CameraPath plain = path();
		assertEquals(plain.positionAt(1500).z, path.positionAt(1500).z, 1e-9);
		assertEquals(10, path.positionAt(1000).x, 1e-9);
	}

	@Test
	void segmentsAreSavedAndFollowTheirKeyframe() {
		CameraPath path = path();
		path.setSegmentInterpolation(1000, CameraPath.Interpolation.CUBIC);
		CameraPath back = new CameraPath();
		PathStore.fromJson(PathStore.toJson(path), back);
		assertEquals(CameraPath.Interpolation.CUBIC, back.segmentInterpolation(1000));
		assertNull(back.segmentInterpolation(0));
		back.movePosition(1000, 1200, back.positions().get(1000L));
		assertEquals(CameraPath.Interpolation.CUBIC, back.segmentInterpolation(1200));
		assertNull(back.segmentInterpolation(1000));
		back.removePosition(1200);
		assertNull(back.segmentInterpolation(1200));
		// a path saved before segments existed still reads
		CameraPath old = new CameraPath();
		PathStore.fromJson("{\"positions\":[[0,1,2,3,0,0,0],[100,4,5,6,0,0,0]],\"times\":[[0,10]]}", old);
		assertEquals(2, old.positions().size());
		assertEquals(0, old.segments().size());
	}
}
