package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.Connection;

@Mixin(Connection.class)
public interface ConnectionAccessor {

	@Accessor("isClient")
	boolean retroreplay$isClient();
}
