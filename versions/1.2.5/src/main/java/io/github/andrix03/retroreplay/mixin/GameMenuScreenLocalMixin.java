package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import io.github.andrix03.retroreplay.server.LocalServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.menu.GameMenuScreen;

/** On the local server the pause menu reads "Save and Quit to Title", like singleplayer. */
@Mixin(GameMenuScreen.class)
public class GameMenuScreenLocalMixin {

	@Redirect(method = "init", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isMultiplayer()Z"))
	private boolean retroreplay$singleplayerLabels(Minecraft minecraft) {
		return minecraft.isMultiplayer() && !LocalServer.isJoined();
	}
}
