package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Like Replay Mod: in a replay "Disconnect" becomes "Exit Replay"; advancements, stats and LAN go. */
@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {

	@Inject(method = "init", at = @At("TAIL"))
	private void retroreplay$replayMenu(CallbackInfo ci) {
		if (!ReplayHandler.isActive()) {
			return;
		}
		// 1.13 clicks go to the children: they leave both lists
		buttons.removeIf(button -> button.id == 5 || button.id == 6 || button.id == 7);
		children.removeIf(child -> child instanceof ButtonWidget && !buttons.contains(child));
		for (ButtonWidget button : buttons) {
			if (button.id == 1) {
				button.message = "Exit Replay";
			}
		}
	}
}
