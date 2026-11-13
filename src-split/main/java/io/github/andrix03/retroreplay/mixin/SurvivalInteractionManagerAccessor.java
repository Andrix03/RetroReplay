package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.SurvivalInteractionManager;

/** Singleplayer's block breaking (survival): the block and how far, for the cracks in the recording. */
@Mixin(SurvivalInteractionManager.class)
public interface SurvivalInteractionManagerAccessor {

	@Accessor("targetBlockX")
	int retroreplay$getTargetX();

	@Accessor("targetBlockY")
	int retroreplay$getTargetY();

	@Accessor("targetBlockZ")
	int retroreplay$getTargetZ();

	@Accessor("miningProgress")
	float retroreplay$getProgress();
}
