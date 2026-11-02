package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.compat.Platform;

import net.minecraft.client.MouseHandler;

/** GLFW reports the wheel by callback: collected for Platform.wheel(). */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

	@Inject(method = "onScroll", at = @At("HEAD"))
	private void retroreplay$scroll(long window, double x, double y, CallbackInfo ci) {
		Platform.onScroll(y);
	}
}
