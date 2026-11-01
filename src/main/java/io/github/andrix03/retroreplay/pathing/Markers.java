package io.github.andrix03.retroreplay.pathing;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replay Mod's event markers: named moments of the replay (ms), set with M while recording or
 * in the replay. Stored like Replay Mod's markers.json: [{"realTimestamp":ms,"value":{"name":"..."}}].
 */
public final class Markers {

	public static final String ENTRY = "markers.json";
	private static final Pattern MARKER = Pattern.compile("\"realTimestamp\"\\s*:\\s*(\\d+)(?:[^{}]*\\{[^{}]*?\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?");

	private final TreeMap<Integer, String> markers = new TreeMap<>();

	public NavigableMap<Integer, String> all() {
		return java.util.Collections.unmodifiableNavigableMap(markers);
	}

	public void put(int time, String name) {
		markers.put(time, name == null ? "" : name);
	}

	public void remove(int time) {
		markers.remove(time);
	}

	public boolean isEmpty() {
		return markers.isEmpty();
	}

	public String toJson() {
		StringBuilder json = new StringBuilder("[");
		boolean first = true;
		for (Map.Entry<Integer, String> marker : markers.entrySet()) {
			json.append(first ? "" : ",").append("{\"realTimestamp\":").append(marker.getKey());
			if (!marker.getValue().isEmpty()) {
				json.append(",\"value\":{\"name\":\"").append(escape(marker.getValue())).append("\"}");
			} else {
				json.append(",\"value\":{}");
			}
			json.append('}');
			first = false;
		}
		return json.append(']').toString();
	}

	public void readJson(String json) {
		Matcher m = MARKER.matcher(json);
		while (m.find()) {
			put(Integer.parseInt(m.group(1)), m.group(2) == null ? "" : unescape(m.group(2)));
		}
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (c == '"' || c == '\\') {
				out.append('\\').append(c);
			} else if (c >= ' ') {
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String unescape(String text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\\' && i + 1 < text.length()) {
				c = text.charAt(++i);
			}
			out.append(c);
		}
		return out.toString();
	}
}
