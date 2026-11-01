package io.github.andrix03.retroreplay.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.PacketRawBytes;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

import net.minecraft.network.Connection;
import net.minecraft.network.NetworkProtocol;
import net.minecraft.network.PacketDecoder;

/**
 * Multiplayer: every play packet the client decodes keeps its frame (VarInt id + payload).
 * It is recorded later, when the play handler takes it (ConnectionMixin): the first ones are
 * decoded before the recording starts.
 */
@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {

	@Unique
	private int retroreplay$start;

	@Inject(method = "decode", at = @At("HEAD"))
	private void retroreplay$mark(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {
		retroreplay$start = in.readerIndex();
	}

	@Inject(method = "decode", at = @At("RETURN"))
	private void retroreplay$keep(ChannelHandlerContext context, ByteBuf in, List<Object> out, CallbackInfo ci) {
		if (out.size() != 1 || context.channel().attr(Connection.PROTOCOL).get() != NetworkProtocol.PLAY) {
			return;
		}
		Connection connection = context.pipeline().get(Connection.class);
		if (connection == null || !((ConnectionAccessor) connection).retroreplay$isClient()) {
			return;
		}
		byte[] data = new byte[in.readerIndex() - retroreplay$start];
		in.getBytes(retroreplay$start, data);
		((PacketRawBytes) out.get(0)).retroreplay$setRaw(data);
	}
}
