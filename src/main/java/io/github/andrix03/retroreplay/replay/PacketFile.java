package io.github.andrix03.retroreplay.replay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * A replay's packets on disk instead of in memory: recording.tmcpr is unpacked once into a temp
 * file and read front to back (a replay only moves forward; a seek back starts a new reader).
 * Only the packet times stay in memory, 4 bytes each: an hour is millions of packets, more than
 * the heap old versions run with.
 */
final class PacketFile {

	private final File file;
	private final int[] times;
	private PacketFile(File file, int[] times) {
		this.file = file;
		this.times = times;
	}

	/** Sees each packet once while the recording is unpacked. */
	interface Visitor {
		void packet(int index, byte[] data, int length);
	}

	static PacketFile unpack(InputStream recording) throws IOException {
		return unpack(recording, null);
	}

	/** Copies the recording out of the zip, noting each packet's time on the way. */
	static PacketFile unpack(InputStream recording, Visitor visitor) throws IOException {
		File temp = File.createTempFile("retroreplay", ".tmcpr");
		temp.deleteOnExit();
		int[] times = new int[1 << 16];
		int count = 0;
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(recording, 1 << 16));
			DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp), 1 << 16))) {
			byte[] buffer = new byte[1 << 16];
			while (true) {
				int time;
				try {
					time = in.readInt();
				} catch (EOFException end) {
					break;
				}
				int length = in.readInt();
				if (buffer.length < length) {
					buffer = new byte[length];
				}
				in.readFully(buffer, 0, length);
				out.writeInt(time);
				out.writeInt(length);
				out.write(buffer, 0, length);
				if (visitor != null) {
					visitor.packet(count, buffer, length);
				}
				if (count == times.length) {
					times = Arrays.copyOf(times, count * 2);
				}
				times[count++] = time;
			}
		} catch (IOException e) {
			temp.delete();
			throw e;
		}
		return new PacketFile(temp, Arrays.copyOf(times, count));
	}

	int[] times() {
		return times;
	}

	/** Reads the packets in order from the first one. */
	Reader reader() throws IOException {
		return new Reader(new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16)));
	}

	void delete() {
		if (!file.delete()) {
			file.deleteOnExit();
		}
	}

	static final class Reader implements Closeable {
		private final DataInputStream in;

		private Reader(DataInputStream in) {
			this.in = in;
		}

		/** The next packet (id byte + payload). */
		byte[] next() throws IOException {
			in.readInt(); // time, known from times()
			byte[] data = new byte[in.readInt()];
			in.readFully(data);
			return data;
		}

		/** Passes over the next packet without reading it. */
		void skip() throws IOException {
			in.readInt();
			int left = in.readInt();
			while (left > 0) {
				int skipped = in.skipBytes(left);
				if (skipped <= 0) {
					throw new EOFException();
				}
				left -= skipped;
			}
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
