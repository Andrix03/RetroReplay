package io.github.andrix03.retroreplay.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class CameraPathTest {

	private static final double EPS = 1e-9;

	@Test
	void splinePassesThroughKeyframesAndHoldsAtTheEnds() {
		Spline spline = Spline.catmullRom(new long[] { 0, 1000, 3000 }, new double[] { 0, 10, 5 }, 0.5, Double.NaN);
		assertEquals(0, spline.valueAt(0), EPS);
		assertEquals(10, spline.valueAt(1000), EPS);
		assertEquals(5, spline.valueAt(3000), EPS);
		assertEquals(0, spline.valueAt(-50), EPS);
		assertEquals(5, spline.valueAt(9000), EPS);
	}

	@Test
	void splineMatchesReplayStudioPolynomial() {
		// first segment of 0, 10, 5 with alpha 0.5: p0 = p1 = 0, p2 = 10, p3 = 5
		// t0 = 0.5 * (10 - 0) = 5, t1 = 0.5 * (5 - 0) = 2.5
		// a = 0 - 20 + 5 + 2.5 = -12.5, b = 30 - 10 - 2.5 = 17.5, c = 5, d = 0
		Spline spline = Spline.catmullRom(new long[] { 0, 1000, 3000 }, new double[] { 0, 10, 5 }, 0.5, Double.NaN);
		double t = 0.5;
		assertEquals(-12.5 * t * t * t + 17.5 * t * t + 5 * t, spline.valueAt(500), EPS);
	}

	@Test
	void cubicAndLinearPassThroughTheKeyframes() {
		long[] t = { 0, 1000, 2000, 3000 };
		double[] v = { 0, 10, 0, 10 };
		Spline cubic = Spline.cubic(t, v, Double.NaN);
		Spline linear = Spline.linear(t, v, Double.NaN);
		for (int i = 0; i < t.length; i++) {
			assertEquals(v[i], cubic.valueAt(t[i]), EPS);
			assertEquals(v[i], linear.valueAt(t[i]), EPS);
		}
		assertEquals(5, linear.valueAt(500), EPS);
		// symmetric data: the natural spline crosses 5 exactly halfway between the middle keyframes
		assertEquals(5, cubic.valueAt(1500), 1e-6);
		// two keyframes: a natural cubic spline is a straight line
		assertEquals(5, Spline.cubic(new long[] { 0, 1000 }, new double[] { 0, 10 }, Double.NaN).valueAt(500), EPS);
	}

	@Test
	void naturalSplineHasContinuousSlope() {
		Spline cubic = Spline.cubic(new long[] { 0, 1000, 2000, 3000 }, new double[] { 0, 10, 0, 10 }, Double.NaN);
		// equal segment lengths: the slope just before and just after a keyframe match
		double before = cubic.valueAt(1000) - cubic.valueAt(999);
		double after = cubic.valueAt(1001) - cubic.valueAt(1000);
		assertEquals(before, after, 0.01);
	}

	@Test
	void anglesTurnTheShortWay() {
		double[] unwrapped = Spline.unwrap(new double[] { 350, 10, 30 }, 360);
		assertEquals(350, unwrapped[0], EPS);
		assertEquals(370, unwrapped[1], EPS);
		assertEquals(390, unwrapped[2], EPS);
		Spline yaw = Spline.catmullRom(new long[] { 0, 1000 }, new double[] { 350, 10 }, 0.5, 360);
		assertEquals(360, yaw.valueAt(500), EPS); // through north, not back around through 180
	}

	@Test
	void replayTimeIsLinearAndHeldOutside() {
		CameraPath path = new CameraPath();
		path.putTime(1000, 5000);
		path.putTime(3000, 9000);
		assertEquals(5000, path.replayTimeAt(0));
		assertEquals(7000, path.replayTimeAt(2000));
		assertEquals(9000, path.replayTimeAt(5000));
	}

	@Test
	void spectatorSegmentsNeedTheSameEntityAtBothEnds() {
		CameraPath path = new CameraPath();
		path.putPosition(0, new CameraPath.Position(0, 64, 0, 0, 0, 0));
		path.putPosition(1000, new CameraPath.Position(0, 64, 0, 0, 0, 0, 7));
		path.putPosition(3000, new CameraPath.Position(0, 64, 0, 0, 0, 0, 7));
		path.putPosition(4000, new CameraPath.Position(0, 64, 0, 0, 0, 0, 9));
		assertEquals(CameraPath.Position.NO_ENTITY, path.spectatedAt(500)); // free -> spectator: free camera
		assertEquals(7, path.spectatedAt(1000));
		assertEquals(7, path.spectatedAt(2000));
		assertEquals(CameraPath.Position.NO_ENTITY, path.spectatedAt(3500)); // 7 -> 9: not one entity
		assertEquals(9, path.spectatedAt(4000));
		assertEquals(9, path.spectatedAt(9000)); // held after the last keyframe
	}

	@Test
	void timeGoingBackwardsCannotPlay() {
		CameraPath path = new CameraPath();
		path.putPosition(0, new CameraPath.Position(0, 64, 0, 0, 0, 0));
		path.putPosition(2000, new CameraPath.Position(1, 64, 0, 0, 0, 0));
		path.putTime(0, 7000);
		path.putTime(2000, 3000);
		assertNotNull(path.playbackProblem(false));
		assertNull(path.playbackProblem(true)); // Shift ignores time keyframes
	}

	@Test
	void playbackNeedsTwoKeyframesOfEachKind() {
		CameraPath path = new CameraPath();
		path.putPosition(0, new CameraPath.Position(0, 64, 0, 0, 0, 0));
		assertNotNull(path.playbackProblem(false));
		path.putPosition(2000, new CameraPath.Position(10, 64, 0, 90, 0, 0));
		assertNotNull(path.playbackProblem(false));
		assertNull(path.playbackProblem(true));
		path.putTime(0, 0);
		path.putTime(2000, 2000);
		assertNull(path.playbackProblem(false));
		CameraPath.Position middle = path.positionAt(1000);
		assertEquals(5, middle.x, EPS);
		assertEquals(45, middle.yaw, 1e-4);
		assertEquals(0, path.start());
		assertEquals(2000, path.end());
	}
}
