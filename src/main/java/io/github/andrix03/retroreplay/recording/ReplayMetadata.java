package io.github.andrix03.retroreplay.recording;

/**
 * metaData.json of a replay. Same fields as Replay Mod's, but fileFormat is "MCPR_LEGACY":
 * pre-Netty packets (1-byte id, no length prefix in the protocol) must never be opened by
 * tools that expect Netty-era MCPR.
 */
public final class ReplayMetadata {
	public static final String FILE_FORMAT = "MCPR_LEGACY";
	public static final int FILE_FORMAT_VERSION = 1;

	public boolean singleplayer;
	public String serverName = "";
	public int duration;
	public long date;
	public String mcversion = "";
	public int protocol;
	public String generator = "RetroReplay";
	public int selfId = -1;

	public String toJson() {
		return "{"
			+ "\"singleplayer\":" + singleplayer
			+ ",\"serverName\":" + quote(serverName)
			+ ",\"duration\":" + duration
			+ ",\"date\":" + date
			+ ",\"mcversion\":" + quote(mcversion)
			+ ",\"fileFormat\":" + quote(FILE_FORMAT)
			+ ",\"fileFormatVersion\":" + FILE_FORMAT_VERSION
			+ ",\"protocol\":" + protocol
			+ ",\"generator\":" + quote(generator)
			+ ",\"selfId\":" + selfId
			+ ",\"players\":[]"
			+ "}";
	}

	private static String quote(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if (c == '"' || c == '\\') {
				b.append('\\').append(c);
			} else if (c < 0x20) {
				b.append(String.format("\\u%04x", (int) c));
			} else {
				b.append(c);
			}
		}
		return b.append('"').toString();
	}
}
