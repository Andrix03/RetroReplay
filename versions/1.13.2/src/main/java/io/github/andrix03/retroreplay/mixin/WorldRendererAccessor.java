package io.github.andrix03.retroreplay.mixin;

import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.render.world.WorldRenderer;

/** Chunks waiting to be built: rendering a video waits until none are left. */
@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {

	@Accessor("dirtyChunks")
	Set<?> retroreplay$getDirtyChunks();
}
