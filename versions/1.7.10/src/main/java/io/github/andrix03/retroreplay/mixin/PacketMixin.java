package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import io.github.andrix03.retroreplay.recording.PacketRawBytes;

import net.minecraft.network.packet.Packet;

@Mixin(Packet.class)
public class PacketMixin implements PacketRawBytes {

	@Unique
	private byte[] retroreplay$raw;

	@Override
	public byte[] retroreplay$raw() {
		return retroreplay$raw;
	}

	@Override
	public void retroreplay$setRaw(byte[] raw) {
		retroreplay$raw = raw;
	}
}
