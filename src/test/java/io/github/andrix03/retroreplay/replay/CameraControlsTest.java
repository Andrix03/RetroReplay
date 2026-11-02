package io.github.andrix03.retroreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CameraControlsTest {

	@Test
	void theWheelStepsTheSpeedBetweenAnEighthAndEightTimes() {
		assertEquals(1.0, CameraControls.speed(0), 1e-9);
		assertEquals(2.0, CameraControls.speed(4), 1e-9);
		assertEquals(0.125, CameraControls.speed(CameraControls.MIN_STEP), 1e-9);
		assertEquals(8.0, CameraControls.speed(CameraControls.MAX_STEP), 1e-9);
		assertEquals(1, CameraControls.stepAfterWheel(0, 120), "one notch is one step, whatever the delta");
		assertEquals(-1, CameraControls.stepAfterWheel(0, -360));
		assertEquals(CameraControls.MAX_STEP, CameraControls.stepAfterWheel(CameraControls.MAX_STEP, 120));
		assertEquals(CameraControls.MIN_STEP, CameraControls.stepAfterWheel(CameraControls.MIN_STEP, -120));
	}
}
