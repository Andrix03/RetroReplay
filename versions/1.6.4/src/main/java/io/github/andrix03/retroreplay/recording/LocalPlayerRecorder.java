package io.github.andrix03.retroreplay.recording;

import io.github.andrix03.retroreplay.mixin.MinecraftAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.AddPlayerPacket;
import net.minecraft.network.packet.EntityAnimationPacket;
import net.minecraft.network.packet.EntityDataPacket;
import net.minecraft.network.packet.EntityEquipmentPacket;
import net.minecraft.network.packet.EntityHeadAnglesPacket;
import net.minecraft.network.packet.EntityTeleportPacket;

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
			Recorder.record(new AddPlayerPacket(player));
			EntityTeleportPacket teleport = new EntityTeleportPacket(player);
			lastX = teleport.x;
			lastY = teleport.y;
			lastZ = teleport.z;
			lastYaw = teleport.yaw;
			lastPitch = teleport.pitch;
			lastHead = angle(player.headYaw);
			lastSwinging = player.armSwinging;
			lastSwingTicks = player.armSwingingTicks;
			lastFlags = player.getSyncedData().getByte(0);
			for (int slot = 0; slot < lastEquipment.length; slot++) {
				lastEquipment[slot] = copy(player.getEquipment(slot));
				if (slot > 0 && lastEquipment[slot] != null) {
					Recorder.record(new EntityEquipmentPacket(player.networkId, slot, lastEquipment[slot]));
				}
			}
			return;
		}

		EntityTeleportPacket teleport = new EntityTeleportPacket(player);
		if (teleport.x != lastX || teleport.y != lastY || teleport.z != lastZ || teleport.yaw != lastYaw || teleport.pitch != lastPitch) {
			Recorder.record(teleport);
			lastX = teleport.x;
			lastY = teleport.y;
			lastZ = teleport.z;
			lastYaw = teleport.yaw;
			lastPitch = teleport.pitch;
		}

		byte head = angle(player.headYaw);
		if (head != lastHead) {
			Recorder.record(new EntityHeadAnglesPacket(player.networkId, head));
			lastHead = head;
		}

		if (player.armSwinging && (!lastSwinging || player.armSwingingTicks < lastSwingTicks)) {
			Recorder.record(new EntityAnimationPacket(player, 1));
		}
		lastSwinging = player.armSwinging;
		lastSwingTicks = player.armSwingingTicks;

		byte flags = player.getSyncedData().getByte(0);
		if (flags != lastFlags) {
			Recorder.record(new EntityDataPacket(player.networkId, player.getSyncedData(), true));
			lastFlags = flags;
		}

		for (int slot = 0; slot < lastEquipment.length; slot++) {
			ItemStack item = player.getEquipment(slot);
			if (!ItemStack.matches(item, lastEquipment[slot])) {
				lastEquipment[slot] = copy(item);
				Recorder.record(new EntityEquipmentPacket(player.networkId, slot, item));
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
