package io.github.andrix03.retroreplay.mixin;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import net.minecraft.util.Logger;

/** Multiplayer: every packet the read thread decodes for the client is recorded byte for byte. */
@Mixin(RemoteConnection.class)
public class RemoteConnectionMixin {

	@Shadow
	private PacketHandler listener;

	@Redirect(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/Packet;deserialize(Lnet/minecraft/util/Logger;Ljava/io/DataInput;ZLjava/net/Socket;)Lnet/minecraft/network/packet/Packet;"))
	private Packet retroreplay$record(Logger logger, DataInput input, boolean server, Socket socket) throws IOException {
		if (!(listener instanceof ClientNetworkHandler) || !Recorder.isRecording() || !(input instanceof InputStream)) {
			return Packet.deserialize(logger, input, server, socket);
		}
		TeeInputStream tee = new TeeInputStream((InputStream) input);
		Packet packet = Packet.deserialize(logger, new DataInputStream(tee), server, socket);
		if (packet != null) {
			Recorder.recordRaw(tee.bytes());
		}
		return packet;
	}
}
