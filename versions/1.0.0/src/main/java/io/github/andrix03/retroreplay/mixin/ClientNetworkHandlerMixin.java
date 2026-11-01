package io.github.andrix03.retroreplay.mixin;

import java.net.InetAddress;
import java.net.Socket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayConnection;
import io.github.andrix03.retroreplay.replay.ReplayHandler;
import io.github.andrix03.retroreplay.server.LocalServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;

/**
 * Starts a recording when the client connects (to our local server for singleplayer, see
 * LocalServer); a replay gets no socket at all, only a Connection that goes nowhere.
 */
@Mixin(ClientNetworkHandler.class)
public class ClientNetworkHandlerMixin {

	@Inject(method = "<init>", at = @At("TAIL"))
	private void retroreplay$start(Minecraft minecraft, String host, int port, CallbackInfo ci) {
		if (ReplayHandler.isStarting()) {
			return;
		}
		boolean singleplayer = LocalServer.isLocal(host, port);
		if (singleplayer) {
			LocalServer.joined();
		}
		Recorder.start(singleplayer, singleplayer ? "" : host + ":" + port);
	}

	@Redirect(method = "<init>", at = @At(value = "NEW", target = "java/net/Socket"))
	private Socket retroreplay$socket(InetAddress address, int port) throws java.io.IOException {
		// a replay: an unconnected socket (Mixin refuses null), the sink Connection never uses it
		return ReplayHandler.isStarting() ? new Socket() : new Socket(address, port);
	}

	@Redirect(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/network/Connection"))
	private Connection retroreplay$connection(Socket socket, String name, PacketHandler listener) throws java.io.IOException {
		return ReplayHandler.isStarting() ? ReplayConnection.sink() : new Connection(socket, name, listener);
	}
}
