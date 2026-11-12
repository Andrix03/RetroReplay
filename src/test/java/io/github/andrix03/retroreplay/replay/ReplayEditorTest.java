package io.github.andrix03.retroreplay.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import io.github.andrix03.retroreplay.pathing.CameraPath;
import io.github.andrix03.retroreplay.pathing.Markers;
import io.github.andrix03.retroreplay.pathing.PathStore;

class ReplayEditorTest {

	/** A replay whose packets are one byte each (its index) at the given times. */
	private static File replay(File folder, String name, String version, int[] times, String markers, String path) throws IOException {
		File file = new File(folder, name + ".mcpr");
		try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
			zip.putNextEntry(new ZipEntry("recording.tmcpr"));
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			for (int i = 0; i < times.length; i++) {
				out.writeInt(times[i]);
				out.writeInt(1);
				out.writeByte(i);
			}
			zip.write(bytes.toByteArray());
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("metaData.json"));
			zip.write(("{\"duration\":" + times[times.length - 1] + ",\"mcversion\":\"" + version + "\",\"fileFormat\":\"MCPR_LEGACY\"}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			if (markers != null) {
				zip.putNextEntry(new ZipEntry(Markers.ENTRY));
				zip.write(markers.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
			if (path != null) {
				zip.putNextEntry(new ZipEntry(PathStore.ENTRY));
				zip.write(path.getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return file;
	}

	/** time, packet byte pairs. */
	private static List<int[]> packets(File file) throws IOException {
		List<int[]> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(file); DataInputStream in = new DataInputStream(zip.getInputStream(zip.getEntry("recording.tmcpr")))) {
			while (true) {
				int time;
				try {
					time = in.readInt();
				} catch (EOFException e) {
					return out;
				}
				int length = in.readInt();
				byte[] data = new byte[length];
				in.readFully(data);
				out.add(new int[] { time, data[0] });
			}
		}
	}

	private static String entry(File file, String name) throws IOException {
		try (ZipFile zip = new ZipFile(file)) {
			ZipEntry entry = zip.getEntry(name);
			return entry == null ? null : new String(readAll(zip, entry), StandardCharsets.UTF_8);
		}
	}

	private static byte[] readAll(ZipFile zip, ZipEntry entry) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[4096];
		try (java.io.InputStream in = zip.getInputStream(entry)) {
			int n;
			while ((n = in.read(buffer)) > 0) {
				bytes.write(buffer, 0, n);
			}
		}
		return bytes.toByteArray();
	}

	@Test
	void trimKeepsTheWorldAndMovesTimes() throws IOException {
		File folder = Files.createTempDirectory("rr_editor").toFile();
		Markers markers = new Markers();
		markers.put(1000, "early");
		markers.put(5000, "kept");
		markers.put(9000, "late");
		CameraPath path = new CameraPath();
		path.putTime(0, 4000);
		path.putTime(2000, 7500);
		File in = replay(folder, "a", "1.0.0", new int[] { 0, 1000, 3000, 4000, 6000, 8000, 10000 }, markers.toJson(), PathStore.toJson(path));
		File out = new File(folder, "a trimmed.mcpr");
		ReplayEditor.trim(in, out, 3000, 7000);

		List<int[]> kept = packets(out);
		assertEquals(5, kept.size()); // 0, 1000, 3000, 4000, 6000; 8000 and 10000 dropped
		assertEquals(0, kept.get(0)[0]); // before the start: at the start, in order
		assertEquals(0, kept.get(1)[0]);
		assertEquals(0, kept.get(2)[0]);
		assertEquals(1000, kept.get(3)[0]);
		assertEquals(3000, kept.get(4)[0]);
		assertEquals(4, kept.get(4)[1]);
		assertTrue(entry(out, "metaData.json").contains("\"duration\":3000"));
		Markers moved = new Markers();
		moved.readJson(entry(out, Markers.ENTRY));
		assertEquals(1, moved.all().size());
		assertEquals("kept", moved.all().get(2000));
		CameraPath shifted = new CameraPath();
		PathStore.fromJson(entry(out, PathStore.ENTRY), shifted);
		assertEquals(1000, (int) shifted.times().get(0L));
		assertEquals(4000, (int) shifted.times().get(2000L)); // 4500 is past the end: held there
		assertThrows(IOException.class, () -> ReplayEditor.trim(in, out, 0, 1000)); // never overwrites
	}

	@Test
	void connectPutsTheSecondAfterTheFirst() throws IOException {
		File folder = Files.createTempDirectory("rr_editor").toFile();
		Markers second = new Markers();
		second.put(500, "b");
		File a = replay(folder, "a", "1.0.0", new int[] { 0, 2000 }, null, null);
		File b = replay(folder, "b", "1.0.0", new int[] { 0, 500, 1500 }, second.toJson(), null);
		File out = new File(folder, "a + b.mcpr");
		ReplayEditor.connect(a, b, out);
		List<int[]> all = packets(out);
		assertEquals(5, all.size());
		assertEquals(2001, all.get(2)[0]);
		assertEquals(3501, all.get(4)[0]);
		assertTrue(entry(out, "metaData.json").contains("\"duration\":3501"));
		Markers markers = new Markers();
		markers.readJson(entry(out, Markers.ENTRY));
		assertEquals("b", markers.all().get(2501));
		File other = replay(folder, "c", "1.7.10", new int[] { 0 }, null, null);
		assertThrows(IOException.class, () -> ReplayEditor.connect(a, other, new File(folder, "x.mcpr")));
	}

	@Test
	void cutTakesAPartOutAndSplitMakesTwo() throws IOException {
		File folder = Files.createTempDirectory("rr_editor").toFile();
		Markers markers = new Markers();
		markers.put(1000, "before");
		markers.put(4000, "inside");
		markers.put(8000, "after");
		File in = replay(folder, "a", "1.0.0", new int[] { 0, 1000, 3000, 4000, 6000, 8000, 10000 }, markers.toJson(), null);
		File cut = new File(folder, "a cut.mcpr");
		ReplayEditor.cut(in, cut, 3000, 6000);
		List<int[]> kept = packets(cut);
		assertEquals(7, kept.size()); // nothing is lost: the cut part is taken at once
		assertEquals(1000, kept.get(1)[0]);
		assertEquals(3000, kept.get(2)[0]); // 3000 and 4000 squashed at the cut
		assertEquals(3000, kept.get(3)[0]);
		assertEquals(3000, kept.get(4)[0]); // 6000 is the cut's end: right after it
		assertEquals(5000, kept.get(5)[0]);
		assertEquals(7000, kept.get(6)[0]);
		assertTrue(entry(cut, "metaData.json").contains("\"duration\":7000"));
		Markers moved = new Markers();
		moved.readJson(entry(cut, Markers.ENTRY));
		assertEquals("before", moved.all().get(1000));
		assertEquals("after", moved.all().get(5000));
		assertEquals(2, moved.all().size());

		File first = new File(folder, "a 1.mcpr");
		File second = new File(folder, "a 2.mcpr");
		ReplayEditor.split(in, first, second, 4000);
		assertEquals(4, packets(first).size());
		List<int[]> rest = packets(second);
		assertEquals(7, rest.size()); // the world before the split is in the second part at 0
		assertEquals(0, rest.get(3)[0]);
		assertEquals(6000, rest.get(6)[0]);
	}
}
