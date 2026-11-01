package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;

/** Leaving the world (disconnect, quit to title) ends the recording and the replay. */
@Mixin(Minecraft.class)
public class WorldCloseMixin {

	@Inject(method = "setWorld(Lnet/minecraft/client/world/ClientWorld;Ljava/lang/String;)V", at = @At("HEAD"))
	private void retroreplay$stop(ClientWorld world, String message, CallbackInfo ci) {
		if (world == null) {
			Recorder.stop();
			ReplayHandler.stop();
		}
	}
}
