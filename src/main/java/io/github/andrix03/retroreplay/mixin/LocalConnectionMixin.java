package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;

import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.LocalConnection;
import net.minecraft.network.PacketHandler;
import net.minecraft.network.packet.Packet;

/** Singleplayer: the integrated server hands packets to the client's LocalConnection here. */
@Mixin(LocalConnection.class)
public class LocalConnectionMixin {

	@Shadow
	private PacketHandler listener;

	@Inject(method = "accept", at = @At("HEAD"))
	private void retroreplay$record(Packet packet, CallbackInfo ci) {
		if (listener instanceof ClientNetworkHandler) {
			Recorder.record(packet);
		}
	}
}
