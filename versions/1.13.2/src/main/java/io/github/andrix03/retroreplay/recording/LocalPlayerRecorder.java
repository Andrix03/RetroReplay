package io.github.andrix03.retroreplay.recording;

import io.github.andrix03.retroreplay.compat.Mc;
import io.github.andrix03.retroreplay.mixin.ClientPlayerInteractionManagerAccessor;
import io.github.andrix03.retroreplay.replay.MiningCracks;
import io.github.andrix03.retroreplay.mixin.MinecraftAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.entity.EquipmentSlot;
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
 * spawn (again after a respawn or dimension change), position, head, arm swing, equipment,
 * flags (sneaking, sprinting, swimming, gliding...), hurt and death. Since 1.8 y is at the feet.
 */
public final class LocalPlayerRecorder {

	private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

	private static LocalClientPlayerEntity spawned;
	private static double lastX, lastY, lastZ;
	private static float lastYaw, lastPitch;
	private static byte lastHead;
	private static boolean lastSwinging;
	private static int lastSwingTicks;
	private static int lastFlags;
	private static int lastDamagedTimer;
	private static boolean lastAlive;
	private static final ItemStack[] lastEquipment = new ItemStack[SLOTS.length];

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
			MiningCracks.resetRecording();
			Recorder.record(new AddPlayerS2CPacket(player));
			rememberPosition(player);
			lastHead = angle(player.headYaw);
			lastSwinging = player.armSwinging;
			lastSwingTicks = player.armSwingingTicks;
			lastFlags = flags(player);
			lastDamagedTimer = player.damagedTimer;
			lastAlive = player.isAlive();
			for (int i = 0; i < SLOTS.length; i++) {
				ItemStack item = player.getEquipment(SLOTS[i]);
				lastEquipment[i] = item.copy();
				if (!item.isEmpty()) {
					Recorder.record(new EntityEquipmentS2CPacket(player.getNetworkId(), SLOTS[i], item));
				}
			}
			return;
		}

		if (player.x != lastX || player.y != lastY || player.z != lastZ || player.yaw != lastYaw || player.pitch != lastPitch) {
			Recorder.record(new EntityTeleportS2CPacket(player));
			rememberPosition(player);
		}

		byte head = angle(player.headYaw);
		if (head != lastHead) {
			Recorder.record(new EntityHeadAnglesS2CPacket(player, head));
			lastHead = head;
		}

		if (player.armSwinging && (!lastSwinging || player.armSwingingTicks < lastSwingTicks)) {
			Recorder.record(new EntityAnimationS2CPacket(player, 0)); // 0 = swing the main hand
		}
		lastSwinging = player.armSwinging;
		lastSwingTicks = player.armSwingingTicks;

		int flags = flags(player);
		if (flags != lastFlags) {
			Recorder.record(new EntityDataS2CPacket(player.getNetworkId(), player.getSyncedData(), true));
			lastFlags = flags;
		}

		// block cracks: the server sends them to the other clients only, never to the one breaking
		ClientPlayerInteractionManagerAccessor mining = (ClientPlayerInteractionManagerAccessor) Minecraft.getInstance().interactionManager;
		if (mining != null) {
			net.minecraft.util.math.BlockPos target = mining.retroreplay$getTarget();
			MiningCracks.recordTick(Mc.entityId(player), mining.retroreplay$isMining(), target.getX(), target.getY(), target.getZ(),
				mining.retroreplay$getProgress());
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

		for (int i = 0; i < SLOTS.length; i++) {
			ItemStack item = player.getEquipment(SLOTS[i]);
			if (!ItemStack.matches(item, lastEquipment[i])) {
				lastEquipment[i] = item.copy();
				Recorder.record(new EntityEquipmentS2CPacket(player.getNetworkId(), SLOTS[i], item));
			}
		}
	}

	private static void rememberPosition(LocalClientPlayerEntity player) {
		lastX = player.x;
		lastY = player.y;
		lastZ = player.z;
		lastYaw = player.yaw;
		lastPitch = player.pitch;
	}

	/** What the synced flags show of the player: a change sends its data again. */
	private static int flags(LocalClientPlayerEntity player) {
		return (player.isOnFire() ? 1 : 0) | (player.isSneaking() ? 2 : 0) | (player.isSprinting() ? 8 : 0) | (player.isSwimming() ? 16 : 0)
			| (player.isInvisible() ? 32 : 0) | (player.isGlowing() ? 64 : 0) | (player.isFallFlying() ? 128 : 0);
	}

	private static byte angle(float degrees) {
		return (byte) (degrees * 256.0F / 360.0F);
	}
}
