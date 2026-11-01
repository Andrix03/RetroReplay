package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.github.andrix03.retroreplay.recording.PacketRawBytes;
import io.github.andrix03.retroreplay.recording.Recorder;

import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.handler.PacketHandler;
import net.minecraft.network.packet.Packet;

/**
 * Every packet the play handler takes is recorded at that moment: the first ones arrive while
 * the login handler is still the listener. Multiplayer packets carry their wire bytes
 * (PacketDecoderMixin); singleplayer ones come as objects over a local channel and are serialized.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Redirect(method = { "tick", "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V" }, at = @At(value = "INVOKE", target = "Lnet/minecraft/network/packet/Packet;handle(Lnet/minecraft/network/handler/PacketHandler;)V"))
	private void retroreplay$record(Packet packet, PacketHandler listener) {
		if (listener instanceof ClientPlayNetworkHandler && Recorder.isRecording()) {
			byte[] raw = ((PacketRawBytes) packet).retroreplay$raw();
			if (raw != null) {
				Recorder.recordRaw(raw);
			} else {
				Recorder.record(packet);
			}
		}
		packet.handle(listener);
	}
}
