package io.github.andrix03.retroreplay.replay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.Markers;
import io.github.andrix03.retroreplay.pathing.PathStore;

/**
 * Replay Mod's Replay Editor, on files: a new replay is written, the original stays.
 * Trim keeps a part: the packets before it keep their effect (they move to its start, where the
 * replay takes them all at once, like Replay Mod's squash), the ones after it are dropped. Cut
 * takes a part out the same way (its packets move to where it was), Split makes two replays at a
 * moment. Connect (not in Replay Mod) puts a second replay of the same Minecraft version after
 * the first; its world starts with its own login. Markers and time keyframes move along.
 */
public final class ReplayEditor {

	private static final String RECORDING = "recording.tmcpr";
	private static final String META = "metaData.json";
	private static final Pattern DURATION = Pattern.compile("\"duration\"\\s*:\\s*(\\d+)");
	private static final Pattern VERSION = Pattern.compile("\"mcversion\"\\s*:\\s*\"([^\"]*)\"");

	private ReplayEditor() {
	}

	/** The replay's duration in ms from its metadata. */
	public static int duration(File replay) throws IOException {
		return number(DURATION, read(replay, META));
	}

	/** Keeps {@code start} to {@code end} ms of {@code in} as {@code out}. */
	public static void trim(File in, File out, int start, int end) throws IOException {
		if (start < 0 || end <= start) {
			throw new IOException("The end must come after the start");
		}
		try (ZipFile zip = new ZipFile(in); ZipOutputStream target = open(out)) {
			target.putNextEntry(new ZipEntry(RECORDING));
			DataOutputStream packets = new DataOutputStream(target);
			int last = 0;
			try (DataInputStream source = packets(zip)) {
				while (true) {
					int time;
					try {
						time = source.readInt();
					} catch (EOFException done) {
						break;
					}
					byte[] data = new byte[source.readInt()];
					source.readFully(data);
					if (time > end) {
						break; // packets are in time order
					}
					last = Math.max(0, time - start);
					packets.writeInt(last);
					packets.writeInt(data.length);
					packets.write(data);
				}
			}
			packets.flush();
			target.closeEntry();
			int length = end - start;
			copyRest(zip, target, last, time -> time < start || time > end ? -1 : time - start,
				time -> Math.max(0, Math.min(length, time - start)));
		}
	}

	/** Takes {@code from} to {@code to} ms out of {@code in}: what happened there still counts, at {@code from}. */
	public static void cut(File in, File out, int from, int to) throws IOException {
		if (from < 0 || to <= from) {
			throw new IOException("The end must come after the start");
		}
		int removed = to - from;
		try (ZipFile zip = new ZipFile(in); ZipOutputStream target = open(out)) {
			target.putNextEntry(new ZipEntry(RECORDING));
			DataOutputStream packets = new DataOutputStream(target);
			int last = 0;
			try (DataInputStream source = packets(zip)) {
				while (true) {
					int time;
					try {
						time = source.readInt();
					} catch (EOFException done) {
						break;
					}
					byte[] data = new byte[source.readInt()];
					source.readFully(data);
					last = time < from ? time : time < to ? from : time - removed;
					packets.writeInt(last);
					packets.writeInt(data.length);
					packets.write(data);
				}
			}
			packets.flush();
			target.closeEntry();
			copyRest(zip, target, last, time -> time < from ? time : time < to ? -1 : time - removed, time -> time < from ? time : time < to ? from : time - removed);
		}
	}

	/** Replay Mod's split: {@code in} up to {@code at} as {@code first}, from {@code at} on as {@code second}. */
	public static void split(File in, File first, File second, int at) throws IOException {
		int duration = duration(in);
		if (at <= 0 || at >= duration) {
			throw new IOException("The split must be inside the replay");
		}
		trim(in, first, 0, at);
		try {
			trim(in, second, at, duration);
		} catch (IOException e) {
			if (!first.delete()) {
				first.deleteOnExit();
			}
			throw e;
		}
	}

	/** {@code first} then {@code second} (same Minecraft version) as {@code out}. */
	public static void connect(File first, File second, File out) throws IOException {
		String firstMeta = read(first, META);
		String secondMeta = read(second, META);
		String version = text(VERSION, firstMeta);
		if (!version.equals(text(VERSION, secondMeta))) {
			throw new IOException("Both replays must be of the same Minecraft version (" + version + " and " + text(VERSION, secondMeta) + ")");
		}
		try (ZipFile a = new ZipFile(first); ZipFile b = new ZipFile(second); ZipOutputStream target = open(out)) {
			target.putNextEntry(new ZipEntry(RECORDING));
			DataOutputStream packets = new DataOutputStream(target);
			int end = copyPackets(a, packets, 0);
			int offset = Math.max(end, number(DURATION, firstMeta)) + 1;
			end = copyPackets(b, packets, offset);
			packets.flush();
			target.closeEntry();
			// markers and the camera path of the second move after the first's
			Markers markers = new Markers();
			readMarkers(a, markers, 0);
			readMarkers(b, markers, offset);
			if (!markers.isEmpty()) {
				write(target, Markers.ENTRY, markers.toJson());
			}
			String path = entry(a, PathStore.ENTRY);
			if (path != null) {
				write(target, PathStore.ENTRY, path);
			}
			write(target, META, DURATION.matcher(firstMeta).replaceFirst("\"duration\":" + end));
		}
	}

	private static int copyPackets(ZipFile zip, DataOutputStream packets, int offset) throws IOException {
		int last = offset;
		try (DataInputStream source = packets(zip)) {
			while (true) {
				int time;
				try {
					time = source.readInt();
				} catch (EOFException done) {
					return last;
				}
				byte[] data = new byte[source.readInt()];
				source.readFully(data);
				last = time + offset;
				packets.writeInt(last);
				packets.writeInt(data.length);
				packets.write(data);
			}
		}
	}

	/** A replay time moved by an edit; markers get -1 when they go. */
	private interface Shift {
		int apply(int time);
	}

	/**
	 * Metadata, markers and camera path after an edit (markers by {@code marker}, time keyframes
	 * by {@code keyframe}); other entries (the thumbnail) as they are.
	 */
	private static void copyRest(ZipFile zip, ZipOutputStream target, int duration, Shift marker, Shift keyframe) throws IOException {
		Enumeration<? extends ZipEntry> entries = zip.entries();
		while (entries.hasMoreElements()) {
			ZipEntry entry = entries.nextElement();
			String name = entry.getName();
			if (name.equals(RECORDING)) {
				continue;
			}
			String text;
			if (name.equals(META)) {
				text = DURATION.matcher(entry(zip, name)).replaceFirst("\"duration\":" + duration);
			} else if (name.equals(Markers.ENTRY)) {
				Markers markers = new Markers();
				Markers kept = new Markers();
				markers.readJson(entry(zip, name));
				for (Map.Entry<Integer, String> mark : markers.all().entrySet()) {
					int time = marker.apply(mark.getKey());
					if (time >= 0) {
						kept.put(time, mark.getValue());
					}
				}
				if (kept.isEmpty()) {
					continue;
				}
				text = kept.toJson();
			} else if (name.equals(PathStore.ENTRY)) {
				CameraPath path = new CameraPath();
				PathStore.fromJson(entry(zip, name), path);
				CameraPath moved = new CameraPath();
				moved.setInterpolation(path.interpolation());
				path.positions().forEach(moved::putPosition);
				path.times().forEach((time, replayTime) -> moved.putTime(time, keyframe.apply(replayTime)));
				path.segments().forEach(moved::setSegmentInterpolation);
				text = PathStore.toJson(moved);
			} else {
				target.putNextEntry(new ZipEntry(name));
				try (InputStream in = zip.getInputStream(entry)) {
					copy(in, target);
				}
				target.closeEntry();
				continue;
			}
			write(target, name, text);
		}
	}

	private static void readMarkers(ZipFile zip, Markers into, int offset) throws IOException {
		String json = entry(zip, Markers.ENTRY);
		if (json != null) {
			Markers markers = new Markers();
			markers.readJson(json);
			markers.all().forEach((time, name) -> into.put(time + offset, name));
		}
	}

	private static ZipOutputStream open(File out) throws IOException {
		if (out.exists()) {
			throw new IOException(out.getName() + " exists already");
		}
		return new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16));
	}

	private static DataInputStream packets(ZipFile zip) throws IOException {
		ZipEntry entry = zip.getEntry(RECORDING);
		if (entry == null) {
			throw new IOException("Not a replay file");
		}
		return new DataInputStream(new BufferedInputStream(zip.getInputStream(entry), 1 << 16));
	}

	private static void write(ZipOutputStream target, String name, String text) throws IOException {
		target.putNextEntry(new ZipEntry(name));
		target.write(text.getBytes(StandardCharsets.UTF_8));
		target.closeEntry();
	}

	private static String read(File replay, String name) throws IOException {
		try (ZipFile zip = new ZipFile(replay)) {
			String text = entry(zip, name);
			if (text == null) {
				throw new IOException("Not a replay file: no " + name);
			}
			return text;
		}
	}

	private static String entry(ZipFile zip, String name) throws IOException {
		ZipEntry entry = zip.getEntry(name);
		if (entry == null) {
			return null;
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (InputStream in = zip.getInputStream(entry)) {
			copy(in, bytes);
		}
		return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
	}

	private static void copy(InputStream in, java.io.OutputStream out) throws IOException {
		byte[] buffer = new byte[1 << 16];
		int n;
		while ((n = in.read(buffer)) > 0) {
			out.write(buffer, 0, n);
		}
	}

	private static int number(Pattern pattern, String json) throws IOException {
		Matcher m = pattern.matcher(json);
		if (!m.find()) {
			throw new IOException("No duration in the metadata");
		}
		return Integer.parseInt(m.group(1));
	}

	private static String text(Pattern pattern, String json) {
		Matcher m = pattern.matcher(json);
		return m.find() ? m.group(1) : "";
	}
}
