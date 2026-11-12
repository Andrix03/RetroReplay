package io.github.andrix03.retroreplay.agent;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * Java agent of the local server (LocalServer builds the jar from these classes): lets the
 * client pause the dedicated server like a singleplayer world.
 *
 * - MinecraftServer's calls to System.currentTimeMillis() are pointed at PauseClock by
 *   editing one constant pool entry, so no bytecode library is needed.
 * - The console gets two extra commands, read before the server sees its input:
 *   "retroreplay pause" and "retroreplay resume". Any other input (or its end) resumes first,
 *   so "stop" always reaches a running server.
 */
public final class PauseAgent {

	public static final String PAUSE = "retroreplay pause";
	public static final String RESUME = "retroreplay resume";
	/** "retroreplay difficulty N worldsField difficultyField": the obfuscated names come from the client. */
	public static final String DIFFICULTY = "retroreplay difficulty ";

	private static final String TARGET = "net/minecraft/server/MinecraftServer";
	private static final String CLOCK = "io/github/andrix03/retroreplay/agent/PauseClock";

	private PauseAgent() {
	}

	public static void premain(String args, Instrumentation instrumentation) throws IOException {
		filterConsole();
		instrumentation.addTransformer(new ClassFileTransformer() {
			@Override
			public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
				if (!TARGET.equals(className)) {
					return null;
				}
				try {
					return redirectClock(bytes);
				} catch (RuntimeException e) {
					System.err.println("[RetroReplay] Cannot patch " + className + ", pausing disabled: " + e);
					return null;
				}
			}
		});
	}

	private static void filterConsole() throws IOException {
		InputStream original = System.in;
		PipedOutputStream toServer = new PipedOutputStream();
		PipedInputStream serverInput = new PipedInputStream(toServer, 4096);
		System.setIn(serverInput);
		Thread reader = new Thread(() -> {
			BufferedReader lines = new BufferedReader(new InputStreamReader(original, StandardCharsets.UTF_8));
			try {
				String line;
				while ((line = lines.readLine()) != null) {
					if (line.equals(PAUSE)) {
						PauseClock.setPaused(true);
					} else if (line.equals(RESUME)) {
						PauseClock.setPaused(false);
					} else if (line.startsWith(DIFFICULTY)) {
						setDifficulty(line.substring(DIFFICULTY.length()).split(" "));
					} else {
						PauseClock.setPaused(false);
						write(toServer, line + "\n");
					}
				}
			} catch (IOException e) {
				// the client is gone, same as the end of the input
			}
			// the game closed or crashed without stopping us: save the world and stop
			PauseClock.setPaused(false);
			try {
				write(toServer, "stop\n");
			} catch (IOException e) {
				Runtime.getRuntime().halt(1);
			}
		}, "RetroReplay console filter");
		reader.setDaemon(true);
		reader.start();
	}

	/**
	 * These servers read the difficulty from server.properties only when they start (no command
	 * for it): the client's change is set on every world directly. The server is found through
	 * the thread running it, its fields by the names the client knows for this version.
	 */
	private static void setDifficulty(String[] args) {
		java.util.logging.Logger log = java.util.logging.Logger.getLogger("Minecraft");
		try {
			int difficulty = Integer.parseInt(args[0]);
			Object server = findServer();
			if (server == null) {
				log.warning("[RetroReplay] No server found to set the difficulty on");
				return;
			}
			Object[] worlds = (Object[]) field(server.getClass(), args[1]).get(server);
			for (Object world : worlds) {
				if (world != null) {
					java.lang.reflect.Field field = field(world.getClass(), args[2]);
					int old = field.getInt(world);
					field.setInt(world, difficulty);
					log.info("[RetroReplay] Difficulty " + old + " -> " + difficulty);
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			log.warning("[RetroReplay] Cannot set the difficulty: " + e);
		}
	}

	private static Object findServer() throws IllegalAccessException {
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			for (Class<?> type = thread.getClass(); type != null; type = type.getSuperclass()) {
				for (java.lang.reflect.Field field : type.getDeclaredFields()) {
					if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
						continue;
					}
					try {
						field.setAccessible(true);
					} catch (RuntimeException e) {
						continue; // a module that does not open it (not on the Java 8 these servers run on)
					}
					Object value = field.get(thread);
					if (value != null && value.getClass().getName().equals(TARGET.replace('/', '.'))) {
						return value;
					}
				}
			}
		}
		return null;
	}

	private static java.lang.reflect.Field field(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> at = type; at != null; at = at.getSuperclass()) {
			try {
				java.lang.reflect.Field field = at.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException e) {
				// up the hierarchy
			}
		}
		throw new NoSuchFieldException(name + " in " + type.getName());
	}

	private static void write(OutputStream out, String text) throws IOException {
		out.write(text.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	/**
	 * Adds a Class entry for PauseClock at the end of the constant pool and points every
	 * Methodref System.currentTimeMillis()J at it (same name and descriptor).
	 */
	static byte[] redirectClock(byte[] bytes) {
		ByteBuffer in = ByteBuffer.wrap(bytes);
		in.position(8);
		int count = in.getShort() & 0xFFFF;
		int[] offsets = new int[count];
		int[] tags = new int[count];
		String[] utf8 = new String[count];
		for (int i = 1; i < count; i++) {
			offsets[i] = in.position();
			int tag = in.get() & 0xFF;
			tags[i] = tag;
			switch (tag) {
				case 1:
					int length = in.getShort() & 0xFFFF;
					byte[] text = new byte[length];
					in.get(text);
					utf8[i] = new String(text, StandardCharsets.UTF_8); // modified UTF-8, fine for ASCII names
					break;
				case 7: case 8: case 16: case 19: case 20:
					in.position(in.position() + 2);
					break;
				case 15:
					in.position(in.position() + 3);
					break;
				case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
					in.position(in.position() + 4);
					break;
				case 5: case 6:
					in.position(in.position() + 8);
					i++; // takes two slots
					break;
				default:
					throw new IllegalStateException("Unknown constant pool tag " + tag);
			}
		}
		int poolEnd = in.position();

		List<Integer> refs = new ArrayList<>();
		for (int i = 1; i < count; i++) {
			if (tags[i] != 10) {
				continue;
			}
			int classIndex = bytes[offsets[i] + 1] << 8 & 0xFF00 | bytes[offsets[i] + 2] & 0xFF;
			int natIndex = bytes[offsets[i] + 3] << 8 & 0xFF00 | bytes[offsets[i] + 4] & 0xFF;
			int className = bytes[offsets[classIndex] + 1] << 8 & 0xFF00 | bytes[offsets[classIndex] + 2] & 0xFF;
			int name = bytes[offsets[natIndex] + 1] << 8 & 0xFF00 | bytes[offsets[natIndex] + 2] & 0xFF;
			int type = bytes[offsets[natIndex] + 3] << 8 & 0xFF00 | bytes[offsets[natIndex] + 4] & 0xFF;
			if ("java/lang/System".equals(utf8[className]) && "currentTimeMillis".equals(utf8[name]) && "()J".equals(utf8[type])) {
				refs.add(offsets[i]);
			}
		}
		if (refs.isEmpty()) {
			throw new IllegalStateException("no System.currentTimeMillis() call found");
		}

		int clockName = count;
		int clockClass = count + 1;
		byte[] patched = bytes.clone();
		for (int offset : refs) {
			patched[offset + 1] = (byte) (clockClass >> 8);
			patched[offset + 2] = (byte) clockClass;
		}
		patched[8] = (byte) ((count + 2) >> 8);
		patched[9] = (byte) (count + 2);

		ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 64);
		out.write(patched, 0, poolEnd);
		try {
			DataOutputStream data = new DataOutputStream(out);
			data.writeByte(1);
			data.writeUTF(CLOCK);
			data.writeByte(7);
			data.writeShort(clockName);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		out.write(patched, poolEnd, patched.length - poolEnd);
		return out.toByteArray();
	}
}
