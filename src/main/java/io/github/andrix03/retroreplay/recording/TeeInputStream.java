package io.github.andrix03.retroreplay.recording;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Copies every byte read from the connection, so a multiplayer packet is recorded exactly as it
 * arrived (some packets drop their wire data after read() and cannot be written again).
 * Never reads ahead: the connection stream must stay positioned at the next packet.
 */
public final class TeeInputStream extends InputStream {

	private final InputStream in;
	private final ByteArrayOutputStream copy = new ByteArrayOutputStream();

	public TeeInputStream(InputStream in) {
		this.in = in;
	}

	@Override
	public int read() throws IOException {
		int b = in.read();
		if (b >= 0) {
			copy.write(b);
		}
		return b;
	}

	@Override
	public int read(byte[] buffer, int offset, int length) throws IOException {
		int n = in.read(buffer, offset, length);
		if (n > 0) {
			copy.write(buffer, offset, n);
		}
		return n;
	}

	public byte[] bytes() {
		return copy.toByteArray();
	}
}
