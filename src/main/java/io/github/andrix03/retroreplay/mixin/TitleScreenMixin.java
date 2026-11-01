package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.RetroReplay;

import net.minecraft.client.gui.screen.menu.TitleScreen;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

	@Inject(method = "init", at = @At("TAIL"))
	private void retroreplay$onInit(CallbackInfo ci) {
		RetroReplay.LOGGER.info("RetroReplay mixin applied to the title screen");
	}
}
