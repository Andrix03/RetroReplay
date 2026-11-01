package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.render.GameRenderer;

/** The replay camera has no hand. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void retroreplay$noHand(float tickDelta, int anaglyphRenderPass, CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			ci.cancel();
		}
	}
}
