package io.github.andrix03.retroreplay.pathing;

import java.util.Map;
import java.util.TreeMap;

/**
 * Replay Mod's keyframe repository: named copies of camera paths, kept in the replay file as
 * "camera_presets.json": [{"name":"...","path":{...camera_path.json...}},...].
 */
public final class PathPresets {

	public static final String ENTRY = "camera_presets.json";

	private final TreeMap<String, String> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	public Map<String, String> all() {
		return java.util.Collections.unmodifiableMap(presets);
	}

	public boolean isEmpty() {
		return presets.isEmpty();
	}

	/** Saves (or overwrites) a copy of the path under that name. */
	public void put(String name, CameraPath path) {
		presets.put(name, PathStore.toJson(path));
	}

	public void remove(String name) {
		presets.remove(name);
	}

	public void rename(String from, String to) {
		String json = presets.remove(from);
		if (json != null) {
			presets.put(to, json);
		}
	}

	/** Replaces the path's keyframes with the preset's. */
	public boolean load(String name, CameraPath path) {
		String json = presets.get(name);
		if (json == null) {
			return false;
		}
		for (Long time : path.positions().keySet().toArray(new Long[0])) {
			path.removePosition(time);
		}
		for (Long time : path.times().keySet().toArray(new Long[0])) {
			path.removeTime(time);
		}
		path.setInterpolation(CameraPath.Interpolation.CATMULL_ROM);
		PathStore.fromJson(json, path);
		return true;
	}

	public String toJson() {
		StringBuilder json = new StringBuilder("[");
		boolean first = true;
		for (Map.Entry<String, String> preset : presets.entrySet()) {
			json.append(first ? "" : ",").append("{\"name\":\"").append(escape(preset.getKey())).append("\",\"path\":").append(preset.getValue()).append('}');
			first = false;
		}
		return json.append(']').toString();
	}

	public void readJson(String json) {
		int at = 0;
		while ((at = json.indexOf("\"name\"", at)) >= 0) {
			int open = json.indexOf('"', json.indexOf(':', at) + 1);
			StringBuilder name = new StringBuilder();
			int i = open + 1;
			for (; i < json.length() && json.charAt(i) != '"'; i++) {
				char c = json.charAt(i);
				if (c == '\\' && i + 1 < json.length()) {
					c = json.charAt(++i);
				}
				name.append(c);
			}
			int path = json.indexOf("\"path\"", i);
			int start = path < 0 ? -1 : json.indexOf('{', path);
			if (start < 0) {
				return;
			}
			int depth = 0;
			int end = start;
			for (; end < json.length(); end++) {
				if (json.charAt(end) == '{') {
					depth++;
				} else if (json.charAt(end) == '}' && --depth == 0) {
					break;
				}
			}
			presets.put(name.toString(), json.substring(start, end + 1));
			at = end;
		}
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
