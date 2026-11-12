package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.ClientPlayerInteractionManager;

/** The block being broken and how far, for the recording (MiningCracks): all private. */
@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {

	@Accessor("isMiningBlock")
	boolean retroreplay$isMining();

	@Accessor("target")
	net.minecraft.util.math.BlockPos retroreplay$getTarget();

	@Accessor("miningProgress")
	float retroreplay$getProgress();
}
