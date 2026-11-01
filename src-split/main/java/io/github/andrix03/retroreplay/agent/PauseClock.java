package io.github.andrix03.retroreplay.agent;

/**
 * Runs inside the local server's JVM. MinecraftServer reads the time through this class
 * instead of System.currentTimeMillis() (see PauseAgent): while paused, the server thread
 * waits here, and afterwards the paused time is left out, so the tick loop does not try
 * to catch up on the ticks it missed. Only JDK classes: it is copied into the agent jar.
 */
public final class PauseClock {

	private static final Object LOCK = new Object();
	private static volatile boolean paused;
	private static volatile long pausedTotal;
	private static long pausedSince;

	private PauseClock() {
	}

	public static long currentTimeMillis() {
		if (paused && "Server thread".equals(Thread.currentThread().getName())) {
			synchronized (LOCK) {
				while (paused) {
					try {
						LOCK.wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		}
		return System.currentTimeMillis() - pausedTotal;
	}

	public static void setPaused(boolean pause) {
		synchronized (LOCK) {
			if (pause == paused) {
				return;
			}
			if (pause) {
				pausedSince = System.currentTimeMillis();
				paused = true;
			} else {
				pausedTotal += System.currentTimeMillis() - pausedSince;
				paused = false;
				LOCK.notifyAll();
			}
		}
	}
}
