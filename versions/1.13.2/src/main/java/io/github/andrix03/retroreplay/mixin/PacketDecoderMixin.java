package io.github.andrix03.retroreplay.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.recording.WireFrames;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import net.minecraft.network.Connection;
import net.minecraft.network.NetworkProtocol;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketFlow;

/** Multiplayer: each play frame the client decodes keeps its bytes (VarInt id + payload) for ConnectionMixin. */
@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {

	@Shadow
	@Final
	private PacketFlow flow;

	@Unique
	private int retroreplay$start;

	@Inject(method = "decode", at = @At("HEAD"))
	private void retroreplay$mark(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {
		retroreplay$start = in.readerIndex();
	}

	@Inject(method = "decode", at = @At("RETURN"))
	private void retroreplay$keep(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {
		if (flow != PacketFlow.CLIENTBOUND || out.size() != 1 || !Recorder.isRecording()
			|| context.channel().attr(Connection.PROTOCOL).get() != NetworkProtocol.PLAY) {
			return;
		}
		byte[] data = new byte[in.readerIndex() - retroreplay$start];
		in.getBytes(retroreplay$start, data);
		WireFrames.decoded(out.get(0), data);
	}
}
