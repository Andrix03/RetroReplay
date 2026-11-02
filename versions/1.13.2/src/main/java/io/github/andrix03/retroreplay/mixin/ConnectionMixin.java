package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.recording.WireFrames;
import io.netty.channel.ChannelHandlerContext;

import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.handler.PacketHandler;
import net.minecraft.network.packet.Packet;

/**
 * Every packet the play handler receives is recorded as it arrives (1.13 sets the play handler
 * on the network thread at login, before the first play packet). Multiplayer packets carry their
 * wire bytes (PacketDecoderMixin); singleplayer ones come as objects over a local channel and are
 * serialized.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

	@Shadow
	private PacketHandler listener;

	@Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"))
	private void retroreplay$record(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		byte[] raw = WireFrames.take(packet);
		if (listener instanceof ClientPlayNetworkHandler && Recorder.isRecording()) {
			if (raw != null) {
				Recorder.recordRaw(raw);
			} else {
				Recorder.record(packet);
			}
		}
	}
}
