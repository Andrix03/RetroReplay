package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.render.platform.InputConstants;

/** The key a binding is set to (1.13 has no getter): the chat key frees the mouse in a replay. */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {

	@Accessor("key")
	InputConstants.Key retroreplay$getKey();
}
