package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.Minecraft;

/** 1.2.5 has no Minecraft.getInstance(), only the private field. */
@Mixin(Minecraft.class)
public interface MinecraftInstanceAccessor {

	@Accessor("INSTANCE")
	static Minecraft retroreplay$getInstance() {
		throw new AssertionError();
	}
}
