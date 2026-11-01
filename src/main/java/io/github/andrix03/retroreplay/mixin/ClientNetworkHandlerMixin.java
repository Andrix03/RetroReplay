package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.handler.ClientNetworkHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.network.LocalConnection;
import net.minecraft.server.integrated.IntegratedConnectionListener;
import net.minecraft.server.integrated.IntegratedServer;

/** Starts a recording when the client opens a connection and stops it when the world closes. */
@Mixin(ClientNetworkHandler.class)
public class ClientNetworkHandlerMixin {

	@Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Ljava/lang/String;I)V", at = @At("TAIL"))
	private void retroreplay$startRemote(Minecraft minecraft, String address, int port, CallbackInfo ci) {
		Recorder.start(false, address + ":" + port);
	}

	@Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Ljava/lang/String;ILnet/minecraft/client/gui/screen/Screen;)V", at = @At("TAIL"))
	private void retroreplay$startRemoteWithScreen(Minecraft minecraft, String address, int port, Screen callbackScreen, CallbackInfo ci) {
		Recorder.start(false, address + ":" + port);
	}

	@Inject(method = "<init>(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/integrated/IntegratedServer;)V", at = @At("TAIL"))
	private void retroreplay$startLocal(Minecraft minecraft, IntegratedServer server, CallbackInfo ci) {
		if (!ReplayHandler.isStarting()) {
			Recorder.start(true, "");
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
