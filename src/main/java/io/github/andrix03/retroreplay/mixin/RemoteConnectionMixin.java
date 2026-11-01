package io.github.andrix03.retroreplay.mixin;

import java.io.DataInput;
import java.io.IOException;
import java.net.Socket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.github.andrix03.retroreplay.recording.Recorder;

import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.RemoteConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Logger;

/** Multiplayer: every packet the read thread decodes for the client is recorded. */
@Mixin(RemoteConnection.class)
public class RemoteConnectionMixin {

	@Shadow
	private PacketHandler listener;

	@Redirect(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/Packet;deserialize(Lnet/minecraft/util/Logger;Ljava/io/DataInput;ZLjava/net/Socket;)Lnet/minecraft/network/packet/Packet;"))
	private Packet retroreplay$record(Logger logger, DataInput input, boolean server, Socket socket) throws IOException {
		Packet packet = Packet.deserialize(logger, input, server, socket);
		if (packet != null && listener instanceof ClientNetworkHandler) {
			Recorder.record(packet);
		}
		return packet;
	}
}
