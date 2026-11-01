package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.gui.RecordingIndicator;
import io.github.andrix03.retroreplay.gui.ReplayOverlay;
import io.github.andrix03.retroreplay.gui.ReplayOverlayScreen;
import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.render.Window;

/** In a replay the HUD is replaced by the replay bar; while recording, the indicator is added. */
@Mixin(GameGui.class)
public class GameGuiMixin {

	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void retroreplay$replayHud(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
		if (ReplayHandler.isActive()) {
			ci.cancel();
			minecraft.gameRenderer.setupGuiState();
			if (!(minecraft.screen instanceof ReplayOverlayScreen)) {
				ReplayOverlay.render(new Window(minecraft.options, minecraft.width, minecraft.height).getWidth(), 0, 0, false);
			}
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void retroreplay$recordingIndicator(float tickDelta, boolean screenOpen, int mouseX, int mouseY, CallbackInfo ci) {
		if (Recorder.isRecording()) {
			RecordingIndicator.render(Recorder.isPaused());
		}
	}
}
