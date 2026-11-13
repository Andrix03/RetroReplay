package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.github.andrix03.retroreplay.recording.NativeRecorder;
import net.minecraft.world.chunk.WorldChunk;

/** Every block or metadata change of a chunk, for the native recording's block changes (read only). */
@Mixin(WorldChunk.class)
public class NativeChunkMixin {

	@Inject(method = "setBlockWithMetadataAt", at = @At("RETURN"))
	private void retroreplay$blockAndMetadata(int localX, int y, int localZ, int block, int metadata, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			NativeRecorder.blockChanged((WorldChunk) (Object) this, localX, y, localZ);
		}
	}

	@Inject(method = "setBlockAt", at = @At("RETURN"))
	private void retroreplay$block(int localX, int y, int localZ, int block, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			NativeRecorder.blockChanged((WorldChunk) (Object) this, localX, y, localZ);
		}
	}

	@Inject(method = "setBlockMetadataAt", at = @At("RETURN"))
	private void retroreplay$metadata(int localX, int y, int localZ, int metadata, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			NativeRecorder.blockChanged((WorldChunk) (Object) this, localX, y, localZ);
		}
	}
}
