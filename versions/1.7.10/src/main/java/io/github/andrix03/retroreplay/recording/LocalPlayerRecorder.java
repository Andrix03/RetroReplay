package io.github.andrix03.retroreplay.recording;

import io.github.andrix03.retroreplay.mixin.MinecraftAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.AddPlayerS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEventS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityHeadAnglesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTeleportS2CPacket;

/**
 * The server never tells a client about its own player, so the recording would miss it.
 * Like Replay Mod, every client tick we write the packets another client would have received:
 * spawn (again after a respawn or dimension change), position, head, arm swing, equipment, flags.
 */
public final class LocalPlayerRecorder {

	private static LocalClientPlayerEntity spawned;
	private static int lastX, lastY, lastZ;
	private static byte lastYaw, lastPitch, lastHead;
	private static boolean lastSwinging;
	private static int lastSwingTicks;
	private static byte lastFlags;
	private static int lastDamagedTimer;
	private static boolean lastAlive;
	private static final ItemStack[] lastEquipment = new ItemStack[5];

	private LocalPlayerRecorder() {
	}

	public static void reset() {
		spawned = null;
	}

	public static void tick() {
		if (!Recorder.isRecording()) {
			spawned = null;
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		Recorder.setPaused(((MinecraftAccessor) minecraft).retroreplay$isPaused());
		LocalClientPlayerEntity player = minecraft.player;
		if (player == null || minecraft.world == null) {
			return;
		}
		if (player != spawned) {
			spawned = player;
			Recorder.record(new AddPlayerS2CPacket(player));
			EntityTeleportS2CPacket teleport = new EntityTeleportS2CPacket(player);
			lastX = teleport.getX();
			lastY = teleport.getY();
			lastZ = teleport.getZ();
			lastYaw = teleport.getYaw();
			lastPitch = teleport.getPitch();
			lastHead = angle(player.headYaw);
			lastSwinging = player.armSwinging;
			lastSwingTicks = player.armSwingingTicks;
			lastFlags = player.getSyncedData().getByte(0);
			lastDamagedTimer = player.damagedTimer;
			lastAlive = player.isAlive();
			for (int slot = 0; slot < lastEquipment.length; slot++) {
				lastEquipment[slot] = copy(player.getEquipment(slot));
				if (slot > 0 && lastEquipment[slot] != null) {
					Recorder.record(new EntityEquipmentS2CPacket(player.getNetworkId(), slot, lastEquipment[slot]));
				}
			}
			return;
		}

		EntityTeleportS2CPacket teleport = new EntityTeleportS2CPacket(player);
		if (teleport.getX() != lastX || teleport.getY() != lastY || teleport.getZ() != lastZ || teleport.getYaw() != lastYaw || teleport.getPitch() != lastPitch) {
			Recorder.record(teleport);
			lastX = teleport.getX();
			lastY = teleport.getY();
			lastZ = teleport.getZ();
			lastYaw = teleport.getYaw();
			lastPitch = teleport.getPitch();
		}

		byte head = angle(player.headYaw);
		if (head != lastHead) {
			Recorder.record(new EntityHeadAnglesS2CPacket(player, head));
			lastHead = head;
		}

		if (player.armSwinging && (!lastSwinging || player.armSwingingTicks < lastSwingTicks)) {
			Recorder.record(new EntityAnimationS2CPacket(player, 0)); // 0 = swing arm since 1.7 (1 is the hurt flash)
		}
		lastSwinging = player.armSwinging;
		lastSwingTicks = player.armSwingingTicks;

		byte flags = player.getSyncedData().getByte(0);
		if (flags != lastFlags) {
			Recorder.record(new EntityDataS2CPacket(player.getNetworkId(), player.getSyncedData(), true));
			lastFlags = flags;
		}

		// hurt flash and death animation, as the server reports them for other players
		if (player.damagedTimer > lastDamagedTimer) {
			Recorder.record(new EntityEventS2CPacket(player, Recorder.EVENT_HURT));
		}
		lastDamagedTimer = player.damagedTimer;
		boolean alive = player.isAlive();
		if (lastAlive && !alive) {
			Recorder.record(new EntityEventS2CPacket(player, Recorder.EVENT_DEATH));
		}
		lastAlive = alive;

		for (int slot = 0; slot < lastEquipment.length; slot++) {
			ItemStack item = player.getEquipment(slot);
			if (!ItemStack.matches(item, lastEquipment[slot])) {
				lastEquipment[slot] = copy(item);
				Recorder.record(new EntityEquipmentS2CPacket(player.getNetworkId(), slot, item));
			}
		}
	}

	private static byte angle(float degrees) {
		return (byte) (degrees * 256.0F / 360.0F);
	}

	private static ItemStack copy(ItemStack item) {
		return item == null ? null : item.copy();
	}
}
