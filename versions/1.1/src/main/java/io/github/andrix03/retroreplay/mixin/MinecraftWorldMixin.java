package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.gui.LocalServerScreen;
import io.github.andrix03.retroreplay.recording.Recorder;
import io.github.andrix03.retroreplay.replay.ReplayHandler;
import io.github.andrix03.retroreplay.server.LocalServer;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.mob.player.PlayerEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;

/** Singleplayer worlds open on the local server; leaving a world ends recordings, replays and that server. */
@Mixin(Minecraft.class)
public abstract class MinecraftWorldMixin {

	private static boolean retroreplay$vanilla;

	// the pause menu pauses the game only outside multiplayer: the local server counts as singleplayer
	@Redirect(method = "runGame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isMultiplayer()Z"))
	private boolean retroreplay$pauseOnLocalServer(Minecraft minecraft) {
		return minecraft.isMultiplayer() && !(LocalServer.isJoined() && minecraft.world != null);
	}

	@Inject(method = "startGame", at = @At("HEAD"), cancellable = true)
	private void retroreplay$startOnLocalServer(String dirName, String displayName, WorldSettings settings, CallbackInfo ci) {
		if (retroreplay$vanilla) {
			return;
		}
		Minecraft minecraft = (Minecraft) (Object) this;
		ci.cancel();
		LocalServer.NewWorld newWorld = settings == null ? null
			: new LocalServer.NewWorld(settings.getSeed(), settings.getGameMode(), settings.allowStructures(), settings.getGeneratorType().name(), settings.isHardcore());
		LocalServer.launch(minecraft.gameDir, dirName, displayName, newWorld, minecraft.session.username, minecraft.options.difficulty);
		minecraft.openScreen(new LocalServerScreen(() -> {
			retroreplay$vanilla = true;
			try {
				minecraft.startGame(dirName, displayName, settings);
			} finally {
				retroreplay$vanilla = false;
			}
		}));
	}

	@Inject(method = "setWorld(Lnet/minecraft/world/World;Ljava/lang/String;Lnet/minecraft/entity/mob/player/PlayerEntity;)V", at = @At("HEAD"))
	private void retroreplay$leaveWorld(World world, String message, PlayerEntity player, CallbackInfo ci) {
		if (world == null) {
			Recorder.stop();
			ReplayHandler.stop();
			LocalServer.left();
		}
	}
}
