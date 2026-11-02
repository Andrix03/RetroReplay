package io.github.andrix03.retroreplay.pathing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Keeps the camera path inside the replay file, like Replay Mod keeps its timelines there:
 * an entry "camera_path.json" with {"positions":[[t,x,y,z,yaw,pitch,roll(,entity)],...],"times":[[t,ms],...]}.
 */
public final class PathStore {

	public static final String ENTRY = "camera_path.json";
	private static final Pattern ROW = Pattern.compile("\\[([^\\[\\]]*)\\]");
	private static final Pattern INTERPOLATION = Pattern.compile("\"interpolation\"\\s*:\\s*\"(\\w+)\"");

	private PathStore() {
	}

	public static String toJson(CameraPath path) {
		StringBuilder json = new StringBuilder("{\"interpolation\":\"" + path.interpolation().name() + "\",\"positions\":[");
		boolean first = true;
		for (Map.Entry<Long, CameraPath.Position> entry : path.positions().entrySet()) {
			CameraPath.Position p = entry.getValue();
			json.append(first ? "" : ",").append(String.format(Locale.ROOT, "[%d,%s,%s,%s,%s,%s,%s%s]",
				entry.getKey(), p.x, p.y, p.z, p.yaw, p.pitch, p.roll, p.isSpectator() ? "," + p.spectated : ""));
			first = false;
		}
		json.append("],\"times\":[");
		first = true;
		for (Map.Entry<Long, Integer> entry : path.times().entrySet()) {
			json.append(first ? "" : ",").append('[').append(entry.getKey()).append(',').append(entry.getValue()).append(']');
			first = false;
		}
		return json.append("]}").toString();
	}

	public static void fromJson(String json, CameraPath path) {
		Matcher interpolation = INTERPOLATION.matcher(json);
		if (interpolation.find()) {
			for (CameraPath.Interpolation type : CameraPath.Interpolation.values()) {
				if (type.name().equals(interpolation.group(1))) {
					path.setInterpolation(type);
				}
			}
		}
		Matcher rows = ROW.matcher(array(json, "positions"));
		while (rows.find()) {
			String[] v = rows.group(1).split(",");
			if (v.length == 7 || v.length == 8) { // the 8th value: the spectated entity
				path.putPosition(Long.parseLong(v[0].trim()), new CameraPath.Position(Double.parseDouble(v[1]), Double.parseDouble(v[2]),
					Double.parseDouble(v[3]), Float.parseFloat(v[4]), Float.parseFloat(v[5]), Float.parseFloat(v[6]),
					v.length == 8 ? Integer.parseInt(v[7].trim()) : CameraPath.Position.NO_ENTITY));
			}
		}
		rows = ROW.matcher(array(json, "times"));
		while (rows.find()) {
			String[] v = rows.group(1).split(",");
			if (v.length == 2) {
				path.putTime(Long.parseLong(v[0].trim()), Integer.parseInt(v[1].trim()));
			}
		}
	}

	/** The inside of the array under that key (brackets counted), or "" without it. */
	private static String array(String json, String key) {
		int at = json.indexOf("\"" + key + "\"");
		int start = at < 0 ? -1 : json.indexOf('[', at);
		if (start < 0) {
			return "";
		}
		int depth = 0;
		for (int i = start; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '[') {
				depth++;
			} else if (c == ']' && --depth == 0) {
				return json.substring(start + 1, i);
			}
		}
		return "";
	}

	/** The path saved in a replay, or an empty one. */
	public static CameraPath load(File replay) throws IOException {
		CameraPath path = new CameraPath();
		String json = readEntry(replay, ENTRY);
		if (json != null) {
			fromJson(json, path);
		}
		return path;
	}

	/** Rewrites the replay with the path entry replaced. */
	public static void save(File replay, CameraPath path) throws IOException {
		saveEntries(replay, java.util.Collections.singletonMap(ENTRY, path.isEmpty() ? null : toJson(path)));
	}

	/** A text entry of the replay file, or null. */
	public static String readEntry(File replay, String name) throws IOException {
		try (ZipFile zip = new ZipFile(replay)) {
			ZipEntry entry = zip.getEntry(name);
			if (entry == null) {
				return null;
			}
			try (InputStream in = zip.getInputStream(entry)) {
				return new String(readAll(in), StandardCharsets.UTF_8);
			}
		}
	}

	/** Text entries (UTF-8), see saveRawEntries. */
	public static void saveEntries(File replay, Map<String, String> replaced) throws IOException {
		Map<String, byte[]> raw = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, String> entry : replaced.entrySet()) {
			raw.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().getBytes(StandardCharsets.UTF_8));
		}
		saveRawEntries(replay, raw);
	}

	/**
	 * Rewrites the replay with these entries replaced (null: removed), the others copied as
	 * they are. Temp file + rename, never a half file. One rewrite at a time: a thumbnail is
	 * saved from its own thread while the paths may be saved on the client's.
	 */
	public static synchronized void saveRawEntries(File replay, Map<String, byte[]> replaced) throws IOException {
		File temp = new File(replay.getPath() + ".tmp");
		try (ZipFile zip = new ZipFile(replay); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (replaced.containsKey(entry.getName())) {
					continue;
				}
				out.putNextEntry(new ZipEntry(entry.getName()));
				try (InputStream in = zip.getInputStream(entry)) {
					byte[] buffer = new byte[64 * 1024];
					int n;
					while ((n = in.read(buffer)) > 0) {
						out.write(buffer, 0, n);
					}
				}
				out.closeEntry();
			}
			for (Map.Entry<String, byte[]> entry : replaced.entrySet()) {
				if (entry.getValue() != null) {
					out.putNextEntry(new ZipEntry(entry.getKey()));
					out.write(entry.getValue());
					out.closeEntry();
				}
			}
		}
		if (!replay.delete() || !temp.renameTo(replay)) {
			throw new IOException("Cannot replace " + replay + " (the new file is " + temp + ")");
		}
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int n;
		while ((n = in.read(buffer)) > 0) {
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}
}
