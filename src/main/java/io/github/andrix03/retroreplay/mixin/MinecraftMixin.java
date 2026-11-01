package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.LocalPlayerRecorder;
import io.github.andrix03.retroreplay.replay.DevAutorecord;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;

@Mixin(Minecraft.class)
public class MinecraftMixin {

	@Inject(method = "tick", at = @At("HEAD"))
	private void retroreplay$tickReplay(CallbackInfo ci) {
		ReplayHandler.tick();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void retroreplay$recordLocalPlayer(CallbackInfo ci) {
		ReplayHandler.tickEnd();
		DevAutorecord.tick();
		LocalPlayerRecorder.tick();
	}
}
