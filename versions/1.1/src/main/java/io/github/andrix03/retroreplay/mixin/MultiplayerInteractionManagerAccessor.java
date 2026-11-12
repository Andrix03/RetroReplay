package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.MultiplayerInteractionManager;

/** The block being broken and how far, for the recording (MiningCracks): all private. */
@Mixin(MultiplayerInteractionManager.class)
public interface MultiplayerInteractionManagerAccessor {

	@Accessor("isMiningBlock")
	boolean retroreplay$isMining();

	@Accessor("targetBlockX")
	int retroreplay$getTargetX();

	@Accessor("targetBlockY")
	int retroreplay$getTargetY();

	@Accessor("targetBlockZ")
	int retroreplay$getTargetZ();

	@Accessor("miningProgress")
	float retroreplay$getProgress();
}
