package io.github.andrix03.retroreplay.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.agent.PauseAgent;
import io.github.andrix03.retroreplay.agent.PauseClock;
import io.github.andrix03.retroreplay.compat.Mc;

import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.packet.KeepAlivePacket;

/**
 * Singleplayer before 1.3 runs the world inside the client, with no packets to record. So the
 * world is played on the matching vanilla dedicated server instead, started in the background
 * on a free local port, and the client joins it like a multiplayer server.
 *
 * The world folder stays in saves/. The singleplayer player lives in level.dat ("Player"), the
 * server's in players/name.dat: it is copied over before the server starts and back after it
 * stopped, so the world keeps working in plain singleplayer too.
 */
public final class LocalServer {

	public enum State {
		IDLE, DOWNLOADING, STARTING, RUNNING, STOPPING, FAILED
	}

	/** What a new world was created with; null when the world exists already. */
	public static final class NewWorld {
		public final long seed;
		public final int gameMode;
		public final boolean structures;
		public final String generator;
		public final boolean hardcore;

		public NewWorld(long seed, int gameMode, boolean structures, String generator, boolean hardcore) {
			this.seed = seed;
			this.gameMode = gameMode;
			this.structures = structures;
			this.generator = generator;
			this.hardcore = hardcore;
		}
	}

	private static final int START_TIMEOUT_MS = 180_000;
	private static final int STOP_TIMEOUT_MS = 60_000;
	private static final int KEEP_ALIVE_MS = 5_000;
	/** In the world folder while the local server runs it; left behind by a session that did not end cleanly. */
	private static final String SESSION_MARKER = "retroreplay_session";

	private static volatile State state = State.IDLE;
	private static volatile String error;
	private static volatile int port;
	private static Process process;
	private static Thread worker;
	private static File world;
	private static String worldName;
	private static String username;
	private static volatile boolean joined;
	private static volatile boolean paused;
	private static long lastKeepAlive;
	private static boolean shutdownHook;

	private LocalServer() {
	}

	public static State state() {
		return state;
	}

	public static String error() {
		return error;
	}

	public static int port() {
		return port;
	}

	/** True for the address the client connects to while our server runs. */
	public static boolean isLocal(String host, int connectPort) {
		State s = state;
		return (s == State.RUNNING || s == State.STARTING) && connectPort == port && "127.0.0.1".equals(host);
	}

	/** Starts the server for saves/dirName in the background; poll state() until RUNNING or FAILED. */
	public static synchronized void launch(File gameDir, String dirName, String displayName, NewWorld newWorld, String player, int difficulty) {
		if (state == State.DOWNLOADING || state == State.STARTING || state == State.RUNNING) {
			RetroReplay.LOGGER.warn("Local server already running, not starting another one");
			return;
		}
		if (!shutdownHook) {
			shutdownHook = true;
			Runtime.getRuntime().addShutdownHook(new Thread(LocalServer::stopAndWait, "RetroReplay local server shutdown"));
		}
		Thread previous = worker;
		error = null;
		state = State.STARTING;
		worker = new Thread(() -> {
			try {
				if (previous != null) {
					previous.join(); // the last session may still be stopping and copying the player back
				}
				run(gameDir, dirName, displayName, newWorld, player, difficulty);
			} catch (Exception e) {
				RetroReplay.LOGGER.error("Local server failed", e);
				error = e.getMessage() == null ? e.toString() : e.getMessage();
				state = State.FAILED;
				destroy();
			}
		}, "RetroReplay local server");
		worker.setDaemon(true);
		worker.start();
	}

	private static void run(File gameDir, String dirName, String displayName, NewWorld newWorld, String player, int difficulty) throws Exception {
		File folder = new File(gameDir, "retroreplay/server-" + Mc.VERSION);
		if (!folder.isDirectory() && !folder.mkdirs()) {
			throw new IOException("Cannot create " + folder);
		}
		File jar = new File(folder, "minecraft_server.jar");
		if (!jar.isFile() || !Mc.SERVER_JAR_SHA1.equals(sha1(jar))) {
			state = State.DOWNLOADING;
			download(Mc.SERVER_JAR_URL, Mc.SERVER_JAR_SHA1, jar);
			state = State.STARTING;
		}

		File saves = new File(gameDir, "saves");
		world = new File(saves, dirName);
		worldName = displayName;
		username = player;
		File marker = new File(world, SESSION_MARKER);
		if (marker.exists()) {
			// the last session ended without copying the player back (crash, killed process):
			// players/ holds the newest player, level.dat at best an old one
			RetroReplay.LOGGER.warn("Last local server session of " + dirName + " did not end cleanly, recovering the player");
			copyPlayerBack();
		}
		NbtCompound level = readLevel();
		if (level != null) {
			copyPlayerToServer(level);
		}
		if (!world.isDirectory() && !world.mkdirs()) { // a new world: the server creates the rest
			throw new IOException("Cannot create " + world);
		}
		if (!marker.exists() && !marker.createNewFile()) {
			throw new IOException("Cannot write " + marker);
		}

		int freePort;
		try (ServerSocket socket = new ServerSocket(0)) {
			freePort = socket.getLocalPort();
		}
		writeProperties(new File(folder, "server.properties"), folder, level, newWorld, freePort, difficulty);
		// a hardcore death bans the player on the server; the ban list is shared by every local world
		for (String bans : new String[] { "banned-players.txt", "banned-ips.txt" }) {
			File list = new File(folder, bans);
			if (list.exists() && !list.delete()) {
				RetroReplay.LOGGER.warn("Cannot clear " + list);
			}
		}

		File agent = new File(folder, "retroreplay-agent.jar");
		writeAgentJar(agent);

		String java = new File(System.getProperty("java.home"), "bin/java").getPath();
		ProcessBuilder builder = new ProcessBuilder(java, "-Xmx1G", "-javaagent:" + agent.getName(), "-jar", jar.getName(), "nogui");
		builder.directory(folder);
		builder.redirectErrorStream(true);
		Process started = builder.start();
		synchronized (LocalServer.class) {
			process = started;
		}
		BufferedReader output = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
		long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
		String line;
		while ((line = output.readLine()) != null) {
			if (line.contains("FAILED TO BIND")) {
				throw new IOException("The local server could not open port " + freePort);
			}
			// the old servers log a crash and then stay running with no world: stop waiting at once
			if (line.contains("[SEVERE]") && line.toLowerCase(Locale.ROOT).contains("unexpected exception")) {
				throw new IOException(newWorld != null
					? "Minecraft " + Mc.VERSION + " crashed generating this world (a bug of the game with some seeds): create it again with another seed"
					: "The local server crashed loading the world (see " + new File(folder, "server.log") + ")");
			}
			if (line.contains("Done (")) {
				break;
			}
			if (System.currentTimeMillis() > deadline) {
				throw new IOException("The local server did not start in time");
			}
		}
		if (line == null) {
			throw new IOException("The local server stopped while starting (see " + new File(folder, "server.log") + ")");
		}
		port = freePort;
		state = State.RUNNING;
		RetroReplay.LOGGER.info("Local server for " + dirName + " running on port " + freePort);

		// keep draining the output so the server never blocks on a full pipe
		while (output.readLine() != null) {
			// discard: the server writes its own server.log
		}
		started.waitFor();
		if (copyPlayerBack() && !marker.delete()) {
			RetroReplay.LOGGER.warn("Cannot delete " + marker);
		}
		synchronized (LocalServer.class) {
			process = null;
		}
		port = 0;
		state = State.IDLE;
		RetroReplay.LOGGER.info("Local server stopped, player saved to level.dat");
	}

	/** The client connected to the local server. */
	public static void joined() {
		joined = true;
	}

	/** The client left a world: if it was ours, save and stop the server. */
	public static void left() {
		if (joined) {
			joined = false;
			stop();
		}
	}

	/** True while the client plays on the local server (it is singleplayer then). */
	public static boolean isJoined() {
		return joined;
	}

	public static boolean isPaused() {
		return paused;
	}

	/** Freezes or resumes the server's world, like the singleplayer pause menu (see PauseAgent). */
	public static synchronized void setPaused(boolean pause) {
		if (pause == paused || process == null || state != State.RUNNING) {
			return;
		}
		paused = pause;
		command(pause ? PauseAgent.PAUSE : PauseAgent.RESUME);
	}

	/**
	 * Every client tick. While paused the client sends nothing either, and the server drops
	 * a connection after 30 s without data: a keep-alive every few seconds prevents that
	 * (the paused server only queues them).
	 */
	public static void keepAlive(ClientNetworkHandler handler) {
		if (!paused || handler == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastKeepAlive >= KEEP_ALIVE_MS) {
			lastKeepAlive = now;
			handler.sendPacket(new KeepAlivePacket(0));
		}
	}

	private static synchronized void command(String line) {
		try {
			Writer input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
			input.write(line + "\n");
			input.flush();
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot send '" + line + "' to the local server", e);
		}
	}

	/** The client left the world: save and stop the server (the worker then copies the player back). */
	public static synchronized void stop() {
		if (process == null || state != State.RUNNING) {
			return;
		}
		state = State.STOPPING;
		paused = false;
		command("stop"); // the agent resumes the server first
		Process stopping = process;
		Thread watchdog = new Thread(() -> {
			try {
				Thread.sleep(STOP_TIMEOUT_MS);
			} catch (InterruptedException e) {
				return;
			}
			if (stopping.isAlive()) {
				RetroReplay.LOGGER.warn("Local server did not stop, killing it");
				stopping.destroy();
			}
		}, "RetroReplay local server watchdog");
		watchdog.setDaemon(true);
		watchdog.start();
	}

	/** Game closing: stop the server and wait for it, or the world would be left half saved. */
	public static void stopAndWait() {
		Thread running;
		synchronized (LocalServer.class) {
			stop();
			running = worker;
		}
		if (running != null) {
			try {
				running.join(STOP_TIMEOUT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static synchronized void destroy() {
		if (process != null) {
			process.destroy();
			process = null;
		}
		port = 0;
	}

	/** The agent classes only use the JDK, so they are copied as they are from the mod. */
	private static void writeAgentJar(File target) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), PauseAgent.class.getName());
		String[] classes = { PauseAgent.class.getName(), PauseAgent.class.getName() + "$1", PauseClock.class.getName() };
		try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(target), manifest)) {
			for (String name : classes) {
				String path = name.replace('.', '/') + ".class";
				try (InputStream in = LocalServer.class.getClassLoader().getResourceAsStream(path)) {
					if (in == null) {
						throw new IOException("Missing " + path);
					}
					jar.putNextEntry(new JarEntry(path));
					byte[] buffer = new byte[8192];
					int n;
					while ((n = in.read(buffer)) > 0) {
						jar.write(buffer, 0, n);
					}
					jar.closeEntry();
				}
			}
		}
	}

	private static NbtCompound readLevel() throws IOException {
		File file = new File(world, "level.dat");
		if (!file.isFile()) {
			return null;
		}
		try (InputStream in = new FileInputStream(file)) {
			return NbtIo.readCompressed(in);
		}
	}

	private static void copyPlayerToServer(NbtCompound level) throws IOException {
		NbtCompound data = level.getCompound("Data");
		if (!data.contains("Player")) {
			return;
		}
		File players = new File(world, "players");
		if (!players.isDirectory() && !players.mkdirs()) {
			throw new IOException("Cannot create " + players);
		}
		writeCompressed(data.getCompound("Player"), new File(players, username + ".dat"));
	}

	/**
	 * After the server stopped: its player file goes back into level.dat, and the world keeps
	 * its name. False if that failed (the session marker then stays for the next start).
	 */
	private static boolean copyPlayerBack() {
		try {
			NbtCompound level = readLevel();
			if (level == null) {
				return true;
			}
			NbtCompound data = level.getCompound("Data");
			File playerFile = new File(world, "players/" + username + ".dat");
			if (playerFile.isFile()) {
				try (InputStream in = new FileInputStream(playerFile)) {
					data.putCompound("Player", NbtIo.readCompressed(in));
				}
			}
			data.putString("LevelName", worldName); // the server names it after its level-name path
			level.putCompound("Data", data);
			writeCompressed(level, new File(world, "level.dat"));
			return true;
		} catch (IOException e) {
			RetroReplay.LOGGER.error("Cannot copy the player back into level.dat", e);
			return false;
		}
	}

	/** Written next to the target and renamed over it, like the game's own saving. */
	private static void writeCompressed(NbtCompound nbt, File target) throws IOException {
		File temp = new File(target.getPath() + ".rrtmp");
		try (OutputStream out = new FileOutputStream(temp)) {
			NbtIo.writeCompressed(nbt, out);
		}
		if (target.exists() && !target.delete()) {
			throw new IOException("Cannot replace " + target);
		}
		if (!temp.renameTo(target)) {
			throw new IOException("Cannot write " + target);
		}
	}

	private static void writeProperties(File file, File serverFolder, NbtCompound level, NewWorld newWorld, int serverPort, int difficulty) throws IOException {
		Properties properties = new Properties();
		// the server resolves level-name against its own folder
		properties.setProperty("level-name", relativePath(serverFolder, world));
		properties.setProperty("server-ip", "127.0.0.1");
		properties.setProperty("server-port", Integer.toString(serverPort));
		properties.setProperty("online-mode", "false");
		properties.setProperty("max-players", "1");
		properties.setProperty("spawn-protection", "0");
		properties.setProperty("white-list", "false");
		properties.setProperty("allow-flight", "true"); // no kicks for "flying" after lag or knockback
		properties.setProperty("allow-nether", "true");
		properties.setProperty("spawn-monsters", "true");
		properties.setProperty("spawn-animals", "true");
		properties.setProperty("spawn-npcs", "true");
		properties.setProperty("pvp", "true");
		properties.setProperty("view-distance", "10");
		properties.setProperty("difficulty", Integer.toString(difficulty));
		properties.setProperty("motd", "RetroReplay");
		if (newWorld != null) {
			properties.setProperty("level-seed", Long.toString(newWorld.seed));
			properties.setProperty("gamemode", Integer.toString(newWorld.gameMode));
			properties.setProperty("generate-structures", Boolean.toString(newWorld.structures));
			properties.setProperty("level-type", newWorld.generator);
			properties.setProperty("hardcore", Boolean.toString(newWorld.hardcore));
		} else if (level != null) {
			NbtCompound data = level.getCompound("Data");
			properties.setProperty("gamemode", Integer.toString(data.getInt("GameType")));
			properties.setProperty("hardcore", Boolean.toString(data.getBoolean("hardcore")));
			properties.setProperty("generate-structures", Boolean.toString(!data.contains("MapFeatures") || data.getBoolean("MapFeatures")));
			properties.setProperty("level-type", data.contains("generatorName") ? data.getString("generatorName").toUpperCase() : "DEFAULT");
		}
		try (OutputStream out = new FileOutputStream(file)) {
			properties.store(out, "Written by RetroReplay for each singleplayer session");
		}
	}

	private static String relativePath(File from, File to) throws IOException {
		java.nio.file.Path base = from.getCanonicalFile().toPath();
		return base.relativize(to.getCanonicalFile().toPath()).toString().replace('\\', '/');
	}

	private static void download(String url, String sha1, File target) throws IOException {
		RetroReplay.LOGGER.info("Downloading the " + Mc.VERSION + " server from " + url);
		File temp = new File(target.getPath() + ".part");
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setConnectTimeout(15_000);
		connection.setReadTimeout(30_000);
		connection.setRequestProperty("User-Agent", "RetroReplay"); // the archive refuses Java's default agent
		try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temp)) {
			byte[] buffer = new byte[16384];
			int n;
			while ((n = in.read(buffer)) > 0) {
				out.write(buffer, 0, n);
			}
		} finally {
			connection.disconnect();
		}
		if (!sha1.equals(sha1(temp))) {
			temp.delete();
			throw new IOException("The downloaded server jar is damaged (checksum mismatch)");
		}
		if (target.exists() && !target.delete()) {
			throw new IOException("Cannot replace " + target);
		}
		if (!temp.renameTo(target)) {
			throw new IOException("Cannot save " + target);
		}
	}

	private static String sha1(File file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		try (InputStream in = new FileInputStream(file)) {
			byte[] buffer = new byte[16384];
			int n;
			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
		}
		StringBuilder hex = new StringBuilder();
		for (byte b : digest.digest()) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}
}
