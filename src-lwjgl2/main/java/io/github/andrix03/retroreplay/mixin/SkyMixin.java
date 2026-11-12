package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.render.RenderEffects;

import net.minecraft.client.render.world.WorldRenderer;

/** Chroma key videos: no sky and no clouds, the key color shows instead. */
@Mixin(WorldRenderer.class)
public class SkyMixin {

	@Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
	private void retroreplay$noSky(float tickDelta, CallbackInfo ci) {
		if (RenderEffects.chroma() != null) {
			ci.cancel();
		}
	}

	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void retroreplay$noClouds(float tickDelta, CallbackInfo ci) {
		if (RenderEffects.chroma() != null) {
			ci.cancel();
		}
	}
}
