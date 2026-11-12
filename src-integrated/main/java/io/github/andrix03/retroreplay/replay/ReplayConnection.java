package io.github.andrix03.retroreplay.replay;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.mixin.MinecraftConnectionAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.LocalConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.integrated.IntegratedServer;

/**
 * The client end of a replay (1.3 and later): a singleplayer ClientNetworkHandler whose
 * LocalConnection is bound to a sink instead of the integrated server.
 */
public final class ReplayConnection {

	public final ClientNetworkHandler handler;
	private final LocalConnection client;

	private ReplayConnection(ClientNetworkHandler handler) {
		this.handler = handler;
		this.client = (LocalConnection) handler.getConnection();
	}

	/** Only while ReplayHandler.isStarting(): the constructor mixin then calls bind() instead of the server. */
	public static ReplayConnection open(Minecraft minecraft) throws java.io.IOException {
		ClientNetworkHandler handler = new ClientNetworkHandler(minecraft, (IntegratedServer) null);
		((MinecraftConnectionAccessor) minecraft).retroreplay$setConnection(handler.getConnection());
		return new ReplayConnection(handler);
	}

	/** Called when the client binds its connection: give it a sink as the other end. */
	public static void bind(LocalConnection clientConnection) {
		Mc.newSinkConnection().bind(clientConnection);
	}

	/** The screen shown until the login packet builds the world. */
	public net.minecraft.client.gui.screen.Screen terrainScreen() {
		return new net.minecraft.client.gui.screen.menu.multiplayer.DownloadingTerrainScreen(handler);
	}

	/** Queued like a server packet, handled on the next client tick. */
	public void accept(Packet packet) {
		client.accept(packet);
	}

	/** Handles the queued packets now (Quick Mode places entities right after its teleports). */
	public void flush() {
		client.tick();
	}
}
