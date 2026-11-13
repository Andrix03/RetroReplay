package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.github.andrix03.retroreplay.recording.NativeRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;

/** The player picking up an item, arrow or orb, for the native recording (read only). */
@Mixin(ClientPlayerEntity.class)
public class NativePickupMixin {

	@Inject(method = "pickUp", at = @At("HEAD"))
	private void retroreplay$pickUp(Entity item, int count, CallbackInfo ci) {
		NativeRecorder.pickUp((Entity) (Object) this, item);
	}
}
