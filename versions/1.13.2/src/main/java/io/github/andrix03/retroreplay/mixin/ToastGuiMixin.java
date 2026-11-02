package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.gui.toast.ToastGui;

/** No advancement, recipe or tutorial popups over a replay. */
@Mixin(ToastGui.class)
public class ToastGuiMixin {

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void retroreplay$noToasts(CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			ci.cancel();
		}
	}
}
