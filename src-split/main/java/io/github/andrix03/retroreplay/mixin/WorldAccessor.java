package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSource;

/** The world's chunk source, to ask whether a chunk is loaded without loading it (NativeRecorder). */
@Mixin(World.class)
public interface WorldAccessor {

	@Accessor("chunkSource")
	ChunkSource retroreplay$getChunkSource();
}
