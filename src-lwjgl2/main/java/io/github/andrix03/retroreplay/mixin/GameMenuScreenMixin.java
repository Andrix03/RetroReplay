package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Like Replay Mod: in a replay "Disconnect" becomes "Exit Replay"; achievements, stats and LAN go. */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

	@Inject(method = "init", at = @At("TAIL"))
	private void retroreplay$replayMenu(CallbackInfo ci) {
		if (!ReplayHandler.isActive()) {
			return;
		}
		buttons.removeIf(button -> ((ButtonWidget) button).id == 5 || ((ButtonWidget) button).id == 6 || ((ButtonWidget) button).id == 7);
		for (Object element : buttons) {
			ButtonWidget button = (ButtonWidget) element;
			if (button.id == 1) {
				button.message = "Exit Replay";
			}
		}
	}
}
