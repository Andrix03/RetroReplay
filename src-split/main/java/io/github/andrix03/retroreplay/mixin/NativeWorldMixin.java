package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.andrix03.retroreplay.recording.NativeRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.world.explosion.Explosion;

/**
 * The world's calls a server turns into packets and singleplayer handles in place: only read
 * for the native recording, nothing about them changes.
 */
@Mixin(World.class)
public class NativeWorldMixin {

	@Inject(method = "doEntityEvent", at = @At("HEAD"))
	private void retroreplay$entityEvent(Entity entity, byte event, CallbackInfo ci) {
		NativeRecorder.entityEvent((World) (Object) this, entity, event);
	}

	@Inject(method = "addBlockEvent", at = @At("HEAD"))
	private void retroreplay$blockEvent(int x, int y, int z, int type, int data, CallbackInfo ci) {
		NativeRecorder.blockEvent((World) (Object) this, x, y, z, type, data);
	}

	@Inject(method = "explode(Lnet/minecraft/entity/Entity;DDDFZ)Lnet/minecraft/world/explosion/Explosion;", at = @At("RETURN"))
	private void retroreplay$explosion(Entity source, double x, double y, double z, float power, boolean createFire, CallbackInfoReturnable<Explosion> cir) {
		Explosion explosion = cir.getReturnValue();
		if (explosion != null) {
			NativeRecorder.explosion((World) (Object) this, explosion.x, explosion.y, explosion.z, explosion.power, explosion.damagedBlocks);
		}
	}

	@Inject(method = "addGlobalEntity", at = @At("HEAD"))
	private void retroreplay$globalEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		NativeRecorder.globalEntity((World) (Object) this, entity);
	}
}
