package io.github.andrix03.retroreplay.recording;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.mixin.ClientPlayerInteractionManagerAccessor;
import io.github.andrix03.retroreplay.replay.MiningCracks;
import io.github.andrix03.retroreplay.mixin.MinecraftAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.LocalClientPlayerEntity;
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
		// the local player's y is at its eyes (eyeHeight above the feet), another player's at its
		// feet: the packets are made at the feet, as the server makes them for the other clients
		double eyes = player.y;
		player.y -= player.eyeHeight;
		try {
			record(player);
		} finally {
			player.y = eyes;
		}
	}

	private static void record(LocalClientPlayerEntity player) {
		if (player != spawned) {
			spawned = player;
			MiningCracks.resetRecording();
			Recorder.record(new AddPlayerPacket(player));
			EntityTeleportPacket teleport = new EntityTeleportPacket(player);
			lastX = teleport.x;
			lastY = teleport.y;
			lastZ = teleport.z;
			lastYaw = teleport.yaw;
			lastPitch = teleport.pitch;
			lastHead = angle(player.headYaw);
			lastSwinging = player.swingArm;
			lastSwingTicks = player.swingArmTicks;
			lastFlags = player.getSyncedData().getByte(0);
			lastDamagedTimer = player.damagedTimer;
			lastAlive = player.isAlive();
			for (int slot = 0; slot < lastEquipment.length; slot++) {
				lastEquipment[slot] = copy(equipment(player, slot));
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

		if (player.swingArm && (!lastSwinging || player.swingArmTicks < lastSwingTicks)) {
			Recorder.record(new EntityAnimationPacket(player, 1));
		}
		lastSwinging = player.swingArm;
		lastSwingTicks = player.swingArmTicks;

		byte flags = player.getSyncedData().getByte(0);
		if (flags != lastFlags) {
			Recorder.record(new EntityDataPacket(player.networkId, player.getSyncedData()));
			lastFlags = flags;
		}

		// block cracks: the server sends them to the other clients only, never to the one breaking
		ClientPlayerInteractionManagerAccessor mining = (ClientPlayerInteractionManagerAccessor) Minecraft.getInstance().interactionManager;
		if (mining != null) {
			MiningCracks.recordTick(Mc.entityId(player), mining.retroreplay$isMining(), mining.retroreplay$getTargetX(), mining.retroreplay$getTargetY(),
				mining.retroreplay$getTargetZ(), mining.retroreplay$getProgress());
		}

		// hurt flash and death animation, as the server reports them for other players
		if (player.damagedTimer > lastDamagedTimer) {
			Recorder.recordEntityEvent(player.networkId, Recorder.EVENT_HURT);
		}
		lastDamagedTimer = player.damagedTimer;
		boolean alive = player.isAlive();
		if (lastAlive && !alive) {
			Recorder.recordEntityEvent(player.networkId, Recorder.EVENT_DEATH);
		}
		lastAlive = alive;

		for (int slot = 0; slot < lastEquipment.length; slot++) {
			ItemStack item = equipment(player, slot);
			if (!ItemStack.matches(item, lastEquipment[slot])) {
				lastEquipment[slot] = copy(item);
				Recorder.record(new EntityEquipmentPacket(player.networkId, slot, item));
			}
		}
	}

	/** 0 = held item, 1-4 = armor (feet to head), like later versions' getEquipment. */
	private static ItemStack equipment(LocalClientPlayerEntity player, int slot) {
		return slot == 0 ? player.inventory.getSelectedItem() : player.inventory.getArmor(slot - 1);
	}

	private static byte angle(float degrees) {
		return (byte) (degrees * 256.0F / 360.0F);
	}

	private static ItemStack copy(ItemStack item) {
		return item == null ? null : item.copy();
	}
}
