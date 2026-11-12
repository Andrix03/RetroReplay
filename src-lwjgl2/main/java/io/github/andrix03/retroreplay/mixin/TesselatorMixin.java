package io.github.andrix03.retroreplay.mixin;

import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.render.vertex.Tesselator;

/**
 * Intel Iris Xe (driver 31.0.101.4502): items in hand, dropped items and the hotbar came out
 * black or invisible on these versions, vanilla too. The texture coordinates of a draw went to
 * the lightmap's texture unit: the client active texture unit is left on it after the world's
 * chunk display lists, though every draw sets it back. Each draw now starts from unit 0.
 */
@Mixin(Tesselator.class)
public class TesselatorMixin {

	@Inject(method = "end", at = @At("HEAD"))
	private void retroreplay$textureUnitZero(CallbackInfoReturnable<Integer> cir) {
		GL13.glClientActiveTexture(GL13.GL_TEXTURE0);
	}
}
