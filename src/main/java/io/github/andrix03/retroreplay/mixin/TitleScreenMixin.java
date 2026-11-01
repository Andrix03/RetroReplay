package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.gui.ReplayGui;
import io.github.andrix03.retroreplay.gui.ReplayViewerScreen;
import io.github.andrix03.retroreplay.replay.DevAutoplay;
import io.github.andrix03.retroreplay.replay.DevAutorecord;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Replay Mod's main menu button: a 20x20 button with the Replay Mod logo, 4 px to the right of
 * the lowest of Singleplayer / Multiplayer / Minecraft Realms. It opens the Replay Viewer.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

	private static final int REPLAY_VIEWER_BUTTON = 17890234;

	private ButtonWidget retroreplay$button;

	@Inject(method = "init", at = @At("TAIL"))
	private void retroreplay$addButton(CallbackInfo ci) {
		ButtonWidget anchor = null;
		for (ButtonWidget button : buttons) {
			if ((button.id == 1 || button.id == 2 || button.id == 14) && button.visible && (anchor == null || button.y > anchor.y)) {
				anchor = button;
			}
		}
		int x = anchor == null ? width - 25 : anchor.x + 200 + 4;
		int y = anchor == null ? 5 : anchor.y;
		buttons.add(retroreplay$button = new ButtonWidget(REPLAY_VIEWER_BUTTON, x, y, 20, 20, ""));
		if (!DevAutorecord.onTitleScreen()) {
			DevAutoplay.onTitleScreen();
		}
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void retroreplay$drawLogo(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
		if (retroreplay$button != null) {
			ReplayGui.bind(ReplayGui.LOGO);
			ReplayGui.stretched(retroreplay$button.x + 3, retroreplay$button.y + 3, 14, 14);
			if (mouseX >= retroreplay$button.x && mouseY >= retroreplay$button.y && mouseX < retroreplay$button.x + 20 && mouseY < retroreplay$button.y + 20) {
				int w = ReplayGui.width("Replay Viewer");
				ReplayGui.fill(mouseX + 9, mouseY - 15, mouseX + 15 + w, mouseY - 1, 0xF0100010);
				ReplayGui.text("Replay Viewer", mouseX + 12, mouseY - 12, ReplayGui.WHITE);
			}
		}
	}

	@Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
	private void retroreplay$onButton(ButtonWidget button, CallbackInfo ci) {
		if (button.id == REPLAY_VIEWER_BUTTON) {
			ci.cancel();
			minecraft.openScreen(new ReplayViewerScreen(this));
		}
	}
}
