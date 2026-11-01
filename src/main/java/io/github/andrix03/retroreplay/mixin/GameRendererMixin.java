package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathPreview;
import io.github.andrix03.retroreplay.render.VideoRenderer;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.render.GameRenderer;

/** The replay camera has no hand and targets nothing (no block outline, which near a block shows as long lines). */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void retroreplay$noHand(float tickDelta, int anaglyphRenderPass, CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			ci.cancel();
		}
	}

	/** A playing camera path moves the camera every frame, not every tick, so it is smooth. */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void retroreplay$playPath(float tickDelta, CallbackInfo ci) {
		if (VideoRenderer.onFrame()) {
			ci.cancel(); // a whole video was rendered instead of this frame
			return;
		}
		PathPlayer.onFrame();
	}

	/** The path preview is part of the world: after the weather, before clouds and the hand. */
	@Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderSnowAndRain(F)V", shift = At.Shift.AFTER))
	private void retroreplay$pathPreview(float tickDelta, long time, CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			PathPreview.render(tickDelta);
		}
	}

	@Inject(method = "pick", at = @At("TAIL"))
	private void retroreplay$noTarget(float tickDelta, CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			Mc.minecraft().crosshairTarget = null;
		}
	}
}
