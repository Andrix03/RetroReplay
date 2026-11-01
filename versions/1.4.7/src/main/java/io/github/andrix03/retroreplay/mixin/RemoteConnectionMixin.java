package io.github.andrix03.retroreplay.mixin;

import java.io.DataInputStream;
import java.io.IOException;

import java.net.Socket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.recording.TeeInputStream;

import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.RemoteConnection;
import net.minecraft.network.packet.Packet;

/** Multiplayer: every packet the read thread decodes for the client is recorded byte for byte. */
@Mixin(RemoteConnection.class)
public class RemoteConnectionMixin {

	@Shadow
	private PacketHandler listener;

	@Redirect(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/Packet;deserialize(Ljava/io/DataInputStream;ZLjava/net/Socket;)Lnet/minecraft/network/packet/Packet;"))
	private Packet retroreplay$record(DataInputStream input, boolean server, Socket socket) throws IOException {
		if (!(listener instanceof ClientNetworkHandler) || !Recorder.isRecording()) {
			return Packet.deserialize(input, server, socket);
		}
		TeeInputStream tee = new TeeInputStream(input);
		Packet packet = Packet.deserialize(new DataInputStream(tee), server, socket);
		if (packet != null) {
			Recorder.recordRaw(tee.bytes());
		}
		return packet;
	}
}
