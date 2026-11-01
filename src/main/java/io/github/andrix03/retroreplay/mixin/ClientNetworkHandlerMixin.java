package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.network.Connection;
import net.minecraft.network.LocalConnection;
import net.minecraft.server.integrated.IntegratedConnectionListener;
import net.minecraft.server.integrated.IntegratedServer;

/** Starts a recording when the client opens a connection and stops it when the world closes. */
@Mixin(ClientNetworkHandler.class)
public class ClientNetworkHandlerMixin {

	@Shadow
	private Connection connection;

	// Every constructor (their number differs between versions): a LocalConnection means singleplayer.
	@Inject(method = "<init>*", at = @At("TAIL"))
	private void retroreplay$start(CallbackInfo ci) {
		if (ReplayHandler.isStarting()) {
			return;
		}
		if (connection instanceof LocalConnection) {
			Recorder.start(true, "");
		} else {
			String address = String.valueOf(connection.getAddress());
			Recorder.start(false, address.startsWith("/") ? address.substring(1) : address);
		}
	}

	// A replay builds the singleplayer handler with no server: skip the server and bind a sink.
	@Redirect(method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/integrated/IntegratedServer;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/integrated/IntegratedServer;getConnection()Lnet/minecraft/server/integrated/IntegratedConnectionListener;"))
	private IntegratedConnectionListener retroreplay$serverConnection(IntegratedServer server) {
		return ReplayHandler.isStarting() ? null : server.getConnection();
	}

	@Redirect(method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/integrated/IntegratedServer;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/integrated/IntegratedConnectionListener;setConnection(Lnet/minecraft/network/LocalConnection;Ljava/lang/String;)V"))
	private void retroreplay$bindConnection(IntegratedConnectionListener listener, LocalConnection connection, String playerName) {
		if (ReplayHandler.isStarting()) {
			ReplayHandler.bind(connection);
		} else {
			listener.setConnection(connection, playerName);
		}
	}

	@Inject(method = "closeWorld", at = @At("HEAD"))
	private void retroreplay$stop(CallbackInfo ci) {
		Recorder.stop();
		ReplayHandler.stop();
	}
}
