package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {

	@Accessor("connection")
	void retroreplay$setConnection(Connection connection);
}
