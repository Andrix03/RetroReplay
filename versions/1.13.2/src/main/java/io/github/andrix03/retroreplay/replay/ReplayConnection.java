package io.github.andrix03.retroreplay.replay;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.multiplayer.DownloadingTerrainScreen;
import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketFlow;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;

/**
 * The client end of a replay (1.13): a ClientPlayNetworkHandler on a Connection with no
 * channel. Recorded packets are handled at once on the client thread.
 */
public final class ReplayConnection {

	private static final SocketAddress ADDRESS = InetSocketAddress.createUnresolved("replay", 0);

	public final ClientPlayNetworkHandler handler;

	private ReplayConnection(ClientPlayNetworkHandler handler) {
		this.handler = handler;
	}

	public static ReplayConnection open(Minecraft minecraft) {
		SinkConnection connection = new SinkConnection();
		ClientPlayNetworkHandler handler = new ClientPlayNetworkHandler(minecraft, null, connection, minecraft.getSession().getProfile());
		connection.setListener(handler);
		return new ReplayConnection(handler);
	}

	/** The screen shown until the login packet builds the world. */
	public Screen terrainScreen() {
		return new DownloadingTerrainScreen();
	}

	@SuppressWarnings("unchecked")
	public void accept(Packet<?> packet) {
		((Packet<ClientPlayNetworkHandler>) packet).handle(handler);
	}

	/** Packets are handled as they come here: nothing waits. */
	public void flush() {
	}

	/** Whatever the client sends goes nowhere. */
	private static final class SinkConnection extends Connection {

		SinkConnection() {
			super(PacketFlow.CLIENTBOUND);
		}

		@Override
		public void send(Packet<?> packet) {
		}

		@Override
		public void send(Packet<?> packet, GenericFutureListener<? extends Future<? super Void>> listener) {
		}

		@Override
		public void tick() {
		}

		@Override
		public void disconnect(Text reason) {
		}

		/** The interaction manager treats a closed connection as a disconnect. */
		@Override
		public boolean isConnected() {
			return true;
		}

		/** Read on login by mods: there is no channel to ask. */
		@Override
		public SocketAddress getAddress() {
			return ADDRESS;
		}
	}
}
