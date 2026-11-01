package io.github.andrix03.retroreplay;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Console logging. Not log4j: Minecraft only ships it from 1.7 on, and the development
 * environment adds it, so a log4j logger works in dev and crashes real installs.
 */
public final class Log {

	private final String name;

	public Log(String name) {
		this.name = name;
	}

	public void info(String message) {
		print(System.out, "INFO", message, null);
	}

	public void warn(String message) {
		print(System.err, "WARN", message, null);
	}

	public void warn(String message, Throwable error) {
		print(System.err, "WARN", message, error);
	}

	public void error(String message) {
		print(System.err, "ERROR", message, null);
	}

	public void error(String message, Throwable error) {
		print(System.err, "ERROR", message, error);
	}

	private void print(java.io.PrintStream out, String level, String message, Throwable error) {
		String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
		synchronized (out) {
			out.println("[" + time + "] [" + Thread.currentThread().getName() + "/" + level + "] (" + name + ") " + message);
			if (error != null) {
				error.printStackTrace(out);
			}
		}
	}
}
