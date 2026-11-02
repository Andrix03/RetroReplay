package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.render.platform.Window;

/** A video frame at another size than the window: the game reads its drawing size from these. */
@Mixin(Window.class)
public interface WindowAccessor {

	@Accessor("framebufferWidth")
	void retroreplay$setFramebufferWidth(int width);

	@Accessor("framebufferHeight")
	void retroreplay$setFramebufferHeight(int height);
}
