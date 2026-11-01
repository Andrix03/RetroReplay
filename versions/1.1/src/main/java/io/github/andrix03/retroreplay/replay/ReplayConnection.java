package io.github.andrix03.retroreplay.replay;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Socket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

/**
 * The client end of a replay (1.2.5): a multiplayer ClientNetworkHandler whose socket
 * connection is replaced, through the constructor mixin, by a Connection that goes nowhere.
 * Recorded packets are handled at once on the client thread.
 */
public final class ReplayConnection {

	public final ClientNetworkHandler handler;

	private ReplayConnection(ClientNetworkHandler handler) {
		this.handler = handler;
	}

	/** Only while ReplayHandler.isStarting(): the constructor mixin then calls sink() instead of opening a socket. */
	public static ReplayConnection open(Minecraft minecraft) throws IOException {
		return new ReplayConnection(new ClientNetworkHandler(minecraft, "127.0.0.1", 0));
	}

	/** A Connection whose constructor never ran (it would start network threads on the socket). */
	public static Connection sink() {
		try {
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			Field field = unsafeClass.getDeclaredField("theUnsafe");
			field.setAccessible(true);
			Object unsafe = field.get(null);
			return (Connection) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, SinkConnection.class);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Cannot create the replay connection", e);
		}
	}

	public void accept(Packet packet) {
		packet.handle(handler);
	}

	private static final class SinkConnection extends Connection {

		private SinkConnection(Socket socket, String name, PacketHandler listener) throws IOException {
			super(socket, name, listener); // never called, see sink()
		}

		@Override
		public void send(Packet packet) {
		}

		@Override
		public void tick() {
		}

		@Override
		public void interrupt() {
		}

		@Override
		public void disconnect(String reason, Object... args) {
		}

		@Override
		public void close() {
		}
	}
}
