package io.github.andrix03.retroreplay.mixin;

import java.io.File;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.RetroReplay;
import io.github.andrix03.retroreplay.replay.DevAutoplay;
import io.github.andrix03.retroreplay.replay.DevAutorecord;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Prototype entry point: a "Replay" button that opens the newest recording. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

	private static final int REPLAY_BUTTON = 7_000;

	@Inject(method = "init", at = @At("TAIL"))
	private void retroreplay$addButton(CallbackInfo ci) {
		buttons.add(new ButtonWidget(REPLAY_BUTTON, width / 2 - 154, height / 4 + 48, 50, 20, "Replay"));
		if (!DevAutorecord.onTitleScreen()) {
			DevAutoplay.onTitleScreen();
		}
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void retroreplay$onButton(ButtonWidget button, CallbackInfo ci) {
		if (button.id == REPLAY_BUTTON) {
			ci.cancel();
			File latest = ReplayHandler.latestRecording();
			if (latest == null) {
				RetroReplay.LOGGER.info("No recordings in replay_recordings");
			} else {
				ReplayHandler.open(latest);
			}
		}
	}
}
