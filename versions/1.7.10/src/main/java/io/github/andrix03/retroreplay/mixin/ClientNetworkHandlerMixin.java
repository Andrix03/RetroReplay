package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.network.handler.ClientPlayNetworkHandler;
import net.minecraft.network.Connection;

/** Starts a recording when the client enters the play state (singleplayer runs on a local channel). */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientNetworkHandlerMixin {

	@Shadow
	@Final
	private Connection connection;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void retroreplay$start(CallbackInfo ci) {
		if (ReplayHandler.isStarting()) {
			return;
		}
		if (connection.isLocal()) {
			Recorder.start(true, "");
		} else {
			String address = String.valueOf(connection.getAddress());
			Recorder.start(false, address.startsWith("/") ? address.substring(1) : address);
		}
	}
}
