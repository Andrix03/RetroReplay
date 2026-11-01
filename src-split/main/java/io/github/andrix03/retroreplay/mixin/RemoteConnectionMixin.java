package io.github.andrix03.retroreplay.mixin;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.recording.TeeInputStream;
import io.github.andrix03.retroreplay.server.LocalServer;

import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

/** Multiplayer: every packet the read thread decodes for the client is recorded byte for byte. */
@Mixin(Connection.class)
public class RemoteConnectionMixin {

	@Shadow
	private PacketHandler listener;

	@Shadow
	private int timeout;

	/** The client also gives up after 1200 ticks without a packet: they do not count while paused. */
	@Inject(method = "tick", at = @At("HEAD"))
	private void retroreplay$noTimeoutWhilePaused(CallbackInfo ci) {
		if (listener instanceof ClientNetworkHandler && LocalServer.isPaused()) {
			timeout = 0;
		}
	}

	/**
	 * A paused local server sends nothing, so the 30 s read timeout would drop the client in
	 * the pause menu: no timeout towards it (it is a process of ours on this machine).
	 */
	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/net/Socket;setSoTimeout(I)V"))
	private void retroreplay$noTimeoutForLocalServer(Socket socket, int timeout) throws SocketException {
		boolean local = socket.getInetAddress() != null && LocalServer.isLocal(socket.getInetAddress().getHostAddress(), socket.getPort());
		socket.setSoTimeout(local ? 0 : timeout);
	}

	@Redirect(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/Packet;deserialize(Ljava/io/DataInputStream;Z)Lnet/minecraft/network/packet/Packet;"))
	private Packet retroreplay$record(DataInputStream input, boolean server) throws IOException {
		if (!(listener instanceof ClientNetworkHandler) || !Recorder.isRecording()) {
			return Packet.deserialize(input, server);
		}
		TeeInputStream tee = new TeeInputStream(input);
		Packet packet = Packet.deserialize(new DataInputStream(tee), server);
		if (packet != null) {
			Recorder.recordRaw(tee.bytes());
		}
		return packet;
	}
}
