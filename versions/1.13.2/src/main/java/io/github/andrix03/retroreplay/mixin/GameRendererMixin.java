package io.github.andrix03.retroreplay.mixin;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.pathing.CameraRoll;
import io.github.andrix03.retroreplay.pathing.PathPlayer;
import io.github.andrix03.retroreplay.pathing.PathPreview;
import io.github.andrix03.retroreplay.render.VideoRenderer;
import io.github.andrix03.retroreplay.replay.MiningCracks;
import io.github.andrix03.retroreplay.replay.ReplayHandler;
import io.github.andrix03.retroreplay.replay.Thumbnail;

import net.minecraft.client.render.GameRenderer;

/** The replay camera has no hand and targets nothing (no block outline, which near a block shows as long lines). */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

	/** A video's cube faces are drawn at 90 degrees, whatever the options say. */
	@Inject(method = "getFov", at = @At("HEAD"), cancellable = true)
	private void retroreplay$renderFov(float tickDelta, boolean usePlayerFov, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Double> cir) {
		if (io.github.andrix03.retroreplay.render.RenderEffects.hasFov()) {
			cir.setReturnValue((double) io.github.andrix03.retroreplay.render.RenderEffects.fov());
		}
	}

	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void retroreplay$noHand(float tickDelta, CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			ci.cancel();
		}
	}

	/** A playing camera path moves the camera every frame, not every tick, so it is smooth. */
	@Inject(method = "render(FJZ)V", at = @At("HEAD"), cancellable = true)
	private void retroreplay$playPath(float tickDelta, long time, boolean renderWorld, CallbackInfo ci) {
		if (VideoRenderer.onFrame()) {
			ci.cancel(); // a whole video was rendered instead of this frame
			return;
		}
		PathPlayer.onFrame();
		Thumbnail.beforeFrame();
	}

	@Inject(method = "render(FJZ)V", at = @At("RETURN"))
	private void retroreplay$thumbnail(float tickDelta, long time, boolean renderWorld, CallbackInfo ci) {
		Thumbnail.afterFrame();
	}

	/** The path preview is part of the world: after the weather, before clouds and the hand (1.13: the world pass). */
	@Inject(method = "render(FJ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderSnowAndRain(F)V", shift = At.Shift.AFTER))
	private void retroreplay$pathPreview(float tickDelta, long time, CallbackInfo ci) {
		io.github.andrix03.retroreplay.render.RenderEffects.worldDrawn();
		if (ReplayHandler.isActive()) {
			PathPreview.render(tickDelta);
			if (MiningCracks.active()) { // the recording player's, written by the recorder
				Mc.renderMiningCrack(MiningCracks.x(), MiningCracks.y(), MiningCracks.z(), MiningCracks.stage(), tickDelta);
			}
		}
	}

	/** It casts the camera entity to the local player: skipped while spectating someone else. */
	@Inject(method = "tickFov", at = @At("HEAD"), cancellable = true)
	private void retroreplay$fovWhileSpectating(CallbackInfo ci) {
		if (Mc.cameraEntity() != Mc.minecraft().player) {
			ci.cancel();
		}
	}

	/** Replay Mod's camera roll: turns the whole view around the eye's axis. */
	@Inject(method = "transformCamera", at = @At("HEAD"))
	private void retroreplay$roll(float tickDelta, CallbackInfo ci) {
		if (ReplayHandler.isActive() && CameraRoll.get() != 0) {
			GL11.glRotatef(CameraRoll.get(), 0, 0, 1);
		}
	}

	@Inject(method = "pick", at = @At("TAIL"))
	private void retroreplay$noTarget(float tickDelta, CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			Mc.minecraft().crosshairTarget = null;
		}
	}
}
