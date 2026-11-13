package io.github.andrix03.retroreplay.recording;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.mixin.MultiplayerInteractionManagerAccessor;
import io.github.andrix03.retroreplay.mixin.SurvivalInteractionManagerAccessor;
import io.github.andrix03.retroreplay.replay.MiningCracks;
import io.github.andrix03.retroreplay.server.LocalServer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.mob.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.AddPlayerPacket;
import net.minecraft.network.packet.EntityAnimationPacket;
import net.minecraft.network.packet.EntityEquipmentPacket;
import net.minecraft.network.packet.EntityHeadAnglesPacket;
import net.minecraft.network.packet.EntityTeleportPacket;

/**
 * The server never tells a client about its own player, so the recording would miss it.
 * Like Replay Mod, every client tick we write the packets another client would have received:
 * spawn (again after a respawn or dimension change), position, head, arm swing, equipment, flags.
 * The client jar of 1.2.5 lacks the server's packet constructors: fields are set by hand.
 */
public final class LocalPlayerRecorder {

	private static ClientPlayerEntity spawned;
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
		Minecraft minecraft = Mc.minecraft();
		// singleplayer runs on the local server: its world and the recording clock pause together
		LocalServer.setPaused(LocalServer.isJoined() && minecraft.world != null && minecraft.paused);
		LocalServer.keepAlive(minecraft.getNetworkHandler());
		LocalServer.followDifficulty(minecraft.options.difficulty);
		Recorder.setPaused(minecraft.paused);
		NativeRecorder.tick(); // vanilla singleplayer: the world's packets, before the player's
		if (!Recorder.isRecording()) {
			spawned = null;
			return;
		}
		ClientPlayerEntity player = minecraft.player;
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

	private static void record(ClientPlayerEntity player) {
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
					Recorder.record(equipmentPacket(player.networkId, slot, lastEquipment[slot]));
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
			EntityHeadAnglesPacket packet = new EntityHeadAnglesPacket();
			packet.id = player.networkId;
			packet.headYaw = head;
			Recorder.record(packet);
			lastHead = head;
		}

		if (player.swingArm && (!lastSwinging || player.swingArmTicks < lastSwingTicks)) {
			Recorder.record(new EntityAnimationPacket(player, 1));
		}
		lastSwinging = player.swingArm;
		lastSwingTicks = player.swingArmTicks;

		byte flags = player.getSyncedData().getByte(0);
		if (flags != lastFlags) {
			// entity data 0x28: id, entry 0 (byte type, index 0) = flags, end marker 0x7F
			int id = player.networkId;
			Recorder.recordRaw(new byte[] { 0x28, (byte) (id >> 24), (byte) (id >> 16), (byte) (id >> 8), (byte) id, 0, flags, 0x7F });
			lastFlags = flags;
		}

		// block cracks: no packet for them before 1.3, the replay draws them from ours
		Object manager = Mc.minecraft().interactionManager;
		if (manager instanceof MultiplayerInteractionManagerAccessor) {
			MultiplayerInteractionManagerAccessor mining = (MultiplayerInteractionManagerAccessor) manager;
			MiningCracks.recordTick(Mc.entityId(player), mining.retroreplay$isMining(), mining.retroreplay$getTargetX(), mining.retroreplay$getTargetY(),
				mining.retroreplay$getTargetZ(), mining.retroreplay$getProgress());
		} else if (manager instanceof SurvivalInteractionManagerAccessor) {
			SurvivalInteractionManagerAccessor mining = (SurvivalInteractionManagerAccessor) manager;
			MiningCracks.recordTick(Mc.entityId(player), mining.retroreplay$getProgress() > 0, mining.retroreplay$getTargetX(), mining.retroreplay$getTargetY(),
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
				Recorder.record(equipmentPacket(player.networkId, slot, item));
			}
		}
	}

	/** 0 = held item, 1-4 = armor (feet to head), like later versions' getEquipment. */
	private static ItemStack equipment(ClientPlayerEntity player, int slot) {
		return slot == 0 ? player.inventory.getSelectedItem() : player.inventory.getArmor(slot - 1);
	}

	private static EntityEquipmentPacket equipmentPacket(int entityId, int slot, ItemStack item) {
		EntityEquipmentPacket packet = new EntityEquipmentPacket();
		packet.id = entityId;
		packet.slot = slot;
		packet.item = item == null ? -1 : item.id;
		packet.metadata = item == null ? 0 : item.getMetadata();
		return packet;
	}

	private static byte angle(float degrees) {
		return (byte) (degrees * 256.0F / 360.0F);
	}

	private static ItemStack copy(ItemStack item) {
		return item == null ? null : item.copy();
	}
}
