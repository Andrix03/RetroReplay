package io.github.andrix03.retroreplay.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.network.packet.s2c.play.LoginS2CPacket;

/** The replay camera takes its own entity id from the recorded login. */
@Mixin(LoginS2CPacket.class)
public interface LoginS2CPacketAccessor {

	@Accessor("gameVersion") // the player's entity id (Feather's name)
	void retroreplay$setEntityId(int id);
}
