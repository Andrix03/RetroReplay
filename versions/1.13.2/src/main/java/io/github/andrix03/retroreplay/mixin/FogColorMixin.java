package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.render.RenderEffects;

import net.minecraft.client.render.FogRenderer;
import net.minecraft.client.render.platform.GlStateManager;

/** Chroma key: the background (and the fog that fades into it) in the key color. */
@Mixin(FogRenderer.class)
public class FogColorMixin {

	@Shadow
	private float fogRed;
	@Shadow
	private float fogGreen;
	@Shadow
	private float fogBlue;

	@Inject(method = "setupClearColor", at = @At("TAIL"))
	private void retroreplay$chromaKey(float tickDelta, CallbackInfo ci) {
		float[] color = RenderEffects.chroma();
		if (color != null) {
			fogRed = color[0];
			fogGreen = color[1];
			fogBlue = color[2];
			GlStateManager.clearColor(color[0], color[1], color[2], 0);
		}
	}
}
